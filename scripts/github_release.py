#!/usr/bin/env python3
"""Verified GitHub releases and guarded metadata promotion. Never log credentials or signed URLs."""
import argparse
import base64
import copy
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
import update_server as local

REPO = "zhixiao113/AIQuizLite-AS"
BRANCH = "update-channel"
MANIFEST_URL = f"https://raw.githubusercontent.com/{REPO}/{BRANCH}/stable.json"
MAX_APK = 128 * 1024 * 1024
MAX_JSON = 128 * 1024
PUBLIC_HOSTS = {"github.com", "raw.githubusercontent.com", "release-assets.githubusercontent.com",
                "objects.githubusercontent.com", "objects-origin.githubusercontent.com", "github-releases.githubusercontent.com"}


class ReleaseError(Exception):
    pass


def now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def encode(value):
    return json.dumps(value, ensure_ascii=False, indent=2).encode("utf-8")


def public_url(url):
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme != "https" or parsed.hostname not in PUBLIC_HOSTS or parsed.port not in (None, 443)
            or parsed.username or parsed.password or parsed.fragment):
        raise ReleaseError("Rejected unsafe public download address")
    return url


class PublicRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        public_url(newurl)
        return super().redirect_request(request, fp, code, message, headers, newurl)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        raise ReleaseError("Authenticated API redirect refused")


def download(url, destination=None, limit=MAX_JSON):
    public_url(url)
    request = urllib.request.Request(url, headers={"User-Agent": "AIQuizLite-release",
                                                  "Cache-Control": "no-cache", "Accept-Encoding": "identity"})
    try:
        with urllib.request.build_opener(PublicRedirect()).open(request, timeout=30) as response:
            content = bytearray()
            total = 0
            output = open(destination, "wb") if destination else None
            try:
                while True:
                    chunk = response.read(65536)
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > limit:
                        raise ReleaseError("Download exceeds allowed size")
                    if output:
                        output.write(chunk)
                    else:
                        content.extend(chunk)
            finally:
                if output:
                    output.close()
            return bytes(content)
    except (OSError, urllib.error.URLError) as error:
        raise ReleaseError("Public download unavailable or TLS verification failed") from error


def validate_manifest(value):
    if not isinstance(value, dict):
        raise ReleaseError("Invalid release manifest")
    code, size = value.get("versionCode"), value.get("apkSize")
    if (type(code) is not int or not 0 < code <= 2147483647 or type(size) is not int or not 0 < size <= MAX_APK
            or value.get("schemaVersion") != 1 or value.get("packageName") != local.PACKAGE_NAME
            or value.get("signerSha256") != local.EXPECTED_SIGNER
            or not re.fullmatch(r"[0-9a-f]{64}", str(value.get("apkSha256", "")))
            or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", str(value.get("versionName", "")))
            or value.get("releaseTag") != "v" + value["versionName"]
            or not re.fullmatch(r"[0-9a-f]{40}", str(value.get("sourceCommit", "")))
            or not re.fullmatch(r"[0-9a-f]{32}", str(value.get("publicationId", "")))):
        raise ReleaseError("Invalid release manifest")
    expected = f"https://github.com/{REPO}/releases/download/{value['releaseTag']}/app-release.apk"
    if value.get("apkUrl") != expected or not isinstance(value.get("notes"), str) or len(value["notes"]) > 16000:
        raise ReleaseError("Invalid release manifest URL or notes")
    return value


def validate_state(state):
    if (not isinstance(state, dict) or type(state.get("highestCode")) is not int
            or not isinstance(state.get("releases"), dict) or not state["releases"]):
        raise ReleaseError("Channel state is invalid; no automatic reset")
    for code, record in state["releases"].items():
        manifest = validate_manifest(record["manifest"])
        if code != str(manifest["versionCode"]) or record.get("status") not in {"stable", "candidate", "withdrawn"}:
            raise ReleaseError("Channel release ledger is invalid")
    if state["highestCode"] < max(int(code) for code in state["releases"]):
        raise ReleaseError("Channel highest version record regressed")
    for key in ("stable", "previous"):
        item = state.get(key)
        if key == "previous" and item is None:
            continue
        validate_manifest(item)
        record = state["releases"].get(str(item["versionCode"]))
        if not record or record["status"] != "stable" or record["manifest"] != item:
            raise ReleaseError("Channel pointer differs from release ledger")
    if state.get("previous") and state["previous"]["versionCode"] >= state["stable"]["versionCode"]:
        raise ReleaseError("Channel previous version is invalid")
    return state


def manifest_for(apk, notes, commit, inspector=local.inspect_apk):
    info = inspector(apk)
    digest = local.sha256_of(apk)
    tag = "v" + info["versionName"]
    return validate_manifest(dict(info, schemaVersion=1, apkSize=Path(apk).stat().st_size,
                                  apkSha256=digest, notes=notes, sourceCommit=commit, releaseTag=tag,
                                  apkUrl=f"https://github.com/{REPO}/releases/download/{tag}/app-release.apk",
                                  publicationId=uuid.uuid4().hex, releasedAt=now()))


def verify_public(manifest):
    validate_manifest(manifest)
    with tempfile.TemporaryDirectory(prefix="aiquiz-public-") as folder:
        apk = Path(folder, "app.apk")
        download(manifest["apkUrl"], apk, MAX_APK)
        if apk.stat().st_size != manifest["apkSize"] or local.sha256_of(apk) != manifest["apkSha256"]:
            raise ReleaseError("Public APK hash/size differs from verified artifact")
        actual = local.inspect_apk(apk)
        if any(actual.get(key) != manifest[key] for key in ("packageName", "versionCode", "versionName", "signerSha256")):
            raise ReleaseError("Public APK version/package/signature differs")


class Api:
    def __init__(self, token):
        if not token:
            raise ReleaseError("GITHUB_TOKEN is required; never pass it as a command argument")
        self.token = token
        self.opener = urllib.request.build_opener(NoRedirect())

    def request(self, method, path, body=None, raw=None, missing_ok=False):
        url = path if path.startswith("https://uploads.github.com/") else f"https://api.github.com/repos/{REPO}/{path}"
        headers = {"Authorization": "Bearer " + self.token, "User-Agent": "AIQuizLite-release",
                   "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28",
                   "Cache-Control": "no-cache"}
        data = encode(body) if body is not None else raw
        headers["Content-Type"] = "application/json" if raw is None else "application/octet-stream"
        try:
            with self.opener.open(urllib.request.Request(url, data=data, method=method, headers=headers), timeout=90) as response:
                result = response.read(2 * 1024 * 1024)
                return json.loads(result) if result else {}
        except urllib.error.HTTPError as error:
            if error.code == 404 and missing_ok:
                return None
            raise ReleaseError(f"GitHub API {method} failed with HTTP {error.code}") from None
        except (OSError, urllib.error.URLError):
            raise ReleaseError("GitHub API unavailable; recheck remote state before retry") from None

    def releases(self):
        page = 1
        while True:
            batch = self.request("GET", f"releases?per_page=100&page={page}")
            yield from batch
            if len(batch) < 100:
                return
            page += 1

    def create_release(self, manifest, apk):
        tag = manifest["releaseTag"]
        if self.request("GET", f"releases/tags/{tag}", missing_ok=True):
            raise ReleaseError("Release tag already exists; refusing overwrite (use a new version)")
        release = self.request("POST", "releases", dict(tag_name=tag, target_commitish=manifest["sourceCommit"],
                               name="AIQuizLite " + manifest["versionName"], body=manifest["notes"],
                               draft=True, prerelease=False))
        upload_url = release["upload_url"].split("{", 1)[0]
        for name, data in (("app-release.apk", Path(apk).read_bytes()), ("release-manifest.json", encode(manifest))):
            asset = self.request("POST", upload_url + "?name=" + name, raw=data)
            if (asset.get("state") != "uploaded" or asset.get("size") != len(data)
                    or asset.get("digest") != "sha256:" + hashlib.sha256(data).hexdigest()):
                raise ReleaseError("Uploaded asset receipt verification failed; draft retained")
        return self.request("PATCH", f"releases/{release['id']}", dict(draft=False, make_latest="false"))


class Channel:
    def __init__(self, api):
        self.api = api

    def read(self):
        ref = self.api.request("GET", f"git/ref/heads/{BRANCH}", missing_ok=True)
        if not ref:
            return dict(sha=None, tree=None, state=None)
        sha = ref["object"]["sha"]
        commit = self.api.request("GET", f"git/commits/{sha}")
        file = self.api.request("GET", f"contents/state.json?ref={sha}")
        state = validate_state(json.loads(base64.b64decode(file["content"])))
        pointer = self.api.request("GET", f"contents/stable.json?ref={sha}")
        if json.loads(base64.b64decode(pointer["content"])) != state["stable"]:
            raise ReleaseError("Public channel file differs from ledger; no automatic reset")
        return dict(sha=sha, tree=commit["tree"]["sha"], state=state)

    def write(self, snapshot, state, event):
        validate_state(state)
        entries = []
        public_event = dict(event, at=now(), run=os.environ.get("GITHUB_RUN_ID", "local"))
        files = {"state.json": state, "stable.json": state.get("stable"), "previous.json": state.get("previous"),
                 f"audit/{uuid.uuid4().hex}.json": public_event}
        for path, content in files.items():
            blob = self.api.request("POST", "git/blobs", dict(content=encode(content).decode("utf-8"), encoding="utf-8"))
            entries.append(dict(path=path, mode="100644", type="blob", sha=blob["sha"]))
        tree_body = dict(tree=entries)
        if snapshot["tree"]:
            tree_body["base_tree"] = snapshot["tree"]
        tree = self.api.request("POST", "git/trees", tree_body)
        commit = self.api.request("POST", "git/commits", dict(message=event["action"], tree=tree["sha"],
                             parents=[snapshot["sha"]] if snapshot["sha"] else []))
        new_sha = commit["sha"]
        try:
            if snapshot["sha"]:
                self.api.request("PATCH", f"git/refs/heads/{BRANCH}", dict(sha=new_sha, force=False))
            else:
                self.api.request("POST", "git/refs", dict(ref=f"refs/heads/{BRANCH}", sha=new_sha))
        except ReleaseError:
            # A lost response may hide a successful update. A concurrent sibling must not be overwritten.
            actual = self.read()
            if actual["sha"] != new_sha:
                raise ReleaseError("Channel update failed or superseded; no force-push performed") from None
        return dict(sha=new_sha, tree=tree["sha"], state=state)


class Publisher:
    def __init__(self, api, channel, verifier=verify_public, health=None, log=None):
        self.api, self.channel, self.verify = api, channel, verifier
        self.health = health or self.check_health
        self.log = log or (lambda stage, outcome: None)

    def check_health(self, manifest):
        for attempt in range(8):
            try:
                value = json.loads(download(MANIFEST_URL + "?publication=" + manifest["publicationId"]))
                if value == manifest:
                    self.verify(manifest)
                    return
            except (ReleaseError, ValueError):
                pass
            if attempt < 7:
                time.sleep(10)
        raise ReleaseError("Public channel did not converge within the health-check window")

    def promote(self, manifest):
        validate_manifest(manifest)
        snapshot = self.channel.read()
        state = copy.deepcopy(snapshot["state"])
        if not state:
            raise ReleaseError("Bootstrap a verified prior stable release before publishing")
        code = str(manifest["versionCode"])
        if manifest["versionCode"] <= state["highestCode"] or code in state["releases"]:
            raise ReleaseError("Version code already used; refusing reuse or out-of-order promotion")
        self.verify(manifest)  # This seam also permits externally uploaded, already verified artifacts.
        old = copy.deepcopy(state)
        state["highestCode"] = manifest["versionCode"]
        state["releases"][code] = dict(manifest=manifest, status="candidate",
                                      previousCode=state["stable"]["versionCode"])
        reserved = self.channel.write(snapshot, state, dict(action="Record verified candidate", versionCode=int(code)))
        state = copy.deepcopy(reserved["state"])
        state["previous"] = state["stable"]
        state["stable"] = manifest
        state["releases"][code]["status"] = "stable"
        switched = self.channel.write(reserved, state, dict(action="Promote stable", versionCode=int(code),
                                              publicationId=manifest["publicationId"]))
        try:
            self.health(manifest)
            self.log("health", "passed")
            return switched
        except Exception:
            self.log("health", "failed")
            # Keep highest-used code and release history; restore only this exact switch.
            latest = self.channel.read()
            if latest["sha"] != switched["sha"]:
                self.log("rollback", "superseded; no write")
                raise ReleaseError("Health check failed; newer channel state prevented automatic rollback") from None
            recovery = copy.deepcopy(latest["state"])
            recovery["stable"], recovery["previous"] = old["stable"], old["previous"]
            recovery["releases"][code]["status"] = "withdrawn"
            try:
                restored = self.channel.write(latest, recovery, dict(action="Automatic rollback", failedCode=int(code)))
                self.health(restored["state"]["stable"])
                self.log("rollback", "verified")
            except Exception:
                self.log("rollback", "failed or not verified")
                raise ReleaseError("Publication failed; rollback is not verified, inspect remote state") from None
            raise ReleaseError("Publication failed; previous stable restored and verified") from None

    def rollback(self, target=None):
        snapshot = self.channel.read()
        state = copy.deepcopy(snapshot["state"])
        if not state or not state.get("previous") and target is None:
            raise ReleaseError("No previous release exists")
        code = str(state["previous"]["versionCode"] if target is None else target)
        record = state["releases"].get(code)
        if not record or record["status"] != "stable" or int(code) >= state["stable"]["versionCode"]:
            raise ReleaseError("Rollback target is not an earlier verified stable release")
        self.verify(record["manifest"])
        withdrawn = str(state["stable"]["versionCode"])
        state["releases"][withdrawn]["status"] = "withdrawn"
        state["stable"] = record["manifest"]
        prior = record.get("previousCode")
        state["previous"] = state["releases"][str(prior)]["manifest"] if prior is not None else None
        switched = self.channel.write(snapshot, state, dict(action="Manual rollback", restoredCode=int(code), withdrawnCode=int(withdrawn)))
        self.health(state["stable"])
        self.log("rollback", "verified")
        return switched


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["publish", "bootstrap", "rollback"])
    parser.add_argument("--apk")
    parser.add_argument("--notes", default="在线更新链路升级。")
    parser.add_argument("--commit")
    parser.add_argument("--target-code", type=int)
    parser.add_argument("--report", default=str(ROOT / "artifacts/github-release/report.json"))
    args = parser.parse_args()
    report = dict(startedAt=now(), mode=args.mode, steps=[], outcome="running")

    def log(stage, outcome):
        report["steps"].append(dict(stage=stage, outcome=outcome, at=now()))
        print(f"{stage}: {outcome}", flush=True)

    try:
        api = Api(os.environ.get("GITHUB_TOKEN"))
        channel = Channel(api)
        publisher = Publisher(api, channel, log=log)
        if args.mode == "bootstrap":
            initial = channel.read()
            if initial["state"]:
                raise ReleaseError("Channel already initialized; bootstrap cannot overwrite it")
            release = api.request("GET", "releases/tags/v1.1.2")
            commit = args.commit or "18054a46a64c748fa527f8201dcf2b24ee75cef6"
            with tempfile.TemporaryDirectory(prefix="aiquiz-bootstrap-") as folder:
                apk = Path(folder, "prior.apk")
                download(f"https://github.com/{REPO}/releases/download/v1.1.2/app-release.apk", apk, MAX_APK)
                manifest = manifest_for(apk, "已核验的原签名基础版本。", commit)
                if (manifest["versionCode"], manifest["versionName"]) != (13, "1.1.2"):
                    raise ReleaseError("Bootstrap package is not the verified baseline")
                verify_public(manifest)
                state = dict(highestCode=13, stable=manifest, previous=None,
                             releases={"13": dict(manifest=manifest, status="stable", previousCode=None)})
                snapshot = channel.write(initial, state, dict(action="Bootstrap original-signer baseline"))
                publisher.health(manifest)
                log("bootstrap", "verified")
        elif args.mode == "publish":
            if not args.apk or not args.commit:
                raise ReleaseError("--apk and --commit are required")
            manifest = manifest_for(args.apk, args.notes, args.commit)
            source_code, source_name = local.parse_version()
            if (manifest["versionCode"], manifest["versionName"]) != (source_code, source_name):
                raise ReleaseError("APK actual version differs from committed source")
            state = channel.read()["state"]
            if not state or manifest["versionCode"] <= state["highestCode"]:
                raise ReleaseError("Missing baseline or reused version code")
            # Reject reuse even after a candidate was public but its metadata update failed.
            for release in api.releases():
                if not release["draft"]:
                    for asset in release["assets"]:
                        if asset["name"] == "release-manifest.json":
                            prior = validate_manifest(json.loads(download(asset["browser_download_url"])))
                            if manifest["versionCode"] <= prior["versionCode"]:
                                raise ReleaseError("Version code already appeared in a public release")
            published_release = api.create_release(manifest, args.apk)
            log("upload", "receipt verified; candidate public, not yet stable")
            publisher.promote(manifest)
            log("publication", "stable verified")
            api.request("PATCH", f"releases/{published_release['id']}", dict(make_latest="true"))
            log("release-display", "latest updated after stable verification")
        else:
            publisher.rollback(args.target_code)
        report["outcome"] = "success"
        return 0
    except Exception as error:
        report["outcome"] = "failed"
        # Error details from auth/HTTP/subprocess can contain credentials; report only sanitized domain errors.
        message = str(error) if isinstance(error, ReleaseError) else "Unexpected validation/API error; publication not verified"
        log("failure", message)
        return 1
    finally:
        report["finishedAt"] = now()
        path = Path(args.report)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(encode(report))


if __name__ == "__main__":
    sys.exit(main())

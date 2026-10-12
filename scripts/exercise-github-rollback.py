"""Real API failure/recovery exercise on an isolated codex/update-test-* metadata branch only."""
import copy
import json
import os
from pathlib import Path
import sys
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parent))
import github_release as release


def main():
    api = release.Api(os.environ.get("GITHUB_TOKEN"))
    production = release.Channel(api).read()
    baseline = production["state"]["previous"]
    candidate = production["state"]["stable"]
    if not baseline:
        raise release.ReleaseError("Exercise needs a verified previous release")
    original_branch, original_url = release.BRANCH, release.MANIFEST_URL
    branch = "codex/update-test-" + uuid.uuid4().hex[:12]
    report = dict(branch=branch, productionSha=production["sha"], startedAt=release.now(), steps=[])

    def log(stage, outcome):
        report["steps"].append(dict(stage=stage, outcome=outcome))
        print(f"{stage}: {outcome}", flush=True)

    try:
        release.BRANCH = branch
        release.MANIFEST_URL = f"https://raw.githubusercontent.com/{release.REPO}/{branch}/stable.json"
        channel = release.Channel(api)
        initial = channel.read()
        if initial["state"] or not branch.startswith("codex/update-test-"):
            raise release.ReleaseError("Exercise cannot reuse or overwrite an existing channel")
        release.verify_public(baseline)
        code = str(baseline["versionCode"])
        state = dict(highestCode=baseline["versionCode"], stable=copy.deepcopy(baseline), previous=None,
                     releases={code: dict(manifest=copy.deepcopy(baseline), status="stable", previousCode=None)})
        channel.write(initial, state, dict(action="Initialize isolated recovery exercise"))
        publisher = release.Publisher(api, channel, log=log)
        health = publisher.health

        def injected_health(item):
            if item["publicationId"] == candidate["publicationId"]:
                raise release.ReleaseError("Injected test-channel health failure")
            health(item)

        publisher.health = injected_health
        try:
            publisher.promote(copy.deepcopy(candidate))
            raise release.ReleaseError("Injected failure did not occur")
        except release.ReleaseError as error:
            if "previous stable restored and verified" not in str(error):
                raise
        restored = channel.read()["state"]
        if (restored["stable"] != baseline or restored["highestCode"] != candidate["versionCode"]
                or restored["releases"][str(candidate["versionCode"])]["status"] != "withdrawn"):
            raise release.ReleaseError("Recovery state verification failed")
        log("isolated-recovery", "original baseline restored; highest code retained; candidate withdrawn")
        report["outcome"] = "passed"
    finally:
        release.BRANCH, release.MANIFEST_URL = original_branch, original_url
        try:
            after = release.Channel(api).read()
            if after["sha"] != production["sha"]:
                report["outcome"] = "production changed concurrently; inspect before claiming isolation"
                raise release.ReleaseError("Production changed concurrently; exercise did not write production")
            log("production", "exact metadata commit unchanged")
        finally:
            report["finishedAt"] = release.now()
            path = release.ROOT / "artifacts/github-release/recovery-exercise.json"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(release.encode(report))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(str(error) if isinstance(error, release.ReleaseError) else "Recovery exercise not verified; inspect report")
        sys.exit(1)

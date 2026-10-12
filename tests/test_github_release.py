import base64
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from scripts import github_release as release


def manifest(code=13):
    name = "1.1." + str(code - 11)
    return dict(schemaVersion=1, packageName=release.local.PACKAGE_NAME, versionCode=code, versionName=name,
                apkSize=20, apkSha256="a" * 64, signerSha256=release.local.EXPECTED_SIGNER,
                apkUrl=f"https://github.com/{release.REPO}/releases/download/v{name}/app-release.apk",
                notes="test", releaseTag="v" + name, sourceCommit="b" * 40,
                publicationId=f"{code:032x}", releasedAt=release.now())


def initial():
    item = manifest()
    return dict(highestCode=13, stable=item, previous=None,
                releases={"13": dict(manifest=item, status="stable", previousCode=None)})


class FakeChannel:
    def __init__(self):
        self.state = initial()
        self.sha = "1"
        self.writes = 0
        self.conflict = False
        self.fail_on = 0

    def read(self):
        return dict(state=copy.deepcopy(self.state), sha=self.sha, tree="tree")

    def write(self, snapshot, state, event):
        release.validate_state(state)
        self.writes += 1
        if self.conflict or snapshot["sha"] != self.sha or self.writes == self.fail_on:
            raise release.ReleaseError("write rejected")
        self.state = copy.deepcopy(state)
        self.sha = str(int(self.sha) + 1)
        return self.read()


class PublicationTest(unittest.TestCase):
    def setUp(self):
        self.channel = FakeChannel()
        self.logs = []
        self.publisher = release.Publisher(None, self.channel, verifier=lambda value: None,
                                           health=lambda value: None, log=lambda *args: self.logs.append(args))

    def test_success_changes_pointer_last_and_preserves_prior(self):
        self.publisher.promote(manifest(14))
        self.assertEqual(14, self.channel.state["stable"]["versionCode"])
        self.assertEqual(13, self.channel.state["previous"]["versionCode"])
        self.assertEqual(2, self.channel.writes)

    def test_public_download_failure_does_not_change_stable(self):
        self.publisher.verify = lambda value: (_ for _ in ()).throw(release.ReleaseError("bad download"))
        with self.assertRaises(release.ReleaseError): self.publisher.promote(manifest(14))
        self.assertEqual(initial(), self.channel.state)
        self.assertEqual(0, self.channel.writes)

    def test_switch_failure_keeps_prior_and_remembers_used_code(self):
        self.channel.fail_on = 2
        with self.assertRaises(release.ReleaseError): self.publisher.promote(manifest(14))
        self.assertEqual(13, self.channel.state["stable"]["versionCode"])
        self.assertEqual(14, self.channel.state["highestCode"])

    def test_health_failure_restores_previous_without_lowering_counter(self):
        def health(value):
            if value["versionCode"] == 14: raise release.ReleaseError("bad health")
        self.publisher.health = health
        with self.assertRaisesRegex(release.ReleaseError, "restored and verified"):
            self.publisher.promote(manifest(14))
        self.assertEqual(13, self.channel.state["stable"]["versionCode"])
        self.assertEqual(14, self.channel.state["highestCode"])
        self.assertEqual("withdrawn", self.channel.state["releases"]["14"]["status"])
        with self.assertRaises(release.ReleaseError): self.publisher.promote(manifest(14))

    def test_superseded_health_failure_cannot_overwrite_newer_state(self):
        def health(value):
            self.channel.sha = "999"
            raise release.ReleaseError("health failed")
        self.publisher.health = health
        with self.assertRaisesRegex(release.ReleaseError, "newer channel"):
            self.publisher.promote(manifest(14))
        self.assertEqual(2, self.channel.writes)
        self.assertEqual("999", self.channel.sha)

    def test_automatic_recovery_failure_remains_failure(self):
        self.channel.fail_on = 3
        self.publisher.health = lambda value: (_ for _ in ()).throw(release.ReleaseError("bad health"))
        with self.assertRaisesRegex(release.ReleaseError, "rollback is not verified"):
            self.publisher.promote(manifest(14))
        self.assertIn(("rollback", "failed or not verified"), self.logs)

    def test_manual_rollback_retains_releases_and_highest_code(self):
        self.publisher.promote(manifest(14))
        self.publisher.promote(manifest(15))
        self.publisher.rollback()
        self.assertEqual(14, self.channel.state["stable"]["versionCode"])
        self.assertEqual(13, self.channel.state["previous"]["versionCode"])
        self.assertEqual(15, self.channel.state["highestCode"])
        self.assertEqual("withdrawn", self.channel.state["releases"]["15"]["status"])

    def test_invalid_or_newer_rollback_target_rejected(self):
        for code in [0, 13, 14, 999]:
            with self.assertRaises(release.ReleaseError): self.publisher.rollback(code)
        self.assertEqual(0, self.channel.writes)

    def test_rollback_download_failure_leaves_pointer_unchanged(self):
        self.publisher.promote(manifest(14))
        before = copy.deepcopy(self.channel.state)
        self.publisher.verify = lambda value: (_ for _ in ()).throw(release.ReleaseError("bad download"))
        with self.assertRaises(release.ReleaseError): self.publisher.rollback()
        self.assertEqual(before, self.channel.state)

    def test_conflicting_promotion_does_not_force_update(self):
        self.channel.conflict = True
        with self.assertRaises(release.ReleaseError): self.publisher.promote(manifest(14))
        self.assertEqual(initial(), self.channel.state)


class RulesTest(unittest.TestCase):
    def test_valid_manifest(self):
        self.assertEqual(13, release.validate_manifest(manifest())["versionCode"])

    def test_invalid_fields_fail(self):
        for key, value in [("versionCode", True), ("versionCode", "13"), ("apkSize", 0),
                           ("apkSize", release.MAX_APK+1), ("signerSha256", "wrong"),
                           ("sourceCommit", "main"), ("apkSha256", ""),
                           ("apkUrl", "https://github.com/a/b/releases/latest/download/app-release.apk")]:
            data = manifest(); data[key] = value
            with self.assertRaises(release.ReleaseError): release.validate_manifest(data)

    def test_transport_policy(self):
        release.public_url("https://release-assets.githubusercontent.com/a?sig=temporary")
        for url in ["http://github.com/a", "https://token@github.com/a", "https://github.com.evil.org/a", "file:///tmp/a"]:
            with self.assertRaises(release.ReleaseError): release.public_url(url)

    def test_corrupt_counter_and_pointer_are_not_reset(self):
        data = initial(); data["highestCode"] = 12
        with self.assertRaises(release.ReleaseError): release.validate_state(data)
        data = initial(); data["stable"] = manifest(14)
        with self.assertRaises(release.ReleaseError): release.validate_state(data)

    def test_api_does_not_accept_empty_token(self):
        with self.assertRaises(release.ReleaseError): release.Api("")

    def test_fixed_signature_checked_before_publication(self):
        with tempfile.TemporaryDirectory() as folder:
            apk=Path(folder,"test.apk"); apk.write_bytes(b"fixture")
            wrong=dict(packageName=release.local.PACKAGE_NAME, versionCode=14, versionName="1.1.3", signerSha256="wrong")
            with self.assertRaises(release.ReleaseError):
                release.manifest_for(apk,"test","b"*40,inspector=lambda _:wrong)


class UploadTest(unittest.TestCase):
    def test_existing_release_is_not_overwritten(self):
        api=release.Api("fixture")
        with patch.object(api,"request",return_value={"id":1}) as request:
            with self.assertRaises(release.ReleaseError): api.create_release(manifest(14),"not-read.apk")
            self.assertEqual(1,request.call_count)

    def test_bad_receipt_stays_draft(self):
        api=release.Api("fixture")
        with tempfile.TemporaryDirectory() as folder:
            apk=Path(folder,"apk"); apk.write_bytes(b"fixture")
            with patch.object(api,"request",side_effect=[None,{"id":1,"upload_url":"https://uploads.github.com/repo/assets{?name,label}"},
                                                          {"state":"uploaded","size":7,"digest":"wrong"}]) as request:
                with self.assertRaises(release.ReleaseError): api.create_release(manifest(14),apk)
                self.assertEqual(3,request.call_count)


class ChannelWriteTest(unittest.TestCase):
    def fake_api(self, fail_ref=False):
        api=release.Api("fixture")
        calls=[]
        def request(method,path,body=None,**kwargs):
            calls.append((method,path,body))
            if path == "git/blobs": return {"sha":"blob"}
            if path == "git/trees": return {"sha":"tree-new"}
            if path == "git/commits": return {"sha":"commit-new"}
            if "refs" in path:
                if fail_ref: raise release.ReleaseError("response lost or conflict")
                return {}
            raise AssertionError(path)
        api.request=request
        return api,calls

    def test_write_is_fast_forward_and_preserves_original_parent(self):
        api,calls=self.fake_api()
        channel=release.Channel(api)
        snapshot=dict(sha="old-tip",tree="old-tree",state=initial())
        channel.write(snapshot,initial(),dict(action="test"))
        commits=[data for method,path,data in calls if path=="git/commits"]
        self.assertEqual(["old-tip"],commits[0]["parents"])
        self.assertEqual({"sha":"commit-new","force":False},calls[-1][2])

    def test_lost_response_only_accepted_if_remote_exactly_matches_commit(self):
        api,calls=self.fake_api(True)
        channel=release.Channel(api)
        snapshot=dict(sha="old-tip",tree="old-tree",state=initial())
        with patch.object(channel,"read",return_value=dict(sha="commit-new")):
            self.assertEqual("commit-new",channel.write(snapshot,initial(),dict(action="test"))["sha"])
        with patch.object(channel,"read",return_value=dict(sha="other-newer-tip")):
            with self.assertRaises(release.ReleaseError): channel.write(snapshot,initial(),dict(action="test"))

    def test_bootstrap_uses_root_commit_and_create_ref_not_force_update(self):
        api,calls=self.fake_api()
        channel=release.Channel(api)
        channel.write(dict(sha=None,tree=None,state=None),initial(),dict(action="bootstrap"))
        self.assertEqual("POST",calls[-1][0])
        self.assertEqual("git/refs",calls[-1][1])
        self.assertEqual([],next(data["parents"] for method,path,data in calls if path=="git/commits"))


if __name__ == "__main__": unittest.main()

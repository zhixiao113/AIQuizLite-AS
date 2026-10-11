import json
from pathlib import Path
import tempfile
import unittest
import sys
from unittest.mock import patch

import update_server as server


class DeployTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.apk = root / "source.apk"
        self.apk.write_bytes(b"verified APK fixture")
        self.output = root / "published"
        self.output.mkdir()
        self.manifest = self.output / "version.json"
        self.manifest.write_text('{"versionCode": 4}', encoding="utf-8")
        self.before = self.manifest.read_bytes()
        for name, value in (("APK_SOURCE", str(self.apk)), ("SERVE_DIR", str(self.output))):
            patcher = patch.object(server, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        self.info = {
            "packageName": "com.example.aiquizlite",
            "versionCode": 6,
            "versionName": "1.0.5",
            "signerSha256": server.EXPECTED_SIGNER,
        }

    def deploy(self):
        with patch.object(server, "inspect_apk", return_value=self.info, create=True):
            return server.deploy(6, "1.0.5", "test notes")

    def test_stale_apk_does_not_replace_manifest(self):
        self.info.update(versionCode=5, versionName="1.0.4")
        self.assertFalse(self.deploy())
        self.assertEqual(self.before, self.manifest.read_bytes())

    def test_wrong_signer_does_not_replace_manifest(self):
        self.info["signerSha256"] = "f2c8f93b8ee0ca3a52e136a439b60f3cb78fb52bb74a93426fff6ab6202c7902"
        self.assertFalse(self.deploy())
        self.assertEqual(self.before, self.manifest.read_bytes())

    def test_wrong_package_is_rejected(self):
        self.info["packageName"] = "other.app"
        self.assertFalse(self.deploy())
        self.assertEqual(self.before, self.manifest.read_bytes())

    def test_valid_apk_metadata_and_hash_are_published(self):
        self.assertTrue(self.deploy())
        info = json.loads(self.manifest.read_text(encoding="utf-8"))
        self.assertEqual(6, info["versionCode"])
        self.assertEqual("1.0.5", info["versionName"])
        self.assertEqual(self.info["signerSha256"], info["signerSha256"])
        self.assertEqual(server.sha256_of(self.apk), info["apkSha256"])
        self.assertEqual(self.apk.read_bytes(), (self.output / info["apkFile"]).read_bytes())

    def test_invalid_apk_keeps_existing_release(self):
        with patch.object(server, "inspect_apk", side_effect=ValueError("Invalid signature"), create=True):
            self.assertFalse(server.deploy(6, "1.0.5", "test"))
        self.assertEqual(self.before, self.manifest.read_bytes())


class StartupEntryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.legacy = self.root / "app" / "release" / "app-release.apk"
        self.legacy.parent.mkdir(parents=True)
        self.legacy.write_bytes(b"old code12")
        self.built = self.root / "artifacts" / "device-release-v13" / "outputs" / "apk" / "release" / "app-release.apk"
        self.published = self.root / "update-server"
        self.published.mkdir()
        for name, value in (("ROOT", str(self.root)), ("APK_SOURCE", str(self.legacy)),
                            ("SERVE_DIR", str(self.published))):
            patcher = patch.object(server, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        patcher = patch.object(server, "parse_version", return_value=(13, "1.1.2"))
        patcher.start()
        self.addCleanup(patcher.stop)

    def run_startup(self, *arguments):
        def inspect(path):
            old = Path(path).read_bytes() == b"old code12"
            return dict(packageName=server.PACKAGE_NAME, signerSha256=server.EXPECTED_SIGNER,
                        versionCode=12 if old else 13, versionName="1.1.1" if old else "1.1.2")
        with patch.object(sys, "argv", ["update_server.py", "--prepare-only", *arguments]), \
                patch.object(server, "inspect_apk", side_effect=inspect):
            return server.main()

    def test_default_startup_uses_current_agent_build_not_stale_studio_apk(self):
        self.built.parent.mkdir(parents=True)
        self.built.write_bytes(b"signed code13")
        self.assertEqual(0, self.run_startup())
        manifest = json.loads((self.published / "version.json").read_text(encoding="utf-8"))
        self.assertEqual(13, manifest["versionCode"])
        self.assertEqual(b"signed code13", (self.published / manifest["apkFile"]).read_bytes())

    def test_restart_can_use_prepared_apk_without_build_directory(self):
        ready = self.published / "AIQuizLite-v13-fixture.apk"
        ready.write_bytes(b"signed code13")
        (self.published / "version.json").write_text(json.dumps(dict(
            versionCode=13, versionName="1.1.2", apkFile=ready.name, notes="保留更新说明")), encoding="utf-8")
        self.assertEqual(0, self.run_startup())
        manifest = json.loads((self.published / "version.json").read_text(encoding="utf-8"))
        self.assertEqual(13, manifest["versionCode"])
        self.assertEqual("保留更新说明", manifest["notes"])

    def test_explicit_apk_remains_authoritative_even_when_new_build_exists(self):
        self.built.parent.mkdir(parents=True)
        self.built.write_bytes(b"signed code13")
        self.assertEqual(1, self.run_startup("--apk", str(self.legacy)))
        self.assertFalse((self.published / "version.json").exists())

    def test_manual_studio_build_still_supported_when_other_outputs_missing(self):
        self.legacy.write_bytes(b"signed code13")
        self.assertEqual(0, self.run_startup("手动更新说明"))

    def test_manifest_does_not_allow_path_traversal(self):
        (self.published / "version.json").write_text(json.dumps(dict(
            versionCode=13, versionName="1.1.2", apkFile="../other.apk")), encoding="utf-8")
        self.assertEqual(1, self.run_startup())
        self.assertEqual("../other.apk", json.loads((self.published / "version.json").read_text())["apkFile"])


if __name__ == "__main__":
    unittest.main()

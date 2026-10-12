"""Secret-free smoke test for the real SDK inspector against an immutable public APK."""
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import github_release as release


def main():
    manifest = release.validate_manifest(json.loads(release.download(
        "https://github.com/zhixiao113/AIQuizLite-AS/releases/download/v1.1.5/release-manifest.json")))
    if (manifest["versionCode"] != 16 or manifest["apkSize"] != 5162262
            or manifest["apkSha256"] != "629a6048bf5492e0cb444fe4cee92c07df0584157369ff9d5f9772c6002be618"):
        raise RuntimeError("Immutable public SDK fixture changed; no production signing attempted")
    print("Public baseline SDK tools:", release.local.sdk_build_tools())
    release.verify_public(manifest)
    print("Public original-signed baseline SDK inspection passed; no private key used.")


if __name__ == "__main__":
    main()

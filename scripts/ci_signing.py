"""Restore an explicitly authorized production key on an ephemeral runner only."""
import base64
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys


def main(mode):
    runner_temp = os.environ.get("RUNNER_TEMP")
    if not runner_temp:
        raise RuntimeError("Signing restore is restricted to a configured ephemeral runner")
    folder = Path(runner_temp).resolve() / "aiquiz-signing"
    file = folder / "app_sign.jks"
    if mode == "cleanup":
        file.unlink(missing_ok=True)
        return
    encoded = os.environ.get("AIQUIZ_RELEASE_KEYSTORE_BASE64", "")
    if not encoded or not os.environ.get("AIQUIZ_RELEASE_STORE_PASSWORD") or not os.environ.get("AIQUIZ_RELEASE_KEY_PASSWORD"):
        raise RuntimeError("Production signing Secrets missing; refusing fallback key")
    data = base64.b64decode(encoded, validate=True)
    if not 0 < len(data) < 36 * 1024:
        raise RuntimeError("Invalid signing Secret size")
    folder.mkdir(mode=0o700, parents=True, exist_ok=True)
    with open(file, "wb") as output:
        output.write(data)
    file.chmod(0o600)
    result = subprocess.run(["keytool", "-exportcert", "-rfc", "-keystore", str(file), "-alias", "key0",
                             "-storepass:env", "AIQUIZ_RELEASE_STORE_PASSWORD"], capture_output=True, text=True)
    match = re.search(r"-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", result.stdout, re.S)
    expected = "6973568484920238d35d94425c70601210ca2eaf7e728326785b66fb7e9576c8"
    if result.returncode or not match or hashlib.sha256(base64.b64decode(match[1])).hexdigest() != expected:
        file.unlink(missing_ok=True)
        raise RuntimeError("Original production certificate verification failed")
    with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as output:
        output.write("AIQUIZ_RELEASE_STORE_FILE=" + str(file) + "\nAIQUIZ_RELEASE_KEY_ALIAS=key0\n")
    print("Original production signing certificate verified; private material is not logged.")


if __name__ == "__main__":
    try:
        main(sys.argv[1])
    except Exception:
        print("Signing setup/cleanup failed; inspect Secrets and runner permissions, no credential values logged.")
        sys.exit(1)

"""One-time, explicitly authorized encrypted upload to a protected Environment."""
import base64
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from github_release import Api, ReleaseError

ENVIRONMENT = "production"
SECRET_NAMES = ("AIQUIZ_RELEASE_KEYSTORE_BASE64", "AIQUIZ_RELEASE_STORE_PASSWORD", "AIQUIZ_RELEASE_KEY_PASSWORD")
EXPECTED_SIGNER = "6973568484920238d35d94425c70601210ca2eaf7e728326785b66fb7e9576c8"


def protection_valid(environment, policies):
    policy = environment.get("deployment_branch_policy") or {}
    review = next((rule for rule in environment.get("protection_rules", [])
                   if rule.get("type") == "required_reviewers"), {})
    owner_review = any(item.get("type") == "User" and item.get("reviewer", {}).get("login") == "zhixiao113"
                       for item in review.get("reviewers", []))
    allowed = {(item.get("type"), item.get("name")) for item in policies.get("branch_policies", [])}
    if (not owner_review or not policy.get("custom_branch_policies") or policy.get("protected_branches")
            or ("branch", "main") not in allowed or not allowed <= {("branch", "main"), ("tag", "v*")}):
        raise ReleaseError("Production must retain owner review and only main/release-tag deployment policies")


def encrypt_secret(value, public_key):
    from nacl.public import PublicKey, SealedBox
    return base64.b64encode(SealedBox(PublicKey(base64.b64decode(public_key))).encrypt(value.encode("utf-8"))).decode("ascii")


def configure(api, values, encrypt=encrypt_secret):
    root = "environments/" + ENVIRONMENT
    environment = api.request("GET", root)
    policies = api.request("GET", root + "/deployment-branch-policies")
    protection_valid(environment, policies)
    existing = api.request("GET", root + "/secrets")
    if any(item.get("name") in SECRET_NAMES for item in existing.get("secrets", [])):
        raise ReleaseError("Signing Secrets already exist; explicit rotation is required, no overwrite performed")
    if set(values) != set(SECRET_NAMES) or not all(values.values()):
        raise ReleaseError("All three signing inputs are required")
    public_key = api.request("GET", root + "/secrets/public-key")
    encrypted = {name: encrypt(values[name], public_key["key"]) for name in SECRET_NAMES}
    if not any(item.get("type") == "tag" and item.get("name") == "v*"
               for item in policies.get("branch_policies", [])):
        api.request("POST", root + "/deployment-branch-policies", dict(name="v*", type="tag"))
    protection_valid(api.request("GET", root), api.request("GET", root + "/deployment-branch-policies"))
    for name in SECRET_NAMES:
        api.request("PUT", root + "/secrets/" + name,
                    dict(encrypted_value=encrypted[name], key_id=public_key["key_id"]))
    names = {item["name"] for item in api.request("GET", root + "/secrets").get("secrets", [])}
    if not set(SECRET_NAMES) <= names:
        raise ReleaseError("Signing Secret metadata verification failed")


def main():
    if os.environ.get("AIQUIZ_CLOUD_SIGNING_AUTHORIZED") != "true":
        raise ReleaseError("Explicit credential-upload authorization is required")
    original = Path(os.environ["AIQUIZ_RELEASE_STORE_FILE"])
    data = original.read_bytes()
    if not 0 < len(data) < 36 * 1024:
        raise ReleaseError("Original keystore size invalid")
    certificate = subprocess.run(["keytool", "-exportcert", "-rfc", "-keystore", str(original), "-alias", "key0",
                                  "-storepass:env", "AIQUIZ_RELEASE_STORE_PASSWORD"], capture_output=True, text=True)
    match = re.search(r"-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", certificate.stdout, re.S)
    if certificate.returncode or not match or hashlib.sha256(base64.b64decode(match[1])).hexdigest() != EXPECTED_SIGNER:
        raise ReleaseError("Original signing certificate verification failed")
    values = {name: os.environ.get(name, "") for name in SECRET_NAMES}
    values[SECRET_NAMES[0]] = base64.b64encode(data).decode("ascii")
    configure(Api(os.environ.get("GITHUB_TOKEN")), values)
    print("Three original-signing production Secrets encrypted, uploaded and metadata-verified; owner review retained.")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("Protected signing configuration failed; check permissions/metadata, no credential values logged.")
        sys.exit(1)

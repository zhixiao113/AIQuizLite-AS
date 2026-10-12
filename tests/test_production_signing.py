from pathlib import Path
import copy
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))
import configure_production_signing as signing


class FakeApi:
    def __init__(self):
        self.environment = dict(deployment_branch_policy=dict(custom_branch_policies=True, protected_branches=False),
                                protection_rules=[dict(type="required_reviewers", reviewers=[dict(type="User", reviewer=dict(login="zhixiao113"))])])
        self.policies = dict(branch_policies=[dict(name="main", type="branch")])
        self.secrets = {}
        self.calls = []

    def request(self, method, path, body=None):
        self.calls.append((method, path, body))
        root = "environments/production"
        if method == "GET":
            if path == root: return copy.deepcopy(self.environment)
            if path.endswith("/deployment-branch-policies"): return copy.deepcopy(self.policies)
            if path.endswith("/secrets/public-key"): return dict(key="public-test-key", key_id="id")
            if path.endswith("/secrets"): return dict(secrets=[dict(name=name) for name in self.secrets])
        if method == "POST" and path.endswith("/deployment-branch-policies"):
            self.policies["branch_policies"].append(body)
            return body
        if method == "PUT" and "/secrets/" in path:
            self.secrets[path.rsplit("/", 1)[-1]] = body
            return {}
        raise AssertionError((method, path))


class ProductionSigningTests(unittest.TestCase):
    def values(self):
        return {name: "dummy-" + str(i) for i, name in enumerate(signing.SECRET_NAMES)}

    def configure(self, api):
        signing.configure(api, self.values(), lambda value, key: "sealed-" + value[::-1])

    def test_uploads_only_encrypted_environment_payloads_and_retains_review(self):
        api = FakeApi()
        self.configure(api)
        self.assertEqual(set(signing.SECRET_NAMES), set(api.secrets))
        self.assertEqual("required_reviewers", api.environment["protection_rules"][0]["type"])
        self.assertIn(dict(name="v*", type="tag"), api.policies["branch_policies"])
        for payload in api.secrets.values():
            self.assertEqual({"encrypted_value", "key_id"}, set(payload))
            self.assertNotIn(payload["encrypted_value"], self.values().values())

    def test_no_upload_without_owner_review(self):
        api = FakeApi()
        api.environment["protection_rules"] = []
        with self.assertRaises(signing.ReleaseError): self.configure(api)
        self.assertFalse(any(method != "GET" for method, _, _ in api.calls))

    def test_broad_branch_policy_is_rejected_before_any_write(self):
        api = FakeApi()
        api.policies["branch_policies"].append(dict(name="*", type="branch"))
        with self.assertRaises(signing.ReleaseError): self.configure(api)
        self.assertFalse(any(method != "GET" for method, _, _ in api.calls))

    def test_existing_signing_secret_is_never_silently_overwritten(self):
        api = FakeApi()
        api.secrets[signing.SECRET_NAMES[0]] = dict(encrypted_value="previous")
        with self.assertRaises(signing.ReleaseError): self.configure(api)
        self.assertFalse(any(method != "GET" for method, _, _ in api.calls))

    def test_missing_input_fails_before_policy_or_secret_writes(self):
        api = FakeApi()
        with self.assertRaises(signing.ReleaseError):
            signing.configure(api, {}, lambda value, key: "sealed")
        self.assertFalse(any(method != "GET" for method, _, _ in api.calls))


if __name__ == "__main__":
    unittest.main()

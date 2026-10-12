from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))
import release_guard as guard

HEAD = "a" * 40
MAIN = "b" * 40


class ReleaseGuardTests(unittest.TestCase):
    def test_matching_tag_on_main_is_accepted(self):
        self.assertEqual(("publish", HEAD), guard.validate("push", "refs/tags/v1.1.6", "publish", "1.1.6", True, HEAD, MAIN))

    def test_branch_push_is_not_a_release(self):
        with self.assertRaises(ValueError):
            guard.validate("push", "refs/heads/main", "publish", "1.1.6", True, HEAD, MAIN)

    def test_tag_outside_main_is_rejected(self):
        with self.assertRaises(ValueError):
            guard.validate("push", "refs/tags/v1.1.6", "publish", "1.1.6", False, HEAD, MAIN)

    def test_wrong_tag_version_or_suffix_is_rejected(self):
        for ref in ["refs/tags/v1.1.5", "refs/tags/v1.1.6-test", "refs/tags/v1.1.6;echo bad"]:
            with self.assertRaises(ValueError):
                guard.validate("push", ref, "publish", "1.1.6", True, HEAD, MAIN)

    def test_tags_cannot_trigger_rollback(self):
        with self.assertRaises(ValueError):
            guard.validate("push", "refs/tags/v1.1.6", "rollback", "1.1.6", True, HEAD, MAIN)

    def test_manual_operations_remain_main_only(self):
        for operation in ["publish", "bootstrap", "rollback"]:
            self.assertEqual((operation, HEAD), guard.validate("workflow_dispatch", "refs/heads/main", operation, "1.1.6", True, HEAD, HEAD))
            with self.assertRaises(ValueError):
                guard.validate("workflow_dispatch", "refs/heads/other", operation, "1.1.6", True, HEAD, HEAD)

    def test_stale_manual_commit_is_rejected(self):
        with self.assertRaises(ValueError):
            guard.validate("workflow_dispatch", "refs/heads/main", "publish", "1.1.6", True, HEAD, MAIN)

    def test_untrusted_event_invalid_operation_and_sha_are_rejected(self):
        for event, operation, head in [("pull_request", "publish", HEAD), ("workflow_dispatch", "bad", HEAD), ("workflow_dispatch", "publish", "main")]:
            with self.assertRaises(ValueError):
                guard.validate(event, "refs/heads/main", operation, "1.1.6", True, head, HEAD)


if __name__ == "__main__":
    unittest.main()

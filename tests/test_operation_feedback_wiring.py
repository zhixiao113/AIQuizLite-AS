"""Source integration guards complement policy tests; they do not prove a phone motor vibrates."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "app/src/main/java/com/example/aiquizlite"


def method(source, name):
    match = re.search(r"(?:public|private|protected)\s+(?:static\s+)?[\w.<>]+\s+" + name + r"\([^)]*\)\s*\{", source)
    if not match:
        raise AssertionError("Missing method " + name)
    depth, start = 1, match.end()
    for position in range(start, len(source)):
        depth += (source[position] == "{") - (source[position] == "}")
        if depth == 0:
            return source[start:position]
    raise AssertionError("Unclosed method " + name)


class OperationFeedbackWiringTests(unittest.TestCase):
    def source(self, file):
        return (JAVA / file).read_text(encoding="utf-8")

    def test_quiz_and_review_favorites_share_one_feedback_gateway(self):
        for file in ("QuizActivity.java", "MockResultActivity.java"):
            self.assertIn("OperationFeedback.perform(source, generalSettings)", method(self.source(file), "performConfiguredHaptic"))

    def test_matching_user_edits_emit_feedback_at_the_action_boundary(self):
        source = self.source("MatchingQuestionView.java")
        for name in ("connect", "undo", "clear", "removeConnection"):
            self.assertIn("OperationFeedback.perform", method(source, name))

    def test_choice_fill_and_final_mock_confirmation_use_user_clicks(self):
        source = self.source("QuizActivity.java")
        self.assertGreaterEqual(source.count("setOnClickListener(this::performConfiguredHaptic)"), 3)
        self.assertIn("performConfiguredHaptic(primaryButton)", method(source, "showUnansweredMockWarning"))

    def test_restore_and_shared_editor_refresh_are_silent(self):
        source = self.source("MatchingQuestionView.java")
        for name in ("setSelectedPairs", "refreshSelection", "onAttachedToWindow", "setSubmitted", "setShowCorrect"):
            self.assertNotIn("OperationFeedback.perform", method(source, name))

    def test_settings_has_explicit_test_and_refreshes_after_system_settings(self):
        source = self.source("GeneralSettingsActivity.java")
        self.assertIn("OperationFeedback.perform", method(source, "testOperationFeedback"))
        self.assertIn("refreshOperationFeedbackState", method(source, "onResume"))
        self.assertIn("ACTION_SOUND_SETTINGS", source)

    def test_no_global_override_or_direct_motor_permission_is_added(self):
        source = self.source("OperationFeedback.java")
        self.assertNotIn("FLAG_IGNORE", source)
        self.assertNotIn("Vibrator", source)
        self.assertIn("HapticFeedbackConstants.VIRTUAL_KEY", source)
        self.assertNotIn("android.permission.VIBRATE", (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()

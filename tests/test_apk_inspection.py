import base64
import hashlib
from pathlib import Path
import unittest
from unittest.mock import patch

import update_server as server


CERTIFICATE = b"public certificate DER fixture"
DIGEST = hashlib.sha256(CERTIFICATE).hexdigest()
BADGING = "package: name='com.example.aiquizlite' versionCode='18' versionName='1.1.7'\n"


def pem(data=CERTIFICATE):
    return "-----BEGIN CERTIFICATE-----\n" + base64.b64encode(data).decode("ascii") + "\n-----END CERTIFICATE-----\n"


class ApkInspectionTests(unittest.TestCase):
    def inspect(self, certificates):
        with patch.object(server, "sdk_build_tools", return_value=Path("public-sdk")), \
                patch.object(server, "run_tool", side_effect=[BADGING, certificates]) as tool:
            result = server.inspect_apk(Path("public.apk"))
            self.assertIn("verify", tool.call_args.args[0])
            self.assertIn("--print-certs-pem", tool.call_args.args[0])
            return result

    def test_sdk37_real_label_format_with_verified_pem(self):
        output = "V2 Signer: certificate SHA-256 digest: " + DIGEST + "\n" + pem()
        self.assertEqual(DIGEST, self.inspect(output)["signerSha256"])

    def test_legacy_numbered_label_is_still_supported(self):
        output = "Signer #1 certificate SHA-256 digest: " + DIGEST + "\n" + pem()
        info = self.inspect(output)
        self.assertEqual((18, "1.1.7", DIGEST), (info["versionCode"], info["versionName"], info["signerSha256"]))

    def test_same_verified_certificate_across_schemes_is_not_two_keys(self):
        self.assertEqual(DIGEST, self.inspect("V2 Signer:\n" + pem() + "V3 Signer:\n" + pem())["signerSha256"])

    def test_multiple_distinct_certificates_are_rejected(self):
        with self.assertRaises(ValueError):
            self.inspect(pem() + pem(b"other public certificate"))

    def test_digest_text_without_certificate_is_not_trusted(self):
        with self.assertRaises(ValueError):
            self.inspect("Signer #1 certificate SHA-256 digest: " + DIGEST + "\n")

    def test_invalid_certificate_encoding_is_rejected(self):
        with self.assertRaises(ValueError):
            self.inspect("-----BEGIN CERTIFICATE-----\nnot!base64\n-----END CERTIFICATE-----\n")

    def test_public_key_block_is_not_a_certificate(self):
        with self.assertRaises(ValueError):
            self.inspect("-----BEGIN PUBLIC KEY-----\nYWJj\n-----END PUBLIC KEY-----\n")

    def test_failed_cryptographic_verification_is_never_parsed(self):
        with patch.object(server, "sdk_build_tools", return_value=Path("public-sdk")), \
                patch.object(server, "run_tool", side_effect=[BADGING, ValueError("DOES NOT VERIFY")]):
            with self.assertRaises(ValueError):
                server.inspect_apk(Path("public.apk"))


if __name__ == "__main__":
    unittest.main()

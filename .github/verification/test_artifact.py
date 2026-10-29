import hashlib
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from artifact import checked_apk_bytes, inspect_apk


class ArtifactTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.archive = Path(self.directory.name) / "artifact.zip"
        self.apk = b"original producer bytes"
        self.checksum = hashlib.sha256(self.apk).hexdigest()

    def bundle(self, checksum=None, extra=False, duplicate=False):
        with zipfile.ZipFile(self.archive, "w") as bundle:
            bundle.writestr("app-debug.apk", self.apk)
            bundle.writestr("app-debug.apk.sha256", (checksum or self.checksum) + "  app-debug.apk\n")
            if extra:
                bundle.writestr("other.txt", "unexpected")
            if duplicate:
                import warnings
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    bundle.writestr("app-debug.apk", self.apk)
        return "sha256:" + hashlib.sha256(self.archive.read_bytes()).hexdigest()

    def test_preserves_original_bytes_and_checksum(self):
        self.assertEqual((self.apk, self.checksum), checked_apk_bytes(self.archive, self.bundle()))

    def test_wrong_outer_digest_rejects_download(self):
        self.bundle()
        with self.assertRaises(ValueError):
            checked_apk_bytes(self.archive, "sha256:" + "0" * 64)

    def test_inner_checksum_is_independently_verified(self):
        digest = self.bundle(checksum="0" * 64)
        with self.assertRaises(ValueError):
            checked_apk_bytes(self.archive, digest)

    def test_unexpected_and_duplicate_members_are_rejected(self):
        for options in ({"extra": True}, {"duplicate": True}):
            with self.subTest(options=options):
                digest = self.bundle(**options)
                with self.assertRaises(ValueError):
                    checked_apk_bytes(self.archive, digest)

    def test_member_bounds_are_checked_before_reading(self):
        digest = self.bundle()
        with patch("artifact.MAX_APK_BYTES", len(self.apk) - 1), self.assertRaises(ValueError):
            checked_apk_bytes(self.archive, digest)


class InspectionTest(unittest.TestCase):
    certificate = "b" * 64
    signature = "Signer #1 certificate SHA-256 digest: " + certificate + "\n"
    badging = "package: name='io.github.anastaci1a.localica.debug' versionCode='78' versionName='0.29.1-dev78'\n"

    def inspect(self, **expected):
        values = dict(version="0.29.1-dev78", version_code=78, certificate=self.certificate)
        values.update(expected)
        return inspect_apk(Path("/owned/app.apk"), Path("/sdk"), **values)

    def responses(self, signature=None, badging=None):
        return [subprocess.CompletedProcess([], 0, self.signature if signature is None else signature),
                subprocess.CompletedProcess([], 0, self.badging if badging is None else badging)]

    @patch("artifact.subprocess.run")
    def test_exact_identity_requires_successful_sdk_verification_and_both_versions(self, run):
        run.side_effect = self.responses()
        result = self.inspect()
        self.assertEqual({"package": "io.github.anastaci1a.localica.debug", "version_code": 78,
                          "version_name": "0.29.1-dev78", "signer_certificates_sha256": [self.certificate]}, result)
        self.assertEqual(["/sdk/apksigner", "verify", "--print-certs", "/owned/app.apk"], run.call_args_list[0].args[0])
        self.assertEqual(["/sdk/aapt", "dump", "badging", "/owned/app.apk"], run.call_args_list[1].args[0])
        self.assertTrue(all(call.kwargs == dict(check=True, capture_output=True, text=True)
                            for call in run.call_args_list))

    @patch("artifact.subprocess.run")
    def test_package_code_and_name_are_independently_required(self, run):
        for old, new in [(".debug'", "'"), ("versionCode='78'", "versionCode='79'"),
                         ("versionName='0.29.1-dev78'", "versionName='0.29.1'")]:
            with self.subTest(field=old):
                run.side_effect = self.responses(badging=self.badging.replace(old, new))
                with self.assertRaises(ValueError):
                    self.inspect()

    @patch("artifact.subprocess.run")
    def test_extra_duplicate_or_malformed_signers_fail_before_badging(self, run):
        invalid = [self.signature + self.signature,
                   self.signature + self.signature.replace("#1", "#2"),
                   self.signature + "Signer #2 public key SHA-256 digest: " + "c" * 64 + "\n",
                   self.signature.replace("#1", "#01"), self.signature.replace("#1", "#x"),
                   self.signature.replace(self.certificate, "c" * 64),
                   self.signature.replace(self.certificate, "short"),
                   self.signature + "Number of signers: 2\n", ""]
        for signature in invalid:
            with self.subTest(signature=signature):
                run.reset_mock()
                run.side_effect = self.responses(signature=signature)
                with self.assertRaises(ValueError):
                    self.inspect()
                self.assertEqual(1, run.call_count)

    @patch("artifact.subprocess.run")
    def test_missing_duplicate_or_malformed_package_record_is_rejected(self, run):
        for badging in ["", self.badging * 2, self.badging + "package: malformed\n",
                        self.badging.replace("versionCode='78'", "versionCode='x'")]:
            with self.subTest(badging=badging):
                run.side_effect = self.responses(badging=badging)
                with self.assertRaises(ValueError):
                    self.inspect()

    @patch("artifact.subprocess.run")
    def test_invalid_expected_identity_never_invokes_sdk(self, run):
        for expected in [dict(version_code=True), dict(version_code=0), dict(version_code=-1),
                         dict(version_code=2 ** 31), dict(version_code="78"),
                         dict(version=""), dict(version=None), dict(certificate=""),
                         dict(certificate="B" * 64), dict(certificate=None)]:
            with self.subTest(expected=expected):
                with self.assertRaises(ValueError):
                    self.inspect(**expected)
        run.assert_not_called()

    @patch("artifact.subprocess.run")
    def test_sdk_failure_is_not_replaced_by_printed_identity(self, run):
        failure = subprocess.CalledProcessError(1, ["apksigner"], output=self.signature)
        run.side_effect = failure
        with self.assertRaises(subprocess.CalledProcessError) as raised:
            self.inspect()
        self.assertIs(failure, raised.exception)
        self.assertEqual(1, run.call_count)
        run.reset_mock()
        missing = FileNotFoundError("aapt unavailable")
        run.side_effect = [self.responses()[0], missing]
        with self.assertRaises(FileNotFoundError) as raised:
            self.inspect()
        self.assertIs(missing, raised.exception)


if __name__ == "__main__":
    unittest.main()

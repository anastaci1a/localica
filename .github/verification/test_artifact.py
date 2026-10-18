import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from artifact import checked_apk_bytes


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


if __name__ == "__main__":
    unittest.main()

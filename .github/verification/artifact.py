"""Check the original artifact bytes before considering an APK reusable."""

import hashlib
from pathlib import Path
import re
import subprocess
import zipfile


MAX_APK_BYTES = 128 * 1024 * 1024


def checked_apk_bytes(archive, expected_digest):
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", expected_digest):
        raise ValueError("Missing GitHub artifact digest")
    digest = hashlib.sha256()
    with Path(archive).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    if "sha256:" + digest.hexdigest() != expected_digest:
        raise ValueError("Downloaded archive does not match GitHub's artifact digest")
    with zipfile.ZipFile(archive) as bundle:
        if sorted(bundle.namelist()) != ["app-debug.apk", "app-debug.apk.sha256"]:
            raise ValueError("Unexpected or duplicate artifact members")
        if not 0 < bundle.getinfo("app-debug.apk").file_size <= MAX_APK_BYTES or \
                bundle.getinfo("app-debug.apk.sha256").file_size > 256:
            raise ValueError("Artifact members exceed their bounds")
        checksum = bundle.read("app-debug.apk.sha256").decode("ascii")
        match = re.fullmatch(r"([0-9a-f]{64})  app-debug\.apk\n?", checksum)
        apk = bundle.read("app-debug.apk")
    if match is None or hashlib.sha256(apk).hexdigest() != match.group(1):
        raise ValueError("APK does not match its original producer checksum")
    return apk, match.group(1)


def inspect_apk(apk, build_tools, *, version, certificate):
    """Use the SDK's verifier; comparing a printed certificate alone is insufficient."""
    signature = subprocess.run(
        [str(Path(build_tools) / "apksigner"), "verify", "--print-certs", str(apk)],
        check=True, capture_output=True, text=True,
    ).stdout
    certificates = re.findall(r"^Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})$", signature, re.M)
    if certificates != [certificate]:
        raise ValueError("APK does not have the expected development signing certificate")
    badging = subprocess.run([str(Path(build_tools) / "aapt"), "dump", "badging", str(apk)],
                             check=True, capture_output=True, text=True).stdout
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.M)
    if package is None or package.group(1) != "io.github.anastaci1a.localica.debug" or package.group(3) != version:
        raise ValueError("APK package/version does not match the intended development release")
    return {"package": package.group(1), "version_code": int(package.group(2)),
            "version_name": package.group(3), "certificate_sha256": certificates[0]}

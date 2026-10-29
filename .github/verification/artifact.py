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


def inspect_apk(apk, build_tools, *, version, version_code, certificate):
    """Verify the intended identity with the SDK; this alone never authorizes reuse.

    The development release policy has one signer, not merely an expected signer
    somewhere in a larger set. APK bytes/provenance must be bound by the caller.
    """
    if (not isinstance(version, str) or not version or
            type(version_code) is not int or not 0 < version_code < 2 ** 31 or
            not isinstance(certificate, str) or not re.fullmatch(r"[0-9a-f]{64}", certificate)):
        raise ValueError("Missing or invalid expected APK identity")
    signature = subprocess.run(
        [str(Path(build_tools) / "apksigner"), "verify", "--print-certs", str(apk)],
        check=True, capture_output=True, text=True,
    ).stdout
    signer_numbers = set(re.findall(r"^Signer #([^ ]+) ", signature, re.M))
    certificate_lines = [line for line in signature.splitlines()
                         if line.startswith("Signer #") and "certificate SHA-256" in line]
    counts = re.findall(r"^Number of signers: (.*)$", signature, re.M)
    if (signer_numbers != {"1"} or
            certificate_lines != ["Signer #1 certificate SHA-256 digest: " + certificate] or
            (counts and counts != ["1"])):
        raise ValueError("APK does not have exactly the expected development signer")
    badging = subprocess.run([str(Path(build_tools) / "aapt"), "dump", "badging", str(apk)],
                             check=True, capture_output=True, text=True).stdout
    records = [line for line in badging.splitlines() if line.startswith("package:")]
    package = re.match(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'(?: |$)",
                       records[0]) if len(records) == 1 else None
    if (package is None or package.group(1) != "io.github.anastaci1a.localica.debug" or
            int(package.group(2)) != version_code or package.group(3) != version):
        raise ValueError("APK package/version does not match the intended development release")
    return {"package": package.group(1), "version_code": int(package.group(2)),
            "version_name": package.group(3), "signer_certificates_sha256": [certificate]}

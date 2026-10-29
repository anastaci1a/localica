"""Read-only source/metadata/APK identity audit, never reuse authorization.

The caller must obtain complete API metadata directly from GitHub. This first
orchestration slice does not admit reports, toolchains or policy bootstrap, so
even a successful identity audit explicitly remains ineligible for reuse.
Nothing here dispatches, skips or publishes a job or changes Git references.
"""

import hashlib
import json
from pathlib import Path
import re
import tempfile

from artifact import checked_apk_bytes, inspect_apk
from equivalence import compare, git
from evidence import eligible_metadata


MAX_ARCHIVE_BYTES = 256 * 1024 * 1024
PENDING_CHECKS = (
    "immutable_executed_policy_bundle",
    "complete_original_reports",
    "resolved_toolchain_dependencies_environment",
    "normal_full_policy_bootstrap",
    "publication_state_and_bytes_recheck",
)


def sha256(value):
    return hashlib.sha256(value).hexdigest()


def canonical_json(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


def committed_version(repo, commit):
    """Only the repository's literal assignment shape is supported, not arbitrary Gradle.

    These are committed expectations for SDK comparison, not resolved Gradle or
    toolchain evidence. Ambiguous/dynamic assignments require further work.
    """
    source = git(repo, "show", commit + ":app/build.gradle.kts").decode("utf-8")
    codes = re.findall(r"^\s*versionCode\s*=\s*(.*?)\s*$", source, re.M)
    names = re.findall(r"^\s*versionName\s*=\s*(.*?)\s*$", source, re.M)
    if (len(codes) != 1 or len(names) != 1 or
            len(re.findall(r"\bversionCode\b", source)) != 1 or
            len(re.findall(r"\bversionName\b", source)) != 1 or
            not re.fullmatch(r"[1-9][0-9]*", codes[0]) or
            not re.fullmatch(r'"[A-Za-z0-9.+_-]+"', names[0])):
        raise ValueError("Require unambiguous literal committed version assignments")
    return int(codes[0]), names[0][1:-1]


def snapshot_archive(original, destination):
    """Hash/check a private snapshot, not a path reopened after its digest check."""
    total = 0
    with Path(original).open("rb") as source, destination.open("xb") as target:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            total += len(chunk)
            if total > MAX_ARCHIVE_BYTES:
                raise ValueError("Canonical archive exceeds the audit size bound")
            target.write(chunk)


def audit_identity(repo, producer, release, run, jobs, artifacts, archive, build_tools,
                   *, repository, workflow_id, certificate):
    # Freeze JSON values so receipt hashes describe the exact metadata consumed,
    # even when a caller retains mutable references to its original dictionaries.
    metadata_bytes = {name: canonical_json(value) for name, value in
                      (("run", run), ("jobs", jobs), ("artifacts", artifacts))}
    values = {name: json.loads(value) for name, value in metadata_bytes.items()}
    equivalence = compare(repo, producer, release)
    if not equivalence["equivalent"]:
        raise ValueError("Committed verification inputs are not equivalent")
    producer_commit = equivalence["producer"]["commit"]
    metadata = eligible_metadata(values["run"], values["jobs"], values["artifacts"],
                                 repository=repository, workflow_id=workflow_id,
                                 producer_commit=producer_commit)
    code, version = committed_version(repo, equivalence["release"]["commit"])
    expected_identity = {"package": "io.github.anastaci1a.localica.debug",
                         "version_code": code, "version_name": version,
                         "signer_certificates_sha256": [certificate]}
    directory = tempfile.TemporaryDirectory(prefix="localica-identity-audit-")
    primary_failure = None
    try:
        temporary = Path(directory.name)
        snapshot = temporary / "original.zip"
        snapshot_archive(archive, snapshot)
        apk_bytes, apk_hash = checked_apk_bytes(snapshot, metadata["artifact_digest"])
        inspected = temporary / "app-debug.apk"
        inspected.write_bytes(apk_bytes)
        identity = inspect_apk(inspected, build_tools, version=version,
                               version_code=code, certificate=certificate)
        if sha256(inspected.read_bytes()) != apk_hash:
            raise ValueError("SDK-inspected APK bytes changed during the identity audit")
        if identity != expected_identity:
            raise ValueError("SDK identity contradicts the committed expectations")
    except BaseException as failure:
        primary_failure = failure
        raise
    finally:
        try:
            directory.cleanup()
        except BaseException as cleanup_failure:
            if primary_failure is None:
                raise
            # Preserve the exact SDK/inspection failure and its traceback. The
            # secondary exception remains machine-readable as well as visible.
            primary_failure.localica_cleanup_failures = (
                *getattr(primary_failure, "localica_cleanup_failures", ()), cleanup_failure)
            if hasattr(primary_failure, "add_note"):
                primary_failure.add_note("Audit temporary cleanup also failed: " + repr(cleanup_failure))
    # These bytes are observations of siblings on disk after the audit, not a
    # fingerprint of imported code. Imports can come from elsewhere or predate
    # file replacement; immutable executed-bundle admission is still pending.
    policies = {name: sha256(Path(__file__).with_name(name).read_bytes())
                for name in ("reuse.py", "equivalence.py", "evidence.py", "artifact.py")}
    return {
        "schema": 1,
        "scope": "source_metadata_apk_identity_only",
        "identity_audit_passed": True,
        "eligible_for_reuse": False,
        "unimplemented_admission_checks": list(PENDING_CHECKS),
        "operator_release_approval": "separate_required_action",
        "equivalence": equivalence,
        "metadata": metadata,
        "canonical_metadata_sha256": {name: sha256(value) for name, value in metadata_bytes.items()},
        "policy_observation_scope": "post_audit_sibling_disk_bytes_not_executed_code",
        "post_audit_policy_files_sha256": policies,
        "apk_sha256": apk_hash,
        "apk_identity": identity,
        "limits": [
            "Metadata snapshots must be fetched from GitHub by the trusted caller.",
            "Literal committed version expectations are not resolved build-configuration proof.",
            "Sibling file hashes are post-audit disk observations, not executed-code fingerprints.",
            "No complete report, native tested-APK/overlay or toolchain admission is implemented.",
            "No workflow integration, job skipping, publication or release authorization.",
        ],
    }

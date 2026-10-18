"""Validate GitHub API metadata; APK checksum/certificate checks remain mandatory."""

import re
from datetime import datetime


JOBS = ("build", "device-tests (26)", "device-tests (36)",
        "compatibility (26)", "compatibility (36)")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def eligible_metadata(run, jobs, artifacts, *, repository, workflow_id, producer_commit):
    """Inputs must be fetched from GitHub for this run, never from PR artifacts.

    Start with successful main/push producers only. This preserves the existing
    release trust boundary; equivalence alone cannot turn a PR into a producer.
    """
    require(run.get("repository", {}).get("full_name") == repository and
            run.get("head_repository", {}).get("full_name") == repository,
            "Producer must belong to this repository")
    repository_id = run.get("repository", {}).get("id")
    require(type(repository_id) is int and repository_id > 0 and
            run.get("head_repository", {}).get("id") == repository_id,
            "Missing or mismatched repository identity")
    require(run.get("workflow_id") == workflow_id and run.get("path") == ".github/workflows/android.yml",
            "Unexpected producer workflow")
    require(run.get("head_sha") == producer_commit and re.fullmatch(r"[0-9a-f]{40}", producer_commit),
            "Unexpected producer commit")
    require(run.get("event") == "push" and run.get("head_branch") == "main",
            "Producer must be a main push")
    require(run.get("status") == "completed" and run.get("conclusion") == "success",
            "Producer run did not finish successfully")
    run_id = run.get("id")
    attempt = run.get("run_attempt")
    require(type(run_id) is int and run_id > 0 and type(attempt) is int and attempt > 0,
            "Missing producer run/attempt identity")
    rows = jobs.get("jobs", [])
    require(jobs.get("total_count") == len(rows), "Incomplete job metadata")
    selected = []
    for name in JOBS:
        matches = [job for job in rows if job.get("name") == name]
        require(len(matches) == 1, "Missing or ambiguous required job: " + name)
        job = matches[0]
        require(job.get("run_id") == run_id and job.get("run_attempt") == attempt and
                job.get("head_sha") == producer_commit,
                "Required job belongs to another run/attempt/commit: " + name)
        require(job.get("status") == "completed" and job.get("conclusion") == "success",
                "Required job did not pass: " + name)
        require(type(job.get("id")) is int and job["id"] > 0, "Missing required job identity: " + name)
        selected.append({"id": job["id"], "name": name})
    items = artifacts.get("artifacts", [])
    require(artifacts.get("total_count") == len(items), "Incomplete artifact metadata")
    matches = [artifact for artifact in items if artifact.get("name") == "localica-debug"]
    require(len(matches) == 1, "Missing or ambiguous canonical APK artifact")
    artifact = matches[0]
    require(artifact.get("expired") is False, "Canonical artifact is expired")
    require(type(artifact.get("id")) is int and artifact["id"] > 0 and
            isinstance(artifact.get("digest"), str) and
            re.fullmatch(r"sha256:[0-9a-f]{64}", artifact["digest"]),
            "Missing canonical artifact identity/digest")
    producer = artifact.get("workflow_run", {})
    require(producer.get("id") == run_id and producer.get("head_sha") == producer_commit and
            producer.get("head_branch") == "main" and
            producer.get("repository_id") == run.get("repository", {}).get("id") and
            producer.get("head_repository_id") == run.get("repository", {}).get("id"),
            "Canonical artifact belongs to another producer")
    # Artifact metadata does not include the attempt number. Require creation
    # during this attempt's successful build, rather than adopting an old upload.
    build = next(job for job in rows if job["name"] == "build")
    try:
        started, created, completed = (datetime.fromisoformat(value.replace("Z", "+00:00"))
                                      for value in (build["started_at"], artifact["created_at"], build["completed_at"]))
        require(all(value.tzinfo is not None for value in (started, created, completed)) and
                started <= created <= completed, "Artifact was not produced by this build attempt")
    except (KeyError, AttributeError, TypeError) as error:
        raise ValueError("Missing artifact/build attempt timing") from error
    return {
        "producer_run_id": run_id,
        "producer_attempt": attempt,
        "producer_commit": producer_commit,
        "workflow_id": workflow_id,
        "required_jobs": selected,
        "artifact_id": artifact["id"],
        "artifact_digest": artifact["digest"],
    }

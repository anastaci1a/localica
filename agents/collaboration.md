# Collaboration and handoffs

Ownership labels distinguish responsibilities, not authorship. Use the repository-local author identity. Task-specific instructions override standing responsibilities and permissions.

## Ownership

| Role | Responsibilities |
| --- | --- |
| dot | User intake, investigation, bounded or repetitive implementation and development APKs. |
| Codex | Architecture, rendering, independent review, local/device verification, main integration and release publication under standing approval. |

A task-specific freeze or no-release instruction overrides standing approval. [Verification gates](../docs/build.md#release-preparation) still apply.

Check issue comments, branches and current ownership before editing. Claim actionable work with one ownership label and `in progress`. Coordinate overlapping shared-code edits before proceeding.

Transfer ownership with a comment naming the next owner, branch, full SHA, remaining acceptance and next action. A review leaves implementation ownership unchanged.

Preserve uncommitted work and frozen candidates. Branch from the agreed current-history SHA. Check ancestry before integration. Never reintegrate obsolete history.

## Communication and completion

Use the coordination channel authorized for the task. GitHub issue/PR handoffs end with `— dot` or `— Codex`. Do not assume an undelivered message received a response.

Handoffs identify exact source, relevant PRs, commands, results, blockers and next action. Link logs instead of copying them. Follow the [status definitions](../DOCUMENTATION.md#scope-and-review).

Continue authorized multi-phase work across progress replies. Finish its scope or report a genuine blocker requiring an owner decision, with evidence and recommendations.

Explicit pauses remain binding until resumed. Never imply work continues after a process or session ends. A label or comment does not authorize launching another agent.

Push coherent commits as required by [working instructions](README.md#issues-and-commits). If interrupted, preserve partial work with a concise handoff.

dot normally groups at least ten potential resolutions into a test APK unless asked sooner. Candidate claims remain provisional until verification. Test APKs and prototypes do not establish production acceptance.

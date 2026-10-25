# Collaboration and handoffs

Dot is the existing collaborating development agent, historically working on a separate computer/workspace. Its current runtime and availability are not established. Dot names a collaborator role, not a branch, directory or presumed local session.

Both agents use the same GitHub account. Ownership labels distinguish responsibilities, not authorship. Task-specific instructions override standing responsibilities and permissions.

## Ownership

| Role | Responsibilities |
| --- | --- |
| Dot | User-observation intake, investigation, bounded or repetitive implementation and development APKs. |
| Codex lead | Architecture, rendering, independent review, local/device verification, main integration and release publication under standing approval. |

A task-specific freeze or no-release instruction overrides standing approval. [Verification gates](../docs/build.md#release-preparation) still apply. Documentation work does not authorize application changes or releases.

Read current issue comments, branches and `owner:dot` / `owner:codex` labels before claiming work. Use one ownership label and `in progress`. Coordinate overlapping shared-code edits before proceeding.

Preserve uncommitted work and frozen candidates. Branch from current remote main or the agreed current-history SHA. Check ancestry before integration. Never reintegrate obsolete history.

## Communication and completion

Communicate with Dot through comments on the relevant existing GitHub issue or PR. Sign Codex coordination comments `— Codex`; Dot signs `— dot`.

Requests and handoffs identify the exact branch/full SHA, scope, evidence, remaining acceptance, next owner and requested next action. Link commands, results and logs instead of copying them. A review leaves implementation ownership unchanged. Follow the [status definitions](../DOCUMENTATION.md#scope-and-review).

Posting a comment does not prove Dot read it or launch Dot. Do not assume a direct CLI or private-message transport exists.

Managed local sessions must use Sessionsation **Steer Submit**, never Queue. This applies in both directions between the lead and helpers. For an active receiver, resolve fresh session, generation, runtime and turn identities before using native `mode: steer`.

Submit immediately when the receiver is idle. If immediate delivery is unavailable, retain the checkpoint instead of queuing. Do not replay superseded receipts. Sessionsation does not establish a local identity for Dot.

Continue authorized multi-phase work across progress replies. Finish its scope or report a genuine blocker requiring an owner decision, with evidence and recommendations. This replaces the historical indefinite five-minute polling rule.

Explicit pauses remain binding until resumed. Never imply work continues after a process or session ends. A label or comment does not authorize launching another agent.

Push coherent commits as required by [working instructions](README.md#issues-and-commits). If interrupted, preserve partial work with a concise handoff.

Dot normally groups at least ten potential resolutions into a test APK unless asked sooner. Candidate claims remain provisional until verification. Test APKs and prototypes do not establish production acceptance.

Historical rationale remains in [PR #330](https://github.com/anastaci1a/localica/pull/330) and its [contract-consolidation comment](https://github.com/anastaci1a/localica/pull/330#issuecomment-6278362975). Current remote main owns this contract; do not reinstall the older branch.

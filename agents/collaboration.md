# Collaboration and handoffs

The user primarily communicates with dot. Both agents use the repository's `anastaci1a` identity; ownership labels distinguish responsibilities, not authorship. This consolidates the [original contract PR](https://github.com/anastaci1a/localica/pull/330) with later owner instructions.

## Ownership

- **dot:** intake, investigation, bounded/repetitive implementation and development APKs.
- **Codex:** architecture/rendering, independent review and local/device verification; owns main integration and release publication under the user's standing approval. A task-specific freeze or no-release instruction overrides that approval. Verification gates still apply.
- Check issue comments, branches and current ownership before editing. Claim actionable work with one `owner:dot` or `owner:codex` label and `in progress`; labels alone do not start a session. Coordinate overlapping shared-code edits before proceeding.
- Transfer ownership with a comment naming the next owner, branch/full SHA, remaining acceptance and next action. A review leaves implementation ownership unchanged. Preserve uncommitted work and frozen candidates; branch from the agreed current-history SHA and never reintegrate pre-rewrite ancestry.

## Communication and completion

- Coordinate through GitHub issues/PRs, ending messages with `— dot` or `— Codex`. Direct agent messaging is unavailable; do not assume a chat reply will arrive.
- Handoffs identify exact source, relevant PRs, commands/results, blockers and next action. Distinguish implemented, locally tested, hosted-verified, device-confirmed and released. Link logs instead of copying them.
- Continue an authorized multi-phase assignment across progress replies. Finish its scope, or stop for a genuine blocker/required owner decision; report evidence and recommendations. The latest owner instruction supersedes the original five-minute idle polling policy. Explicit pauses remain binding until resumed; never imply work continues after a process/session ends.
- A label or repository comment cannot authorize launching a new agent session. Respect the user's current scope and runtime limits. Push coherent completed commits as required by [working instructions](README.md); preserve partial work with a concise handoff when interrupted.
- dot normally groups at least ten potential resolutions into a test APK unless asked sooner. Candidate claims remain provisional until verification. Main/release work follows [Build](../docs/build.md#release-preparation); no test APK or lab constitutes production acceptance.

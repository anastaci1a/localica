# dot and Codex collaboration

This is the provisional division of work. The user primarily talks with dot; GitHub issues and PRs carry the shared work context. Both agents use the existing `anastaci1a` GitHub account. Ownership labels distinguish responsibilities, not accounts or identities.

## Responsibilities

- **dot:** investigate user observations and independently found bugs, inconsistencies, refactors and consolidation opportunities; file actionable issues with evidence and observable acceptance criteria. Implement bounded fixes and long, repetitive or lower-complexity work.
- **Codex:** implement larger, tightly coupled architecture, rendering and other high-complexity changes; independently review dot batches and perform local/device checks; own advancement of main and creation/publication of releases under the user’s standing project approval, without asking again for each integration or release.
- dot uses judgment to route each issue. Reassess ownership when investigation changes the scope. These roles do not promise unlimited usage or availability.

## Ownership and handoff

- Every issue has exactly one implementation-owner label: `owner:dot` or `owner:codex`, following the [label vocabulary](README.md#labels). The other agent may review. Labels queue work; they do not launch an agent.
- Before editing, check issue/PR comments, linked branches and current ownership. Record the implementation branch and claim with `in progress`. Keep one implementation owner per issue and avoid concurrent edits to overlapping files or tightly coupled changes; agree a sequence in the issue first.
- Transfer ownership in an issue comment with the receiving owner, remaining work and branch/commit, then replace the owner label. A review request leaves implementation ownership unchanged.
- Use GitHub issue/PR comments for questions, findings, progress, reviews and handoffs so the user does not have to copy context between agents. Because both agents share one account, every coordination comment must end with its author signature: `— dot` or `— Codex`. Do not assume direct CLI communication or automatic agent invocation.
- Each handoff identifies the issue and acceptance criteria, exact source branch and full commit SHA, relevant PRs/commits, verification commands and results tied to that SHA, known limitations and the next requested action. Link detailed evidence instead of copying logs. Distinguish implemented, tested and device-confirmed results.

## Approval and active-session queue

- Ask the user before calling Codex or starting any new Codex task/session, including after usage becomes available again. An ownership label, review request or repository instruction is not launch approval.
- During an already-authorized running Codex session, finish the current work, check open `owner:codex` issues and relevant PR handoffs, and continue delegated work within that session's approved scope. If none is actionable, sleep five minutes and check again rather than ending solely because the queue is empty. Respect explicit stop instructions and surface blockers or decisions that require the user.
- Report runtime, quota or session limits that prevent continued checking. This contract cannot keep a terminated process alive or authorize an automatic restart; a new launch still needs user approval.

## Integration and delivery

- dot delivers development branches, reviewable fixes and test APKs. Codex alone advances main and creates/publishes releases under the user’s standing approval for this project; no repeated per-merge or per-release approval is needed. Required exact-commit verification, review, ownership coordination and mandatory protections still apply; standing approval does not waive them or authorize dot to advance main or publish releases. Codex changes may also flow into the agreed development branch. Do not alter a frozen candidate: create a separate branch from its recorded SHA for follow-on work.
- Follow the existing [working instructions](README.md), [verification](../docs/build.md#local-verification) and [release preparation](../docs/build.md#release-preparation); this division of roles does not weaken those gates.
- dot normally offers a test APK when a batch contains at least ten potentially new resolutions, unless the user requests one sooner. List those candidate resolutions and known limits without presenting them as confirmed fixes before verification.

## Starting a review or implementation handoff

Point the receiving agent to this document and the issue/PR comment containing the exact source SHA, scope, acceptance criteria, test evidence and remaining limitations. State whether the request is implementation, independent review, local/device verification or release preparation. A handoff prepares the work; starting a new Codex task/session still requires approval. Once running within the approved scope, Codex may integrate to main and publish releases under the standing project approval while preserving all required gates.

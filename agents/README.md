# Working instructions

Read the [documentation map](../docs/README.md) before editing. Follow the [documentation standard](../DOCUMENTATION.md) and [collaboration contract](collaboration.md). Task-specific owner instructions take precedence.

## Component integration

Before integrating a component change, review its shared owner, regression coverage and [engineering rules](../AGENTS.md). Update its authoritative documentation in the same change.

## Verification coordination

Follow [development and final validation](../docs/development/testing.md). The Localica lead initiates focused local checks. Freeze a complete candidate before full validation; coordinate parallel comparisons and any cancellation with the parent and run owner.

## Agent communication

Use `sessionsation` from PATH with a fresh target: Steer Submit while the recipient is active, immediate Submit while idle. Never Queue, replay an accepted input or substitute a new session for recovery of an existing attachment.

Keep messages short and separate longer messages into paragraphs.

**Sessionsation only:** use prose with file/module references, without diffs, code, file contents or attachments. Enclose messages in `-- INCOMING MSG: <sender_session_name> --` and `-- MSG END (<actual_UTC_ISO_timestamp>) --`, with one blank line between each delimiter and the body.

**GitHub and other online communication:** use ordinary formatting without the Sessionsation envelope; a short session-name signature such as `–localica-lead` is fine. GitHub attachments are allowed.

## Issues and commits

[Issues](https://github.com/anastaci1a/localica/issues) own actionable work and acceptance criteria. Use the [bug](../.github/ISSUE_TEMPLATE/bug_report.md) or [feature](../.github/ISSUE_TEMPLATE/feature_request.md) template, including CLI/API submissions. Omit irrelevant optional fields.

Write short issue bullets. Bugs explain observed and expected behavior, reproduction and known cause. Features state the request and observable completion. Link supporting evidence.

Keep commits coherent and reviewable. Separate unrelated cleanup, implementation and release automation. Reference relevant issues and use descriptive imperative subjects.

Use the repository-local author identity. Inspect Git state and appropriate verification before committing. Audit staged content for secrets and report material limitations.

Commit checks reject every coauthor trailer and AI session or generated attribution line. Manual dispatches and new refs scan all reachable commits. Updates to existing refs inspect their pushed range.

Push every commit immediately and verify the remote branch tip. Never leave completed work only locally.

Delete task-owned alias and proof branches when the task finishes. Preserve commits in main or a verified local bundle before deleting unmerged refs. Keep active, paused and uncertain branches.

Use `umask 0002` when creating workspace contents.

### Labels

Use one type and usually one or two areas. Add workflow labels only while applicable. GitHub's open/closed state owns completion.

| Purpose | Labels |
| --- | --- |
| Type | `bug`, `enhancement`, `documentation`, `maintenance` |
| Area | `area: library`, `area: playback`, `area: interface`, `area: storage`, `area: build` |
| Workflow | `in progress`, `blocked`, `question` |
| Ownership | `owner:dot`, `owner:codex`. See the collaboration contract. |
| Accessibility | `accessibility`, plus type and area. |
| Contributions | `help wanted`; `good first issue` for bounded, approachable work. |
| Closure reason | `duplicate`, `invalid`, `wontfix`, with a short reason or link. |

Reuse this vocabulary. Add labels only for recurring gaps and update the table. Do not invent priorities or duplicate roadmap milestones.

## Releases

Follow [release preparation](../docs/development/release.md), including its exact-commit gate and [release template](../.github/RELEASE_TEMPLATE.md).

Use `vX.X.X` tags matching app versions: major for breaking changes, minor for features and patch for fixes. Leave published release descriptions unchanged unless requested.

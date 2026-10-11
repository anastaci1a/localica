# Working instructions

Read the relevant page in the [documentation map](../docs/README.md) before editing. Product contracts belong in `docs/`; this file owns working practices.

## Passive documentation maintenance

**Consolidate as part of every relevant change. Do not wait for a separate cleanup task.**

- Give each fact one authoritative home, following the map. Link to it elsewhere; do not restate contracts in the roadmap, architecture, release notes or multiple settings inventories.
- Edit the existing rule in place. In the same pass, remove superseded statements, completed plans and nearby repetition. Aim for the touched section to stay the same size or shrink; new length must convey a new durable decision.
- Keep the current choice, necessary rationale, defaults, exceptions and constraints. Preserve specific user-agreed behavior while removing repeated explanations, implementation narration and exhaustive inventories already maintained in code/tests.
- Use short focused sections, bullets for independent rules, and tables for defaults/comparisons. Break dense mixed-topic paragraphs by responsibility. Split a document only for a distinct reader/task; prefer consolidating existing sections over adding files.
- The roadmap owns upcoming outcomes/dependencies, Issues own tasks/acceptance criteria, and Git/Releases own history. Never append session logs, release-by-release summaries, duplicated ticket lists or stale completion notes to living docs.
- Before committing docs, review the diff for duplicated facts, stale tense/current-vs-planned ambiguity and unnecessary growth; check moved headings/links. Touch only affected documents. A docs-only change needs these checks, not an Android build.
- Keep the public README short with current real-app previews. Follow [preview maintenance](../docs/build.md#previews) during release preparation.

## Issues and commits

- [GitHub Issues](https://github.com/anastaci1a/localica/issues) track bugs/actionable work. Use the [bug](../.github/ISSUE_TEMPLATE/bug_report.md) or [feature](../.github/ISSUE_TEMPLATE/feature_request.md) template, including for CLI/API submissions; omit irrelevant optional fields.
- Issues and replies must be short, direct bullets. Bugs describe observed/expected effects, reproducibility and known cause; features state the request and observable completion. Link supporting detail instead of copying narratives/logs.
- Keep commits coherent and reviewable; separate unrelated cleanup, implementation and release automation. Reference relevant issues, use descriptive imperative subjects and repository-local `anastaci1a` identity. Check the working tree and appropriate verification; report material limitations plainly.
- **Push every commit.** Never leave completed work only locally.

### Labels

Use one type and usually one or two areas. Add workflow labels only while applicable and remove them on closure; GitHub's open/closed state owns completion. Reuse this vocabulary; add labels only for recurring gaps, updating this table.

| Purpose | Labels |
| --- | --- |
| Type | `bug` (broken behavior), `enhancement` (capability/improvement), `documentation`, `maintenance` (internal/tooling). |
| Area | `area: library` (index/metadata/queries), `area: playback` (audio/queues/statistics), `area: interface`, `area: storage` (access/persistence/recovery), `area: build` (CI/releases). |
| Workflow | `in progress`, `blocked` (state/link dependency), `question` (state missing decision). |
| Accessibility | `accessibility`, plus type/area. |
| Contributions | `help wanted`; `good first issue` for bounded, approachable work. |
| Closure reason | `duplicate`, `invalid`, `wontfix`, with a short reason or link. |

Do not invent priorities or duplicate roadmap milestones with labels.

## Releases

Follow [Build → Release preparation](../docs/build.md#release-preparation), including its exact-commit verification gate, preview review and [release template](../.github/RELEASE_TEMPLATE.md). Use `vX.X.X` tags matching app versions: major for breaking changes, minor for features, patch for fixes. Release descriptions must remain brief, bullet-based and include every resolved issue; leave already published descriptions unchanged unless requested.

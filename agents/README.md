# Working instructions

Read the [documentation map](../docs/README.md) before editing. Follow the [documentation standard](../DOCUMENTATION.md) and [collaboration contract](collaboration.md). Task-specific owner instructions take precedence.

## Passive documentation maintenance

Consolidate documentation during every relevant change. Edit the authoritative rule and remove superseded statements in the same pass.

Preserve agreed behavior, defaults, exceptions and constraints. Keep the README short and review [previews](../docs/build.md#previews) during release preparation.

Run the [documentation checks](../docs/build.md#documentation) before committing prose. Review meaning, duplication and current-versus-planned status as well as automated results.

## Shared interface controls

Build app-owned choices, switches, sliders, text fields, dialogs and drag visuals from the shared `ui` controls documented in [Interface](../docs/interface.md).

Extend the abstraction when behavior is missing. Avoid raw Material segmented buttons, radio rows and parallel visual implementations. System permissions and external pickers remain native.

Keep shared gesture, selection and material regression tests with changes to those abstractions.

## Issues and commits

[Issues](https://github.com/anastaci1a/localica/issues) own actionable work and acceptance criteria. Use the [bug](../.github/ISSUE_TEMPLATE/bug_report.md) or [feature](../.github/ISSUE_TEMPLATE/feature_request.md) template, including CLI/API submissions. Omit irrelevant optional fields.

Write short issue bullets. Bugs explain observed and expected behavior, reproduction and known cause. Features state the request and observable completion. Link supporting evidence.

Keep commits coherent and reviewable. Separate unrelated cleanup, implementation and release automation. Reference relevant issues and use descriptive imperative subjects.

Use the repository-local author identity. Inspect Git state and appropriate verification before committing. Audit staged content for secrets and report material limitations.

Push every commit immediately and verify the remote branch tip. Never leave completed work only locally.

Use `umask 0002` when creating workspace contents. Run `./fix-permissions.sh` on created or modified paths. It preserves executable bits and grants the shared group access.

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

Follow [release preparation](../docs/build.md#release-preparation), including its exact-commit gate and [release template](../.github/RELEASE_TEMPLATE.md).

Use `vX.X.X` tags matching app versions: major for breaking changes, minor for features and patch for fixes. Leave published release descriptions unchanged unless requested.

# Contributor entry point

Read the [working instructions](agents/README.md), [documentation standard](DOCUMENTATION.md) and [documentation map](docs/README.md) before changing Localica. Keep behavior, ownership and evidence clear enough for another contributor to review.

## Shared UI ownership

Screens supply content and intent to the shared `ui.components` and `ui.engine` system. They never provide popup homes, motion kinds or laws, private triggers, or drawing paths for shared roles. System permissions and external pickers remain native.

Every release passes the UI architecture lint gate with zero new overrides. Recorded counts of existing incidents may only decrease, per rule. A strengthened rule records its initial count when adopted, then follows the same ratchet. Counts remain visible in the gate rather than hidden by a new baseline.

The [UI system](docs/architecture/ui-system.md) documents owners and enforcement. Keep gesture, selection and material regression checks with changes to their shared abstractions.

## One implementation per role

Extend the existing owner instead of adding a parallel implementation. Consolidate duplicates when found. Extend by composition: a parameter, slot, data field or sealed case. A subclass or abstract base layer requires reviewer agreement that composition cannot serve the case.

## Design note first

A failing test asks why the model permits a bad state. Before changing shared app code, write a short design note and have it reviewed. Cover:

1. The bad state and why the existing model permits it.
2. The smallest model change that makes it impossible.
3. State, flags, special cases and lines removed and added.
4. Why the model cannot absorb any proposed new state.

Prefer absorbing the problem into the existing model over adding a flag, phase, guard, wait or clamp. A code-path workaround is not a model change. Reviewer agreement precedes implementation. Environment, tooling and CI fixes only need an account of what changed.

Dropping a fix is valid when its side effects cost more than the original problem. Every release includes a simplicity review of shared code asking what can be removed.

## Keep app code small

Write the shortest efficient implementation that meets the contract. Prefer deleting, merging and reusing. Avoid speculative helpers, defensive branches and comments that restate code.

Report net app-source lines under `app/src/main` with every change. Aim for net zero or negative. Report removed state alongside additions. Each shared component's review includes its state/flag count, special cases and line delta. Growth needs a written reason accepted by the reviewer.

Each release compares total app-source lines with the previous release; the trend should decrease. Verification requirements live in [Testing](docs/development/testing.md) and [Performance](docs/development/performance.md).

## Workflow files

Keep exactly the five approved files in `.github/workflows/`: `android.yml`, `prototype.yml`, `documentation.yml`, `dependency-cache.yml` and `tooling-contracts.yml`. Edit these files when needed; never add or rename workflow files. Run one-off and diagnostic checks through `prototype.yml`. CI rejects added or renamed workflow files.

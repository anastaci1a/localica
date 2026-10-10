# Working instructions

These instructions govern how to work on localica. Product goals and plans belong in `docs/`; working method belongs here. Read only the relevant linked document before a task, then update the smallest set of living documents affected by the result.

## Keep context compact

- Treat `README.md`, `docs/`, and this directory as **living documents**, not activity logs. Replace stale statements, merge overlapping notes, and delete superseded detail. Do not append a new section for every work session.
- Keep each fact in one canonical place. `README.md` is a short public introduction; `docs/README.md` is the map; the other `docs/` files hold current product decisions, structure, and milestones. Link instead of copying.
- Before adding a document, try to fit the information into an existing one. Split only when a document has a distinct reader or purpose. Keep headings scannable and prose as short as accuracy allows.
- Record decisions as the current choice, its reason, and any important constraint. Preserve history in Git when it is no longer needed for the next decision. Do not keep daily notes, transcripts, repeated task lists, or a running journal.
- At the end of meaningful work, reconcile the plan with what is now true. Remove completed or obsolete plan text. Keep open questions only when they affect a coming decision.
- Keep the main `README.md` easy to read. Add a small number of real app screenshots once screens exist; update or remove outdated screenshots when the UI changes. Avoid placeholder images and lengthy feature inventories there.

## Work and issues

- [GitHub Issues](https://github.com/anastaci1a/localica/issues) are the source of truth for bugs and actionable work. Create or update an issue with reproduction, expected behavior, and acceptance criteria as relevant; refer to its number in commits or pull requests. Do not maintain a bug list or issue tracker in `docs/`.
- Keep submitted issues and replies short and to the point. For bugs, state the observed effect, how to reproduce it, and the cause if known. For features, state the requested change directly. Link to supporting details instead of repeating plans, implementation narratives, or test logs.
- `docs/roadmap.md` tracks milestone outcomes and their order, not individual tickets. Remove a milestone when its outcome is delivered, or rewrite it to reflect the next decision.
- Keep commits focused on one coherent change so each can be reviewed or reverted safely. Separate planning changes, implementation, and release automation when practical. Never mix an unrelated cleanup into a bug fix. Use descriptive imperative commit subjects and the repository-local `anastaci1a` identity.
- Check the working tree and relevant verification before each commit. Document material limitations in the final report or the relevant issue, rather than burying them in a new project log.

## Versioning and releases

- Use `vX.X.X` tags for releases and the corresponding `X.X.X` app version name. Increase major for breaking changes, minor for features, and patch for fixes.
- Add APK build and release automation only after a buildable Android project and signing/distribution choices exist. A workflow must have a real build command and a verifiable artifact before it is treated as release automation.

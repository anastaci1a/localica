# Agent entry point

Read [working instructions](agents/README.md) and the [documentation standard](DOCUMENTATION.md) before changing this repository. Follow the [documentation map](docs/README.md) for product contracts and plans.

## Workflow files

Keep exactly the five approved files in `.github/workflows/`: `android.yml`, `prototype.yml`, `documentation.yml`, `dependency-cache.yml` and `tooling-contracts.yml`. Edit these files when needed; never add or rename workflow files. Run one-off and diagnostic checks through `prototype.yml`. CI rejects added or renamed workflow files.

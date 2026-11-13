# Release

A release is a tag on a commit in `main` whose exact Git tree has a complete green Android run. Releases are scoped and time-boxed: ship what is accepted at the cut, and let the rest ride the next one. Nothing is tagged on focused or partial evidence, and a candidate is never altered after its gate.

- Tags are `vX.Y.Z` and match the app version. Every distributed update increments `versionCode`.
- The UI-rule ratchet must pass: no new override, and no count going up.
- Release notes list every resolved issue.
- Each release reviews the roadmap, the screenshots and the documentation.
- Status: Implemented.

## Steps

### Step 1: version and notes

Update the app version and name, and increment `versionCode`. Fill the [template](../../.github/RELEASE_TEMPLATE.md) into [RELEASE_NOTES](../../.github/RELEASE_NOTES.md): a short introduction, short Changes bullets, all resolved issue URLs with brief descriptions, and Install steps. Include only completed issues, and say so if there are none. Keep earlier published descriptions intact.

### Step 2: review and profile

Work through the [checklist](#checklist), then commit and push.

Refresh `.github/test-evidence/class-durations.csv` from the preceding release's complete run before freezing the candidate. Download its `complete-verification` artifact and copy the generated profile. Record the source run in the commit. For older producers, run `refresh_durations.py` on the downloaded `device-reports-api-*` directories.

### Step 3: the gate

Wait for a complete green Android run with exactly the release commit's Git tree before tagging. The producer may be a same-repository integration push or a manual full run.

| Must succeed | Notes |
| --- | --- |
| Build and static checks | Unit tests, lint with the UI-rule ratchet, release build. |
| All device shards, API 26 and 36 | With complete inventory unions. |
| Both compatibility jobs | See the gate below. |
| Both distribution smoke jobs | On the exact APK to be published. |

A `main` push reuses retained exact-tree evidence when it exists; otherwise it runs normal verification. Release selection verifies the original run, attempt, tree, union receipts and artifact identity (`release-verification.py`). APK checksum, version and signing checks are mandatory. Missing, expired or changed inputs need a new complete run. Unfilled placeholders in the notes block publication.

### Step 4: publish and tidy

Verify the published assets: `localica-vX.Y.Z-pre-release.apk` and its `.sha256`, named by the release job. Close resolved issues and remove workflow labels. Delete the released integration branch once its commits are in `main`. Keep integration branches for unreleased trains.

## The UI-rule ratchet

Every release passes the [lint gate](../architecture/ui-system.md#the-lint-gate) with zero new screen-level overrides. The recorded count of older incidents, per rule, may only go down. Adoption of the remaining incidents continues across releases until each count reaches zero. A release is not held until then.

## Compatibility gate

Queue schema 7 cannot be reopened by the original schema-6 APK. Before distributing a build, dispatch the exact candidate through the Android workflow with `compatibility=true`.

- On API 26 and 36, the gate installs candidate → legacy-behaviour bridge → candidate, with the same package and signature and increasing private version codes, without clearing data.
- `ShuffleApkCompatibilityTest` checks duplicate occurrences, saved order and cursor, controller reopening and statistics across the `seed`, `bridge` and `verify` phases.
- The [isolated overlay](../../.github/compatibility/prepare.py) changes only traversal behaviour. Schema, journal and recovery safety stay current.
- Private compatibility artifacts are never distributed. Ordinary migration tests do not replace this gate.

Exact-source evidence is tracked in [#295](https://github.com/anastaci1a/localica/issues/295). Downgrade limits for tag-editing records are in [File editing](../architecture/file-editing.md#metadata-refresh).

## Checklist

| Check | What to do |
| --- | --- |
| Roadmap | Replace completed outcomes with the next ones in [Roadmap](../roadmap.md). |
| Documentation | Confirm each changed contract has its page updated, and each status word is still true. |
| Screenshots | Review the README images. Recapture changed screens from the candidate, inspect at README size, update the version and credits. |
| App-source lines | Report total lines under `app/src/main` against the previous release. The trend should go down. |
| Simplicity review | One review of shared code whose only question is what can be removed. |
| Run timings | Log build, slowest-shard and whole-run durations against the [CI budgets](ci.md). |

### Screenshots

Keep navigation recordings short and silent. Keep third-party demo media outside Git and the APK. Reuse `${XDG_DATA_HOME:-$HOME/.local/share}/localica/previews/` for the local library, setup notes and raw captures. Update the emulator with `adb install -r` so its music and app data survive.

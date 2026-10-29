# Isolated paired native suite-cost comparison

Not a prototype, complete product test run, or release gate. No source mutation is
made except checking out the two immutable supplied test-only commits in an owned
disposable checkout. The active project/dev77 branch is never updated.

Invocation, from a separate harness checkout:

```
bash .github/suite-cost/run-paired.sh /absolute/source /absolute/config.json /absolute/evidence 26
```

The source checkout must initially be at baseline and contain no tracked changes,
untracked app source, or previous native result directories. Evidence must be fresh
and outside the source checkout. Both commits must be locally fetched. Install the
pinned Node publisher dependencies in the source `.github/test-evidence` first.

Example config (replace both SHAs with real immutable commits):

```json
{
  "label": "artwork-bulk",
  "class": "io.github.anastaci1a.localica.ArtworkEditRestorationTest",
  "baseline_sha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "candidate_sha": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "changed_paths": ["app/src/androidTest/java/io/github/anastaci1a/localica/ArtworkEditRestorationTest.kt"],
  "precheck_class": "io.github.anastaci1a.localica.NativeSuiteCostEquivalenceTest"
}
```

Only the artwork comparison needs `precheck_class`: P0 runs exactly one passing
native pixel-equivalence test, is archived, and is excluded from measured timings.
Measured order is A1 baseline, B1 candidate, B2 candidate, A2 baseline. Each trial
retains all three selected methods, precompiles APKs, freezes source/APK identities,
requires empty fresh native outputs, clears only the exact app.debug/app.debug.test
packages when installed on the verified disposable emulator (absent is fresh), and uses the original observer/supervisor/
publisher with a unique lane. Original owned package guard is sourced once for the
whole sequence; interruption forwards to Python then the supervisor before restore.

Each trial archives raw XML, HTML and additional outputs before reconciliation,
including failure. First failure stops the sequence; no retry/replacement occurs.
Read-only Android crash/window/activity context is captured on failure. APK files
are preserved once per variant. Identical-source repeated trials must have identical
APKs, and the production app APK must match across variants. Candidate diff is
restricted to the exact configured test-source paths. Guard and evidence helpers
must remain byte-identical. Every exact expected method must pass without skip.

`verified.json` records each raw XML digest and individual duration; `comparison.json`
reports ABBA arithmetic means of method/suite/whole-command time separately. Build
time is recorded separately, not hidden in a native speedup. Required `NativeSuiteCost`
logcat stage timings are retained and parsed without replacing raw logs. Two pairs
are observations on one emulator, not a statistically established universal saving.
Failures and missing repeats cannot produce a complete comparison.

Offline validation:

```
python3 -m unittest discover -s .github/suite-cost -p 'test_run_paired.py' -v
bash -n .github/suite-cost/run-paired.sh
```

The self-tests cover exact XML and single precheck pass, omitted/extra/duplicate/
skipped/failed tests, XML count/entity rejection, no XML/symlink roots, monitor
identity/degradation/failure-event rejection, stale evidence and original output
archiving. Native Android execution remains a separate required validation.

### Required timing-probe diagnostics

`read_probes` now requires one original AGP `logcat-CLASS-METHOD.txt` per XML
method, with exactly one matching `TestRunner: started:` and `finished:` boundary
in the recorded threadtime schema. Every probe must be between these boundaries,
from the same process. Unknown logs, methods, stages, malformed samples, duplicate
records and missing/extra counts reject the diagnostic trial. Repeated elapsed
values are allowed; duplicate complete log records are not.

Fixed per-method counts: artwork initialization 1 each (3 total); popup utility
endpoint/field/profile = 2/16/16, nested = 2/15/15; capsule and equivalence precheck
must have zero probes but still require their original bounded logs. Counts are
not candidate-controlled. Boundary format was verified against original API26
posttrim PopupShadowContinuityTest logs.

`native-xml-verified.json` records XML assertion success independently, before
probe checking. `diagnostic-failed.json` explicitly disallows a savings conclusion
on missing/invalid diagnostic evidence. Raw XML/logs are always retained; no next
trial or `comparison.json` follows a diagnostic failure. Offline contracts are not
native execution evidence.

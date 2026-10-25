# Native full suite cost audit 2026 10 25

## Current failures come first

The latest candidate is **not validated**. [Run 40226680680](https://github.com/anastaci1a/localica/actions/runs/40226680680), at [16ff1a4634ee2a7ac3563e02e1ed86b7379ba759](https://github.com/anastaci1a/localica/commit/16ff1a4634ee2a7ac3563e02e1ed86b7379ba759), completed with two unresolved failures:

- API 26: `FrostedSurfaceTest#neutralFrostFollowsConfiguredContrastInBothInkPolarities`; configured contrast expected `0.66183007`, observed `0.5529412` for `lightInk=false`, `trim=0.5`, `weight=2.0`.
- API 36: `ListSearchTest#visibleSearchVacancyRestoresWithoutCumulativeListMovement`; `awaitSearchFocus` exceeded 5000 ms.

Preserve and repair these failures; neither is a removal candidate. This run is **not** the timing baseline below. The objective is a substantial reduction in total full-suite work while retaining regression coverage, not merely earlier feedback, fewer reported methods, or redistributed work. The evidence supports several bounded experiments, but no measured percentage reduction or halving claim.

## Evidence and measured cost

The exhaustive source inventory contains **1,037 test methods in 213 native test-bearing Kotlin files** at [fcc6001d9f6a60e45c4f8f098530d9ea9b4bfb0f](https://github.com/anastaci1a/localica/commit/fcc6001d9f6a60e45c4f8f098530d9ea9b4bfb0f). Semantic review was targeted, not exhaustive: expensive methods, diagnostic writers, copied comparison algorithms, repeated setup, and frame synchronization. Further detailed inspection used `16ff1a4`; its timings remain historical, not measurements of that revision. No product test was proven obsolete because a requirement was canceled. Closed bug reports do not establish obsolete coverage.

The [historical cost inventory](2026-10-25-native-cost-inventory.json) records **eight paired API 26/36 runs** with exact SHAs and run links: 15 complete suites and one API 26 process-crash partial suite. Supplemental same-SHA retry logs distinguish emulator instability from assertion failures. Baseline [run 40208893739](https://github.com/anastaci1a/localica/actions/runs/40208893739) is exactly `fcc6001d9f6a60e45c4f8f098530d9ea9b4bfb0f`.

| Timing ledger | Baseline | After |
| --- | ---: | --- |
| API 26 suite XML | 3028.299 s (50m28s) | Not measured |
| API 36 suite XML | 3326.674 s (55m27s) | Not measured |
| API 26 native job | 3402 s (56m42s) | Not measured |
| API 36 native job | 3716 s (61m56s) | Not measured |
| Aggregate native job runner-minutes | 118.633 | Not measured |

The XML testcase sums were 3006.833/3294.324 seconds (API 26/36). XML totals were 1036/1037 tests including 18/11 skipped, with 0/1 failures. Inventory is not executed count. The ten most expensive classes consumed 32.05%/27.46% of suite time.

The suite totals equal **105.916 aggregate device-execution minutes**, versus **118.633 native job runner-minutes**. Both jobs started at 15:51:11 UTC and the last ended at 16:53:07: **61m56s parallel native-job elapsed time**, excluding queue time. All non-skipped jobs, including build and compatibility, totaled 155.383 runner-minutes. These are durations, not billed cost. Suite XML excludes startup and other job overhead; parallelism does not turn summed seconds into equal elapsed savings. XML and process outcomes are authoritative; progress counters can overcount skipped tests.

## Failure history and ordering

Frontload the current Frosted and ListSearch failures, then retain these historical sentinels:

- `SearchImeMotionTest`: baseline API 36 docked-keyboard readiness failure; changed coverage, not a proven flake.
- `SettingsMotionTest`: the same method failed API 26 on two distinct SHAs; inexpensive repeated warning, not established same-SHA nondeterminism.
- Selector stacking, registration and rebound classes: eight API 26 failures clustered at one shared presentation precondition in one run. Treat this as correlated capture/environment evidence, not eight independent product defects.
- `PlayerExpansionMotionTest`: one API 26 RenderThread crash truncated a suite; preserve crash-sensitive coverage.
- `UtilityPopupRenderDiagnosticTest`: same-SHA retry succeeded after an emulator disappeared. That demonstrates execution instability, not a flaky visual assertion.

Ordering reduces time to evidence, not total work. A duplicated preflight followed by an unchanged full suite costs more. Any reordered gate must execute each retained case once and follow [final validation rules](https://github.com/anastaci1a/localica/blob/16ff1a4634ee2a7ac3563e02e1ed86b7379ba759/docs/build.md#development-and-final-validation).

## Priorities

Times below are **whole affected-method or class bounds in seconds**, never estimated savings. All require retained work and before/after measurement.

| Priority | Isolated scope | API 26 / API 36 bound | Coverage condition |
| --- | --- | ---: | --- |
| 1 Diagnostic lane | UtilityPopup plus three queue profiling/comparator classes | 171.338 / 111.669 | Keep lean native lifecycle and production DAO correctness |
| 2 Endpoint pumping | GlassOriginPreparation, LyricsHeaderActions, SettingsLinkMorph, one GlassMorph method | 138.960 / 120.599 | Same deadlines, native scheduling and observed checkpoints |
| 3 Structural matrix replacement | PopupEnvelope matrix method | 89.626 / 98.414 | 24 representative mounts plus exhaustive native shared-face and production-mapping checks |
| 4 JVM or equivalent replacement | PlayerExpansion sampling; GlassPressure axis releases | 57.028 / 35.330; 58.553 / 51.894 | Retain renderer witnesses and exhaustive host sweeps |
| 5 Successful export removal | SwitchTapOptics three methods | 32.173 / 27.827 | Keep pixel assertions, readiness and bounded failure evidence |

These bounds must not be totaled as savings. LyricsHeader export and pumping touch the same method; PopupShadow export and pumping also overlap. Native capture and retained assertions still run. The diagnostic-lane bound alone is only 5.66%/3.36% of baseline suite time before replacement cost, so it cannot deliver the whole objective.

## Isolated implementation recipes

All named native files are under [`app/src/androidTest/java/io/github/anastaci1a/localica/`](https://github.com/anastaci1a/localica/tree/16ff1a4634ee2a7ac3563e02e1ed86b7379ba759/app/src/androidTest/java/io/github/anastaci1a/localica).

1. **Separate diagnostics explicitly.** Move UtilityPopup's three ten-cycle optical recordings into an opt-in lane. Retain restored-draw acknowledgment, first reopened frames, original window identity, shadow ownership, endpoint geometry and disposal in mandatory native checks. Move copied old/new SQL comparisons from `QueueInsertPreparationBenchmarkTest` and `QueueSqlCountBenchmarkTest`, and repeated profiling from `QueueInitialSaveWorkTest`. Retain production DAO repair, rollback, reopen and no-journey initial-save correctness. Use explicit selectors, never a `*Diagnostic*` exclusion.

2. **Batch only unobserved endpoint interiors.** Start with `GlassOriginPreparationTest` and `LyricsHeaderActionsTest`; extend separately to SettingsLinkMorph and `GlassMorphTest#returnedTriggerKeepsTheSameLowDampingRebound`. Short virtual-frame batches must yield to native work, retain frame-rounded budgets and leave 80/100 ms interruption checks, restored-draw acknowledgment and first reopened frames untouched. Early completion is valid for positive readiness, not an absence observation. Preserve original-horizon checks against delayed reappearance. Do not batch per-frame motion, native IME, or shadow assertions indiscriminately.

3. **Replace the redundant envelope matrix structurally.** Use the source-proven partition below, rather than stopping at one duplicate draft or retaining every draft as a full mount. Independently measure relevant-only database setup in BrowseOrderViewport and bulk pixel fixture initialization in ArtworkEditRestoration. Neither has booked savings; activity-recreation coverage stays.

4. **Transfer numerical density carefully.** PlayerExpansion may replace generic 21-point sweeps with native endpoint, actual-clearance-boundary and reversal witnesses across every layout/aspect/reduced-motion combination. GlassPressure must retain all four axis/damping configurations, first-lobe and opposite-phase evidence, containment and settled endpoints. Preserve exhaustive [`app/src/test/`](https://github.com/anastaci1a/localica/tree/16ff1a4634ee2a7ac3563e02e1ed86b7379ba759/app/src/test) sweeps. Pure parser/writer utilities can move unchanged to JVM, but their combined 0.078/0.085 seconds is hygiene, not material savings. Keep PinchReleaseHandoff's every-frame detector; host formulas cannot replace renderer evidence. Separately, the older `ContinuousPlaybackTest` PCM and full-frame AAC methods have stronger static controls in `QueueContinuousPlaybackTest`. Consolidation is conditional on output-provider equivalence; retain native decoder/sink checks, all queue-controller variants, and MP3/FLAC coverage. Their combined 5.451/5.626-second bound is a small, lower-priority opportunity, not minutes of savings.

5. **Gate successful serialization only.** Retain pixel acquisition and assertions in SwitchTap, PopupShadow, LyricsReturnBackdrop and SeekReleaseHandoff. Preserve failure images and numeric state before recycling; move any assertions embedded in export blocks outside the gate. LyricsHeader's human-only PNGs can be optional only after explicit native readiness replaces any capture side effect. Do not gate input fixtures or existing failure-only evidence.

**Do not book skipped work.** The four font2/phone `RepeatOptionsEnvelopeTest` cases were opt-in and skipped in the baseline; they provide no default-suite saving. `PrivateQueueBoundaryTest` normally skips without private inputs, so its long timeout is not spent runtime. The candidate-to-bridge-to-candidate compatibility gate remains separately required; native migration tests do not replace it.

## Structural envelope finding

Source inspection at `16ff1a4634ee2a7ac3563e02e1ed86b7379ba759` strengthens the PopupEnvelope proposal. In [BrowseSortDialog.kt](https://github.com/anastaci1a/localica/blob/16ff1a4634ee2a7ac3563e02e1ed86b7379ba759/app/src/main/java/io/github/anastaci1a/localica/ui/BrowseSortDialog.kt), inert measurement declarations do not receive the edited draft or selected values. `PopupNaturalWidth` and `StablePopupBody` in [StablePopupBody.kt](https://github.com/anastaci1a/localica/blob/16ff1a4634ee2a7ac3563e02e1ed86b7379ba759/app/src/main/java/io/github/anastaci1a/localica/ui/StablePopupBody.kt) derive the envelope from those declarations, which already measure both pages, all available option labels, five slider titles, reserved errors and action/footer alternatives. This supersedes the earlier recommendation to retain every distinct draft as a full-dialog mount.

**Proposed, not implemented or timed:** replace 38 drafts × two callers × three window/font cases (**228 mounts**) with four live topologies—List/gallery × Manual/non-Manual—at all six caller/window combinations (**24 mounts**). Keep both tab transitions, footer reachability, main normal-window wrapping and envelope equality. Preserve one complete queue randomize/revert/two-reversals/failure sequence per window, including no-Revert and error/Close reachability. Gallery+Manual adds a structural interaction missing from the old matrix; Main Manual remains a compatibility initial value, not a newly supported choice.

Selection is not uniformly draw-only: list-size, information and sort summaries can wrap differently, and slider digits can have different metrics. Retain exhaustive **native shared-face** probes for actual labels and all five slider titles at original narrow/large-font constraints, including live/inert dimensions and containment/overflow. Retain fractional-density geometry and native choice/slider endpoint coverage. Fixed choice-label selection changes lens/semantics without selected typography; that narrower equivalence does not justify moving Android text shaping to JVM.

Keep explicit legacy/fallback probes for Main Tracklist and Manual/Album/Folder sorts, Queue Count sort and Inherited information. These selections are outside the corresponding declared choices; the old outer-size assertion did not establish that their labels fit. Add exhaustive checks for all original 38 rows against a **production-used declaration builder**, not a handwritten duplicate. Relevant host bounds coverage is `ui/PopupSizingTest`.

The reduction is **204 mounts and 408 repeated tab gestures**, not an 89.5% runtime saving. The whole-method bound remains 89.626/98.414 seconds; retained declarations and new face probes still cost time. This replaces the existing PopupEnvelope opportunity, not an additive saving. Measure both APIs before/after: mounts, navigation, native face cases, JVM mapping cases, label-fit outcomes, focused elapsed time and full-suite runner cost. RepeatOptions has related structural distinctions, but its default tests mostly cover distinct behavior and its large profile matrices already skip; no additional large default saving is established.

## Implemented preparation and acceptance

[SwitchTap export preparation de3c208a15b5d5e714512833befa43e3f9c2612d](https://github.com/anastaci1a/localica/commit/de3c208a15b5d5e714512833befa43e3f9c2612d) makes review exports opt-in with `switchTapOpticsReview=true`, retains pixel checks, and saves the last successfully captured frame on failure without new capture or pacing. That frame is not necessarily the failing frame. This is implemented preparation, **not measured native validation or demonstrated savings**.

For each isolated change, record immutable before/after SHAs, runner/emulator configuration, API, repeated focused timings, capture/yield counts, fixture cost and exact retained assertion/case mapping. Maintain one method-level ledger so overlapping proposals cannot double-count cost. Record skipped and diagnostic-only cases explicitly. Verify timeout/failure behavior and exported evidence, then freeze the combined candidate for complete API 26/36 validation and applicable compatibility checks. Compare both aggregate execution cost and job elapsed/runner minutes; sharding, renamed counts and shorter timeouts are not work elimination.

Supplemental same-SHA retry evidence: [run 39976631232](https://github.com/anastaci1a/localica/actions/runs/39976631232).

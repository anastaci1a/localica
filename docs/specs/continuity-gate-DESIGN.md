# Continuity release gate

Status: **Proposed, design only**, for [883](https://github.com/anastaci1a/localica/issues/883). No implementation or acceptance claimed. Enforce [884](https://github.com/anastaci1a/localica/issues/884) before implementation.

## 1. Decision and scope

Certify shared motion, rendering and content contracts once per dependency revision. Screens prove conformance. Generate residuals for uncertified paths. Releases require complete coverage, valid certificates and zero unresolved violations; residuals never waive architecture rules.

Certificates expire when shared renderers, content contracts, native adapters, laws or proof dependencies change. Screen changes rerun conformance; uncertified content or wiring needs transition frame proofs.

Cover popup open/close/reversal, navigation/Back, predictive peek/cancel/commit, Settings push/pop, player/Queue drawers and shared/component flights. Loading, IME, scrolling and configuration changes are overlapping inputs. System UI has a declared boundary.

Limits:

- Finite fuzzing establishes tested coverage, not a mathematical proof over arbitrary event histories or unrestricted content.
- Paused clocks and screenshots alone do not establish capture of every frame physically presented by Android.

Unproved domains or missing presentation receipts produce **Incomplete**, blocking publication.

## 2. Threat model and historical witnesses

Threats include legal reversal, resize, navigation, delayed attachment, stale measurement, asynchronous content and disposal at presentation boundaries. Also test illegal geometry and stale receipts. Correct motion can coexist with wrong ink, clipping or window order.

| Glitch class | Real witness | Required detection |
| --- | --- | --- |
| Ghost or blank trigger | [880](https://github.com/anastaci1a/localica/issues/880): wrong Sort/View pill during return | Trigger identity, continuous ink, pixel residual |
| Reverting layout | [881](https://github.com/anastaci1a/localica/issues/881): Creator, displaced counts and placeholder on artist Back | Content/row coherence; whole viewport pixels |
| Conflicting titles | [882](https://github.com/anastaci1a/localica/issues/882): Library and Settings overlap during predictive Back | Exclusive heading role plus predicted opacity, position and pixel contributions |
| Wrong plane or occlusion | [867](https://github.com/anastaci1a/localica/issues/867): Library pill paints over the closing More menu | Window/stratum ordering and ownership ledger; actual display composite |
| Detached shared ink | [887](https://github.com/anastaci1a/localica/issues/887): Queue title disappears on opening | Single title, continuous ink, native transform |
| Invalid geometry | [878](https://github.com/anastaci1a/localica/issues/878): negative corner radius | Finite-domain checks before presentation, including intermediate values |
| Teleport or early retirement | Mutants at every family boundary | Value/velocity joins, lifetime, node/pixel checks |

The 887 reproduction confirms disappearance on fallback and RC with short and long titles; its scaling cause remains unproved. Encoded recordings identify symptoms, not exact original pixels or capture completeness.

Preserve recordings, frame indices and timestamps. Validate historical bad-source replays and mutants before fixes, using unchanged observers. Videos test temporal symptoms; they cannot supply missing node trees or replace native reproduction.

## 3. Existing mechanisms to extend

Source basis: `1ff6f3cae6511e81714c2dd8927ebc7d069355d1`, the 884 branch with legacy popup motion. Certify the actual integrated laws; the Apple design is not assumed implemented. Current project rules supersede historical tolerances and fixture scales.

| Existing owner | Extension and limit |
| --- | --- |
| Engine clock/trajectory/curve files | Emit segment/join descriptors. Clock callbacks precede layout/draw and cannot attest pixels. |
| Expansion, popup motion and compositor | Expose phase, mapped edges, residuals, generations and transfer ledgers. |
| Shared Popup, PopupSharedContent, Sheet and Drawer | Enrol owners; derive kinds/homes/triggers internally. Screens use components. |
| MorphLayout and GlassMorph | Observe actual layer recording, clipping, submission and world transforms. Semantics alone misses ink. |
| Existing sharedTest ContinuityObserver/IdentityObserver | Bind production receipts; synthetic fixtures and current tolerances are not native proof. |
| NativeFrameCapture, ShadowNativeCapture and PopupCapture | Extend existing capture/generation validation; preserve original windows and scrim provenance. |

Extend existing implementations by composition, never duplicate engines, renderers or observers. Independent mathematics is a test oracle. Bind through 893’s accepted shared API; this design does not freeze competing signatures.

## 4. Certificate and proof obligations

Certificates identify admitted domains, invariants, proof arguments, sampled partitions, oracle versions, environments and dependency digests. Reject unsupported input; never clamp defects away.

### Engine proof

Prove scalar/mapped trajectories over admitted geometry, velocity, density and time domains. Partition piecewise laws at joins/extrema. Verify C0 position and required C1 velocity at retarget, release, rebase, admission and rest, including between samples.

Use state-machine induction: every legal event preserves admission and each transition preserves invariants. Enumerate lifecycle/event-order classes, then supplement with reproducible generated sequences and shrinking. Unproved reachable branches remain residuals despite green fuzzing.

Cover all laws/kinds, zero/unequal travel, initial velocity, repeated reversals, third-host transfer, hold/release, stale generations, source removal, resize and native return. Certify future Apple main/cross phases and residuals together.

Enumerate all admitted speed values and settings; analyse continuous scale/geometry domains. Exercise Reduced entry/live changes, scale zero with respect enabled/disabled, live speed/scale changes, density/font updates and root recreation. An unexpected jump fails.

### Presentation and content proof

Certify actual transforms, clips, corners, opacity, shadows, material parameters, text/content recording and window lifetime. Scalar proof cannot cover 887. Render calibrated adversarial content through real shared components on both APIs.

| Obligation | Required witness |
| --- | --- |
| Identity | Stable logical identity across source, native and return. Instances and content revisions are distinct fields. Equal text strings do not imply duplicate ownership. |
| Visibility | A required identity has the contract's predicted visible contribution until retirement. Measurement copies never count as ink. Legitimate occlusion is independently derived from the stack. |
| Handoff | Source retirement follows an acknowledged destination presentation; return retirement follows restored source ink. Test every legal cross-window ordering, including mixed generations. |
| Composition | One legal owner per shared element; transforms multiply through every ancestor and window coordinate conversion. All transforms, clips and material parameters share a coherent epoch. |
| Content | Layout/content revisions update atomically. New text, artwork and rows require an explicit data event; an unexpected transient revision fails even if geometry is smooth. |

Central crossfade contracts declare both identities and contribution laws. Distinct headings can violate an exclusive role without duplicate node IDs or equal strings.

### Per-screen conformance

Conformance checks resolved wiring, measured geometry and content capabilities. Mount each route in its real root/windows; probe owner/home, native density/font scale, roles, content epochs, slots and trigger restoration at endpoints/handoffs.

Only certified content schemas inherit engine proof: static recordings or shared text/artwork/row/loading contracts. Arbitrary composable slots, Canvas, private clocks and unclassified effects require shared-contract extension or explicit residuals.

881 makes content conformance necessary: shared motion cannot prove screen data correctness. Content-binding changes rerun conformance; unverifiable content cannot silently inherit certification.

## 5. Node records and law-derived bounds

Record frame/intent/segment/spec epochs, presented buffer/window/host generations, and expected/observed node sets. Nodes record logical/instance/content IDs, parent/role/window, world matrix, bounds, alpha, clip/corners, stratum, shadow/material values and actual draw occurrence.

Collect real layout/draw primitives, including retained faces and recorded layers. Join unmerged semantics for meaning. Keep the whole app viewport; unmatched submissions or missing draw hooks fail coverage.

### Trajectory envelopes

Predict from frozen segment inputs and an independent law calculation, never from fitting observations. Include clock mapping, physical displacement and rebase residual:

$$
q(t)=q_0+\Delta q\,g(p(\tau(t)))+r(\tau(t)).
$$

$$
\dot q(t)=\left[\Delta q\,g'(p)\dot p+\dot r\right]\dot\tau(t),\qquad
|q(t_{k+1})-q(t_k)|\leq\int_{t_k}^{t_{k+1}}|\dot q(t)|\,dt+E_{q,k}+E_{q,k+1}.
$$

Use tight trajectory intervals, splitting at intents, scale changes, branches, gates and derivative roots. Also require each observed pose inside its predicted interval; a generous displacement bound alone is insufficient.

For an event-free interval, the acceleration envelope also bounds deviation from the tangent:

$$
|q(t+h)-q(t)-h\dot q(t)|\leq\frac{h^2}{2}\sup_{u\in[t,t+h]}|\ddot q(u)|+E_{\mathrm{arith}}.
$$

Bound Hermite polynomials analytically and spring exponentials/trigonometry by outward-rounded intervals. Extend existing `Curve` bounds. Unresolved subdivision is Incomplete. Propagate through matrix products, division and perspective; singular transforms fail admission.

Derive error from IEEE-754 operations, input intervals, timestamp resolution and actual rounding. No fixed px/dp/percentage tolerance. Validate float32 expressions against independent higher-precision evaluation at joins and adversarial magnitudes.

Use exact rounding cells and retain unrounded receipts. Finite/range/identity assertions have no slack: negative radius, NaN, illegal alpha, duplicate owner or unexplained missing ink fails.

Require monotonicity only between certified derivative roots. Overshoot and post-reversal travel can be legal. Reduced/discontinuous inputs use exact central pre/post contracts, never neighboring-frame exemptions or blank handoffs.

## 6. Pixel oracle

Laws bound geometry, not arbitrary image differences. Valid subpixel travel can sharply change checkerboards; faint ghosts barely change average scores. SSIM, area cutoffs and tuned per-screen thresholds cannot certify continuity.

Compare without golden images:

| Comparison | Construction | Failure rule |
| --- | --- | --- |
| Predicted scene | Capture immutable production content layers; replay them at independently predicted poses, clips, opacities and central window order using existing rendering primitives. Never copy observed poses into the oracle. | Actual channel value lies outside the derived raster interval, at any pixel. |
| Temporal residual | Transport consecutive content layers using predicted motion; account for declared content events, occlusion, scrims, shadows and material changes. Inspect one-frame and two-frame excursions with a five-frame sliding window. | Unexplained residual, blanking, duplicate contribution or reverting content revision. Persistence is diagnostic, not a minimum defect duration. |

Freeze reference layers from admitted endpoint/content revisions before the sweep, never from suspect frames. Reuse existing rendering primitives; independent law/ownership checks and mutants test common-mode failures. Unobservable properties remain unproved.

For recorded rasters, bound all reachable texels under the predicted transform and reconstruction kernel. Include antialias coverage, premultiplied blending, color conversion and quantization. Smooth certified regions admit this local form:

$$
|\Delta I_c|\leq G_c\,\delta_{\mathrm{pose}}+A_c\,\delta_{\alpha}+\sum_j L_{c,j}\delta_{m_j}+E_{\mathrm{raster},c}.
$$

Bound gradients/sensitivities independently, never from suspect frames. At discontinuities use reachable-texel intervals. Uninformative bounds require calibrated exact-state witnesses or leave that partition uncertified.

Unchanged deterministic samples require exact bytes. Nonzero error needs an arithmetic/raster derivation, never empirical relaxation. Calibrate colors, half-coverage edges, real translation, clipping and native density before observer freeze.

Test checkerboards, pixel lines, transparency, gradients, low contrast, long/truncated titles, emoji, RTL, large fonts and loading. Assert required ink visibility. Drive dynamic data from recorded events; never mask defects or crop failing regions.

## 7. Sampling and native completeness

[PixelCopy](https://developer.android.com/reference/android/view/PixelCopy) reads the latest queued buffer from its source window. It does not promise an atomic multi-window display composite or historical frame access. [Compose test time](https://developer.android.com/develop/ui/compose/testing/synchronization) does not control Android measure/draw. These API limits require two receipts, not a longer idle wait.

### Controlled frame sweep

Drive existing fixture time at declared 60/90/120 Hz intervals, shifted phase and irregular/duplicate/stale timestamps. Probe adjacent representable times around joins. Cover pre-intent, every logical frame, clock-independent traversal, attachment, handoff, retirement and post-transition rest.

Snapshot on Main; validate each submitted window’s original generation token through existing native capture. Copy each app window and capture the actual display composite using [UiAutomation](https://developer.android.com/reference/android/app/UiAutomation#takeScreenshot()). Keep scrim/order receipts; pasted windows are not observed composites.

Select the app viewport using asserted native insets; retain full raw captures privately. Measure density/font scale inside every native window. Keep IME/system surfaces in composite evidence; check app overlap at their declared boundary.

Pair by independent generation tokens, retaining and checking old or mixed epochs. No nearest-frame matching, correct-looking-pixel waits, replacement retries or undeclared clock advances. Timeouts are Incomplete.

### Every physically presented frame

Controlled sweeps cannot prove every physical refresh. Add emulator compositor collection accounting for each presentation and latched window-buffer tuple, including extra traversals. Deduplicated pixels retain presentation timestamps/IDs.

Prove feasibility on API 26/36. A pinned-image compositor/output-buffer tap is a candidate, not an available-API claim. Require lossless output, presentation association, zero gaps and rejection of one-presentation mutants. Callback counts alone cannot prove completeness.

If public APIs cannot meet this receipt, build the emulator collector before full-gate activation. Disclose capability gaps to the lead; controlled sweeps must not be relabeled complete. Real-time runs exercise interleavings that capture barriers can hide.

## 8. Automatic enrolment and residual report

Generate a closed-world inventory from resolved shared factories and route declarations. Use stable declaration IDs plus runtime instance IDs, never text/list position/object hashes. Shared internals select laws, homes, triggers and content capabilities.

Descriptors bind certificate family, route, content schema, events and driver recipe. Shared families generate open/close/reversal, Back/outside dismissal and interruptions. Screens bind data/reachability; they cannot opt out or supply laws.

Lint resolves aliases, wrappers, extensions and qualified names. Reject screen engine access, private clocks/windows, unclassified motion/drawing and duplicate implementations. Unknown factories, drivers or dispatch fail closed, including reflection.

Reconcile registered owners, drawn nodes and windows with static declarations. Reach every transition, reverse path and nested instance; reject undeclared runtime paths. Unrunnable scenarios are Incomplete. Seed omissions to prove detection.

Residual rows name transition/element, source, owner, reason, missing proof, frame scenarios and removal condition. Ratchet identities and obligations, not counts: renames cannot hide growth. New obligations fail; verified removals shrink the report.

Residuals support migration, never release exemptions. Forbidden overrides/duplicates block despite green pixels. Other uncertified content needs full frame proof and a disposition. Recommended final gate: zero unresolved residuals.

## 9. Cost and evidence

Strip diagnostic registries/scans/buffers/writers from distribution builds; verify stripping and exact output. On Main, capture only immutable records. Serialize/encode off Main. Diagnostic builds never establish qualified timing.

Diagnostic memory scales with visible nodes/live windows, never library/queue length. Stream off Main. A full bounded capture queue makes the test Incomplete, never drops frames. Retain five comparison frames; preserve proof artifacts.

| Cost | Planning estimate, not a measured baseline |
| --- | --- |
| Node observations | Linear in drawn nodes/live windows. Benchmark runtime allocation cost. |
| Pixel comparison | Linear in viewport pixels, live windows and captured frames; no full library/queue materialization. |
| Image working set | At 1080 × 2340 RGBA, about 9.64 MiB per image; five composite frames about 48.2 MiB, plus per-window copies and nodes. |
| Illustrative sweep | 50 family scenarios × 8 event schedules × 90 frames × 2 APIs: 72,000 frame bundles. This is arithmetic, not a coverage cap. |
| Wall time | Assuming 30–100 ms per bundle: 36–120 emulator-minutes, excluding boot/setup/analysis. Benchmark first. |
| Raw storage | Composite images alone: about 678 GiB. Stream lossless compression/deduplication; report actual bytes. |

Cache by dependency digest. Every release checks inventory, conformance and evidence; heavy suites rerun for invalidated certificates, partitions or residual changes. Observer changes invalidate their artifacts.

Run local API 26/36 proofs through gated `localica-*` containers, respecting queues/holds. Hosted diagnostics need lead approval. Publish aggregate cost/RSS/evidence receipts through the existing measurement pool; no duplicate daemon or per-frame pool writes.

Stress 13.4k–50k tracks and one million queue entries; report heap/GC. Preserve A35’s 8.3 ms whole-frame target. Qualify overhead using plain non-debuggable, non-profileable release builds, speed-profile, screenrecord off, badging and dexopt receipts.

Performance proposals cite Performance Standard §G1/§B1 (main/RT/GPU), §G3/§M5 (exact equality), §G6 (architecture). Hook removal targets zero added work; measured overhead is unknown. Frozen Normal/Single-Tone optics remain untouched.

## 10. Release gate and certificate reuse

Extend existing release/tooling entry points; no sixth workflow. Validate local certificates alongside the required complete release suite. Local acceptance authorizes neither hosted dispatch nor publication.

Require:

1. Architecture lint passes with zero baselines, suppressions or prohibited implementations.
2. Generated inventory equals declared, reachable and observed coverage; all required content partitions are classified.
3. Every referenced engine/presentation certificate matches the current dependency closure and native environment.
4. Node, pixel, capture-completeness and screen-conformance results pass; no unresolved residual remains.
5. Historical bad-source witnesses and seeded faults fail under the unchanged observer; repaired controls pass on both APIs.
6. Method inventories reconcile with XML failures/errors/skips, artifacts and installed bytes; missing or partial results block.

Use Passed, Failed and Incomplete. Originals remain failed after reruns. No golden baseline, ignored region, retry-until-green or known-flicker allowance. Zero executed scenarios cannot pass.

Keys cover engine/component/layout/material/content contracts, specs, compiler/Compose/rendering dependencies, observer/oracle/drivers, image/GPU/API and viewport/density/font profiles. Include resources/generated/indirect dependencies; unknown closure changes invalidate conservatively.

Screen changes rerun exact-candidate conformance. Reuse heavy proof only within unchanged admitted capabilities and dependency closure. Preserve original source/APK hashes; reused evidence is not an exact-candidate native run.

Archive app/test APKs built together before execution. Retain full source SHA, observer/fixture hashes, installed-byte receipts or absence, continuous logcat, XML, frame/window ledgers, law inputs, raw failures and minimal reproducers. Never infer missing native receipts.

## 11. Delivery tasks and ownership

These waves are separate from UI consolidation Stages. B/C/D2/E are templates: one law, adapter, API or raster primitive per fresh agent. Freeze concrete file ownership before parallel work; serialize shared edits. Integration owns shared wiring.

| Task | Depends on | Exclusive write ownership | Reviewed exit |
| --- | --- | --- | --- |
| A: contract | 884/893 agreement | Existing diagnostic contracts; spec amendment | Freeze domains, records, certificate keys and concrete file ownership |
| B: law oracle | A | Existing trajectory/curve tests; diagnostics laws subtree | Interval/join proof, reachable sequences, mutants |
| C: observations | A; B first if engine edits needed | Existing production hooks and observer adapters | Draw coverage, exact output, no duplicate observer |
| D1: feasibility | A | One new native capture feasibility fixture; private tooling | Both APIs: lossless presentation feasibility and gap mutant |
| D2: collector | D1 | Existing native capture files; selected emulator collector | Account for every presented tuple; reject mixed/extra-traversal faults |
| E: pixels | A/C/D2 | Diagnostics pixels subtree | Raster bounds; blob, low-contrast and one-pixel mutants |
| F: enrolment | A; 893 coordination | Existing lint detector; inventory/conformance subtree | Reject aliases, missing drivers/windows, arbitrary slots and omissions |
| G: witnesses | B/C/D2/E/F | Separate continuity witness test files | Historical bad-source failures, 878 radius rejection, repaired controls |
| H: integration | All reviewed exits | Existing Gradle, workflow, release and documentation wiring | Exact-candidate rehearsal; reject stale certificates and incomplete publication |

B, D1 and F may run in parallel after A, with disjoint files. Only C edits production hooks. E waits for frozen capture/contracts. Separate witness files permit separate owners. This design assignment launches no agents.

Compile both proof arms locally, self-check fixtures and freeze observers. Derive methods from callers plus the popup regression set, following the slice checklist. Shared-law changes cover all affected families.

## 12. Open decisions and recommended defaults

| Open question | Recommended default |
| --- | --- |
| Every presentation on stock images? | Unproved. D1 proves completeness or selects a pinned compositor collector; full gate waits. |
| Is fuzzing exhaustive? | No. Separate state-machine/interval arguments from sampled partitions and unresolved obligations. |
| What is checked once? | Shared renderers, content schemas and native adapters within one dependency closure. Screen data needs conformance. |
| Arbitrary screen content? | No blanket certification. Extend shared contracts or retain residual frame obligations. |
| Pixel slack? | Derived arithmetic/sampling/quantization intervals only. Unsupported shader/content partitions remain uncertified. |
| Do tested residuals waive lint? | No. Zero forbidden overrides/duplicates and zero unresolved residuals at release. |
| Which motion model? | Certify the integrated model; add accepted Apple partitions without screen overrides. |
| Full screen frame suites each release? | Only uncertified obligations; always run cheap route/content conformance. |
| Certificate invalidation? | Any motion, rendering, capture or oracle dependency; uncertain closure invalidates. |
| Can cost reduce frames/data scale? | No. Reuse valid certificates, deduplicate losslessly and respect capacity gates. |
| Are historical clips sufficient? | No. Reproduce bad source natively and seed each anomaly in real shared paths. |

# Continuity release gate

Status: **Proposed, design only**, for [883](https://github.com/anastaci1a/localica/issues/883). No native acceptance claimed. Implementation requires [884](https://github.com/anastaci1a/localica/issues/884) and 893's accepted shared API.

## 1. Decision and scope

Certify shared motion, rendering, lifecycle and content contracts once per dependency revision. Screens prove conformance; uncertified paths need frame proofs. Publication requires complete coverage and zero unresolved residuals, never architecture exemptions.

Cover popups, navigation/Back/predictive gestures, Settings, player/Queue and component flights, overlapping loading, IME, scrolling, resize, density/font changes and recreation. Declare system-surface interaction boundaries.

Separate analytic proofs, finite tested partitions and pinned-emulator presentations. Fuzzing/screenshots cannot prove arbitrary histories or every A35 refresh. Missing proof/receipts are **Incomplete**, blocking publication.

Research exits: complete capture and a useful real glass/text bound on API 26/36. Expand only afterward.

## 2. Threat model and witnesses

Test wrong ink/content/order/sampling despite correct curves, legal interruptions and stale/illegal submissions.

| Glitch | Historical witness | Required discriminator |
| --- | --- | --- |
| Ghost/blank trigger | [880](https://github.com/anastaci1a/localica/issues/880): Sort/View return blob | Independent trigger identity, ink/material; one-presentation fault |
| Reverting content | [881](https://github.com/anastaci1a/localica/issues/881): Creator, displaced counts/placeholder on artist Back | Input-derived route/rows/revisions from first return layout |
| Conflicting titles | [882](https://github.com/anastaci1a/localica/issues/882): Library/Settings predictive overlap | Exclusive heading role and contributions |
| Wrong plane | [867](https://github.com/anastaci1a/localica/issues/867): pill above closing More; 884 C1: More above chrome | Intent-derived depth policy and actual order |
| Sampling feedback | 884 C2: Settings return samples its projection | Read generations/source policy, even behind opaque ink |
| Detached ink | [887](https://github.com/anastaci1a/localica/issues/887): Queue title disappears | Delay destination recording/retire source; reject exact presentation |
| Invalid geometry | [878](https://github.com/anastaci1a/localica/issues/878): negative radius | Finite/range checks before every presentation |
| Teleport/early retirement | Seeded faults at family boundaries | Position/velocity joins, ownership, node and pixel checks |

Preserve recordings/timestamps/frame indices. 887's short/long-title fallback/RC controls establish disappearance, not scaling causality. Videos cannot establish nodes/completeness.

Freeze bad-source/repaired controls under one observer on both APIs. Retain focused regressions until equivalent rejection works. All detector results here are future obligations.

## 3. Extend existing owners

Basis: `1ff6f3cae6511e81714c2dd8927ebc7d069355d1` (884, legacy motion). Certify integrated laws; Apple is not assumed implemented. Current rules supersede historical tolerances.

| Existing owner | Extension and limit |
| --- | --- |
| Engine clock/trajectory/Curve/expansion/popup/compositor | Segment inputs, phases, residuals, transfers. Clock callbacks precede layout/draw. |
| Popup/PopupSharedContent/Sheet/Drawer | Enrol roles; select homes/laws/triggers internally. |
| MorphLayout/GlassMorph/GlassPopupHome | Actual recording, transforms, ownership/submission; semantics misses ink. |
| FrostedMaterial.BackdropRecordingAncestry | Extend recording/layout edges with reads/writes/generations. NoneAtlasInput assertions do not prove Normal/Single-Tone. |
| sharedTest ContinuityObserver/IdentityObserver | Production receipts; synthetic fixtures/current tolerances are not native proof. |
| NativeFrameCapture/ShadowNativeCapture/PopupCapture | Original-window generations/scrims; consolidate collector adapters. |
| PopupPolicyDetector/UiPolicyDetector; accepted 884/893 contracts | Extend architecture lint/inventory; no parallel scanner. |

Extend by composition, without duplicates or screen overrides.

## 4. Checkable certificates and screen conformance

A certificate names domain/preconditions, predicates, abstract inputs/transitions, production branches/symbols, checker hashes and evidence. Label analytic, exhaustive-abstraction and sampled obligations separately. Green schedules cannot establish induction.

Prove initialization/preservation for every reachable branch; bind production via reviewed digests/executable assertions. Unbound branches, unsupported inputs or undischarged predicates remain residuals. Never clamp defects away.

Partition laws at joins/extrema; prove C0 and required C1 at retarget/release/rebase/admission/rest, including between samples. Cover initial velocity, zero/unequal travel, reversals, third-host transfer, source removal and stale generations.

Enumerate settings/kinds; analyse continuous scale/geometry. Cover Reduced entry/live changes, scale zero with respect enabled/disabled, live speed/scale changes and recreation. Future Apple main/cross phases/residuals need proof.

### Shared intent and return-plane contract

Version a shared role/event policy independent of observed flags/segments. Chrome-menu Back, outside tap, Scan and Settings must start above chrome, cross below, present inline and restore the trigger before retirement, even without screen departure.

Derive depth from the approved shared role/geometry contract. Intent/role selects obligations; `prepared`, `handedOff`, observed plane and `expanded || settingsOpen` cannot define expectations. Check preparation/crossing/ink/completion against presentations. Reduced uses exact shared atomic return without blanking.

Settings retains the outgoing home for page transfer; Back/outside/Scan retain browse. Preserve `PopupChromeDepthTest`'s Scan/Settings distinction; add Back/outside. Smooth native above-chrome dismissal must fail.

### Worked content certificate: artist return

Proposed `ArtistGridReturn/v1` admits certified rows/loading/artwork. Preconditions: committed Album-Artists return route, stable row keys/roles, frozen repository revision and separate external event ledger. Navigation/repository inputs supply these facts, never the renderer.

| Obligation | Predicate / legal transition | Checker and evidence |
| --- | --- | --- |
| Initial state | Route, row key/role and title/count/artwork revisions match input snapshot | Independent fixture model; entry binding assertions |
| Back/peek/cancel | Back selects recorded return route; peek/cancel preserve snapshot; commit changes admitted route state | Shared reducer table; production branches mapped to cases |
| Data/loading | Only matching repository delivery/artwork completion authorizes change | External sequence and causal request/row/revision IDs, separate from render revisions |
| Atomicity/order | Presented row has one committed title/count/artwork tuple; stale completion cannot overwrite it | Commit IDs; input/binding/draw happens-before assertions |
| Intermediate binding | Every return frame has expected route/role/latest legal tuple; loading changes only admitted fields | Proven binder/reducer predicates and row receipts from first return layout |
| Retirement | Required row/title ink survives until destination presentation | Ownership and node/pixel receipts |

Discharge base/preservation cases, including stale/out-of-order deliveries. Hash binder/reducer/resources/indirect dependencies. Unproved intermediate bindings cannot inherit certification.

A screen qualifies for endpoint/handoff-only conformance only when **all intermediate bindings are proven**. Mount real roots/windows; check resolved bindings/domain, geometry, density/font, roles/epochs and trigger restoration. Row schemas alone are insufficient; arbitrary slots/Canvas/clocks/effects remain residuals.

881 mutant: change one returning screen binding to Creator/wrong count/artwork. Conformance must reject its digest/domain or full frame residuals must fail, including during shared-certificate reuse. Render revisions cannot authorize external events.

## 5. Actual node records and law bounds

Record frame/intent/spec/segment epochs, window/host/buffer generations, expected/observed nodes, logical/instance/content IDs, parent/role/window, world matrix/bounds, alpha/clip/corners/stratum/material/shadow and draw occurrence. Include retained layers; semantics is not ink. Whole-viewport coverage rejects unmatched draws/windows.

Predict from frozen segment inputs using independent mathematics, never fitted observations:

$$
q(t)=q_0+\Delta q\,g(p(\tau(t)))+r(\tau(t)).
$$

$$
\dot q(t)=\left[\Delta q\,g'(p)\dot p+\dot r\right]\dot\tau(t),\qquad
|q(t_{k+1})-q(t_k)|\leq\int_{t_k}^{t_{k+1}}|\dot q(t)|\,dt+E_{q,k}+E_{q,k+1}.
$$

Check each pose and displacement interval. Split at intents, scale changes, branches/gates/derivative roots. Between events:

$$
|q(t+h)-q(t)-h\dot q(t)|\leq\frac{h^2}{2}\sup_{u\in[t,t+h]}|\ddot q(u)|+E_{\mathrm{arith}}.
$$

Bound Hermite analytically and springs by outward-rounded intervals; extend Curve via its hook owner. Propagate matrices/division/perspective; singular transforms fail, unresolved subdivision is Incomplete.

Derive error from actual IEEE-754 operations, input/time intervals and rounding cells. Check unrounded float32 receipts against independent higher precision at joins/adversarial magnitudes. No fixed slack; monotonicity applies only between derivative roots, allowing legal overshoot/reversal.

Require one legal visible owner, coherent epochs and predicted occlusion. Measurement copies are not ink. Retirement needs destination presentation/restored trigger. Crossfades need contribution laws/exclusive roles. Reject NaN, illegal alpha/radius and identity/lifetime violations without slack.

### Render dependency evidence

Extend `BackdropRecordingAncestry`: resource ID, recording/write generation, containment, actual read source/generation, reader role, transaction/presentation. Export via the existing observer, including retained children. Node parentage cannot supply this evidence.

Reject same-generation cycles. Temporal reads require shared allowed-source/age and causal provenance: older resources containing the popup's projection remain forbidden even without cycles. Undeclared sources/generations or missing reads are Incomplete.

C2's home-recording → projected-flight → material → home-recording must fail in Normal/Single-Tone, API 26/36, even with opaque concealment. Preserve NoneAtlas rejection checks; extend diagnostics without changing frozen optics or duplicating the graph.

## 6. Pixel comparison without baselines

Subpixel travel sharply changes checkerboards; faint ghosts evade averages. Laws alone cannot bound pixels. No SSIM, area cutoff, golden image or tuned tolerance.

Freeze reference layers from admitted endpoints/content revisions, never suspect frames. Replay existing primitives with independently predicted poses/clips/alpha, intent-derived stack and permitted sources. Independent ownership/dependency checks and mutants expose common-mode rendering errors.

Compare every channel to its derived raster interval. Transport consecutive layers using predicted motion, legal data events, occlusion/scrims/shadows/material changes. A five-frame window diagnoses excursions; no minimum fault duration. Check required ink and unauthorized revisions explicitly.

Bound reachable texels/kernel support, antialias coverage, premultiplied blending, color conversion and quantization. In smooth certified regions:

$$
|\Delta I_c|\leq G_c\,\delta_{\mathrm{pose}}+A_c\,\delta_{\alpha}+\sum_j L_{c,j}\delta_{m_j}+E_{\mathrm{raster},c}.
$$

Derive sensitivities independently; discontinuities use reachable-texel intervals. Unchanged deterministic samples require exact bytes. Derive nonzero errors; uninformative bounds remain uncertified, never widened until green.

Feasibility must derive a useful real glass/text bound and reject a one-pixel fault and faint one-presentation ghost through the existing renderer, without empirical slack. No universal-oracle commitment. Expand to checkerboards, transparency/gradients/clipping, long titles, emoji/RTL, large fonts and loading only with discharged bounds.

## 7. Sampling and every-presentation receipt

[PixelCopy](https://developer.android.com/reference/android/view/PixelCopy) copies the latest queued buffer, without history/atomic window composition. [Compose time](https://developer.android.com/develop/ui/compose/testing/synchronization) does not control Android measure/draw. [UiAutomation](https://developer.android.com/reference/android/app/UiAutomation#takeScreenshot()) supplies no presentation ID. These cannot certify every presentation.

### Controlled sweep

Sweep 60/90/120 Hz, shifted/irregular/duplicate/stale timestamps and representable join-adjacent times. Cover pre-intent, logical frames, extra traversals, attachment/handoff/retirement/rest. Snapshot records on Main; capture off Main.

Validate original native tokens, including mixed/old epochs and scrim/order receipts. Retain actual composites, not pasted windows. Assert native insets/density/font; retain full raw captures and IME/system boundary evidence.

No nearest-frame matching, retries, correct-pixel waits or undeclared clock advances. Distinct frozen-time traversals must expose destination-delay/source-retirement faults. Timeout is Incomplete.

### Pinned emulator presentation boundary

A presentation is one completed virtual-display refresh, including unchanged refreshes, not an app submission. Pin image/backend/API/GPU/refresh/color path. [AOSP composition](https://source.android.com/docs/core/graphics/surfaceflinger-windowmanager) may delegate to Hardware Composer; a SurfaceFlinger client target may omit later layers.

Proposed boundary: final virtual-display output **after all composition**, joined to display-model refresh completion. D1 must identify each API's exact hook and prove no app/system layers bypass it. Missing unchanged refreshes/final pixels defeats the boundary.

| Presentation record | Required evidence |
| --- | --- |
| Identity/time | Display ID/recreation epoch; source-assigned sequence/timestamp; refresh mode |
| Completion | Scanout/present completion and fence, or documented backend equivalent; pending/failed cannot pass |
| Composite | Final buffer ID, immutable lossless bytes/hash, size/format/orientation |
| Latched state | Layer order/IDs, buffer/frame generations, transactions, transforms/crop/alpha/visibility, window epochs/app draw tokens |
| Coverage | Independent start/end counters; enqueue/write counts, overflow/epoch bridges |

The independent display refresh/completion ledger assigns sequence before enqueue, outside the collector queue. Reconcile its range/epochs one-for-one with completed pixels/latched state. Collector-only counters are circular. Repeated output IDs are legal; missing/duplicate presentation IDs fail.

Start with a settled presentation before input; end after async/transfer obligations settle plus a confirming refresh. Timeout is Incomplete. Preserve repeats/traversals/resize/recreation bridges; log dropped/unlatched submissions separately.

Refusal, lost ledger, overflow or unmatched fence/output/transaction/epoch is Incomplete. Reject dropped/duplicated/mismatched records and one-presentation visual mutants. Never throttle the app or insert real-time capture barriers.

Build a pinned collector if stock images fail. Only pinned presentations/exercised histories are attested; A35 timing is separate.

## 8. Automatic enrolment and residuals

Generate inventory from resolved factories/routes. Stable declaration/runtime IDs bind roles, certificates, content/events and drivers. Shared families generate open/close/reversal, Back/outside/action dismissals and interruptions. Screens bind data/reachability, never laws/opt-outs.

Extend lint across aliases/wrappers/extensions/qualified names. Reject screen engine/window/clock/draw overrides, duplicates and unknown/reflected dispatch. Reconcile declarations with runtime owners/draws/reads/windows, including reverse/nested paths. Missing drivers/unclassified paths are Incomplete; seed omissions to prove closure.

Residual rows name transition/element, source/owner, missing proof, scenarios and removal condition. Ratchet obligations/identities, not counts. Full frame proof can discharge a path, never waive lint. Publication requires zero unresolved residuals.

## 9. Capacity, runtime and evidence cost

Strip diagnostics from distribution; verify stripping/exact output. Main captures immutable records; serialization is off Main. Diagnostic builds cannot qualify timing. Memory follows visible nodes/live resources/windows, never library/queue length.

Plan **serial APIs**: archive APKs, release build reservation, run/stop API 26, run/stop API 36, then analyse offline. Emulators cost 7–8 GB each; reservation cap is 15 GB, plus new limit + 6 GB available-memory admission. Two 8 GB emulators exceed it; smaller pairs are not guaranteed.

Use gated `localica-*` containers and queues/leases/reserves locally first; hosted work needs lead approval. Provision one 8 GB emulator/capture-worker container; reserve offline analysis sequentially. D1 measures limits; live gates still govern admission.

| Pipeline boundary | Required envelope / receipt |
| --- | --- |
| Guest/host | Guest heap/GC, emulator/worker RSS/high-water marks, limits, retained/reference layers, GPU/readback/driver memory where observable |
| Online capture | Final pixels/ledger/checksums; initially 32 queued bundles, at most 0.267 s at 120 Hz. Measure enqueue/readback/encoder/disk throughput/stalls |
| Buffers | 1080 × 2340 RGBA: 9.64 MiB/image, 48.2 MiB/five comparison frames, 308.5 MiB/32 capture composites, plus windows/references/readback/encoder buffers |
| Offline oracle | Expected rasters/intervals after capture; initially 256 × 256 tiles: float32 RGBA lower/upper pair about 2 MiB versus 77.1 MiB/full viewport. Include halos/copies |
| Disk | Reserve uncompressed composites/windows/references, ledgers and codec overhead for declared duration; assume no savings on high-entropy artwork |

Composites alone generate 1.13 GiB/s at 120 Hz, before window evidence. Benchmark sustained drain, stalls, queue high-water and disk reserve against full input rate. Raw spooling/lossless encoding must sustain it; dropping evidence/app throttling cannot pass.

Preflight derives RAM/disk from viewport/windows/resources/queue/duration. Unknown bounds/insufficient capacity are Incomplete. Drain/analyse between settled scenarios; preserve overflow failures, never retry into passing evidence.

Illustration: 50 scenarios × 8 schedules × 90 frames × 2 APIs gives 72,000 bundles/678 GiB composite bytes. Offline analysis is separate from real-time drain. Benchmark before forecasting duration; counts never cap coverage.

Retain manifests/ledgers/replayable lossless evidence through review and certificate-retirement policy; never discard failing frames. Archive APKs before runs. Report aggregate cost/RSS/bytes through the existing measurement pool, without another daemon/per-frame writes.

Stress 13.4k–50k tracks/one million queue entries; report heap/GC. A35 target stays 8.3 ms/whole frame. Qualified timing: plain non-debuggable/non-profileable release, speed-profile, screenrecord off, badging/dexopt receipts. Cite Performance Standard §G1/§B1, §G3/§M5, §G6 (budget/equality/architecture). Hook removal targets zero overhead, currently unmeasured. Glass optics stay frozen.

## 10. Publication and reuse

Extend existing release/tooling entry points; no sixth workflow or implied hosted/publication approval. Require:

1. Zero architecture baselines, suppressions, overrides or duplicate implementations.
2. Declared/reachable/observed inventories agree; input/content obligations are discharged.
3. Certificates match dependency/native closures, including intermediate binding proofs.
4. Node/dependency/pixel/presentation/conformance checks pass; zero unresolved residuals.
5. Unchanged observers reject historical bad source/mutants; controls pass on both APIs.
6. Methods reconcile with XML failures/errors/skips, APKs/artifacts; partial evidence blocks.

Use Passed/Failed/Incomplete; zero scenarios cannot pass. Preserve failures; no baselines, masks, flicker allowances or retry-until-green.

Keys cover laws/components/binders/reducers, resources/generated/indirect dependencies, material/content/intent, compiler/Compose, observer/oracle/drivers, capture image/backend/GPU/API and viewport/density/font. Unknown changes invalidate.

Always rerun inventory/exact-candidate conformance. Reuse heavy proof only with unchanged capabilities/dependency closure. Preserve original source/APK hashes; reuse is not a candidate native run. Screen binding changes invalidate conformance despite valid shared certificates.

Retain source/observer/fixture hashes, paired app/test APKs, installed-byte receipts or absence, logcat/XML/ledgers/law inputs/reproducers. Never infer native receipts.

## 11. Delivery tasks and exclusive ownership

Templates, **not assignments**. Split D1 into one-API capture, one-material raster and capacity tasks. Other agents own one law/adapter/partition/witness.

A freezes reviewed **path/symbol ownership** and **interface dependencies** against accepted 884/893. Name exact files/symbols/writers/tests/consumers before parallel work. Shared edits require serial ownership transfer/interface freeze.

Name existing ContinuityObserver/IdentityObserver, NativeFrameCapture/ShadowNativeCapture/PopupCapture, FrostedMaterial.BackdropRecordingAncestry and PopupPolicyDetector/UiPolicyDetector. C owns production Curve/GlassMorph/GlassPopupHome/ancestry hooks; B requests bounds through C or serial transfer. D2 owns capture/receipt files; C only consumes that interface.

| Task | Dependency and write scope to freeze in A | Reviewed exit |
| --- | --- | --- |
| A: contracts/map | 884/893; diagnostic contracts/spec | Writers/interfaces/domains/keys and feasibility criteria |
| D1: feasibility | A; isolated fixture/private collector experiment | Both-API receipts/faults, real glass/text bound/faults, capacity benchmark |
| B: laws | D1 go; allocated existing law tests/oracle files; production via C | Interval/join/branch obligations and mutants |
| C: observations | D1 go/B interface; allocated hooks/ancestry/observer files | Draw/read coverage, intent/content binding, exact output |
| D2: collector | D1 go; allocated existing capture files/pinned collector | Presentation/fence/layer reconciliation; corruption faults |
| E: pixels | C/D2 interfaces; allocated raster files | Useful bounds; ghost/low-contrast/pixel faults |
| F: enrolment | A; accepted lint owner and allocated conformance files | One scanner rejects bypasses/omissions/wrong bindings |
| G: witnesses | B/C/D2/E/F; separate witness files | Bad-source red/repaired green, C1/C2/screen-only 881 |
| H: integration | All exits; existing build/workflow/release wiring, sequential | Exact-candidate rehearsal; stale/incomplete gate rejection |

D1 precedes broad B/C/E investment. After A's map, small B preparation/F inventory may overlap D1 in disjoint files. C/D2 overlap requires frozen receipts. Consolidate/remove D1's temporary adapter in D2; never retain a duplicate.

Compile both arms/self-check fixtures locally. Derive methods from changed-symbol callers plus popup regression set per slice checklist; cover every affected family.

## 12. Open decisions and defaults

| Question | Recommended default |
| --- | --- |
| Stock-image completeness? | Unproved; D1 demonstrates final-output path or activation waits. |
| Universal raster oracle? | Prove one real partition first; unknown domains remain residuals. |
| Checked once? | Shared contracts/dependencies; screen data/bindings still need conformance. |
| Endpoint-only screens? | Only proven intermediate bindings qualify. |
| Pixel slack? | Derived intervals only. |
| Cost versus coverage? | Serial APIs/reuse/lossless storage; never fewer frames/data. |

## Review response

| Finding | Change in this revision |
| --- | --- |
| 1: presentations | §7 independent ledger/final pixels/fences/epochs/faults; §11 early D1. |
| 2: C1 | §4 intent-derived return plane/completion and four witnesses. |
| 3: C2 | §3/§5 existing ancestry owner, read generations/temporal policy. |
| 4: content | §4 worked binding/event/branch obligations and screen-only mutant; §10 reuse invalidation. |
| 5: capacity | §6/§9 real raster feasibility, serial emulators/cap, throughput/queues/tiles/disk. |
| 6: ownership | §11 mandatory file/symbol/interface map, existing owners and serial transfers. |

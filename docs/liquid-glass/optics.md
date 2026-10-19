# What can we establish about the lens?

[Guide](README.md) · [Apple sources](sources.md) · [Interactive lab](lab.html) · [Portable equations](model.md) · [Implementation requirements](specification.md#geometry-and-readability)

## Evidence, not a recovered shader

The strongest defensible description is **a designed rounded optical surface with independently adaptive transmission, scattering and illumination**. A shallow cushion with a quiet interior and stronger curved shoulders is a useful model for some observed controls. Neither a sphere, a constant-thickness extrusion nor a literal water droplet is established as Apple's universal geometry.

| Evidence class | Supports | Does not establish |
| --- | --- | --- |
| [Apple's material overview](https://developer.apple.com/videos/play/wwdc2025/219/?time=89) | Lensing, interaction-driven shape/light, and size-dependent material changes | Height field, refractive index, light rig, exact timing or shader equations |
| [Materials HIG](https://developer.apple.com/design/human-interface-guidelines/materials) | Regular material scatters/adjusts luminosity; Clear prioritizes media; controls can become glass temporarily | One blur amount or tone curve across variants/settings |
| [Custom-view API](https://developer.apple.com/documentation/swiftui/applying-liquid-glass-to-custom-views) | Capsule default, configurable outline, shared containers and stable morph identities | That a capsule outline implies a cylindrical three-dimensional lens |
| [Measurements in #222](https://github.com/anastaci1a/localica/issues/222) | In selected scenes, registered interior landmarks and localized peripheral displacement; blur and illumination matter independently | Unique surface normals/thickness, tangential displacement from a single bar, or cross-scene visual parity |

An apparently flat optical core need not look like a sanded face. Continuous curvature and soft directional reflection can suggest a rounded body while preserving central registration. Conversely, a large central zoom is not evidence of correct depth. Test image displacement separately from highlights and perceived shape.

## Reproducible reference comparison

The [Apple morph asset](https://developer.apple.com/tutorials/videos/com.apple.SwiftUI/liquid-glass-morph-views.mp4) exposes the later circle's backdrop before it splits. Prior #222 work selected a label-free 21×21 patch centered at (423,60), approximate circle center (429,114), radius 83. It lies on a shoulder, **not at the center**. Its geometry, source registration and chosen patch remain assumptions of this experiment.

Run [reference_patch.py](experiments/reference_patch.py) against a privately downloaded asset. It verifies SHA256 `4feea1c34a97ae1a4853c8f38831b9c7579c739db2c1060b1f4b84d3082d3992`, decodes native frames, reads actual presentation timestamps, and checks identity/known-shift synthetic controls. Requirements: Python 3, NumPy, OpenCV and ffprobe. That low-resolution experiment is distinct from the newer [HD reconstructions](reconstruction.md), which publish attributed comparisons and fit both optical hypotheses against three scenes.

- Training parameters are frozen from the earlier experiment at 1.133333 s; repeated observations are 1.400000, 1.666667, 4.066667, 4.350000 and 4.616667 s. Variable frame intervals prohibit assuming frame index / 60.
- The new reproduction also freezes photometry from the training frame, instead of only refitting positive RGB gain/offset on every observation. Results below use OpenCV 5.0.0 / NumPy 2.4.6; values are RGB RMSE on a 0–255 scale.

| Frozen geometric model (sigma, inverse dx/dy in decoded px) | Held-out mean, tone refitted | Held-out mean, tone frozen |
| --- | ---: | ---: |
| Identity (0, 0, 0) | 3.260 | 3.334 |
| Blur only (3.25, 0, 0) | 2.883 | 2.964 |
| Translation only (0, 0.25, 2.5) | 2.794 | 2.875 |
| Blur + translation (3, 0, 2.5) | **2.517** | **2.609** |

The joint model retains its advantage when tone is frozen. This is evidence for a modest displaced, softened lookup in this patch, not an independently validated global lens. Earlier sensitivity analysis admitted sigma 1.5–4.5 and vertical lookup 1–3.75 px within +0.15 training RMSE; it is not a statistical confidence interval. Background reconstruction, encoding, unknown light/tone and the aperture problem remain confounded.

Other #222 observations constrain different properties: Apple's clear slider retains sharp track edges in the core and widens them nearer the rim; a regular text panel supports much stronger effective blur. Those are different materials/scenes. Do not average their fitted constants into a universal preset. Touch-light propagation has no synchronized contact signal in these assets; no defensible latency or pressure equation was recovered.

## Context audit · 19 October 2026

This review separates three questions: whether pixels are refracted, whether an image resembles a particular Apple context, and whether a physical surface has been identified. Passing the first does not establish the other two. Material names are API roles; a screenshot's apparent transparency alone does not identify its API variant.

| Observation / provenance | What it supports | Limits / decision |
| --- | --- | --- |
| **Clear slider**, [UIKit session, 18:12](https://developer.apple.com/videos/play/wwdc2025/284/?time=1092); earlier #222 joint track-edge fits | Track core stays sharp and registered (within ~0.18 displayed-HD px at 36–51 px inward). At 11 px inward, top edges move −2.20…−2.66 px; bottom edges +3.41…+5.88 px. | Supports peripheral widening, not a uniformly weak lens. Final 8 px were excluded because highlights obscure correspondence. One horizontal bar does not constrain tangential refraction. Earlier results retained, not newly remeasured here. |
| **Regular circle**, registered morph asset and frozen comparison above | Modest displacement plus blur explains one shoulder patch better than either alone. | Same-scene repeats, not independent scenes. No global rim/depth calibration. |
| **Regular text panel**, [material session, 11:51](https://developer.apple.com/videos/play/wwdc2025/219/?time=711); earlier #222 reconstruction | Strong scattering can preserve foreground legibility while obscuring underlying text; effective sigma ~12 displayed-HD px in that scene. | Not the lab's blur-radius unit, not a universal Apple blur. Alignment and photometry were refitted per frame. |
| **Music mini player / navigation**, [Apple's June 2025 interface clip](https://www.apple.com/newsroom/videos/2025/autoplay/06/apple-introduces-a-delightful-and-elegant-new-software-design/apple-wwdc25-liquid-glass-apple-music-dynamic-tab-bars/large_2x.mp4), 0–7 s, newly inspected | Album cards and text pass beneath the mini player. Color survives; lettering softens and curved boundaries distort differently from the center. Navigation and mini player have different apparent treatments. | Announcement footage, exact build/settings unknown. Scrolling, morphing and concealed source prevent an absolute displacement fit. Some third-party videos reuse this same clip: do not count them as independent evidence. |
| **Media controls / sliders / segments**, [Apple's interface-details clip](https://www.apple.com/newsroom/videos/2025/autoplay/06/apple-introduces-a-delightful-and-elegant-new-software-design/apple-wwdc25-liquid-glass-details/large_2x.mp4), 6–16 s, newly inspected | Media shows through round playback controls. Active slider preserves a visible track; moving segmented selection bends underlying letters. This is more informative than a lens over an unstructured color field. | Multiple component roles, not one parameter set. Stylized transitions and foreground occlusion preclude fitting a universal cap. |
| **On-device Lock/Home Screen, Photos, notifications and Control Center**, [MacRumors hands-on](https://www.youtube.com/watch?v=gCov2x7oo6Y), 0:47–1:07, 3:56–4:10, 5:37–5:53, newly inspected | Real-use context supplement: dense controls and already softened backgrounds make scattering/tint and foreground contrast as important as visible bending. | June 2025 early-beta report; filming angle, reflections, focus and compression confound optics. Use its original phone footage, not the reused Apple Music insert. No current-iOS default or quantitative shader claim follows. |
| **Owner's Music / Control Center screenshots**, private reference folder | Qualitative target for edge definition, relative material appearance and legibility. | OS build/settings and uncovered source unknown; cannot recover z-depth or absolute refraction. Private files stay outside the repository. |

The [source manifest](experiments/context-sources.json) records acquisition hashes, reviewed intervals and sampling density. Contact sheets and full source videos remain private. These new observations are visual checks, not new displacement fits.

Apple explicitly describes stronger refraction, scattering and shadows when controls grow into menus ([adaptivity](https://developer.apple.com/videos/play/wwdc2025/219/?time=360)). Larger size is not a reason to apply a globally weaker transparent overlay. Conversely, uniformly strong refraction is not supported for every resting control. Clear and Regular API variants must also not be conflated with system appearance/accessibility preferences.

### Conclusions retained, corrected and unresolved

- **Retain:** independent refraction, scattering, reflection and contrast; foreground drawn separately; stronger peripheral variation with a comparatively registered interior in several examples.
- **Correct:** the old default lab's 0.10 shoulder gain and zero displacement at the silhouette were engineering choices, not Apple measurements. On its 196×92 capsule, maximum top-axis displacement was only 2.73 logical pixels by default (~1.37 pixels in the 480-wide software raster); even its maximum depth/width settings reached only 7.69 logical pixels. This unnecessarily restricted exploration.
- **Correct:** vertical lines reveal horizontal displacement but conceal much of the vertical displacement along a horizontal pill's top/bottom. Use crossed lines, text and two-axis coordinate probes as well. Low-resolution rendering further attenuates small differences.
- **Correct:** an image feature moving outward is not the sign of the inverse lookup. Compare source coordinates and their spatial derivatives: a lookup jump, translation, magnification and local compression are different phenomena. More global zoom does not establish stronger edge compression.
- **Unresolved:** outermost-rim correspondence, a global height field, exact tone/blur rules, optical changes during real contact, and version-specific appearance. A shape's 2D capsule outline does not prove a cylindrical 3D body; a bright rim does not prove a flattened face.
- **Validation boundary:** GPU/software agreement and nonzero displacement are implementation evidence only. Neither the existing lab nor context-based presets constitute a calibrated recreation of Apple's proprietary renderer.

### Evidence needed before claiming a closer match

1. Select a known OS/build and material role; preserve appearance/accessibility settings. Keep official presentations, original phone footage and owner screenshots distinguishable.
2. Obtain exposed source pixels and matched shape/pose. Exclude foreground glyphs and ambiguous highlights. Reject unregistered handheld footage for subpixel fitting.
3. Fit core registration, peripheral displacement and blur separately. Report normalized distances and spatial derivatives as well as pixel offsets; record excluded regions.
4. Freeze parameters and evaluate independent backgrounds, both axes, rounded ends and long edges. A new frame of the same background is a repeat, not a new scene.
5. Only then promote a candidate to a fidelity claim. Until then, presets are context-based approximations, and extreme optical settings are diagnostics rather than evidence.

## Portable models and audit findings

The [portable equation specification](model.md) owns coordinates, formulas, supported domains, Jacobians, sampling and motion. [model.js](model.js) is its framework-independent numerical reference; it is not the native renderer.

| Model | Construction | Current purpose / limitation |
| --- | --- | --- |
| Boundary field (default) | Independent core lookup, edge amplitude, band width and finite-rim mixing | Tunable image warp; spatial derivatives distinguish compression from magnification. Not an identified optical solid. |
| Refracting cap | Analytic rounded height and normal, Snell entry, planar exit and independent backdrop gap | Geometric comparator with restricted index/pose. Synthetic lighting is not energy-conserving ray transport. |

### Mathematical corrections

- **Inactive outer band:** the old shoulder field explicitly tends to zero at the outline, quadratically. On the 196×92 reference capsule its edge-only displacement is 0.058 logical px one unit inside, compared with 5.695 for the new finite-rim/compression profile; these are model outputs, not Apple measurements. The old cap height also forced a flat normal at its rim. The new cap has an analytic vertical limiting rim normal; no finite-difference normal stencil is needed.
- **Compression versus displacement:** a nonzero rim offset alone does not establish compression. The reference differentiates the inverse map and supplies conservative local nonfolding bounds. The lab warns about uncertified parameter combinations instead of presenting all extreme settings as physically valid.
- **Scale and ray validity:** the optical band has no fixed-pixel floor; cap height and backdrop gap have explicit normalized dimensions. Index range and camera assumptions exclude TIR for this comparator. The general reference reports TIR instead of inventing a transmitted vector.
- **Volume and color:** determinant-one strain is only an affine volume invariant; reconstructing rounded geometry does not guarantee exact volume. Encoded-sRGB channel filtering is not linear-light scattering. Neither should be labeled a physical proof.
- **Dynamics:** spring updates now use the closed-form solution. Fixed-target evolution composes across frame subdivisions; sampled contact impulses and clamps remain interaction approximations. Critical damping alone cannot guarantee monotonic contact with arbitrary inherited velocity.

### Pressure direction

Normal contact lowers depth and expands the footprint. Wall contact compresses the travel axis. **Motion → Boundary compression** compares an in-plane baseline with configurable redistribution into depth. That additional depth can partially offset normal finger compression; it need not exceed the resting depth. The equation, volume limitation and pinned-edge rule live in [Pressure and dynamics](model.md#pressure-and-dynamics). Apple footage does not establish this redistribution law.

### Fixed-field check against the registered circle patch

The earlier reproduction script compares the old lab field and the rim-only candidate (profile 1) on the same approximate circle (center 429,114; radius 83). Geometry, core amount 0.012 and band fraction 0.55 stay fixed. Only blur and positive per-channel photometry are fitted on frame 65, then frozen for the five repeated observations.

| Fixed field | Inverse Y lookup at patch center | Training blur sigma | Repeated-frame mean RMSE, frozen tone |
| --- | ---: | ---: | ---: |
| Previous shoulder profile, edge strength 0.10 | +4.435 px | 3.00 | 2.589 |
| Rim-concentrated candidate, edge strength 0.40 | +3.167 px | 2.75 | 2.565 |

This comparison predates the finite-rim/compression profile (profile 2) and does not validate it. The 0.024/255 error difference is too small and scene-dependent to establish superiority. The useful result is narrower: stronger bending at the outer rim can coexist with *less* lookup displacement at this interior shoulder patch. Neither this patch nor the comparison tests the outermost rim. Remapped fields use OpenCV bilinear interpolation; do not rank their tiny error differences against the earlier translation fit as if resampling and geometry were identical. The source hash, timestamps, positive synthetic control and numerical outputs are emitted by [reference_patch.py](experiments/reference_patch.py).

## Context presets and controls

The presets are the current **qualitative starting hypotheses** for observed circumstances, not a ranking established by independent image-error measurements. Exact numeric values live in `lab.js`. Selecting a preset resets optical/material settings and outline while preserving the backdrop, motion preference and lens position for comparison; custom edits are marked as custom.

| Preset | Observed property being approximated | Important limit |
| --- | --- | --- |
| Transparent active control | Sharp, registered core; localized peripheral bending; no blur/tint/brightness trim | Isolates optics; not a complete slider, switch or their press illumination |
| Clear media control | Media remains visible; stronger rim definition, little scattering | Default study, not a calibrated Apple Music mini player |
| Regular · light / dark foreground | Scattering and brightness adjustment support legible text | Two polarity variants of Localica’s monotonic curve; no automatic Apple contrast algorithm claimed |
| Expanded menu | Greater optical power, scattering and shadow on a larger outline | A fixed context snapshot; merely pressing Expand does not automatically reproduce Apple’s evolving blur/tone |
| Strong edge · diagnostic | Large edge displacement with a registered center | Extreme range, possible folds; not an Apple preset |
| Previous lab · baseline | Reproduces the earlier conservative field | Comparison only; not an endorsed calibration |

Sidebar categories: **Scene** selects outline/backdrop and comparison visibility; **Lens** controls model, profile, power, edge/core, band and outline offset, or cap thickness/gap/index; **Material** controls scattering, tint, readability, rim and shadow; **Motion** controls release damping, pressure redistribution and Reduced Motion. Model-inapplicable controls are disabled. Every control states its function; reset restores all settings, position, collapsed state and Scene tab. Vertical lines remain the default artwork; crossed lines make both displacement axes visible.

## Reading the lab honestly

- Zero optical power, blur, tint, trim, reflection and shadow must reproduce the backdrop, including the shoulder. Coordinate mode encodes X/Y in R/G so tests can decode the actual GPU lookup; exterior and center registration can be checked directly.
- Compare circle, capsule and panel over vertical lines, grid, text and color. Watch the center, shoulder join, corners and diagonal axes through a morph, reversal and held press. Blur must not conceal broken registration or seams.
- The contrast mapping is Localica's monotonic endpoint compression from the [specification](specification.md#geometry-and-readability), not Apple's tone algorithm. Foreground ink stays crisp. Simulated reflection is separate from sampled transmission.
- This small browser experiment does not validate Android adapters, modal hierarchy, merged selections, production accessibility or physical-phone frame rates. The software renderer evaluates the same inverse maps at a lower resolution; sparse GPU/CPU agreement checks do not establish full pixel equivalence.
- Before production changes: freeze model parameters on one suitable reference, evaluate independent scenes with exposed backgrounds, then compare native coordinate-coded renders on both Android adapters. Retain alternative hypotheses when the source cannot distinguish them.

## Run and verify

- Open the [live lab](https://anastaci1a.github.io/localica/liquid-glass/lab.html). `?fallback=1` selects software refraction. The status line identifies the renderer and explains why software mode is active. **Retry WebGL** removes the forced-software URL option and tries GPU rendering again; a failed attempt leaves software refraction running. No account, uploaded image or runtime network dependency is needed after the site loads.
- From `docs/liquid-glass`: `npm ci`, `npm run build`, then serve `site/` with any static server. The generated site contains only allowlisted public guide files and original assets; `build.json` records source SHA and file checksums.
- `node experiments/equations-test.cjs` runs the portable math checks without browser dependencies. For renderer tests, `npx playwright install chromium`, then `npm test`. An existing browser can be supplied through `CHROMIUM_PATH`; `LAB_EVIDENCE` selects the output directory and `LAB_URL` can point tests at the deployment. Keep raw test output untracked.
- Browser checks exercise both models/three outlines, zero-power texture identity, actual shoulder displacement, center/exterior registration, tone ordering/endpoints, diagonal continuity, contact/release/wall recovery, interrupted morphs, idle sleep, Reduced Motion, context loss/restoration, mobile sizing, full reset, repeated morph/model changes and context recovery. Software checks add sampled GPU agreement for both models, zero-power identity, blur/contrast behavior, an unavailable WebGL context and retry. Preset checks exercise every context, independent core/edge controls, stronger near-rim output, both renderers, extreme ranges, model-specific disabling, keyboard tabs and phone layout. Directional-pressure checks additionally cover both walls/renderers, depth transfer versus the baseline, the fixed contacting edge, the volume proxy, rebound and idle sleep. The independent reference suite also probes actual framebuffer coordinates over all outlines, three boundary profiles and the cap, at rest and held, including the first fully covered rim pixels. These are targeted probes, not proof of every pixel/state.
- Performance design: one static texture upload per backdrop change, one analytic shader pass, bounded nine-tap scattering, early exit outside the lens, render density capped at 1.5× and a maximum 960×400 framebuffer, and sleeping clocks after settlement or while offscreen. Software mode uses a 480-pixel-wide framebuffer, caches the unmodified backdrop and evaluates optical math only within the lens/shadow bounds. Its source pixels are read once per backdrop change. Test-only GPU readback is never used during interaction. Shader submission timing is not GPU duration; SwiftShader captures are not physical-device performance evidence.
- Defaults: gradient with vertical lines, light foreground ink, boundary-field model and capsule shape. **Reset all settings** restores every input, lens position and collapsed state; Reduced Motion returns to the operating-system preference. The mobile preview shows a narrower region of the same texture, preserving the lens proportions.
- Publish the generated `site/` contents to the dedicated `gh-pages` branch. GitHub Pages uses that branch's root with `.nojekyll`; Android source/main and APK workflows are independent. Rebuild from the merged docs commit and verify `build.json` plus the live demo after deployment. Never include private feedback or Apple reference frames.

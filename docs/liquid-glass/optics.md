# What can we establish about the lens?

[Guide](README.md) · [Apple sources](sources.md) · [Interactive lab](lab.html) · [Implementation requirements](specification.md#geometry-and-readability)

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

Run [reference_patch.py](experiments/reference_patch.py) against a privately downloaded asset. It verifies SHA256 `4feea1c34a97ae1a4853c8f38831b9c7579c739db2c1060b1f4b84d3082d3992`, decodes native frames, reads actual presentation timestamps, and checks identity/known-shift synthetic controls. Requirements: Python 3, NumPy, OpenCV and ffprobe. No Apple pixels are stored in this repository.

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

## Two deliberately different lab models

Both use one analytic rounded-rectangle silhouette (circle/capsule are special cases), one background texture, and an **inverse** mapping: each output pixel looks up its displaced source coordinate. Labels remain outside that map. No triangle-specific optical interpolation or DOM screenshot illusion is involved.

| Model | Construction | Purpose / limitation |
| --- | --- | --- |
| Boundary-focused (default) | Small interior lookup plus a smooth compact shoulder displacement, inward along the boundary normal | Separates quiet-core registration from edge bending; inspired by observations, **not fitted to all Apple scenes** |
| Physical cap comparison | Smooth shoulder height plus a weak curved crown; normal from height gradient; Snell entry, planar exit and backdrop gap | Makes thickness/normal/ray relationships inspectable; assumes a plano-convex body and orthographic viewing, neither established for iOS |

For the boundary model, with inward distance `d`, shoulder width `b`, `u=clamp(d/b,0,1)`, use `16u²(1−u)²` inside the band and zero beyond it. Value and first derivative vanish at both ends, avoiding a sharp optical boundary. A small smooth interior term retains gentle magnification. Height scales its strength; it is a phenomenological parameter, not a measured physical thickness.

For the cap comparison, height is a quintic shoulder ramp plus a gentle elliptical crown, with derivatives zero at the shoulder's inner join. Backward rays enter from the viewer, travel through the cap, exit a plane and travel to the backdrop. [Snell/Fresnel](https://www.pbr-book.org/4ed/Reflection_Models/Specular_Reflection_and_Transmission) supplies generic optics, not Apple's model. The index 1.33 is water-inspired and fixed for this experiment. The reflected environment is a synthetic light rig, not a reconstructed room. No caustics, dispersion, multiple scattering or full fluid simulation.

Pressing reduces height and narrows the shoulder while expanding the footprint. Held shape approaches critically; released strain/depth can rebound with adjustable damping. These are Localica choices. A requested geometric response must be tested independently from Apple's interaction-light behavior.

## Reading the lab honestly

- Zero optical power, blur, tint, trim, reflection and shadow must reproduce the backdrop, including the shoulder. Coordinate mode encodes X/Y in R/G so tests can decode the actual GPU lookup; exterior and center registration can be checked directly.
- Compare circle, capsule and panel over vertical lines, grid, text and color. Watch the center, shoulder join, corners and diagonal axes through a morph, reversal and held press. Blur must not conceal broken registration or seams.
- The contrast mapping is Localica's monotonic endpoint compression from the [specification](specification.md#geometry-and-readability), not Apple's tone algorithm. Foreground ink stays crisp. Simulated reflection is separate from sampled transmission.
- This small WebGL experiment does not validate Android adapters, modal hierarchy, merged selections, production accessibility or physical-phone frame rates. A disclosed 2D fallback preserves controls but cannot establish refractive parity.
- Before production changes: freeze model parameters on one suitable reference, evaluate independent scenes with exposed backgrounds, then compare native coordinate-coded renders on both Android adapters. Retain alternative hypotheses when the source cannot distinguish them.

## Run and verify

- Open the [live lab](https://anastaci1a.github.io/localica/liquid-glass/lab.html). `?fallback=1` explicitly demonstrates the non-refracting fallback. No account, uploaded image or runtime network dependency is needed after the site loads.
- From `docs/liquid-glass`: `npm ci`, `npm run build`, then serve `site/` with any static server. The generated site contains only allowlisted public guide files and original assets; `build.json` records source SHA and file checksums.
- `npx playwright install chromium`, then `npm test`. An existing browser can be supplied through `CHROMIUM_PATH`; `LAB_EVIDENCE` selects the output directory and `LAB_URL` can point tests at the deployment. Keep raw test output untracked.
- Browser checks exercise both models/three outlines, zero-power texture identity, actual shoulder displacement, center/exterior registration, tone ordering/endpoints, diagonal continuity, contact/release/wall recovery, interrupted morphs, idle sleep, Reduced Motion, context loss/restoration, mobile sizing, full reset, repeated morph/model changes and fallback controls. These are targeted probes, not proof of every pixel/state.
- Performance design: one static texture upload per backdrop change, one analytic shader pass, bounded nine-tap scattering, early exit outside the lens, render density capped at 1.5× and a maximum 960×400 framebuffer, and sleeping clocks after settlement or while offscreen. Test-only readback is never used during interaction. Shader submission timing is not GPU duration; SwiftShader captures are not physical-device performance evidence.
- Defaults: gradient with vertical lines, light foreground ink, boundary-field model and capsule shape. **Reset all settings** restores every input, lens position and collapsed state; Reduced Motion returns to the operating-system preference. The mobile preview shows a narrower region of the same texture, preserving the lens proportions.
- Publish the generated `site/` contents to the dedicated `gh-pages` branch. GitHub Pages uses that branch's root with `.nojekyll`; Android source/main and APK workflows are independent. Rebuild from the merged docs commit and verify `build.json` plus the live demo after deployment. Never include private feedback or Apple reference frames.

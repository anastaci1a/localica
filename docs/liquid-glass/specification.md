# Localica material and motion specification

[Guide home](README.md) · [Apple evidence](sources.md) · [Motion lab](lab.html)

**Status: implementation requirements, with experimental optical models.** The [interface contract](../interface.md) owns branch behavior; releases and PR evidence establish what shipped and what was tested. These checks are not a claim that every device meets them. Selection entrance motion remains deferred in [#107](https://github.com/anastaci1a/localica/issues/107).

This is our translation of the [Apple evidence](sources.md) into testable Android behavior. The [context audit](optics.md#context-audit--19-october-2026) distinguishes evidence-backed properties from uncalibrated optics. The equations below are Localica engineering models, not Apple's private formulas. The [portable optical/motion specification](model.md) owns their domains, derivations and mathematical verification; device profiling and Apple calibration remain separate gates.

## Surface roles

| Role | Treatment | Localica examples |
| --- | --- | --- |
| Content | Artwork, text and ordinary backgrounds; no permanent lens over every item | Songs, album cards, collection heroes |
| Floating control | One adaptive refracting surface with sharp foreground content | Mini-player, navigation, filter/search pills, return buttons |
| Expanded task | Same originating surface becomes larger and more scattering; foreground gets stable space | Plus/More menu, sorting, search |
| Transient selection | Explicit exception to the usual content/control separation | User-requested lenses over selected media |
| Nested control | Prefer a light overlay inside the parent's material, with one coherent backdrop | Sort choices, menu actions |

Apple's preference against layered glass conflicts with some requested Localica treatments. Preserve the requested selection lenses, frosted dialogs and bubble actions; ordinary song overflow and queue-row rename/delete remain plain icons. A future review should compare those with a single-material alternative; do not silently replace them because a guideline differs. Likewise, keep configurable overflow tabs and detail-page tab hiding.

Treat blur, refraction, illumination, contrast support and shadow as separate parameters. An element may be clear yet need strong edge curvature; a text-heavy panel may need scattering without a heavy border. The current glass sliders remain user controls, not claims that every combination is equally legible.

### Material selection

- Use Regular as the navigation/text default. Its scattering and brightness support respond to content and size; do not implement it as a uniform white wash or a fixed blur everywhere.
- Use Clear over rich media when the foreground is prominent and bright and suitable dimming preserves the content. Begin with negligible scattering; retain detailed texture and independently strong rim refraction.
- Active slider/switch lenses are a contact-state treatment: sharp, transparent and independent of ordinary appearance customization. Their quiet resting finish is a separate state.
- Keep scroll-edge filtering and media dimming separate from lens scattering. Larger presentations can change their material continuously, rather than inheriting a small trigger's optical settings unchanged.
- Apple accessibility/appearance preferences can alter these treatments. Record known state when calibrating; transparency alone cannot identify an API variant.

Primary evidence and comparison images live in [material-role observations](reconstruction.md#when-to-use-each-material). These guide calibration; the existing native renderer and lab are not certified matches.

## Input and motion

Model a surface using a persistent identity, current geometry, velocity, strain and interaction owner. Events change that state; they do not enqueue a press animation followed by an opening animation.

```text
rest → tracking → settling → rest
          ↑          │
          └─ re-grab ─┘
```

- Contact produces feedback on the next drawable frame. The existing action's activation rules still decide whether release commits or cancels it.
- Track from the grabbed point. A thumb or panel must not teleport to put its center under the finger.
- Re-grabbing starts from the displayed geometry. Retargeting keeps velocity unless an actual constraint or direct manipulation changes it.
- Recognition may begin with competing candidates. Once artwork, queue, slider, reorder or pinch owns the gesture, retain that owner through reversals until release/cancellation. Redirecting within an owned interaction is different from accidentally transferring it to another screen.
- Do not wait for a decorative rebound before starting a useful transition. A closing modal must remain represented until its restored trigger can draw; avoid both a blank frame and two visible silhouettes. Quiet triggers fade their entire temporary material away; glass triggers retain their material and deformation. Give small controls bounded additional impulse response so return rebounds remain visible.
- Nonmodal conditional controls share `GlassPresence`: one interruptible emergence spring resolves the full material and shadow into its footprint. Reserve layout while hidden; retain only drawing during exit, immediately removing pointer, focus and accessibility participation. Reduced motion resolves visibility immediately. This does not implement the deferred optical selection entrance in #107.

### Destination and settling

Separate choosing an endpoint from moving there. A simple candidate projection for one axis is:

```text
projected = position + velocity / friction
endpoint  = nearest allowed destination(projected)
```

This is the asymptotic result of exponential velocity decay, with velocity in units/second and positive friction in 1/second. Bound implausible input spikes. Tune the projection independently from the final spring, and retain occurrence identity when choosing playback neighbors. At a hard boundary, projecting farther must not select an invalid item.

For a settling degree of freedom, use a damped spring:

```text
acceleration = (-stiffness * (position - target) - damping * velocity) / mass
```

Advance with a stable integrator and bounded substeps, or an analytic solution. A long paused frame must not explode the simulation. For constrained selectors, position reaches the destination without visible travel beyond it; the deformation channel conveys impact. Unconstrained motion can have a different policy. Never copy one spring preset into every component simply because it looks playful.

### Shape carries impact

Use a separate damped strain state driven by changes in travel velocity and held edge pressure. The inexpensive cap approximation includes a separate logarithmic thickness state:

```text
pressure(excess) = -limit * (1 - exp(-abs(excess) / distanceScale))
scaleAlong      = exp(strain - depthStrain / 2)
scaleAcross     = exp(-strain - depthStrain / 2)
scaleDepth      = exp(depthStrain)
```

The three scales multiply to one: an **affine volume invariant**, used as a proxy when rebuilding rounded geometry. It does not prove conservation of the integrated cap volume. Held contact should approach a shallower, wider cap without a new oscillation; critical damping suppresses oscillatory modes but does not ensure monotonicity with arbitrary inherited velocity (see the [contact caveat](model.md#pressure-and-dynamics)); externally driven travel impulses remain responsive under that damping rather than being projected to zero: central refraction weakens and the remaining curvature concentrates at the rim. Only release permits the user's low-damping rebound; depth, footprint and refraction oscillate together. This is not a fluid simulation or a reconstruction of Apple’s private renderer. Shape damping is independent of constrained travel; [Glass settings](../interface.md#appearance-and-settings) own its defaults/range and resting optical depth. Motion clocks sleep after settling.

Map strain to the motion axis. A horizontal stop compresses width and expands height; a vertical one does the reverse. Keep the contacting edge pinned (not the center) while deforming inward so the apparent contact does not slide through the wall. Additional pull produces diminishing compression. Release removes pressure and lets the same body settle. Reversal during settling changes its response immediately. A sampled velocity sign flip adds bounded directional braking to the motion and depth impulse inputs; the correction vanishes as the crossing speed approaches zero, while ordinary magnitude-based acceleration and braking stay unchanged.

Acceptance: a slow drag, fast flick, abrupt reversal and long boundary hold must yield distinguishable shapes. Replaying the same wiggle on all four fails the model. Reduced Motion removes decorative strain while preserving immediate tracking and state feedback.

## Settings sliders and switches

The [control specification](controls.md) owns button, switch, slider and choice behavior, including cancellation, clear-thumb optics, accessibility and layout. It uses the shared motion model above; individual controls supply their interaction state instead of replaying independent bounce sequences.

## Geometry and readability

Maintain one authoritative outline per displayed surface. Draw-space deformation must preserve backdrop registration; physical translation moves the sampling origin with the body. Clip, refract, illuminate and shadow that outline in the same frame. Use its actual transformed coordinates for overlays; do not lag list movement through a later bounds callback. Hit regions may be larger for accessibility, but must remain attached to the visible control.

Nested curves should share a deliberate inset relationship. Begin with `innerRadius = max(0, outerRadius - inset)` where the geometry permits it; then inspect optical balance. Capsules, square artwork and full-player corners need distinct policies. In particular, inset mini-player artwork away from the curved rim, and retain one outline throughout player expansion.

Suggested optical pipeline:

```text
content/backdrop → padded sample → distortion/scattering → rim + illumination
                                                       → contrast support
                                                       → crisp labels/icons
outline + elevation → one shadow field behind the surface group
```

- A rounded height field with entry/exit refraction is one engineering approximation; an index of 1.33 is water-inspired, not an Apple finding. The lab's corrected equations are candidates for a later native port, not evidence that Android already implements them. Preserve quiet-core registration, continuous shoulder bending and depth-dependent deformation; avoid an unintended identity strip inside the outline, and keep optical mapping independent of coverage; compare the [physical and boundary-focused hypotheses](optics.md) before changing defaults.
- Keep rectangular crowns smooth, without directional wedges, medial-axis ridges, flat masks or inset highlights. Inspect changing curvature as well as normal continuity. Circles retain a radial profile; illumination follows the crown.
- Calibrate footprint, resting height, central curvature and rim transition separately by control role. A comparatively flat playback crown and a continuously rounded active thumb are distinct hypotheses; material transparency does not determine their geometry. Use the [curvature comparators](model.md#tangential-curvature-and-smooth-crowns) and image evidence before choosing defaults.
- Define the silhouette with an inset rim: broader white highlights toward top-left/bottom-right, tapering into faint dark shading on the opposite corners. Follow deformation and joins without outlining a flat face. This interprets Apple’s [geometry-responsive highlights](https://developer.apple.com/videos/play/wwdc2025/219/?time=630); it does not reproduce its shader.

Neighboring glass shares a coherent source without recursively sampling its own output. Sample required padding but clip recording and filtered output to their bounds. Merged selections preserve unselected holes; diagonal gallery bridges remain disabled. Selections appear immediately until #107 is reviewed.

Contrast is a page-level decision, inherited by its controls and menus. Keep foreground pixels sharp and unfiltered; test them over moving black/white text, checkerboards and bright artwork.

- For white icons, map normalized background channels through `f(x) = x − t·x^p`; for dark icons use `1 − f(1 − x)`. Trim `t ∈ [0, 0.5]` bounds the problematic end; weighting `p ∈ [1, 2]` preserves the safe end more strongly. These bounds guarantee channel ordering, including at maximum settings; the [color-space contract](model.md#sampling-coverage-and-color) distinguishes encoded channels, luminance and contrast guarantees.
- Map glass after white tint and keep optical illumination within the mapped brightness endpoints; never add a uniform white/black overlay or filter the icons. Page polarity stays stable while content scrolls beneath individual buttons. Media selection lenses remain clear.
- This is Localica’s user-directed adaptation model, not Apple’s private algorithm. Customization does not replace accessible semantics or guarantee legibility for every possible setting; [Apple’s material guidance](sources.md#hig--materials-and-color) still informs the defaults and review.

## Rendering and measurement

Optimize the expensive stage identified by a trace. Lowering animation duration can conceal neither late frames nor a discontinuous handoff.

- Reuse geometry when only translation changes. Cache keys include every value that changes the result; size, density, corner shape and optical band cannot become stale.
- Bound filtered surfaces to visible content plus required sampling padding. Downsample blurred backdrops where visually acceptable; retain full-resolution labels, icons and genuinely clear content.
- Do not rebuild bitmaps, read GPU pixels or remeasure entire lists on every gesture frame. Keep drawing updates separate from semantic/layout updates where possible.
- Sleep motion clocks after position, velocity and strain settle. Wake them for new input; “continuous behavior” does not require continuous idle work.
- Avoid repeated shadows and redundant masks. A union shadow must preserve each control's shape and fade to zero before its allocated bounds.
- Keep API 26 fallback behavior correct. A cheaper approximation should remain spatially stable even when it cannot reproduce modern live optics.

Capture reproducible interaction traces on representative physical devices, separately from emulator functional checks. Record display refresh rate, build, device, selected-item count, effect settings and gesture script. Compare frame deadlines, p50/p95/p99 duration, worst stalls, allocation churn and idle activity. Use identical inputs before/after; report regressions as well as improvements. Android frame tools and Apple hitch metrics are related evidence, not interchangeable numbers.

## Acceptance checks

| Scenario | Observe throughout the interaction |
| --- | --- |
| Tap, hold, leave, re-enter, release | Immediate feedback; correct cancellation; no delayed replay |
| Re-grab an opening/closing menu | One continuous surface, no missing frame or label jump |
| Pull a selector beyond either end | Stable edge, bounded strain, recovery without position overshoot |
| Scroll a selected list, reverse, fling | Lens and content share the same frame and coordinates |
| Overflowing tabs | Lens-origin drags adjust selection; other origins scroll the strip. When tabs fit, any option can acquire the selector, subject to shared scroll-intent arbitration. Preserve clipping and accessible alternatives; [#366](https://github.com/anastaci1a/localica/issues/366) supersedes the old overflow rule. |
| Expand/collapse mini-player slowly | Matched corners/shadow; artwork and material blend reversibly |
| Swipe artwork diagonally, then reverse | Same gesture owner; no queue opening or player dismissal |
| Change text size or use long labels | Wrapping/ellipsis by role, preserved target size and spacing |
| Press Back with the keyboard visible | Editing ends, cursor stops; draft and parent remain intact |
| Reduced Motion, screen reader, keyboard | Equivalent actions and state without elastic spectacle |
| Bright/dark/striped moving backdrops | Stable readable controls, no polarity chatter or tinted flash |
| Repeated selection/menu cycles | Bounded allocations, no accumulation of layers or idle clocks |

The [live lab](https://anastaci1a.github.io/localica/liquid-glass/lab.html) demonstrates real background refraction and input/shape response in WebGL. Its [models and tests](optics.md) do not establish Android performance, iOS parity or completion of this specification.

---

[Back to the evidence](sources.md) · [Guide home](README.md) · [Current interface contract](../interface.md)

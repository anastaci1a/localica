# Localica material and motion specification

[Guide home](README.md) · [Apple evidence](sources.md) · [Motion lab](lab.html)

**Status: implementation specification.** The [interface contract](../interface.md) owns released behavior; the checks below remain verification criteria, not a claim that every device meets them. Selection entrance motion remains deferred in [#107](https://github.com/anastaci1a/localica/issues/107).

This is our translation of the [Apple evidence](sources.md) into testable Android behavior. The equations below are Localica engineering models, not Apple's private formulas. They need device profiling and perceptual tuning.

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
- Do not wait for a decorative rebound before starting a useful transition. A closing modal must remain represented until its restored trigger can draw; avoid both a blank frame and two visible silhouettes.

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

The three scales multiply to one: **approximate volume is preserved** while the footprint and thickness change together. Held contact approaches a shallower, wider cap monotonically, with critical damping; only release permits the user's low-damping rebound. Depth, footprint and refraction oscillate together. This is not a fluid simulation or a reconstruction of Apple’s private renderer. Shape damping is independent of constrained travel; [Glass settings](../interface.md#appearance-and-settings) own its defaults/range and resting optical depth. Motion clocks sleep after settling.

Map strain to the motion axis. A horizontal stop compresses width and expands height; a vertical one does the reverse. Keep the contacting edge pinned (not the center) while deforming inward so the apparent contact does not slide through the wall. Additional pull produces diminishing compression. Release removes pressure and lets the same body settle. Reversal during settling changes its response immediately.

Acceptance: a slow drag, fast flick, abrupt reversal and long boundary hold must yield distinguishable shapes. Replaying the same wiggle on all four fails the model. Reduced Motion removes decorative strain while preserving immediate tracking and state feedback.

## Settings sliders and switches

The [control specification](controls.md) owns button, switch, slider and choice behavior, including cancellation, clear-thumb optics, accessibility and layout. It uses the shared motion model above; individual controls supply their interaction state instead of replaying independent bounce sequences.

## Geometry and readability

Maintain one authoritative outline per displayed surface. Clip, refract, illuminate and shadow that outline in the same frame. Use its actual transformed coordinates for overlays; do not lag list movement through a later bounds callback. Hit regions may be larger for accessibility, but must remain attached to the visible control.

Nested curves should share a deliberate inset relationship. Begin with `innerRadius = max(0, outerRadius - inset)` where the geometry permits it; then inspect optical balance. Capsules, square artwork and full-player corners need distinct policies. In particular, inset mini-player artwork away from the curved rim, and retain one outline throughout player expansion.

Suggested optical pipeline:

```text
content/backdrop → padded sample → distortion/scattering → contrast support
                                                       → rim + illumination
                                                       → crisp labels/icons
outline + elevation → one shadow field behind the surface group
```

Lenses derive a normal from a rounded height field, refract through the entry and exit interfaces using Snell's law, and offset sampling by the ray's travel. Resting depth changes curvature as well as displacement; a uniform zoom is insufficient. Derive illumination from the same normal without an inset plate-shaped highlight or a uniform reflection floor. Separate the outline corner radius from the optical crown span so expanded panels retain curvature across their face. Localica uses a water-like refractive index near 1.33 as an artistic starting point, following [PBRT's optics derivation](https://www.pbr-book.org/4ed/Reflection_Models/Specular_Reflection_and_Transmission), not an Apple parameter.

Neighboring glass shares a coherent source without recursively sampling its own output. Sample required padding but clip recording and filtered output to their bounds. Merged selections preserve unselected holes; diagonal gallery bridges remain disabled. Selections appear immediately until #107 is reviewed.

Contrast adaptation needs to assess what lies beneath the **glyph area**, not merely the album's average color. Test a white icon over alternating white/black text, a checkerboard and bright moving artwork. Safeguards to verify:

1. Choose foreground polarity and supporting tint together.
2. Add hysteresis and a short response filter so a scrolling edge does not flicker between black and white.
3. Preserve tonal ordering: black must never become lighter than gray under identical settings. User white tint uses a monotonic highlight roll-off. Readability support follows the actual foreground silhouette instead of coating the entire scene; ink polarity has hysteresis. Avoid whole-panel polarity flips on large menus.
4. Offer a robust high-contrast fallback. User customization must not remove accessible semantics or hide selection state.

These are acceptance targets; the existing shader's local brightness adjustment is not proof that they all pass. The [evidence atlas](sources.md#hig--materials-and-color) explains why a universal zero-blur treatment is insufficient.

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
| Overflowing tabs | Every drag scrolls the strip; the selector cannot be dragged. When tabs fit, dragging anywhere moves the selector. No clipping outside the capsule. |
| Expand/collapse mini-player slowly | Matched corners/shadow; artwork and material blend reversibly |
| Swipe artwork diagonally, then reverse | Same gesture owner; no queue opening or player dismissal |
| Change text size or use long labels | Wrapping/ellipsis by role, preserved target size and spacing |
| Press Back with the keyboard visible | Editing ends, cursor stops; draft and parent remain intact |
| Reduced Motion, screen reader, keyboard | Equivalent actions and state without elastic spectacle |
| Bright/dark/striped moving backdrops | Stable readable controls, no polarity chatter or tinted flash |
| Repeated selection/menu cycles | Bounded allocations, no accumulation of layers or idle clocks |

The [motion lab](lab.html) is an explanatory prototype for input and shape response. It does not demonstrate production refraction, Android performance, or completion of this specification.

---

[Back to the evidence](sources.md) · [Guide home](README.md) · [Current interface contract](../interface.md)

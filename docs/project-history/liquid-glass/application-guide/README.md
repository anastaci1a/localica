# The Liquid Glass field guide

Reference evidence, historical reconstructions and shared-model contracts. Start with [current research](../research/current/state.md) for the active scientific status.

[Apple evidence](sources.md) · [Recreations and scores](reconstruction.md) · [Geometric optics](geometric-optics.md) · [Historical recipes](recreation-models.md) · [Localica specification](specification.md) · [Live lab](https://anastaci1a.github.io/localica/liquid-glass/lab.html) · [Project docs](../README.md)

![The current dark lab showing a refracting lens over a gradient with vertical lines](assets/lab-desktop.png)

The Lab is a separate illustration. Its historical empirical options are not automatically realizable optical surfaces.

## Contents

| Topic | Reference |
| --- | --- |
| Interaction principles | [Fluid interaction foundations](sources.md#interaction-foundations) |
| Apple guidance | [Evidence atlas with video timestamps](sources.md) |
| Implementation rules | [Localica specification](specification.md) |
| Settings, sliders, switches and buttons | [Control roles, states and review exercises](controls.md) |
| Interactive rendering model | [Live WebGL lab](https://anastaci1a.github.io/localica/liquid-glass/lab.html) — runs in your browser |
| Measurements and model limitations | [Optical findings and reproducible experiments](optics.md) |
| Calibrated reference studies | [Apple/reconstruction comparisons, scores and replay](reconstruction.md) |
| Geometric model contract | [Surfaces, normals and shared optical paths](geometric-optics.md) |
| Historical reproduction contract | [Equations and frozen image-fit recipes](recreation-models.md) |
| Recorded demonstrations | Expand the demonstrations below |
| App behavior | [Current interface contract](https://github.com/anastaci1a/localica/blob/main/docs/interface.md) |

The guide separates **Apple evidence**, **Localica models**, and **shipped behavior**. It is a research reference, not a claim of pixel-identical iOS rendering. The [hosted field guide](https://anastaci1a.github.io/localica/liquid-glass/) has the same notes and navigable links.

## Refraction study

![WebGL capture of a capsule refracting a gradient with vertical lines](assets/lab-clear.png)

The lab samples an original backdrop through two selectable inverse-map models. WebGL and software rendering both apply refraction; the status line identifies the active renderer. Compare edge displacement, thickness, blur, contrast and synthetic reflection independently. Its coordinate diagnostic and reproducible browser tests check actual shader output. [Run and verify](optics.md#run-and-verify).

<details>
<summary>Panel expansion and collapse</summary>

![A refracting capsule expands into a panel and returns over vertical lines](assets/lab-morph.gif)

Original WebGL output; deterministic 30 Hz simulation captures, not a phone frame-rate measurement or an Apple reference.

</details>

## Motion studies

These captures use the same WebGL renderer as the live lab. The backdrop is static; the lens changes position, shape and refraction. Animations remain inside opt-in sections.

<details>
<summary>Boundary pressure and recovery</summary>

![Lens moves to the right boundary, compresses under additional pull, then recovers](assets/lab-pressure.gif)

The contacted edge stays fixed while the lens compresses and recovers. Compare with [shape carries impact](specification.md#shape-carries-impact).

</details>

## Scope

The specification defines the shared system and its acceptance checks. Physical-device performance still needs review; optical materialization remains deferred. Current selection lenses intentionally appear immediately; their entrance motion awaits [#107](https://github.com/anastaci1a/localica/issues/107). Requested glass-on-content and nested controls remain explicit departures from Apple's usual layer guidance.

Update evidence and specification together when guidance or owner decisions change; source review dates live in the [atlas](sources.md). Keep release history in Git and Releases. The lab and its captures are original project assets. The separate recreation study includes attributed Apple excerpts; source videos and font files are not bundled.

[Main repository](../README.md) · [Evidence atlas](sources.md) · [Acceptance checks](specification.md#acceptance-checks)

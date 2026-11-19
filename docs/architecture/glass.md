# Glass

Glass uses one default material, with a lens on supported versions and an average-colour fallback on older versions.

Proposed: every glass element has a presence, from 0 to 1, and a shape. Glass appears by raising presence and disappears by lowering it, never by fading. Press also drives a shared touch glow; squish and morph change shape. The shared material selects the platform capability directly.

- Presence scales every material property. Shape is geometry: size, position, morph, squish and thickness.
- Constant volume (Proposed): thickness is derived as resting volume divided by footprint area.
- The optics formula is frozen. Only its inputs change over time.
- The lens needs Android 13 or newer. Older versions draw the average colour.
- Status: renderers, fallbacks and touch light (#642/#1002) are Implemented. Touch-light spatial optics remain provisional; presence, constant volume and lensing in (#928) are Proposed.

## Renderers

| Renderer | Material |
| --- | --- |
| Normal, Android 13+ | Padded AGSL refraction, illuminated rim and tone mapping over recorded scenes. |
| Normal, older Android | Coverage-weighted scene average with tint/contrast and the same outline; no refraction or blur. |

Shapes, shadows, input and motion are shared. Frosted panels use downsampled scattering on Android 12+; refracting glass requires Android 13+. Foregrounds and clear glass stay full-resolution.

## Presence and shape

![Glass presence against shape: presence scales material strength, shape changes geometry](../assets/diagrams/glass-presence-shape.svg)

The diagram separates material strength (presence, 0–1) from geometry (shape). Proposed in #928: appearance changes presence; presses, squish and morph change shape. Lens strength uses presence and thickness. At zero presence the material matches its background. Fallbacks ignore thickness.

Implemented appearance instead uses scale/opacity at full optical strength. It does not yet ramp lens inputs.

Touch light shares the [contact response](motion.md#deformation). Its broad and local fills use provisional gain, support, falloff and filtering; this does not establish iOS spatial parity. [GlassTouchLightTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/GlassTouchLightTest.kt) specifies the pixel and ownership contracts.

### Constant volume

Proposed: thickness derives from resting volume and footprint area:

$$
t = \frac{V_{\mathrm{rest}}}{A_{\mathrm{footprint}}}
$$

Spreading thins the material. Morphing changes resting volume between shapes; it is not a press deformation. Presence alone changes material quantity. Current press thickness/growth remain independent.

## The lens model

One outline serves clipping, refraction, illumination and shadow. Foreground polarity is explicit, requiring no ink readback. Resting depth is 1.35; menu controls/mini-player use 1.3×, tab shelf 0.72×. Trigger depth carries into its popup. Optics remain frozen outside milestone M6; exact changes require pixel equality.

## What glass samples

Recorded browsing, panel, list, player and lyric scenes stay separate to avoid cycles. Inline glass excludes itself; nested panels sample completed parents. One live recording-local mapping handles crop and clip, including scroll/scale. Non-finite, singular or projective mappings are rejected. Popup return follows its live trigger footprint.

Sampling leases retain shared scenes; scrolling display lists let narrow crops cull hidden rows. Unreferenced scenes skip readback, and pending native copies retain live producers. Registration/continuity tests specify coordinate and ownership transitions.

### The average fallback

Progressive halving produces shared 32×32 thumbnails. The coverage-weighted region uses its own source and parent beyond that box, stopping at scene edges. No full-resolution CPU capture is required. Apart from raster quantization, mean error is bounded by boundary-cell area fraction times maximum channel contrast.

## Sliders and switches

Resting white thumbs hold no lease. Normal contact captures only the procedural neighbourhood and refracts inside the shared geometry. Explicitly eligible panel-contained controls use an 8×8 local GPU average on Android 13+.

## Backings and selectors

Backings stay viewport-bounded; released/unrecorded layers cannot read back. Queue frost is independent of floating glass. Navbar selectors use the same default material and draw labels/icons once above it. Graphics remain independent of storage/playback.

## Tests

[GlassRenderModesTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/GlassRenderModesTest.kt).

[GlassRendererSwitchingTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/GlassRendererSwitchingTest.kt).

[FlatNoneMaterialTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/FlatNoneMaterialTest.kt).

[GlassOpticsTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/GlassOpticsTest.kt).

[LiquidGlassTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/LiquidGlassTest.kt).

[NestedGlassRegistrationTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/NestedGlassRegistrationTest.kt).

[BackdropProducerContinuityTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/BackdropProducerContinuityTest.kt).

[BackdropInvalidationTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/BackdropInvalidationTest.kt).

[HeaderGlassFreshnessTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/HeaderGlassFreshnessTest.kt).

[SelectorRegistrationTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SelectorRegistrationTest.kt).

[CollectionControlsBackdropTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/CollectionControlsBackdropTest.kt).

[GlassToneShaderParityTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/GlassToneShaderParityTest.kt).

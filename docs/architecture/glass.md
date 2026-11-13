# Glass

Glass is one material drawn by one of two renderers: Normal (lens) or None (flat fills). Proposed: every glass element has a presence, from 0 to 1, and a shape. Glass appears by raising presence and disappears by lowering it, never by fading. Press, squish and morph change shape only. Each renderer shows what it can.

- Presence scales every material property. Shape is geometry: size, position, morph, squish and thickness.
- Constant volume (Proposed): thickness is derived as resting volume divided by footprint area.
- The optics formula is frozen. Only its inputs change over time.
- The lens needs Android 13 or newer. Older versions draw the average colour.
- Status: renderers and fallbacks are Implemented. Presence, constant volume and lensing in (#928) and touch light (#642) are Proposed.

A third renderer, Single-Tone Blur, exists up to v0.34.2 as a testing mode. It is removed in v0.34.3 (#919), and stored choices migrate to Normal. This page does not describe it.

## Renderers

| Renderer | What it draws | Cost |
| --- | --- | --- |
| Normal, Android 13+ | Padded AGSL rounded-surface refraction, illuminated rim and tone mapping over a live backdrop. | Sampling leases, recordings and shader passes. |
| Normal, below Android 13 | The average colour of the covered backdrop, with the same tint, contrast mapping and outline. No refraction or blur. | Shared 32×32 thumbnails. |
| None | Opaque white or dark-grey theme fills. | No glass layers, sampling, atlas, readback or backdrop recording. |

Both renderers share shapes, rims, shadows, layout and motion. A renderer switch never changes a shadow, a hit target or a trajectory. None is the flat baseline for performance work: what remains in None is the cost of the interface without glass.

Live scattering uses bounded downsampled layers: frosted panels on Android 12+, refracting glass on Android 13+. Foreground and clear glass draw at full resolution, with tint below icons and text.

## Presence and shape

![Glass presence against shape: presence scales material strength, shape changes geometry](../assets/diagrams/glass-presence-shape.svg)

The diagram plots two independent axes. Presence, from 0 to 1, is how much material exists. Shape is where and how large the material is. Status: Proposed (#928).

| Value | Meaning | Changed by |
| --- | --- | --- |
| Presence | How much glass material exists. | Appearing and disappearing only. |
| Shape | Size, position, morph, squish and thickness. | Press, drag, morph and layout. |

Each renderer maps presence to what it can physically show:

| Renderer | Presence scales |
| --- | --- |
| Lens | Bending strength, with blur and frost. The lens bends by presence × thickness. |
| Average-colour fallback | Blur and frost tint. |
| None | Fill opacity. |

At presence 0 every renderer matches the background, so the start and end of a motion agree across renderers. No animation is defined per renderer. A press never touches presence, and the fallbacks never read thickness, so no exception is needed.

Today glass arrives at full optical strength. Appearance uses a scale and opacity curve, and popup expansion does not ramp the lens inputs.

### Constant volume

Status: Proposed. Volume is presence × the shape's resting volume. Glass gains or loses volume only while presence changes.

$$
t = \frac{V_{\mathrm{rest}}}{A_{\mathrm{footprint}}}
$$

- A press that spreads the footprint thins the glass. A squash that keeps the area keeps the thickness.
- Thickness is derived, never animated on its own. Today press thickness and press growth are two independent values.
- A morph between different shapes is not a deformation. It moves the resting volume from source to target, so larger glass stays thicker.

## The lens model

Controls and panels use one lens model. Outline geometry is shared by clipping, refraction, illumination and shadow. The page supplies foreground ink polarity, so the lens needs no GPU readback to choose black or white.

Depth is a setting with a default of 1.35. Controls that morph into menus use deeper glass and carry that depth into their popups. The tab bar is flatter because it keeps its shape. The mini-player and menu-opening controls use 1.3× the resting depth; the tab shelf uses 0.72×.

Frozen optics: the refraction and tone formula does not change outside the dedicated specification work (milestone M6). Output must stay pixel-equivalent. Motion and presence may change the formula's inputs.

## What glass samples

Glass refracts recorded scenes, never the live tree, and never itself.

- Browsing, panel material, lists, player and lyrics are recorded separately, which prevents sampling cycles.
- An inline lens excludes itself. A modal window uses global coordinates. A nested panel samples its completed parent.
- Source and consumer resolve in one live, recording-local basis, including scale and scrolling. The same mapping serves crop coverage and clipping.
- A non-finite, singular or projective mapping is rejected, never replaced by a translation.
- A popup samples its trigger's footprint on opening and follows the live footprint on return.
- Floating list controls sample the finished viewport after its fade and scroll transforms, and stay out of their own source.

Sampling leases keep shared scenes as offscreen rasters. Scrolling lists keep display lists, so a narrow lens crop culls unseen rows. An unreferenced scene skips readback. Visible producers draw live while a native copy is pending.

### The average fallback

Below Android 13 the mean is coverage-weighted over the scene the lens would sample: the element's own source, with its parent beyond that source's box. Changed recordings are halved progressively into shared 32×32 thumbnails. No full-resolution CPU capture is needed.

The region stops where the recorded scene ends, so material reaching past a screen edge never turns translucent. Excluding raster quantization, the mean error is bounded by the boundary-cell area fraction multiplied by the maximum channel contrast of those cells.

## Sliders and switches

At rest, thumbs are white and hold no sampling lease. On contact in Normal, the thumb morphs into clear glass: one procedural scene, unchanged outside the lens, refracted inside it. Only the thumb neighbourhood is captured. Track, touch mapping and wall contact share one geometry. None uses the same geometry and motion with opaque fills.

A control that sits entirely over recorded panel material uses an 8×8 local GPU average on Android 13+. Eligibility is explicit. It is never inferred from colour similarity.

## Backings and selectors

Composed backings stay within their viewport. An unrecorded or released layer is guarded before readback. Floating-glass blur is independent of Queue frost. Navigation selectors keep live padded recordings and use the same material path. Graphics stay replaceable and independent of data and playback.

The navbar selector samples only bar material. Its overlay option includes labels and icons in the sampled scene without drawing them twice.

## Tests

| Topic | Classes |
| --- | --- |
| Renderers and switching | `GlassRenderModesTest`, `GlassRendererSwitchingTest`, `FlatNoneMaterialTest` |
| Optics | `GlassOpticsTest`, `LiquidGlassTest` |
| Sampling and recordings | `NestedGlassRegistrationTest`, `BackdropProducerContinuityTest`, `BackdropInvalidationTest`, `HeaderGlassFreshnessTest` |
| Selectors and backings | `SelectorRegistrationTest`, `CollectionControlsBackdropTest` |

`GlassToneShaderParityTest` and the `None*` reduction tests cover the mode that #919 removes. Check each before citing it after v0.34.3.

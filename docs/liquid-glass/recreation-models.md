# Mathematical models for the reference recreations

These are **rendering-method-independent inverse image maps and material functions**. They can be evaluated by a shader, a mesh with sufficient sampling, a CPU renderer or another implementation. The Python replay is a reference implementation, not the specification's coordinate system or required rendering technology.

[Comparisons and scores](reconstruction.md) · [Exact parameter records](experiments/reconstruction/recipes.json) · [General optical models](model.md) · [Replay](reconstruction.md#reproduce)

The circular playback control is the **current most-polished calibration**, selected after visual review. The toolbar, slider and uniform light/dark surfaces have independent frozen recipes. A recipe reproduces a particular study; it does not identify Apple's private shader, imply physical correctness or establish defaults for every background.

## Common contract

- Let the element's shorter dimension be `D`; use `L=D/2` as the length unit. Local output coordinates are `v=(p−center)/L`. Every spatial parameter in `recipes.json` is dimensionless in these units, including blur, rim lighting and shape dimensions. Multiply by `L` only when sampling the backdrop or rasterizing. `reference_length` records the source-image scale; it is not an extra model parameter.
- Across differently sized observations, use each pose's `Lᵢ`: `σᵢ=σ̂Lᵢ`. Optimizer records may store `σref` at an explicitly declared `Lref`, giving `σᵢ=σref Lᵢ/Lref`; published normalized lengths remain `σ̂=σref/Lref`. Source and output blur use the same conversion. Raster sampling/quarter-pixel kernel quantization remain implementation approximations.
- `F(v)` is the **inverse map**: the backdrop sample is `B(center+L F(v))`. It describes where to read pixels, not the direction in which the visible feature moves. Independent foreground content is composited afterward.
- The examples operate on **encoded RGB values in [0,1]**. This is a calibrated image-space approximation; changing to linear-light processing changes the output. Coordinates increase rightward/downward, and the camera is frontal.
- Let `s(v)` be signed distance to the outline, negative inside, `n=∇s` the outward unit normal and `d=max(−s,0)`. Shape coverage is independent of material color. Evaluate the equations continuously before coverage/raster filtering.
- Gaussian `σ` is standard deviation, not kernel diameter. Source blur, glass blur after refraction/shading, and foreground-color blur are different operations. The foreground's coverage remains sharp.
- Foreground geometry must use a verified exact vector, or a clean symmetric trace when none is available. Solid coverage is independent of tint, transmission and blur, and is rasterized anew at each output resolution. The Home/Search follow-up uses private SVG inputs with frozen hashes; older photo-derived masks below remain historical calibrations pending replacement. Optical masks/scores stay fixed when foreground measurements change.
- Parameters and operation order are fixed for replay. Re-optimizing coefficients produces a new calibration, not an exact reproduction of this one. Source registration, the estimated hidden backdrop, video decoding and raster conventions are additional inputs.
- Missing backdrop pixels may be extended for presentation, with the method and valid-mask numerical effect recorded. Preserve the original recovered inputs and native benchmark; completed pixels are estimates, not new observed evidence.

## Playback: radial map and smooth inner transition

For the circle, `r=|v|`, `d=1−r`, and `F(v)=(1−a(d)/r)v`. At the center set `F(0)=0`; the displacement has already reached zero there. Use the following constants:

| Parameter | Value |
| --- | ---: |
| Outer displacement `A` | 0.6338092221139378 |
| Initial transition width `b` | 0.09445809156460266 |
| Initial exponent `p` | 2.10022237760162 |
| Inner-tail width multiplier `k` | 4.5 |
| Inner endpoint exponent `q` | 4 |
| Displacement gain | 1 |
| Source Gaussian σ / L | 0.0020408163265306124 |
| Glass post-refraction σ / L | 0.006122448979591836 |
| Final foreground-color σ / L | 0.012244897959183673 |

For `d≤b/2`, use `a=A clamp(1−d/b,0,1)^p`. For `b/2<d<(1+k)b/2`, define `t=(d−b/2)/(kb/2)` and use:

```text
a = A 2^(−p) exp[q ln(1−t) + (q−pk)t + (q−pk²)t²/2]
```

For larger `d`, use `a=0`. The join matches value, first and second derivatives; the inner endpoint has zero derivatives through order three. This controls the transition from the strongly refracted rim to the quiet center without inserting a discontinuous shoulder. It does **not** prescribe a 3D height or thickness. The map has a fold in its supported calibration; reversed rim details are intentional in this still-image fit.

### Glass and play-symbol processing

1. Blur the backdrop with the source σ, sample it through `F`, and apply the common material function below.
2. Blur that calculated glass color with the glass σ. This is after refraction and shading.
3. Calculate the foreground color `C0=1−strength×(1−glass)^gamma`, componentwise. Use the exact three-channel coefficients from the recipe:

```text
strength = [0.9999999999999285, 0.9999999999999999, 0.4829334270156419]
gamma    = [11.731766541351298, 8.166089277320348, 5.294455609674747]
```

4. Let `Y=dot(C0,[.2126,.7152,.0722])` and `w=clamp((1−Y)/.15,0,1)²`. Increase dark-region saturation: `C1=clamp(Y+(1+.2w)(C0−Y),0,1)`.
5. Componentwise, set `u=clamp((C1−.9)/.1,0,1)` and `C2=C1−.5u³(1−C1)`. This reduces near-white highlights without creating an early white plateau. For a general strength `h`, the function is monotone for `0≤h≤4`; `.5` is the selected value.
6. Gaussian-blur **C2**, with the final foreground-color σ. This is the last color operation. Composite it through the sharp triangular coverage: `output=(1−α)glass+αCblurred`.

The included [playback coverage mask](experiments/reconstruction/playback-mask.png) is a reference-derived triangle silhouette with its antialias fringe, not copied Apple icon colors. Its box is stored in normalized coordinates. There is no stroked icon outline. The foreground response was fitted on this photograph and can produce unsuitable hues elsewhere; this is not a universal contrast algorithm. A light icon also needs a different treatment over a white backdrop.

## Toolbar: normal displacement and tangential compression

The outline is a rounded rectangle with normalized half-extents `[392/170,1]` and corner radius `1`. Its fitted map allows normal displacement and tangential scaling to vary separately. With tangent `t=(−n_y,n_x)`:

```text
a(d) = Σ c_i N_i(d/ρ), i=0…6
u = clamp(d/(ρ band),0,1)
W = (1−u)^4 (1+4u)
F(v) = diag(1−core_x,1−core_y) v − a(d)n − tangent W (t·v)t
```

Here `ρ` is the normalized corner radius. The cubic B-spline basis has knots `[0,0,0,0,.015,.04,.09,.2,.45,.75,1,1,1,1]`; append three zero coefficients after the seven stored coefficients. Clamp the basis argument to `[0,1]`. Displacement is in `L` units. The complete coefficients, core terms and tangential strength are in the `regular` recipe.

This is a tensor image field, **not a recovered solid or a proved gradient-potential lens**. Along straight top/bottom segments, `∂F_x/∂x=1−core_x−tangent W`: a smaller positive source derivative makes displayed text wider. Both directions follow the outline near its ends, rather than applying one arbitrary global horizontal stretch.

For the toolbar only, blend unblurred and source-blurred samples using `H=6u⁵−15u⁴+10u³`, with `u=clamp(d/blur_transition,0,1)`. Use the recipe's source σ `11/170`; it supersedes the older study's eight-pixel search ceiling. No glass post-blur is selected here. Apply the common material function afterward. Exact archive/plus symbols are retained from the source frame for comparison and excluded from glass scoring; their own rendering has not been reconstructed.

## Active slider: oval outline and channel-separated map

The outline is **not a capsule**. Set `h_x=118.5/56.5`, `h_y=1`, `U=x/h_x`, `V=y/h_y`, and `Q(U)=1+aU²+bU⁴`, where:

```text
a = 0.4994011732582939
b = −0.23031415352591186
T(U,V) = U² + [V/Q(U)]²
```

The boundary is `T=1`. Use Euclidean distance to this curve for `s`, and its normalized implicit gradient for `n`. The radius parameter `ρ=43.38/56.5` controls the spline's distance scale; it is not a claim that the whole outline has constant radius. The top dark ridge constrains the measured outline; bottom/right symmetry remains an assumption.

Use the same tensor map as the toolbar with the **slider's** independent recipe. For channel wavelengths `λ=[.650,.550,.450]` in micrometers, use:

```text
f_λ = 1 + dispersion (λ^−2 − .550^−2)
F_λ(v) = v + f_λ [F(v)−v]
```

Read the red, green and blue channels at their respective `F_λ`, then apply the common material. No Gaussian blur is selected. This is an RGB dispersion proxy; it does not prove that Apple's colored rim comes from physical dispersion. It is not a wavelength-resolved radiometric model.

The recovered background includes an antialiased track of height `20/56.5` in normalized units, centered `(.9295089030362/56.5)` below the lens center. Its hidden blue endpoint is estimated. The replay fixes this input explicitly; source reconstruction uncertainty remains part of the comparison.

The measured narrow exterior-shadow ratios are width `1963/1896`, height `929/906`, centered horizontally and bottom-aligned. Those measurements are retained for subsequent shadow modeling; the current comparison retains exterior context and **does not claim to reconstruct that shadow or the broader blue halo**.

## Uniform light and dark surfaces

These use a rounded rectangle with half-extents `[245/106.5,1]` and radius `1`. Let `u=clamp(d/(ρ band),0,1)`, `v=1−u`, `S=6u⁵−15u⁴+10u³`, and:

```text
P = rim v³(1+3u) + (1−rim)(256/27)u v³
a = ρ band edge P
F(x,y) = ((1−core S)x, (1−core S)y) − a n
```

The `regular-light` and `regular-dark` recipes independently specify field parameters and material coefficients. Neither uses Gaussian blur. Their nearly uniform backdrops cannot identify an optical model; high scores here validate tone/rim approximation, **not refraction**.

## Common material function

All five frozen recipes have 23 coefficients. Define `X=x/h_x`, `Y=y/h_y`, `thin=exp[−(d/thin_rim)²]`, `e=exp[−(d/rim_width)²]`, `f=exp[−(d/(4 rim_width))²]`. Let `z=clamp(d/.30,0,1)` and `c=6z⁵−15z⁴+10z³`. The 14 lighting basis values, in order, are:

```text
J = [thin, thin n_x, thin n_y,
     e, e n_x, e n_y, f, f n_x, f n_y,
     X, Y, X², Y², XY]
gain = (1−c) coefficients[0:3] + c coefficients[20:23]
glass = clamp(sample × gain + coefficients[3:6]
              + dot(J,coefficients[6:20]), 0, 1)
```

The scalar lighting term is added to all RGB channels. This is a bounded fitted basis, not a Fresnel/environment reconstruction. In particular, the white inner band in some earlier fits can arise from these terms independently of refraction. Do not interpret a fit coefficient as physical radiance or encode it as an extra painted outline.

## Solid-vector foreground responses

Foreground coverage `α` comes from solid geometry, independently of material color. Its input `B` includes preceding layers, such as a fixed selection backing. Affine styles use `F(B)=(1−o)B+oC`; response styles use the shared per-channel function:

```text
F_c(B) = t_c [1−s_c(1−clamp(B_c,0,1))^γ_c]
output = (1−α)B + αF(B)
```

For `t,s∈[0,1]` and `γ>0`, the function stays bounded and monotone. The affine operation embeds exactly with `v=1−o`, `q=oC`, `t=v+q`, `s=v/t` and `γ=1`; set `s=0` when `t=0`. Response styles already contain opacity, so applying it again would change the model. One fitted response is used on both observed backgrounds; it is an empirical color function, not a Fresnel law. Glass and foreground errors remain separate.

## Reproducing and transferring a model

- Preserve equations, operation order, color space, shape and all normalized parameters when changing renderer. The source image's registration and the glyph coverage are separate inputs. Use the [frozen replay command](reconstruction.md#reproduce); optimization commands are for research only.
- The reference implementation uses bilinear lookup, four samples per output axis for still materials, separate shape coverage and reflected-border Gaussian filtering. These sampling choices affect exact raster pixels; they are not physical model constants. Higher sampling must converge to the same continuous map.
- Compare native-resolution glass, rim, interior and foreground separately. Use identical source/mask definitions for cross-renderer scores. PNG rounding changes the reported errors slightly; record whether measurements precede or follow quantization.
- Transfer the light circular playback recipe first to other Clear circular media controls. Preserve `σ/L`, not the reference pixel σ. Toolbar and active-slider geometry require their own mappings; a circular model should not be stretched into either shape without verification.
- Do not score an unrecovered or moving backdrop as though it were known. Other media controls and Music toolbar footage are useful next references, but require their original glyphs and independent backdrop recovery. They remain candidates, not additional calibrated successes.

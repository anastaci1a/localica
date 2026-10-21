# Mathematical models for the reference recreations

These are **rendering-method-independent inverse image maps and material functions**. They can be evaluated by a shader, a mesh with sufficient sampling, a CPU renderer or another implementation. The Python replay is a reference implementation, not the specification's coordinate system or required rendering technology.

[Comparisons and scores](reconstruction.md) · [Exact parameter records](experiments/reconstruction/recipes.json) · [General optical models](model.md) · [Replay](reconstruction.md#reproduce)

The circular playback control is the **current most-polished calibration**, selected after visual review. The toolbar, slider and uniform light/dark surfaces have independent frozen recipes. A recipe reproduces a particular study; it does not identify Apple's private shader, imply physical correctness or establish defaults for every background.

## Common contract

- Let the element's shorter dimension be `D`; use `L=D/2` as the length unit. Local output coordinates are `v=(p−center)/L`. Every spatial parameter in `recipes.json` is dimensionless in these units, including blur, rim lighting and shape dimensions. Multiply by `L` only when sampling the backdrop or rasterizing. `reference_length` records the source-image scale; it is not an extra model parameter.
- Across differently sized observations, use each pose's `Lᵢ`: `σᵢ=σ̂Lᵢ`. Optimizer records may store `σref` at an explicitly declared `Lref`, giving `σᵢ=σref Lᵢ/Lref`; published normalized lengths remain `σ̂=σref/Lref`. Source and output blur use the same conversion. Raster sampling/quarter-pixel kernel quantization remain implementation approximations.
- `F(v)` is the **inverse map**: the backdrop sample is `B(center+L F(v))`. It describes where to read pixels, not the direction in which the visible feature moves. Independent foreground content is composited afterward.
- The examples operate on **encoded RGB values in [0,1]**. This is a calibrated image-space approximation; changing to linear-light processing changes the output. Coordinates increase rightward/downward, and the camera is frontal.
- Preserve opaque RGB input precision: divide 8-bit code values by 255 and 16-bit values by 65535. A decoded 10-bit video stored as RGB16 must not pass through an 8-bit image reader. [Raster input helpers](experiments/reconstruction/raster_input.py) preserve code values without transfer/ICC/exposure changes; [controls](experiments/reconstruction/verify-raster-input.py) check exact legacy equality and high-bit preservation. Record decoding precision in the input epoch.
- For uniform raster resizing, integer pixel centers transform as D(p)=s(p+½)−½. Rescale a registration as D_destination composed with T composed with D_source_inverse; simply multiplying translations misses the half-pixel convention. Integer-factor array crop indices scale directly; center/vector positions use D. [Coordinate controls](experiments/reconstruction/verify-raster-coordinates.py) verify both paths without refitting image features.
- Let `s(v)` be signed distance to the outline, negative inside, `n=∇s` the outward unit normal and `d=max(−s,0)`. Shape coverage is independent of material color. Evaluate the equations continuously before coverage/raster filtering.
- Gaussian `σ` is standard deviation, not kernel diameter. Source blur, glass blur after refraction/shading, and foreground-color blur are different operations. The foreground's coverage remains sharp.
- Foreground geometry must use a verified exact vector, or a clean symmetric trace when none is available. Solid coverage is independent of tint, transmission and blur, and is rasterized anew at each output resolution. The Home/Search follow-up uses private SVG inputs with frozen hashes; older photo-derived masks below remain historical calibrations pending replacement. Optical masks/scores stay fixed when foreground measurements change.
- Parameters and operation order are fixed for replay. Re-optimizing coefficients produces a new calibration, not an exact reproduction of this one. Source registration, the estimated hidden backdrop, video decoding and raster conventions are additional inputs.
- Missing backdrop pixels may be extended for presentation, with the method and valid-mask numerical effect recorded. Preserve the original recovered inputs and native benchmark; completed pixels are estimates, not new observed evidence.

## Model responsibilities and evidence

- A model owns the footprint, front/back surfaces or explicitly empirical map, thickness, transmission/reflection, dispersion, ordinary blur, foreground response, contrast, highlights and exterior shadows. Each stage has declared normalized parameters and operation order; no renderer-specific tricks are required by the specification.
- **Ordinary blur and dispersion are separate.** Ordinary blur averages neighboring samples with one spatial kernel shared by the channels. Dispersion changes the sampling/transport by wavelength (or a declared RGB proxy); it can produce colored edge spreading without ordinary blur. Corner color highlights require a separate lighting test before attributing them to dispersion.
- Geometry-led candidates must state camera, surfaces, IOR, background distance and valid ray branches. Derive normals from one surface and check its [ray-map compatibility](model.md#flat-front-inverse-map-compatibility); zero image-map curl alone neither establishes nor rejects a refracting solid. Empirical maps remain labeled approximations; folds and total internal reflection are distinct phenomena.
- Pill silhouettes should be tested for smoothly joined shoulders and a minute center crown rather than assumed to be a straight capsule. Curvature is a parameter of the element family, not a pixel correction on one reference.
- Do not retain the photographed element's shadow in a reconstruction backdrop. Recover an unobstructed frame or reconstruct the incident background, recording uncertainty; generate the modeled shadow separately. Earlier retained-context figures remain historical estimates until corrected.
- No target-pixel painting, hidden per-image optical coefficients, artifact copying or blur that conceals an incorrect shape. Icons use reusable solid geometry; their material is evaluated separately. Additional brightness or saturation beyond the sampled scene needs an explicit lighting/material hypothesis.

## Verified improvement protocol

The legacy records below predate this stricter protocol. Preserve their experiment counts and inputs; do not relabel them as qualifying improvements without an audit.

- Freeze an input epoch: reference/source hashes, registration, masks, color space, native sampling, quantization and measurement definitions. Changed input evidence requires a separately reported epoch and baseline, not an apparent model improvement.
- For each observation, measure RGB errors in 0–255 units: `E = sqrt(0.7 glass_RMSE² + 0.3 rim_RMSE²)`. A new qualifying pass requires `E_new ≤ E_previous` **on every active background independently**, at least one measurable improvement beyond known numerical uncertainty, and no whole-glass regression. This prospective nonregression policy replaces the earlier 0.5% minimum; preserve earlier acceptance records unchanged. Retain foreground and exterior-shadow nonregression guards where measured. Ties and rejected trials do not count.
- Preview widths are per individual image/side. Iterations use `min(2W, max(W, cap))`, where W is the original reference crop width; cap is720px for small controls or1080px for navbars/player bars. Preserve aspect ratio. Originals wider than cap retain their width; high-quality exports are1080px wide. [Sizing reference](experiments/reconstruction/preview_size.py). Native measurement resolution never follows preview resizing.
- A numerical win also requires independent replay, source-footprint validation and visual review against known defects. [Acceptance gate](experiments/reconstruction/progress_gate.py) consumes those separate evidence decisions; it cannot infer them from a low error.
- There is no promised minimum compound gain. The gate retains `--policy relative` for reproducing historical 0.5% decisions. Prefer short bounded proposals and reuse valid unchanged-stage evidence. Display `100(1−E/E₀)` as **residual reduction from baseline**, never percent Apple accuracy. A zero residual is terminal, not another successful tie.
- Review assumptions and model deficiencies after every three substantive experiments, including rejected trials. If the milestone stalls, revise the hypothesis or report the limitation; do not reset the baseline or accept known visual regressions to advance a counter.
- Work on two distinct elements concurrently. Commit and push each verified pass; after three verified non-regressive passes per element, merge and rotate. This replaces the earlier 15/20-pass targets prospectively. Retain all active backgrounds and historical evidence; adding a reference is a documented new stage, not an excuse to drop older scores.
- [Local crop diagnostics](experiments/reconstruction/crop_diagnostics.py) are advisory: fixed masks/positions, RGB and channel-edge errors, contour/refraction landmarks, and shadow/highlight profiles. Do not realign or recolor crops independently. Their purpose is to expose local defects hidden by the global average.

## Continuous pill footprint candidate

This optional footprint replaces the capsule's exactly straight central interval. It is a **2D geometry hypothesis**, not a recovered Apple solid. Optical calibration must be evaluated separately. The existing recipes retain their original footprint unless `crown_softness` is enabled.

For major/minor half-extents `a ≥ b > 0`, softness `0 < w ≤ 1`, and `e=wb`, define `sp(t)=ln(1+exp(t))` and:

```text
c = sp((e − (a−b))/e)
D = sp((sqrt(a²+e²) − (a−b))/e) − c
q(x) = b [sp((sqrt(x²+e²) − (a−b))/e) − c] / D
T(x,y) = [q(x)² + y²] / b²
```

- The boundary is `T=1`. `q(0)=0`, `q(a)=b`, and `q` is even, smooth and convex. Hence the top/bottom have a unique maximum extent at the center and no exactly straight interval. This does not require an abrupt cap/body join.
- At `a=b`, this softened longitudinal formula does **not** become a circle: X/Y remain inequivalent. Use the separate radial footprint for circles. The current pill study does not establish a continuous pill-to-circle morph.
- Use `∇T/|∇T|` for the boundary normal. The reference image-map coordinate `b(sqrt(T)−1)` has the correct boundary/sign but is **not Euclidean signed distance**; do not substitute it into a distance-based physical derivation without accounting for its gradient.
- Switch review revision 6 uses softness `0.08`, one material across both observations and generated exterior shadows. [Recipe](experiments/reconstruction/studies/active-switch/review-round/pass-06.json) records the kernel length basis; the dimensionless source/output blur widths are `sigma/216.5` and `post_sigma/216.5`. This incremental image-score improvement does not validate the inherited empirical displacement field as a physical solid.
- Reproduce the revision from private frozen `a/` and `b/` study inputs: `python revision_replay.py INPUTS studies/active-switch/review-round/pass-05.json studies/active-switch/review-round/pass-06.json OUTPUT --scale 4` from `experiments/reconstruction/`. The script independently replays both states and all emitted transmission rays; human visual approval remains separate.
- [Implementation](experiments/reconstruction/crowned_footprint.py) evaluates normalized geometry and analytic derivatives. The same `T` could define a 3D height field, but that height, camera and IOR must be specified and tested separately.
- Independent fits to the two selector silhouettes suggest softness about0.185. This is a calibration candidate, not a default for all pills. [Contour measurements](experiments/reconstruction/studies/active-segmented-selector/crown-diagnostics.json) describe the fixed white-ridge sample and its limits. The initial clear optical trials did not pass the improvement gate.

## Playback: radial map and smooth inner transition

For the circle, `r=|v|`, `d=1−r`, and `F(v)=(1−a(d)/r)v`. At the center set `F(0)=0`; the displacement has already reached zero there. Use the following constants:

| Parameter | Value |
| --- | ---: |
| Outer displacement `A` | 0.6344430313360516 |
| Initial transition width `b` | 0.09445809156460266 |
| Initial exponent `p` | 2.10022237760162 |
| Inner-tail width multiplier `k` | 4.5 |
| Inner endpoint exponent `q` | 4 |
| Displacement gain | 1 |
| Source Gaussian σ / L | 0.0020408163265306124 |
| Glass post-refraction σ / L | 0.006530612244897959 |
| Final foreground-color σ / L | 0.012244897959183673 |

For `d≤b/2`, use `a=A clamp(1−d/b,0,1)^p`. For `b/2<d<(1+k)b/2`, define `t=(d−b/2)/(kb/2)` and use:

```text
a = A 2^(−p) exp[q ln(1−t) + (q−pk)t + (q−pk²)t²/2]
```

For larger `d`, use `a=0`. The join matches value, first and second derivatives; the inner endpoint has zero derivatives through order three. This controls the transition from the strongly refracted rim to the quiet center without inserting a discontinuous shoulder. It does **not** prescribe a 3D height or thickness. The map has a fold in its supported calibration; reversed rim details are intentional in this still-image fit.

### Glass and play-symbol processing

The [current playback recipe](experiments/reconstruction/studies/playback/review-round/pass-03.json) multiplies all original material coefficients by 1.005 before clipping/shading integration. The original coefficients remain archived in `recipes.json`; the round record contains the exact resulting vector. Replay the frozen inputs with `python playback_replay.py INPUTS studies/playback/review-round/pass-03.json OUTPUT --scale 4`; add `--record` with the same file to `motion.py` for the moving preview.

1. Blur the backdrop with the source σ, sample it through `F`, and apply the common material function below.
2. Blur that calculated glass color with the glass σ. This is after refraction and shading.
3. Calculate the foreground color `C0=1−strength×(1−glass)^gamma`, componentwise. Use the exact three-channel coefficients from the recipe:

```text
strength = [0.9999999999999285, 0.9999999999999999, 0.4829334270156419]
gamma    = [11.731766541351298, 8.166089277320348, 5.294455609674747]
```

4. Let `Y=dot(C0,[.2126,.7152,.0722])` and `w=clamp((1−Y)/.15,0,1)²`. Increase dark-region saturation: `C1=clamp(Y+(1+.2w)(C0−Y),0,1)`.
5. Componentwise, set `u=clamp((C1−.9)/.1,0,1)` and `C2=C1−.48u³(1−C1)`. This reduces near-white highlights without creating an early white plateau. For a general strength `h`, the function is monotone for `0≤h≤4`; `.48` is the current value.
6. Gaussian-blur **C2**, with the final foreground-color σ. This is the last color operation. Composite it through the sharp triangular coverage: `output=(1−α)glass+αCblurred`.

The included [playback coverage mask](experiments/reconstruction/playback-mask.png) is a reference-derived triangle silhouette with its antialias fringe, not copied Apple icon colors. Its box is stored in normalized coordinates. There is no stroked icon outline. The foreground response was fitted on this photograph and can produce unsuitable hues elsewhere; this is not a universal contrast algorithm. A light icon also needs a different treatment over a white backdrop.

## Toolbar: normal displacement and tangential compression

The current footprint uses the continuous crown above, half-extents `[392/170,1]` and softness `0.10`. The inherited normal-profile length basis is `ρ=1`; its boundary coordinate is not Euclidean distance. The fitted map allows normal displacement and tangential scaling to vary separately. With tangent `t=(−n_y,n_x)`:

```text
a(d) = Σ c_i N_i(d/ρ), i=0…6
u = clamp(d/(ρ band),0,1)
W = (1−u)^4 (1+4u)
F(v) = diag(1−core_x,1−core_y) v − a(d)n − tangent W (t·v)t
```

The cubic B-spline basis has knots `[0,0,0,0,.015,.04,.09,.2,.45,.75,1,1,1,1]`; append three zero coefficients after the seven stored coefficients. Clamp the basis argument to `[0,1]`. Displacement is in `L` units. Coefficients 4–6 have supports beginning at `d/r` values `.015`, `.04` and `.09`; value and first two derivatives vanish at their support endpoints. The signed coefficients describe the inverse map, not lens heights. The complete coefficients, core terms and tangential strength are in the [current recipe](experiments/reconstruction/studies/archive-plus/review-round/pass-03.json).

This is a tensor image field, **not a recovered solid or a proved gradient-potential lens**. In the straight-edge limit, `∂F_x/∂x=1−core_x−tangent W`: a smaller positive source derivative makes displayed text wider. Crowned boundaries add derivatives of the changing normal. Both displacement directions follow the outline.

For the toolbar only, blend unblurred and source-blurred samples using `H=6u⁵−15u⁴+10u³`, with `u=clamp(d/blur_transition,0,1)`. Use the recipe's source σ `11/170`. Apply `C_input=max(C_blend,0)^γ` componentwise, with one shared `γ=1.0456395525912732`, **before** the common affine material and clipping. The revised material coefficients are stored directly in the recipe. This empirical encoded-RGB curve is strongly confounded with material/source photometry; it does not identify Apple's pipeline. Positive gamma and positive material gains preserve nondecreasing response under fixed lighting and positive filtering.

After the common material, apply ordinary color blur with `σ=1.25/170` in normalized units. This is distinct from dispersion (none added in this revision). For conditional glass color `C` and coverage `α`, use `C′=Gσ(Cα)/max(Gσ(α),ε)`, then retain the original sharp coverage for composition. Filtering zero-padded RGB directly would create an unsupported dark edge.

The neutral exterior shadow is `A_s=o·G_s(α)(x−δ)`: opacity `o=0.21968065`, width `s=0.29999995 L`, offset `δ=[−0.002930886,0.007772859] L`. Generate the whole shape with at least four-sigma padding before cropping. Composite `C′α + B(1−A_s)(1−α)`; no reference shadow is retained. The fitted width reaches its declared bound, so these are provisional appearance parameters, not a recovered lighting rig.

Finally apply solid SVG coverage with constant foreground color. The plus comes from the original font; the archive is a symmetric geometric trace. Font/vector provenance and pose are independent of optics. Native source and SVG hashes are frozen in the recipe. Reproduce from private inputs with `python tensor_replay.py INPUTS studies/archive-plus/review-round/pass-03.json OUTPUT --scale 4` from `experiments/reconstruction/`. The command verifies hashes and renders fresh high-resolution geometry, glass, shadow and icons without fitting.

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

## Music and switch profile family

The newer [paired studies](reconstruction.md#apple-music-studies) use one normalized image-map family. Each `studies/<id>/paired/accepted.json` supplies the shared coefficients; `a.json` and `b.json` supply measured poses and frozen input hashes. Home's corrected chain protects the retained single-background result; the earlier invalid pairing is archived separately.

Subtract the recipe's shape offset from `v`, then apply its half-extent/radius corrections. Obtain boundary coordinate `s`, outward unit normal `n`, and `d=max(−s,0)`. The usual outline is a rounded rectangle/capsule. For a circle, keep equal half-extents and use `core_y=core_x` with no directional optical parameters.

For `spline=true`, evaluate a cubic B-spline at `clamp(d/R,0,1)`, with knots `[0,0,0,0,.015,.04,.09,.2,.45,.75,1,1,1,1]` and coefficients `[c0,…,c6,0,0,0]`. Otherwise use `a=amplitude×max(1−d/band,0)^power`. Signed spline coefficients permit folds and reduced center images; they are not lens heights.

```text
q = n_x² (horizontal major axis), or n_y² (vertical major axis)
a ← a (1 + cap_normal_gain q) + q Σᵢ bᵢ Bᵢ(d/R)
u = clamp(d/tangent_band,0,1)
W = (1−u)^m (1+mu) (1−cap_tangent_fade q)
W_d = −m(m+1)u(1−u)^(m−1) / tangent_band
τ = −n_y x + n_x y
K = ½ coupling × tangent × W_d × x²
F_x = (1−core_x)x − a n_x + tangent W τ n_y − K n_x
F_y = (1−core_y)y − a n_y − tangent W τ n_x − K n_y
```

The optional `cap_edge_profile=[b₀,…]` uses the same cubic basis as `a`; missing coefficients are zero. The current switch uses three terms, whose added displacement vanishes with two continuous derivatives at `d/R=.09`. This changes normal refraction near the caps without altering the silhouette or deeper interior. Coefficients are dimensionless source-coordinate displacements, not surface heights.

Here `m=tangent_exponent` (default 4). Restore the shape offset to `F` before backdrop lookup. Missing cap gains/fades are zero. `W_d` is deliberately the base falloff derivative **without** the orientation weight; with these empirical tensor terms, the field is not asserted to be a gradient potential or ray trace through an identified solid.

The compact mini-player additionally smooths the capsule's cap/body join. Along its major axis let `a=max(h)−R`, `z=|x|−a`, and `w=cap_join_width`. Replace `max(z,0)` by `q=w(2t³−t⁴)` for `|z|<w`, where `t=(z/w+1)/2`; use 0 below and `z` above that interval. The boundary coordinate is `sqrt(q²+y²)−R`; normalize its analytic gradient for `n`. This is a C2 implicit join, **not exact Euclidean distance** in the join band. Rotate axes for a vertical capsule.

Sample the source-blurred backdrop through `F`. When `blur_transition>0`, blend clear and source-blurred samples with quintic smoothstep of `d/blur_transition`, then apply `sample^gamma`. Apply the common material, average subpixels, then apply output Gaussian blur. Coverage, exterior shadows and solid-vector foreground layers remain separate. Exact normalized coefficients, foreground styles and any context option are part of each recipe, not inferred from its preview image.

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

### Source-context color response

The active-switch study optionally contracts the **material's** RGB bias on neutral backgrounds. Let `μ` be the area mean of the unrefracted, encoded-RGB backdrop over the current footprint, and `χ=max(μ)−min(μ)`. With shared sensitivity `k≥0`, floor `f∈[0,1]`, and encoded-luma weights `w=(.2126,.7152,.0722)`:

```text
t = f + (1−f) clamp(kχ,0,1)
A = t I + (1−t) 1 wᵀ
effective_triplet = A × shared_triplet
```

Apply `A` to the rim gain, offset and core gain triplets before the common material calculation. Leave the 14 scalar lighting coefficients and sampled RGB ray colors unchanged. Thus `wᵀA=wᵀ`: contraction preserves each coefficient triplet's encoded-luma response, while reducing its color bias. This is not desaturation of the final image or a radiometric conservation law.

- Use the same `k,f` and 23 base coefficients on both backgrounds; recompute `μ` when the backdrop or footprint moves. The reference implementation uses native-center area quadrature, independent of target/foreground/scoring masks.
- An absent context option means `A=I`, preserving earlier recipes. The current switch selects `k=10,f=0`; it still misses colored rim detail. This empirical adaptation is not an identified Apple formula.

### Source-brightness response of neutral rim lighting

An optional three-value response adjusts neutral thin/broad rim terms using the unrefracted source mean `μ`. Its sampling footprint may be inset by a declared distance `iL`; Home uses `i=.08` to avoid an unrecovered boundary strip. Let `y=μ·(.2126,.7152,.0722)`, shared responses `r₀,r₁,r₂` and existing radial lighting bases `thin,e,f`:

```text
additional_light(x) = (y−1/2) [r₀ thin(x) + r₁ e(x) + r₂ f(x)]
effective_material = A material + b(y)
b₆=(y−1/2)r₀, b₉=(y−1/2)r₁, b₁₂=(y−1/2)r₂; other b entries=0
```

- The added light is identical in all three RGB channels; it cannot assign independent RGB materials to each background. It changes lighting, not the ray map or the sampled transmission colors. `A` is the preceding optional color-contraction matrix, otherwise identity.
- Recompute the source mean when the backdrop moves. Every mean sample must have defined input, including interpolation support; average coverage cannot excuse a missing pixel. Check this footprint separately from scored rays and cap motion along its declared axis. Material derivatives use `A`; the offset still affects clipping and therefore the active derivative regions.
- A declared calibration range `[l,h]` optionally replaces `y` with `l+(h−l)S(clamp((y−l)/(h−l),0,1))`. This bounded, C2 interpolation preserves the measured endpoints and prevents uncontrolled extrapolation.
- Switch revision 6 retains shared nonnegative responses `[2.793309,1.311647,~0]` with no inset or luma-range remapping. The two source means are `0.758766` and `0.814351`. Positive grayscale gains, positive gamma and nonnegative radial bases/response imply a nondecreasing uniform-gray output; clipping and positive filtering preserve that order. This limited [response audit](experiments/reconstruction/studies/active-switch/review-round/pass-06-response-audit.json) does not validate arbitrary colored or spatial backgrounds. The context operator itself does not alter geometry; the revision 6 joint fit separately changes existing optical coefficients. Kernels and shadow stay fixed.
- This is an empirical encoded-brightness response, not radiometric illumination or an Apple formula. Home tests a narrow range of approximately 0.413–0.436; outside behavior is bounded but unvalidated. Zero response preserves the prior material.

### Directional grazing reflection

The switch follow-up separates a reflected field from the transmission's output blur. With normalized inward distance `d`, outward normal `n`, unit lighting direction `l`, `0≤a≤1`, width `w>0`, angular exponent `p>0`, and quintic smoothstep `S`:

```text
u = clamp(d/w,0,1)
F = a [1−S(u)] max(n·l,0)^p
E = Gaussian(unrefracted source, σ_environment/L)
C_final = (1−F) C_transmission_after_output_blur + F E
```

- The reflection width and source kernel scale with each pose's `L`. Sample `E` at the unrefracted surface position; update its source offset during motion. Keep shape coverage, exterior shadow and foreground separate.
- Reflection and transmission have independent filtering. Applying the transmission blur again after this mixture changes the model. Material derivatives therefore acquire `(1−F)` **after** their output filter.
- This bounded mixture approximates the incident field from the available backdrop. It is not a recovered environment, a Fresnel law, or Apple's shader; the directional lobe does not explain the observed rainbow. No target RGB is used as its input.
- Exponent `p=0` selects an isotropic rim. An explicitly declared RGB environment may replace the backdrop proxy; it adds no background sample or photographic observation. Home tests a uniform white field to improve the fitted material's poorly constrained outer edge. This remains an empirical lighting assumption, checked on both full composites as well as the frozen glass masks.
- An absent option preserves the earlier calculation exactly. Source coverage includes the environment kernel separately. Frozen native gates remain unchanged; the excluded outer-contour band needs an additional visual/error check.

### Directional chromatic transmission

The switch's lateral color-fringe hypothesis separates background channels **before** common lighting. For the existing map `F(x)`, source `B`, gamma `γ`, fixed direction `u=(cos θ,sin θ)` and signed separation `δ`:

```text
s_R=1, s_G=0, s_B=−1
q_c = x + s_c δ u
F_out(q) = F(q) inside the footprint, q outside
D_c(x) = max(B_c(F_out(q_c)),0)^γ
z = (x_x−shape_dx)/half_width
w(x) = a [1−S(clamp((z−fade_center)/fade_width+1/2,0,1))]
T_c(x) = (1−w) max(B_c(F(x)),0)^γ + w D_c(x)
```

- `B` uses the existing source-filter rule. Apply material gain and lighting at **x**, then subpixel integration, output filtering and the preceding reflection mixture. Do not shift the finished highlights/shadow or multiply gamma twice.
- `δ` uses the pose's `L`; the fade center/width use horizontal half-width. The separation remains constant across the fade: its contribution decreases, not its length. Check each displaced ray and source kernel separately, including during backdrop movement.
- Symmetric RGB offsets are a controllable approximation to lateral chromatic aberration, not measured wavelengths, a dispersion law or Apple's implementation. Direction and left-side fade remain hypotheses calibrated across the two references. An absent option, zero strength or zero separation preserves the prior model.

## Solid-vector foreground responses

Selection studies may declare separate incident and exterior content layers. The optical map samples the incident layer; uncovered pixels sample the exterior layer in the same coordinate system. This permits testing selected-text recoloring independently of optical displacement. Both inputs require frozen hashes and explicit estimation provenance. With no separate exterior declared, both use the same source, preserving existing studies. Recoloring is a content-state assumption, not an optical property or evidence of Apple's pipeline.

Foreground coverage `α` comes from solid geometry, independently of material color. Its input `B` includes preceding layers, such as a fixed selection backing. Affine styles use `F(B)=(1−o)B+oC`; response styles use the shared per-channel function:

```text
F_c(B) = t_c [1−s_c(1−clamp(B_c,0,1))^γ_c]
output = (1−α)B + αF(B)
```

For `t,s∈[0,1]` and `γ>0`, the function stays bounded and monotone. The affine operation embeds exactly with `v=1−o`, `q=oC`, `t=v+q`, `s=v/t` and `γ=1`; set `s=0` when `t=0`. Response styles already contain opacity, so applying it again would change the model. One fitted response is used on both observed backgrounds; it is an empirical color function, not a Fresnel law. Glass and foreground errors remain separate.

## Reproducing and transferring a model

- Preserve equations, operation order, color space, shape and all normalized parameters when changing renderer. The source image's registration and the glyph coverage are separate inputs. Use the [frozen replay command](reconstruction.md#reproduce); optimization commands are for research only.
- The reference implementation uses bilinear lookup, separate shape coverage and reflected-border Gaussian filtering. Earlier HD recipes use four samples per output axis; the newer Music/switch studies use two. Preserve each study's native protocol when replaying its scores. These sampling choices affect exact raster pixels; they are not physical model constants.
- Compare native-resolution glass, rim, interior and foreground separately. Use identical source/mask definitions for cross-renderer scores. PNG rounding changes the reported errors slightly; record whether measurements precede or follow quantization.
- Transfer the light circular playback recipe first to other Clear circular media controls. Preserve `σ/L`, not the reference pixel σ. Toolbar and active-slider geometry require their own mappings; a circular model should not be stretched into either shape without verification.
- Do not score an unrecovered or moving backdrop as though it were known. Other media controls and Music toolbar footage are useful next references, but require their original glyphs and independent backdrop recovery. They remain candidates, not additional calibrated successes.
- Display captures may extend unrecovered source pixels with the nearest known RGB. Stills blend estimated contact context into the reference by distance from the shape; movies translate the completed source consistently inside and outside the lens, avoiding a fixed strip at the moving confidence boundary. Frozen benchmark inputs stay unchanged; report completion's RGB difference on the score mask, including filter/subpixel spill. Missing context remains estimated.

### Current vector-control revisions

The [Search recipe](experiments/reconstruction/studies/music-search-vector/review-round/pass-03.json) refines the shared affine material with map, blur, SVG geometry and generated shadow fixed. The [Navigation recipe](experiments/reconstruction/studies/music-navigation/review-round/pass-02.json) adjusts its existing empirical outer-normal profile. Both freeze native pixel blur widths, normalized map coefficients, case fingerprints and vector hashes. Scale pixel widths with the source short half-size L.

Replay: `python vector_revision.py INPUTS RECORD OUTPUT` with either linked recipe and the original private a/b source, target, known masks and SVGs. The command checks case, source and vector hashes. Unknown exterior uses nearest defined RGB for presentation only; no photographed shadow or foreground is copied.

For final figures add `--high-quality` (1080px per side). Moving exports accept `extended_media.py INPUT --width 1080`; sampling uses a binary integer scale above the requested size before downsampling. Original native scoring and default media commands are unchanged.

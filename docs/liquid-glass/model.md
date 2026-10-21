# Portable optical and motion equations

[Guide](README.md) · [Observed Apple evidence](optics.md) · [Lab](lab.html) · [Executable reference](model.js)

**Status:** mathematically specified Localica hypotheses, not recovered Apple formulas. These equations define a candidate for future rendering, not the current Android renderer. The small reference has no browser, GPU or framework dependency. The [portable fixtures](experiments/model-fixtures.json) provide boundary, core and corner coordinates/normals/derivatives for later backend comparisons. Use the same units and conventions when porting it; do not copy a shader's constants without its domains.

The frozen reference recreations have their own [dimensionless recipes](recreation-models.md). This page also describes experimental models preserved on `research/glass-lab-preserved-222`; mentions of their lab controls do not describe the deployed lab. Its executable reference is separate from the deployed renderer.

## Coordinates and validity

- Shape-local position `p=(x,y)`, positive X right, positive Y down; positive Z toward the viewer. All lengths use one logical coordinate system. Convert device pixels only at rasterization/sampling boundaries.
- Rounded rectangle half-size `h=(hx,hy)>0`; `0<r≤min(h)`. Circle and capsule are special cases. Zero radius is outside this model's domain.
- Rays propagate **toward** the interface. This differs from conventions that store a vector pointing back toward the ray origin.
- `F(p)` is an **inverse image map**: output position `p` samples source position `F(p)`. Source-feature movement has the opposite interpretation. Add the surface's backdrop-space origin after evaluating this local map.
- Material/optical state, source transform, clip, foreground and shadow must use the same frame and geometry. Optical mapping does not include foreground text.
- Valid finite input is a caller precondition. The reference is an oracle, not an untrusted-input API. Production adapters must reject invalid dimensions, nonfinite values and unsupported optical ranges.

For component-wise absolute value and maximum:

```text
q = abs(p) − h + r
sd(p) = length(max(q,0)) + min(max(qx,qy),0) − r
d = −sd(p)                         // positive inside
b = min(r, min(h) × band)          // 0 < band ≤ 1
u = clamp(d/b, 0, 1)
n = gradient(sd)                  // unit outward normal where differentiable
S(u) = 6u⁵ − 15u⁴ + 10u³
```

Inside the active band, straight sides have constant `n`; circular corner contours have curvature `κd=1/(r−d)`. **Straight segments have zero curvature even when one component of `max(q,0)` is positive.** The medial axis has no unique normal; the edge contribution and its first derivative vanish before or at that axis. The signed-distance outline is C1 at straight/arc joins, not globally C2. One-sided derivatives are appropriate there.

## Boundary field: displacement is not compression

This is a designed image warp, not an optical solid. It allows separate core magnification, edge strength, band width and outline displacement.

```text
R(u) = (1−u)³(1+3u)
H(u) = (256/27) u(1−u)³
P(u) = ρ R(u) + (1−ρ) H(u)        // 0 ≤ ρ ≤ 1
c = core × power
a(d) = b × edge × power × P(u)
F(p) = (1 − c S(u)) p − a(d) n
```

- `P(0)=ρ`, so a positive `ρ`, edge and power retain displacement at the outline. `P′(0)=(1−ρ)256/27`, so the map also has a rising compression band when `ρ<1`.
- `P(1)=P′(1)=P″(1)=0`: no artificial inner shoulder join. `0≤P≤1`; the maximum edge contribution is bounded by `b×edge×power`.
- In the core, `F=(1−c)p`; displayed magnification is `1/(1−c)`, provided `c<1`. The slider named optical power is dimensionless gain, **not geometric thickness**.
- The older `16u²(1−u)²` profile forces displacement and its derivative to zero at the outline. It explains an apparently inactive outer strip. The alternative `R` retains outline displacement but has a negative inward derivative: much of that band magnifies rather than compresses. Both remain explicitly labeled comparisons.
- None of these outline continuations is calibrated to Apple's outermost rim. A physical interface can have a source-coordinate discontinuity at its silhouette; that is different from an unwanted seam inside the material.

![Inverse displacement and normal derivative across the edge band](assets/edge-profile.svg)

### Jacobian and folds

Let `t=(-ny,nx)`, `S_d′=30u²(1−u)²/b` within the band, and `a_d′=edge×power×P′(u)`. On a differentiable boundary segment:

```text
J = ∂F/∂p = (1−cS)I + c S_d′ p⊗n + a_d′ n⊗n − a κd t⊗t
Jnn = 1−cS + c S_d′(p·n) + a_d′
Jtt = 1−cS − a κd
Jtn = c S_d′(p·t); Jnt = 0
 det(J) = Jnn × Jtt
```

- A straight-edge normal derivative above 1 compresses source detail in the output; between 0 and 1 magnifies it. A large constant lookup offset is translation, not compression.
- Singular values of `J` describe general local directional scale; their reciprocals describe displayed feature scale. Determinant alone cannot establish isotropic compression. Zero determinant is a local collapse; a sign reversal indicates a fold.
- For nonnegative core, edge and power, the following **sufficient local nonfolding bounds** follow from `p·n≥0` and `b/(r−bu)≤1/(1−u)`:

```text
power × (core + edge × D) < 1
power × (core + edge × T) < 1
```

| Profile | Upper bound D on −P′ | Upper bound T on P/(1−u) |
| --- | ---: | ---: |
| Old shoulder | `16√3/9` | `64/27` |
| Rim-only R | `16/9` | `256/243` |
| Finite rim + compression | `ρ16/9+(1−ρ)64/27` | `ρ256/243+(1−ρ)1024/729` |

These are conservative: failing a bound means **uncertified**, not necessarily folded. They certify local orientation, not global one-to-one mapping. Motion changes effective power, so repeat the bound with its maximum depth multiplier. Extreme controls intentionally permit folds for inspection; do not silently ship those ranges as safe defaults.

## Refracting cap comparator

A second model relates a surface normal, thickness and backdrop distance through geometric optics. Its assumed solid has a rounded top and a flat exit plane; the camera is orthographic. Apple does not document this geometry. Generic transmission and reflection are distinct parts of the [Snell/Fresnel model](https://www.pbr-book.org/4ed/Reflection_Models/Specular_Reflection_and_Transmission).

### Height and analytic normal

```text
v = 1−u; f² = 1−v³; f = sqrt(f²)
C(p) = 1 − 0.036[(x/hx)² + (y/hy)²]
hcap(p) = H f C(p)
grad(C) = (−0.072x/hx², −0.072y/hy²)
N = normalize( H[1.5v² C n/b − f² grad(C)], f )
```

- The last expression is `(-grad(hcap),1)` multiplied by `f`. It evaluates the **vertical limiting rim normal** without a finite-difference stencil or division by zero. For `H>0`, `Nz→0` as `d→0+`.
- At `u=1`, `f` joins the weak curved crown with zero first and second shoulder derivatives. The crown coefficient is an adjustable-model choice fixed here at 0.036; it is not an Apple measurement.
- The former quintic-height ramp made the normal vertical in Z at the outline, flattening the rim and suppressing its refraction. Increasing height alone could not correct that boundary condition.
- `H` is geometric height; `G≥0` is the separate distance from the exit plane to the backdrop. The lab uses `H=L×thickness×power×dynamicDepth`, `G=L×gap`, with `L` the shorter **unstrained** half-size. No fixed 14/5-pixel geometry remains.

### Two-interface ray

For unit incident propagation vector `I`, unit normal `N` opposing it, and index ratio `η=nIncident/nTransmitted`:

```text
c = −dot(I,N)
k = 1 − η²(1−c²)
k < 0: no transmitted ray (total internal reflection)
T = ηI + (ηc − sqrt(k))N
```

For this comparator:

```text
I = (0,0,−1)
Tinside = refract(I, N, 1/ior)
Q = p + Tinside.xy × hcap(p)/(−Tinside.z)
Tout = refract(Tinside, (0,0,1), ior)
F(p) = Q + Tout.xy × G/(−Tout.z)
```

- Restrict `1≤ior≤1.4<√2` here. With orthographic incident rays, the first transmitted ray has `−Tinside.z≥1/ior`; at exit `−Tout.z≥sqrt(2−ior²)>0`. This avoids TIR and grazing denominators in this specific construction. It is **not** a general guarantee for tilted cameras or other shapes.
- Never replace a failed refraction with a zero vector or clamp a ray denominator to an arbitrary positive constant: both silently change the model. A more general renderer must handle reflection/visibility explicitly.
- The reference rejects exits outside the projected body. Tests sample ray segments beneath the top surface; they do not prove global solid-path validity for every future shape/parameter. Treat unvalidated geometries as unsupported.
- At the outline, thickness is zero but a nonzero gap can still yield a finite limiting offset. With `G=0`, outline displacement vanishes for a legitimate geometric reason. A physically correct grazing interface also reflects strongly; finite lookup displacement alone does not imply visible transmitted detail there.
- `H=0` or `ior=1` explicitly gives the identity map. The `H→0` limit is not uniform at an exactly vertical rim when `G>0`; this comparator is unsuitable as a fade-to-zero-opacity formula. Fade material coverage independently.
- Full Fresnel-weighted energy, internal reflections, absorption, dispersion, caustics and multiple scattering are absent. The lab adds an independent synthetic highlight (the field model uses a fixed 0.3-reference-height cap for that lighting only), so calling its complete output a physically accurate renderer would be incorrect.

## Flat-front inverse-map compatibility

This separate comparator has an orthographic camera looking along −z, a flat front at z=0, one back surface z=−h(x,y), index n>1, and background plane z=−G. All lengths use the same normalized unit; h>0 and s=G−h>0. It is a test of a specified geometry, not an identified Apple lens.

For a proposed inverse image map F, let v=F−(x,y). Normal entry leaves the internal ray vertical. Snell's law requires:

```text
M = dot(v,v), R = sqrt(M+s²), D = nR−s
∇h = v/D
transmitted branch: s > sqrt(M/(n²−1))
```

- h is one unknown surface; s cannot be fitted independently at each pixel. The branch inequality rejects the wrong-side solution and critical/grazing rays. Do not clamp them into validity.
- Define c=∂x(vy)−∂y(vx) and K=vy·dot(v,∂xv)−vx·dot(v,∂yv). Equality of the surface's mixed derivatives gives **Dc−nK/R=0**. Terms containing ∇s cancel because ∇s=−v/D. A valid surface may therefore have nonzero image-map curl.
- For reliably nonzero c, set ρ=K/c. The transmitted branch requires **ρ>M**. Its necessary clearance is:

```text
s² = [2n²(ρ−M)+M+sqrt(M²+4n²ρ(ρ−M))] / [2(n²−1)]
```

- This root still must satisfy −∇s=v/D, compatible boundary conditions, positive thickness and valid complete ray paths. It does not establish a surface by itself. Near-zero curl, derivative noise and footprint-crossing differences require separate treatment; do not turn them into impossibility claims.
- The inequality follows from ρ=DR/n=M+s²−s·sqrt(M+s²)/n, which increases from M on the strictly transmitted branch. Failure rules out an **exact** map in this stated family; it supplies no lower bound on image error from an approximate surface or a different camera/front surface.
- [Executable equations](experiments/reconstruction/surface_compatibility.py) and [independent controls](experiments/reconstruction/verify-surface-compatibility.py) check scalar versus vector Snell refraction, a known quadratic surface with nonzero map curl, inverse recovery, scale invariance and invalid branches. Run `python verify-surface-compatibility.py` in the reconstruction directory. These require NumPy, not private images.

The [sampled audit](experiments/reconstruction/map_compatibility_audit.py) applies this necessary condition to an existing inverse map. On the [current switch revision 6](experiments/reconstruction/studies/active-switch/review-round/pass-06-map-compatibility.json), 39.38% / 39.49% of sampled interior points fail it with a large margin over finite-step disagreement; uncertain points remain unresolved. The preceding revision 5 gives [38.51% / 38.65%](experiments/reconstruction/studies/active-switch/review-round/pass-05-map-compatibility.json). Lower image error therefore does not imply a more physically realizable map. This is numerical evidence against an exact flat-front/single-back realization of that map, **not** a bound on approximate image agreement or other surface families. [Controls](experiments/reconstruction/map-compatibility-controls.json) distinguish a known physical surface, a rotational displacement and a zero map; nonfinite inputs are rejected. Run `python verify-map-compatibility-audit.py` for those controls, or the audit's documented command with frozen private study inputs.

The equations above use the stated propagation convention. [Bösel and Gross](https://arxiv.org/html/1510.01129) discuss the broader coupling between surface integrability and ray maps, including the limits of small-angle constructions. Their later [two-surface study](https://arxiv.org/html/1701.03076) also couples refraction, surface continuity and optical path length; adding a second freeform surface does not by itself certify a chosen image map. That paper assumes collimated input/output beams, so its beam-shaping PDE must not be applied unchanged to this camera/backdrop configuration. Physical radiometry, dispersion, reflection, side-wall visibility and source recovery remain separate requirements.

## Finite-camera slab control

Use an infinite plane-parallel slab to check camera calibration before fitting a curved solid. Put the pinhole at (0,0,C), front at z=0, back at z=−h and backdrop at z=−G, with C>0, G>0, 0≤h≤G and n≥1. Let p be the ray's front-plane position. The unlensed backdrop point is (1+G/C)p; divide physical backdrop coordinates by that factor before comparing image maps.

```text
m = p/C; A = sqrt(n² + (n²−1)|m|²)
F(p) = p + h [m/A − m] / (1+G/C)
f = h/(C+G)
J_tangential = 1 + f(1/A − 1)
J_radial = 1 + f(n²/A³ − 1)
```

- Both derivatives are at least 1−f>0: this finite-camera slab cannot fold/reverse its image. Curved surfaces, sidewalls and multiple paths are outside that result. Changing viewpoint alone does not justify adding a mirrored rim.
- h=0 or n=1 gives identity. C→∞ also gives identity for this parallel slab; a curved surface need not. At the optical axis both derivatives equal 1−h(1−1/n)/(C+G). Central contraction of the source map corresponds to apparent image magnification.
- [Implementation](experiments/reconstruction/camera_slab.py) and [independent vector-Snell controls](experiments/reconstruction/verify-camera-slab.py) check both interfaces, Jacobian, equal-index/no-slab/far-camera limits and unit scaling. Run `python verify-camera-slab.py`. The NumPy oracle tests bounded floating-point cases; the algebra supplies the stated geometric bound. No Apple camera, curved lens or reconstruction pass is inferred.

### Curved back surface with a finite camera

For the same flat front, let the back be z=−h(q). Its intersection q differs from the front-plane sample p. Define H(p)=h(q(p)), use the slab's internal slope w=m/A and set q=p+Hw. The desired physical backdrop point is U=(1+G/C)F(p). With unit internal/outgoing directions t and v, Snell's normal is N=nt−v. A back graph has gradient b=N_xy/N_z. The chain rule gives:

```text
B(p,H) = (I + H Dw)^T b / (1 − w·b)
∇p H = B(p,H)
∂x B_y − ∂y B_x + B_x ∂H B_y − B_y ∂H B_x = 0
```

Partial x/y derivatives include the prescribed F(p), holding H fixed. The expanded condition includes H's unknown derivatives through the last two terms; raw image-map curl alone remains insufficient. Require 0<H<G, N_z<0, n(t·v)>1 and 1−w·b>0 on this transmitted local branch. Do not clamp failures into rays. Call the scalar compatibility residual Q(p,H). A pointwise depth root also requires ∇p Q + Q_H B=0 along the surface; independently choosing a root at each pixel is insufficient. Boundary conditions, first-hit/sidewall paths and global height consistency remain separate checks.

[Executable inverse equation](experiments/reconstruction/perspective_back_surface.py) and [independent forward controls](experiments/reconstruction/verify-perspective-back-surface.py) recover known quadratic surfaces from vector Snell paths. Tests cover height derivatives, the expanded condition, unit scaling, wrong-branch rejection and the orthographic limit. Run `python verify-perspective-back-surface.py`. These validate the stated equations, not a finite-camera realization of Apple's images or of the current fitted switch map.

## Reconstruction comparators

The [HD study](reconstruction.md) tests offline hypotheses beyond the portable lab's circle/capsule equations:

- **Superellipse control:** `|x/hx|^n+|y/hy|^n=1`. The earlier slider used visually selected `n=2.5`. Measuring the dark upper contour exposed its error; it remains a shape/control test, not the final slider outline.
- **Measured smooth oval:** `u=x/hx`, `v=y/hy`, `Q(u)=1+a u²+b u⁴`, `T=u²+[v/Q(u)]²=1`. The [contour fit](experiments/reconstruction/contour.json) gives `a≈0.49940`, `b≈−0.23031` and half-extents 118.5 × 56.5. Thus `Tx=[2u−2v²Q′/Q³]/hx`, `Ty=2v/(hy Q²)`. Nearest-boundary distance and implicit-gradient normals use the same curve. Its sampled minimum curvature radius is about 43.39 pixels; the optical band is limited to 43.38. A measured dark ridge is still a silhouette proxy, not proof of Apple's equation. Bottom/right symmetry is assumed; numeric convexity sampling is not a proof for arbitrary coefficients.
- **Flexible inverse-map profile:** `a(d)=min(hx,hy) Σ ci Bi(clamp(d/r,0,1))`, with cubic B-splines on knots `[0,0,0,0,.015,.04,.09,.2,.45,.75,1,1,1,1]`. Seven coefficients are fitted and the final three are zero. Therefore `a`, `a′` and `a″` vanish at the inner join. The map is `F=(x(1−cxS), y(1−cyS))−a n`, with quintic `S` evaluated at `d/r`. This allows independently fitted central X/Y scale and a flexible folded rim, without a fitted displacement image. It is an empirical comparator, not a physical surface reconstruction or a nonfolding guarantee.

### Radial transition and foreground-color controls

For the circular playback study, a radial inverse map isolates the rim-to-center transition from silhouette and lighting. With `r=|v|`, inward depth `d=R−r`, and displacement `a(d)`, use `F(v)=(1−a/r)v`; the center is identity when the displacement support ends before `R`. The initial profile is `a0=A clamp(1−d/b,0,1)^p`, `p>2`. An exponent barely above two is mathematically C2 at its inner endpoint but its second derivative approaches zero very slowly. C2 continuity alone does not ensure a perceptually gradual transition at pixel scale.

The following splice preserves the outer map and independently varies the inner tail. Define `da=b/4`, `ds=b/2`, `w0=b/2`, width factor `k≥1`, shoulder gain `g>0`, and inner-end exponent `q>2`:

```text
for d≤ds:
  a = a0(d) [1+(g−1) S5(clamp((d−da)/(ds−da),0,1))]
for ds<d<ds+k w0:
  t = (d−ds)/(k w0)
  a = g A 2^(−p) (1−t)^q
      × exp[(q−pk)t + (q−pk²)t²/2]
after that: a = 0
S5(u) = 10u³−15u⁴+6u⁵
```

- At `ds`, value, first and second derivative match `g a0`; the gain ramp also joins C2. `q>3` makes the **inner endpoint** C3; it does not make the entire splice C3. `k=g=1, q=p` recovers the original profile exactly.
- Require `ds+k w0<R` for a quiet center. The outermost `b/4` is unchanged. Tail support grows with `k`, but visible transition width need not grow proportionally: matching the entry derivatives constrains the tail. Neither `g` nor `q` is a measured physical surface curvature.
- The radial/tangential Jacobian eigenvalues are `1+a′(d)` and `1−a/r`. A sign change can invert source detail. Test supported parameters for additional folds; smoothness and gradient-potential integrability do not establish a valid refracting solid or Apple fidelity.

Foreground color is a separate control. Independent RGB power responses can fit one pose yet reverse warm hues when moved over a different part of the source. A shared monotone scalar response `C=(f(BR),f(BG),f(BB))` preserves channel ordering and neutral grays, with a white plateau if `f(u)=1` above a threshold. One compact approximation uses monotone PCHIP through input knots `t[0,.3,.55,.8,1]` followed by `1`, with increasing output values from a configurable floor to `1`, followed by `1`. Positive normalized output increments enforce ordering. This interpolation is C1, not generally C2. It may fit the measured icon less closely than independent channels; retain that accuracy/generalization tradeoff. These are empirical encoded-RGB shading controls, not identified Apple lighting equations.

A highlight-reduction alternative retains the existing response and applies `f(c)=c−k u³(1−c)`, `u=clamp((c−t)/(1−t),0,1)`, independently to each component. For `0≤k≤4` it is monotone: within the shoulder, `f′=1−k u²(3−4u)≥1−k/4`. It leaves values below `t` unchanged, joins them C2, preserves the black/white endpoints and introduces no white plateau. Increasing `k` delays near-white output. This operator preserves existing channel ordering; it does not repair hue inversions already produced by a preceding independent-channel shader.

Express blur and lighting lengths in the same geometry units as the element, then convert at rasterization. For these circular controls, `D=min(width,height)` and `σ=ratio×D`. The selected playback-study calibration uses width factor 4.5, gain 1, glass post-blur `1.5D/490`, dark saturation 0.2, highlight taper 0.5 and final icon-color blur `3D/490`, retaining the pass04 independent-channel response. These are visual-study choices, not Apple constants or native-app defaults. Normalize every lighting length too, including thin-rim terms. Source-space filtering, filtering after refraction/shading, and filtering only the calculated icon-color field are distinct operations. Icon-color blur is the last color operation before sharp coverage/compositing; do not blur the symbol silhouette or silently exchange filtering stages.

### Tangential curvature and smooth crowns

A scalar normal offset cannot explain all two-dimensional distortion. On the toolbar's straight horizontal edge, `n=(0,±1)`: `F=p−a(d)n` changes Y while leaving X unchanged. A displaced strip is not necessarily a widened strip. The following alternatives expose that restriction rather than increasing one depth gain.

**Tensor field.** Let `t=(-ny,nx)`, `bT=r×tangentBand`, `v=1−clamp(d/bT,0,1)`, and `B=v⁴(5−4v)`. With the same spline `a(d)`:

```text
F(p) = (I−diag(cx,cy))p − a(d)n − τB(d)(p·t)t
```

`0≤τ≤0.8`, `0.05≤tangentBand≤1`, `cx∈[0,0.3]`, `cy∈[0,0.65]`. On a straight horizontal edge, `∂Fx/∂x=1−cx−τB`; its reciprocal is displayed horizontal scale. The central scale is retained while tangential stretch grows toward the silhouette. `B`, `B′` and `B″` vanish at its inner join. Normals/tangents rotate around curved ends, so the deformation follows the outline instead of fixed screen axes. A circle has `p·t=0`, providing a useful zero-effect control. This is a designed image field, not a recovered solid or a nonfolding guarantee.

**Gradient-potential control.** A competing, more restrictive hypothesis uses `Ψ=V(d)+K(d)x²/2+cy y²/2`, with `V′=−a`, `K=cx+τB` and `F=p−∇Ψ`:

```text
F = ((1−K)x, (1−cy)y) + [−a + K′x²/2]n
K′ = −20τu(1−u)³/bT, u=clamp(d/bT,0,1)
```

The extra normal term is required by this potential; dropping it would break equality of mixed derivatives. This control tests integrability, not exact thick-lens optics. It did not improve the toolbar fit. The better tensor fit cannot therefore be relabeled as a verified phase surface.

**Smooth height surface.** A continuous crown removes the old cap's explicit inner shoulder join. Define the footprint field `T` and height:

```text
circle / oval: T = |x/hx|ⁿ + |y/hy|ⁿ
measured oval: T = u² + [v/Q(u)]²
capsule:       T = [max(|x|−(hx−r),0)² + y²]/r²
s = 1−Tᵐ
A = cx(x/hx)²+cy(y/hy)²; W = (1−k)+kT; C = 1−AW
z(p) = H sᵝ C                    // inside T<1
N ∝ ( H[βmTᵐ⁻¹Tx C − s Cx],
      H[βmTᵐ⁻¹Ty C − s Cy], s¹⁻ᵝ )
```

Domains: `H=min(h)×height`, `height∈[0.01,1.8]`, `m∈[1,40]`, `β∈[0.2,0.95]`; `n=2` for the circle, or the fitted polynomial field for the measured oval. Earlier broad-crown trials use `cx=cy=c/2`, `c∈[0,0.7]`, `k=0`. Independent X/Y crowns and `k∈[0,1]` vary how cross-sections change with depth; require `C>0`. Increasing `m` concentrates curvature near the boundary. At `m=1, β=1/2, cx=cy=0`, a circle/ellipse has an ellipsoidal meridian. Height, central curvature and rim curvature are distinct parameters.

The capsule formula uses its long-axis frame, `hx≥hy=r`; rotate coordinates for a vertical capsule. It is not a general rounded-rectangle height field. Those additional footprints need their own validated implicit geometry before reuse.

The exponential alternative substitutes `s=[1−exp(−m(1−T))]/[1−exp(−m)]`, with `−ds/dT=m exp(−m(1−T))/[1−exp(−m)]`. It provides another smooth crown without an inner join; use `expm1` near small arguments. Both families use the same two-interface Snell construction, `G=min(h)×gap`, `gap∈[0,1.5]` and reference IOR 1.33.

The outline does not identify `H,m,β,c` uniquely. For a height graph, principal curvatures are eigenvalues of `I⁻¹II`, where `I=Id+∇z⊗∇z` and `II=Hess(z)/sqrt(1+|∇z|²)` (sign depends on chosen orientation). Height alone is not a curvature radius. The current checks establish analytic-normal/scale/mirror limits; full path-validity and temporal calibration remain open.

**Independent transverse curvature.** A preferred longitudinal section need not produce the desired optical onset across Y. For a separable footprint `T=U(x)+V(x,y)`, set `S=1−U`, `W=V/S` inside the body and use:

```text
T* = U + S Wᵖ, p≥1
T*x = Ux[1+(p−1)Wᵖ] + pWᵖ⁻¹Vx
T*y = pWᵖ⁻¹Vy
```

Use `T*` consistently in the height, crown and analytic normal. At `p=1` it recovers the original field. It preserves the silhouette (`W=1`) and longitudinal Y=0 section (`W=0`), while larger `p` reduces central transverse curvature. The implementation's `cross_section_power` controls `p`; exterior occupancy retains the original field to avoid singular perspective extrapolation at `U=1`. This is a geometric hypothesis for a quiet interior and strong near-rim refraction, not a measured Apple parameter. Preserve coverage and validate convexity/path behavior before using a new combination.

### Cap joins and closed-body tracing

The capsule's `q=max(|x|−a,0)`, `a=hx−r`, gives a C1 surface with a jump in second derivatives at the straight/circular join. A continuous normal does not ensure continuous curvature or optical magnification. The optional geometric transition uses `s=|x|−a`, width `0<w<a`, and:

```text
u = clamp((s/w+1)/2, 0, 1)
q = 0                     for s≤−w
q = w(2u³−u⁴)            for −w<s<w
q = s                     for s≥w
q′ = 3u²−2u³             within the transition
```

Value, first and second derivative match both adjoining pieces. Use this `q` in the footprint and its derivatives; `cap_join=w/r` controls the width. The silhouette changes slightly, so coverage must follow the modified footprint too. This is not a promise of C2 smoothness for arbitrary footprints or a reconstruction of Apple's shape.

The offline [closed-body comparator](experiments/reconstruction/solid.py) replaces the flat exit with `−rear×z(x,y)≤Z≤z(x,y)`, inside `T≤1`. Front and rear normals come from the same height gradient. Changing `cx,cy,k` therefore changes the actual body and all traced interfaces, rather than directly widening a sampled image.

For unit incident direction `I`, an opposing unit normal `N`, index ratio `η=ni/nt`, `ci=−I·N` and `K=1−η²(1−ci²)`:

```text
reflected   = I + 2ci N
transmitted = ηI + (ηci−sqrt(K))N       when K≥0
```

Unpolarized dielectric Fresnel weights split each path. At total internal reflection (`K<0`) there is no transmitted direction; its entire weight continues along reflection. Subsequent transmissions hit the background plane or the environment. A finite path series reports remaining untraced energy; it must not silently become fabricated background detail. A local first-path Jacobian does not describe this entire multi-path image.

- Intersection bisection requires a convex supported body. The general parameter family is **not certified convex**; new combinations need geometry/path checks before interpretation. Body continuity alone is insufficient.
- The [material fit](experiments/reconstruction/solid_material.py) still uses encoded-RGB nuisance lighting, including signed coefficients. Correct Snell paths do not make that fitted lighting radiometrically physical.
- [Independent controls](experiments/reconstruction/verify-solid.py) compare intersections with an analytic ellipsoid, check Snell/Fresnel, explicit TIR, energy accounting, scale/mirror limits, analytic cross-coupled normals and C2 join derivatives. Example horizontal stretch emerges from geometry, but these checks do not calibrate it to Apple.
- This comparator is separate from the interactive lab's existing field/flat-exit models. No Apple-shader or live-lab parity claim follows from the offline experiment.

### Reflection versus color separation

For a view direction along +Z, the reflected lookup direction is `R=(2NzNx,2NzNy,2Nz²−1)`. The surface comparator fits a low-order environment evaluated at **that direction**, weighted by unpolarized dielectric Fresnel reflectance. It replaces independently positioned Gaussian highlight bands; changing the surface changes reflection and transmission together. The first-order RGB environment uses `E(R)=e0+exRx+eyRy+ezRz`; the quadratic alternative adds `RxRy,RxRz,RyRz,Rx²−Ry²,3Rz²−1` per channel. Neither paints a particular edge. The environment is inferred nuisance lighting, not a measured Apple light rig; signed polynomial coefficients do not enforce a nonnegative physical environment. Encoded-RGB fitting is not energy-conserving radiometry.

[Dispersion](https://www.pbr-book.org/4ed/Reflection_Models/Specular_Reflection_and_Transmission) means wavelength-dependent refraction; a colored rim alone does not identify it. Two explicit controls are tested:

- **Snell RGB proxy:** `n(λ)=1.33+B(λ⁻²−0.55⁻²)`, `λ={0.65,0.55,0.45} μm`, `B∈[0,0.025] μm²`. Trace one ray per channel and evaluate wavelength-dependent entry Fresnel factors. Transmission is scaled relative to the reference wavelength by `(1−Fλ)/(1−FG)`; reflected-environment terms use `Fλ`. At `B=0` both recover the common-IOR comparator. All indices stay below `√2`, preserving its flat-exit TIR bound. This is not spectral-to-RGB integration or complete internal/exit-reflection transport.
- **Image-map sensitivity:** `Fλ=p+[1+D(λ⁻²−0.55⁻²)](F0−p)`, `D∈[0,0.2] μm²`. This tests channel separation on the better-fitting flexible map; it is not equivalent to changing a physical IOR. `D=0` recovers the common lookup exactly.

[curvature.py](experiments/reconstruction/curvature.py) implements these hypotheses. [verify.py](experiments/reconstruction/verify.py) checks finite-differenced height normals, scale/mirror/Snell/Fresnel limits, potential mixed derivatives, tangential scale and integrated material regression. The 30 older fixtures still cover only the original field/cap. Image scores and color evidence belong to the [comparison study](reconstruction.md), not mathematical proof of Apple's renderer.

## Sampling, coverage and color

- Optical coordinates extend to the boundary. Apply antialiasing to final **coverage**, not by multiplying displacement by a several-pixel edge ramp. The lab uses a one-framebuffer-pixel coverage band; filtering can still soften the first visible pixel.
- Capture enough backdrop beyond the body: a conservative field padding is `b×edge×power + core×power×max|p| + blurFootprint`. Cap padding must cover its validated ray offsets. Clamping to the capture boundary can mimic an inactive edge; repeating the last texel is not extra optical information.
- Scale all geometric lengths, backdrop coordinates and source padding together. Coverage width depends on raster density. The optical band has no fixed two-pixel floor. Shadows/highlight widths are separate artistic lengths, not optical invariants.
- The lab interpolates and blurs **encoded sRGB channels**, then tints, illuminates and applies its tone curve. This is an image-space approximation, not radiometric scattering. Physical-light processing requires decoding, linear-light filtering and re-encoding, including alpha/compositing conventions; [W3C defines those conversions](https://www.w3.org/TR/css-color-4/#color-conversion-code). [Apple documents default light-linear filtering in Core Image](https://developer.apple.com/library/archive/documentation/GraphicsImaging/Conceptual/CoreImaging/ci_performance/ci_performance.html#//apple_ref/doc/uid/TP30001185-CH202-SW4). That supports testing linear-light alternatives, but does not identify Liquid Glass's private pipeline.
- Nine blur taps have nonnegative weights summing to one: center 1/4, four axial taps 1/8 each, four diagonals 1/16 each. Radius is an offset, not Gaussian sigma. At large compression, production sampling needs source-footprint filtering; nine taps do not guarantee alias-free reconstruction.

For an encoded channel `x∈[0,1]`, trim `τ∈[0,0.5]` and exponent `γ∈[1,2]`:

```text
light foreground: f(x) = x − τx^γ
 dark foreground: g(x) = 1−f(1−x) = x + τ(1−x)^γ
f′(x) = 1−τγx^(γ−1) ≥ 0
```

Endpoints are `[0,1−τ]` and `[τ,1]`; darker inputs cannot wrap above brighter ones. This is a channel bound, not a physical-luminance curve or a guarantee of a WCAG contrast ratio. Applying it after synthetic illumination preserves the chosen material endpoint. Foreground ink is outside the operation.

## Pressure and dynamics

Normal finger contact and a side-wall reaction act along different axes. Strictly, pressure is scalar; the boundary traction (force per area) has direction, and a stress tensor describes the general internal state. The lab models prescribed deformation, not a stress solution. A homogeneous, approximately incompressible deformation has principal stretches with product one; it does not require one specific redistribution law.

```text
sx = exp(q − z/2)
sy = exp(−q − z/2 − f p)
sz = exp(z + f p)
sx sy sz = 1
```

`q` is in-plane strain including the wall contribution, `z` is normal strain (negative while held), `p` is positive wall-compression magnitude, and `f∈[0,1]` apportions wall compensation into depth. During recovery `p` is a signed spring state. At fixed `z`, increasing `p` increases depth when `f>0`; simultaneous normal pressure can still leave total depth below rest. This answers the pressure-direction question without assuming Apple uses a volume law.

- This determinant-one construction conserves an affine volume **only if the entire original body is transformed**. Rebuilding corner radius and the height function is not that transformation. The lab's scale product is a proxy, not exact integrated volume.
- For exact conservation of a reconstructed cap, compute `V=H∫D fC dA` and choose `H=Vtarget/∫D fC dA`. The lab does not enforce that integral. An exact-volume model must also resolve constraints when maximum depth is reached.
- Pin the contacting edge by setting center to `wallPosition ± deformedHalfWidth`; do not compress about the old center. Changing footprint, optical band and depth must use the same strain state.
- Held normal pressure uses a critically damped approach; release retains velocity and uses configurable damping. Critical damping prevents oscillatory eigenmodes, not every overshoot from arbitrary initial velocity. A strict monotonic-contact rule requires a separate velocity/force constraint; abruptly clamping velocity sacrifices derivative continuity. This remains an interaction design tradeoff, not an automatic property of the spring. A retargeted spring does not reset its velocity. Constant area, constant perimeter and constant volume are different constraints; none is interchangeable.

For each spring coordinate with mass `m>0`, stiffness `k>0`, damping `c≥0`, constant target `x*` over a step, let `y=x−x*`, `a=c/(2m)` and `w0²=k/m`:

```text
y″ + 2a y′ + w0² y = 0
underdamped: w=sqrt(w0²−a²)
y(t)=exp(−at)[ y(0) cos(wt) + (v(0)+a y(0)) sin(wt)/w ]
critical: y(t)=exp(−at)[ y(0) + (v(0)+a y(0))t ]
overdamped: y(t)=A exp(r1 t)+B exp(r2 t)
r1,2=−a±sqrt(a²−w0²); A=(v(0)−r2 y(0))/(r1−r2); B=y(0)−A
```

The reference returns both position and the derivative of these expressions. Fixed-target energy `½mv²+½ky²` cannot grow. Exact steps compose independently of frame partition; changing targets/forces and contact constraints still require event-time sampling. The lab retains bounded substeps for those inputs, with analytic spring updates. Its braking impulse, strain clamps and frame-delta cap are interaction approximations, not a solved fluid/stress system. Test pauses, interruptions and boundary ownership separately.

## Verification and remaining gates

Run `node experiments/equations-test.cjs` without browser dependencies, with no browser required. Seeded tests cover limits, independently differenced Jacobians, both ray interfaces, pressure and exact spring evolution. Random coverage is evidence, not proof of untested domains. Experimental browser-oracle work remains on the preserved lab branch; it is not part of this publication. GPU/software agreement alone could reproduce the same bug.

Required before native adoption:

1. Freeze supported parameter domains; reject or clearly distinguish folding/unsupported cases. Retain a shared source-coordinate and color-space contract across backends.
2. Port reference fixtures, scale/mirror tests, endpoint and interruption cases to both native adapters. Measure device frame times separately from functional correctness.
3. Calibrate on exposed-source Apple scenes with known state/settings, then hold out independent scenes. The [HD reconstruction study](reconstruction.md) now measures three conditional still-image fits, with explicit source/foreground exclusions. Independent-scene and temporal validation remain open.
4. Keep the measured evidence, artistic choices and mathematical guarantees separate. Neither the finite-rim profile nor the cap has established Apple rendering parity.

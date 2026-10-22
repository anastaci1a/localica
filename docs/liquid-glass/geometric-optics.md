# Geometric optics

[Recreations](reconstruction.md) · [Historical reproduction recipes](recreation-models.md) · [Guide](README.md)

New refraction studies use explicit optical surfaces. Their intersections, normals, ray paths and section views describe the same geometry. Historical displacement maps and filtered image fits remain reproducible controls; they are not the new optical implementation.

## Scope and conventions

- Lengths use the minor half-extent `L`; x points right, y down, +z toward the camera. The first comparator uses an orthographic camera, front plane `z=0` and background plane `z=-D`.
- Geometry determines refraction. Parameters describe shape, thickness, camera, index or scene placement. No independent normal adjustment, fitted screen displacement or post-trace residual warp is allowed.
- Pixel integration and interpolation are numerical sampling. Optical scattering/blur, spectral dispersion, reflection, lighting, color, foregrounds and shadows are separate material/compositing stages. They cannot conceal a failed geometric comparison.
- The first tracer follows the primary transmitted branch. It does not implement finite-camera entry, arbitrary side intersections, reflected radiance or a complete dielectric energy solution. Those are separate claims requiring corresponding geometry/path tests.
- Source bytes, registration, masks, reference crops and accepted historical baselines stay fixed. A new source or sampling convention is declared separately, never counted as an optical improvement.

## Shared height surface

The initial closed solid has a flat front, a convex footprint and back `z=-h(x,y)`. Its height is concave and nonnegative. Over a circle, `rho=sqrt(x²+y²)`. Define:

```text
u = max((rho-core)/(1-core), 0)
h = edge + depth (1-u^p),  rho <= 1
```

`0<=core<1`, `depth>0`, `edge>=0`, `p>=3`. The plateau and bevel meet with continuous first and second derivatives. Height decreases toward the edge and its radial second derivative is nonpositive. A finite cut edge is part of this hypothesis; it does not establish Apple's surface shape.

The side closes the solid over the footprint boundary, between `z=-edge` and0. A ray exactly on a crease has no unique interface normal. The tracer reports that path unresolved, with NaN source coordinates, instead of choosing an image sample. Clear misses use their ordinary unobstructed ray.

For a continuous pill, the same height profile can use the convex coordinate `rho=sqrt(T)` from the [continuous footprint](recreation-models.md#continuous-pill-footprint-candidate). This coordinate is neither a homogeneous norm nor Euclidean distance. Its actual derivatives enter `grad h=h'(rho) grad rho`. A decreasing concave profile composed with this convex coordinate gives a concave height. Footprint shape and depth curvature remain meaningful geometric parameters; optical calculations are shared.

## Refraction and complete primary paths

Given unit incident direction `i` and unit normal `N` pointing toward the incident medium:

```text
eta = n_from / n_to
c = -dot(i,N)
k = 1-eta²(1-c²)
t = eta i + (eta c-sqrt(k)) N
```

For `k<0` there is total internal reflection; `k=0` is the critical grazing boundary. Neither supplies a transmitted pixel in this diagnostic. Snell refraction and Fresnel energy transport are distinct parts of a dielectric model. [PBRT's derivation and implementation](https://pbr-book.org/4ed/Reflection_Models/Specular_Reflection_and_Transmission) documents both, with a different incident-vector/index-ratio convention.

Orthographic entry through the flat front is normal incidence. The internal ray reaches `(x,y,-h)` directly. Back outward normal is `normalize(-h_x,-h_y,-1)`, derived from the actual height. The ray then intersects the background plane:

```text
travel = (D-h) / (-t_z)
background_hit = (x,y,-h) + travel t
```

For exterior index1 and interior index `n>1`, a global slope bound `M<1/sqrt(n²-1)` guarantees subcritical transmission. Require `D>max(h)` and a downward exit. Concavity certifies no back reentry: along the exiting ray, `g(s)=z(s)+h(x(s),y(s))` is concave and starts with a negative derivative. It stays below the back; upon leaving the footprint it is below the side. This proof applies to the restricted orthographic geometry, not arbitrary cameras or solids.

**Primary path completeness is not full radiance completeness.** Reflected/environment branches and Fresnel weights remain unimplemented here. Unknown background samples, unresolved boundaries and invalid branches must be reported, never hidden by clamping coordinates or dropping scored pixels.

## Verification and reproduction

[Shared profile, Snell and restricted tracer](experiments/reconstruction/geometric_optics.py) · [Geometry controls](experiments/reconstruction/test_geometric_optics.py)

```sh
cd docs/liquid-glass/experiments/reconstruction
python -m unittest test_geometric_optics.py
```

The controls cover height/gradient consistency, section identity, Snell reciprocity, unit-index identity, symmetry, clear misses, exact crease rejection, critical/TIR rejection, and the C2 concave profile. Declared floating-point tolerances test numerical implementation; they do not relax reference-image acceptance.

Each experiment declares the visible defect, physical parameters, frozen nuisance stages, references, wall/evaluation budgets, checkpoint and stop condition. Review after at most three substantive proposals, including rejects. Visible fidelity and local regressions lead acceptance; an imperceptible aggregate gain is insufficient. Mathematical correctness, visual fidelity, cross-control transfer and device speed remain separate assessments.

## Circular play control: first geometric comparison

Reference: [Apple's clear playback control,219 at3:10](https://developer.apple.com/videos/play/wwdc2025/219/?time=190), using the frozen registered photograph from3:00. Ana identified the abrupt shoulder and unwanted reversed outer strip in the historical fit. Exact local windows remain bottom-left[690,570,910,810], top-left[690,290,910,530], upper-right[1025,320,1245,560].

**Useful negative result; no accepted fidelity improvement.** Three explicit bevel surfaces were evaluated, with4×4 native pixel integration and no optical source/output blur. The reversed strip is absent, but visible dune crossings under-refract and their curves remain inaccurate. These tests do not rule out a different convex back, a curved front or a finite camera.

| Surface | Core / depth / power | Edge displacement / L | Sampled minimum radial map derivative |
| --- | --- | ---: | ---: |
| Narrow bevel | .6 / .02 /3 | .15129 | .21924 |
| Broad bevel | .3 / .04 /3 | .17336 | 0.47862 |
| Geometrically limited depth | .6 / 0.02397512 /3 | .18204 | 0.05069 |

The third depth came from12 bounded geometry-only bisections with a sampled nonfold margin, without target pixels or image optimization. Sampled derivatives are diagnostics, not interval proofs. By comparison, the old empirical map folds from about radius.91327L outward. Reconstructing that map alone would preserve its reversal rather than fix the visible complaint.

![Apple, historical filtered fit, and unblurred geometric diagnostic in fixed rim crops](assets/geometric-playback-g01-crops.png)

Columns: Apple; historical filtered fit; new geometric depth-limit diagnostic. Bottom-left/top-left/upper-right RGB errors change from8.083/8.448/7.193 to14.493/14.416/11.483. Color, missing lighting and optical-filter differences also affect these errors; they are not isolated geometric-error measures. None is counted as a successful reconstruction.

The new body uses only the frozen historical constant RGB gain/bias, without its spatial lighting field. Existing calculated icon color and coverage are frozen separately, including their historical icon-only filtering. This isolates lens work and does not establish a new unified icon material. The current most-polished historical image remains unchanged.

![Actual back section and traced rays with equal x and z scale](assets/geometric-playback-g01-rays.png)

This section and these rays use the same height function and parameters as the rendered refraction. Exact rim-crease rays are unresolved; native4×4 circle samples have no exact crease hits. The shape certificate and all actual source queries were checked. No Fresnel radiance, finite-camera, spectral or device-speed claim is made.

[Exact parameters, local measurements and limits](experiments/reconstruction/studies/geometric-playback/g01.json) · [Geometry invariants](experiments/reconstruction/studies/geometric-playback/g01-invariants.json) · [Partial public-wrapper identity control](experiments/reconstruction/studies/geometric-playback/g01-portable-control.json)

Reproduce one diagnostic from the same frozen private inputs and historical cached material:

```sh
cd docs/liquid-glass/experiments/reconstruction
python geometric_playback.py INPUTS \
  studies/playback/review-round/pass-03.json HISTORICAL_M.npy \
  studies/geometric-playback/nonfold-depth-limit.json NEW_OUTPUT
```

The public wrapper reproduced an8160-pixel strip of a saved candidate exactly, without a second full-control render. All3 original candidates and their failed visual comparisons remain recorded. The bounded image study used3 renders,16 small geometry evaluations,0 image fits, and6min18s of its20-minute budget; subsequent code/packaging checks introduced no candidate search.

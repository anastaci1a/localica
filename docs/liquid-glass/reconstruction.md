# Apple reference reconstructions

Three image comparisons using Apple's original 1920 × 1080 session downloads. **Apple is on the left; our reconstruction is on the right.** The experiment fits the [portable optical models](model.md) to the recorded pixels, instead of judging them only against a synthetic grid.

[Clear control](#clear-playback-control) · [Blurred toolbar](#blurred-toolbar) · [Slider](#active-slider) · [Measurements](#measurements) · [Method](#method) · [Reproduce](#reproduce) · [Field guide](README.md)

## What is being compared

- The right-hand glass material is rendered numerically from a reconstructed **uncovered background**. It is not a painted imitation or an image-generation result. Symbols are approximate vector redraws.
- The exterior—including Apple's shadow outside the silhouette—is retained as context. It is **not** a reconstructed shadow. No exterior pixels or foreground-symbol regions contribute to the scores. The fractional one-pixel silhouette coverage is also excluded.
- These are offline material fits. Optical equations match the lab's numerical reference; Gaussian blur and fitted lighting/color are an experimental material, **not the live lab's current shader**. The comparison does not claim that the hosted lab or Android app now produces these images.
- The closest fits contain local reversals of the background mapping near the rim. [Constrained comparisons](#local-folds-and-the-constrained-alternative) show the cost of preventing those folds. The fitted values are not proposed production defaults.
- The first two references are Apple-produced design demonstrations; their underlying rendering pipeline is not established. The slider is from Apple's UIKit application demonstration. None establishes one universal material for every iOS control.

## Clear playback control

Source: Apple, [Meet Liquid Glass, 3:10](https://developer.apple.com/videos/play/wwdc2025/219/?time=190). The glass circle is approximately 490 pixels across. Its uncovered photograph appears at 3:00, allowing background recovery without inventing the dunes underneath it.

![Apple clear playback control on the left and fitted cap reconstruction on the right](assets/match-media.png)

- **Improved:** the inward displacement near the rim, comparatively quiet center, dark upper edge and bright lower edge.
- **Still different:** the reconstruction softens sand texture too much; the sharp dune/rim intersections and narrow reflections remain misplaced. The redrawn play symbol has a flat fill, unlike Apple's graded fill; symbols are excluded from the material score.
- Glass-only RGB RMSE falls from **17.70 to 10.17/255** versus the starting field optics with separately fitted material. A no-refraction material scores **13.86**, so simply adjusting color and blur is insufficient here.
- Fitted cap: height/half-height **0.396**, backdrop gap/half-height **0.255**, rim-band/half-height **0.079**, index **1.33**. Blur σ **3.5 native pixels**. These are fit parameters, not measured Apple dimensions or refractive index.

<details>
<summary>Full-resolution screenshots, side by side</summary>

[![Full HD Apple playback scene and reconstructed material in the same context](assets/match-media-full.png)](assets/match-media-full.png)

Each half retains 1920 × 1080 source pixels; open the image for native resolution. Outside-glass context is shared, not independently rendered.

</details>

<details>
<summary>Starting model, recovered background and error map</summary>

![Apple playback control compared with the starting field optics](assets/match-media-starting.png)

![Apple playback control compared with the registered uncovered photograph](assets/match-media-input.png)

![Glass-only error and spatial scoring mask for the playback control](assets/match-media-error.png)

Error image: mean absolute RGB difference, fixed scale 0–48/255, dark to bright. Mask: blue fitting region, gold withheld blocks; dark regions are excluded. Only a deterministic subset of the blue region is used during fitting.

</details>

## Blurred toolbar

Source: Apple, [Meet Liquid Glass, 11:50](https://developer.apple.com/videos/play/wwdc2025/219/?time=710). The capsule is approximately 784 × 340 pixels. Text later exposed at 12:00 provides its reconstructed background.

![Apple blurred toolbar on the left and fitted cap reconstruction on the right](assets/match-regular.png)

- **Improved:** the curved text displacement and broad scattering, without assuming the clear control's settings also describe a blurred surface.
- **Still different:** Apple's rim is broader and more irregular; the top reflected text detail is missing. Our left glyph is an approximation and is unscored. Source registration is less reliable than in the dune scene, so some residual error belongs to the input reconstruction.
- Glass-only RGB RMSE falls from **10.72 to 7.24/255**. The no-refraction baseline scores **10.09**. The remaining rim RMSE is **11.11**, substantially worse than the interior's **4.29**.
- Fitted cap: height/half-height **0.678**, gap/half-height **0.807**, band/half-height **0.472**, index **1.33**. Blur σ **17.5 pixels**. The fit requires a much broader band and stronger blur than the clear circle.

<details>
<summary>Full-resolution screenshots, side by side</summary>

[![Full HD Apple toolbar scene and reconstructed material in the same context](assets/match-regular-full.png)](assets/match-regular-full.png)

Each half retains 1920 × 1080 source pixels. The exterior shadow is Apple's retained context; it is not evidence of a successful shadow model.

</details>

<details>
<summary>Starting model, estimated background and error map</summary>

![Apple toolbar compared with the starting field optics](assets/match-regular-starting.png)

![Apple toolbar compared with the reconstructed uncovered text](assets/match-regular-input.png)

![Glass-only error and spatial scoring mask for the toolbar](assets/match-regular-error.png)

The later frame's own glass contaminates part of the warped source. That area is replaced by the surrounding neutral field, RGB 219/255. It is an explicit flat-background assumption, not a recovered measurement.

</details>

## Active slider

Source: Apple, [Build a UIKit app with the new design, 18:12](https://developer.apple.com/videos/play/wwdc2025/284/?time=1092). The thumb is approximately 237 × 113 native pixels; the enlarged presentation below does not add source detail.

![Apple active slider on the left and fitted boundary-field reconstruction on the right](assets/match-slider.png)

- **Improved:** the track bending into the left cap and the clear center. The fitted boundary field has a slightly lower training loss than the cap model for this case.
- **Still different:** Apple's upper rim has a darker colored reflection and a more defined blue fold. The reconstruction's rim remains too pale. The blue exterior halo is retained context, not a newly rendered effect.
- Glass-only RGB RMSE falls from **13.44 to 12.01/255**; withheld-block error remains **13.85**. This is the weakest improvement of the three, not a completed match.
- Fitted field: edge gain **1.573**, band/half-height **0.329**, center scale adjustment **0.00037**, rim coefficient **0.942**. Blur σ **1 pixel**. The hidden blue endpoint is estimated at x=719; geometry and endpoint uncertainty limit what this fit can establish.

<details>
<summary>Full-resolution screenshots, side by side</summary>

[![Full HD Apple UIKit slider demonstration and reconstructed material](assets/match-slider-full.png)](assets/match-slider-full.png)

Each half retains 1920 × 1080 source pixels. The surrounding UI and exterior halo are shared context.

</details>

<details>
<summary>Starting model, estimated track and error map</summary>

![Apple slider compared with the starting field optics](assets/match-slider-starting.png)

![Apple slider compared with the estimated uncovered track](assets/match-slider-input.png)

![Glass-only error and spatial scoring mask for the slider](assets/match-slider-error.png)

Track thickness and colors come from uncovered sections. The covered endpoint is inferred from the near-identity center; it is not independently observed.

</details>

## Measurements

RGB errors below use encoded 0–255 channels, **only inside the scored glass mask**. Lower RMSE is better; higher SSIM is better. These are not percentages of fidelity. The exterior photograph, captions and symbols cannot inflate the scores.

| Scene | No refraction RMSE | Starting optics RMSE | Fitted RMSE | Withheld blocks RMSE | Fitted rim / interior RMSE | Fitted SSIM |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Clear playback control | 13.86 | 17.70 | **10.17** | 10.62 | 11.96 / 8.03 | 0.9580 |
| Blurred toolbar | 10.09 | 10.72 | **7.24** | 7.05 | 11.11 / 4.29 | 0.9736 |
| Active slider | 14.02 | 13.44 | **12.01** | 13.85 | 18.95 / 8.36 | 0.9488 |

- Every baseline gets the **same material-fitting freedom**. “Starting optics” fixes the lab's field defaults—edge 0.4, band 0.55, core 0.012, rim 0.35—but refits blur, lighting and color. It is not a screenshot of the unmodified lab.
- “Rim” means inward distance less than 20% of the shorter half-extent. It is a common measurement region, not each model's fitted band. The first 0.75 pixel inside the silhouette is excluded to avoid coverage ambiguity.
- The models are selected by weighted training loss. The gold 32-pixel blocks are omitted from parameter fitting, but are **not an independent test scene**. They share the same video frame, source reconstruction and manually chosen geometry. Model development also inspected these results.
- SSIM uses the mean of encoded RGB channels, an 11 × 11 Gaussian window and a mask eroded by that window. It is an implementation-specific structural diagnostic, not a perceptual guarantee. Full RGB MAE, gradient error, counts and all competing fits are in [results.json](experiments/reconstruction/results.json).
- [Sensitivity probes](experiments/reconstruction/sensitivity.json) shift the reconstructed source by ±1 pixel and inspect sampled map derivatives. They expose registration sensitivity; they are not confidence intervals or global invertibility proofs.

### Local folds and the constrained alternative

A low image error alone permits extreme optical parameters. Numerical Jacobians reveal negative determinants in **18.9%**, **54.4%** and **28.0%** of the sampled scored pixels for the clear circle, toolbar and slider respectively. Those regions reverse local orientation. Refraction can produce such folds, but matching a still does not establish that Apple's control uses the same mechanism or that our result will remain stable in motion.

An additional field fit enforces both [sufficient local nonfolding bounds](model.md#jacobian-and-folds), with a 0.02 margin at unit optical power. It keeps the same source, scoring masks and material freedom:

| Scene | Unrestricted fit RMSE | Constrained field RMSE | Constrained withheld RMSE |
| --- | ---: | ---: | ---: |
| Clear playback control | 10.17 | 13.02 | 12.20 |
| Blurred toolbar | 7.24 | 9.63 | 9.71 |
| Active slider | 12.01 | 12.70 | 14.72 |

The constrained fit is closer than the starting optics in all three full-mask scores, but cannot reproduce the same boundary details as the unrestricted fits. It is certified only for the stated local orientation bounds at unit power, not globally one-to-one or safe under arbitrary pressure/depth multipliers.

<details>
<summary>Apple versus the constrained field, all three scenes</summary>

![Apple playback control and locally nonfolding field reconstruction](assets/match-media-bounded.png)

![Apple toolbar and locally nonfolding field reconstruction](assets/match-regular-bounded.png)

![Apple slider and locally nonfolding field reconstruction](assets/match-slider-bounded.png)

</details>

## Method

### Reference quality and background recovery

| Case | Target presentation time | Uncovered-source time | Recovery evidence |
| --- | --- | --- | --- |
| Clear circle | 189.989800 s | 180.013167 s | 71 SIFT/RANSAC inliers outside the glass; local exterior MAE 1.42/255. |
| Toolbar | 710.009300 s | 719.985933 s | 37 exterior inliers; local exterior MAE 8.60/255. Scroll/zoom and a replaced neutral region make this conditional evidence. |
| Slider | 1091.990900 s | Analytic track | Uncovered colors and 21-pixel round track; hidden endpoint inferred, not recovered. |

- Inputs are Apple's original HD downloads at 30000/1001 fps. No social-media repost, upscaled thumbnail or AI reconstruction is used as reference. Video compression and Apple presentation processing remain limitations.
- Frame indexes, actual timestamps, geometry, foreground exclusion boxes, affine transforms and scores are saved in [results.json](experiments/reconstruction/results.json). Download URLs and exact SHA256 hashes are in [sources.json](experiments/reconstruction/sources.json).
- Lens geometry is manually located at native resolution and held fixed during fitting. The source registration uses exterior features, not features already refracted inside the lens. An exterior MAE includes lighting/shadow differences; it is not pure registration error.

### Optical and material fitting

The [boundary-field and cap equations](model.md) are ported into [render.py](experiments/reconstruction/render.py). Their output is a source-coordinate lookup, followed by a separately fitted material:

`output_c = clamp(gain_c × blurredSource_c(lookup) + offset_c + directionalRimLight, 0, 1)`

- Optical parameters are shared across the whole element; there is no fitted per-pixel displacement image. Cap index is fixed at 1.33 to reduce the number of interchangeable parameters.
- Material: one Gaussian blur before refraction; three channel gains; three offsets; nine achromatic rim-light coefficients. The light basis has fixed 1.4-pixel, fitted-width and four-times-fitted-width Gaussian profiles, each with constant, horizontal-normal and vertical-normal terms. The narrow term allows a thin reflection without forcing the broad highlight to be equally narrow.
- This color operation is performed in **encoded RGB** to fit the screenshots. It is not a physical radiance computation, nor a claim that Apple uses this blur kernel or lighting basis. Gaussian blur and refraction do not generally commute.
- Fitting uses up to 6,500 deterministic training samples from a half-resolution raster, with 1.3× residual weighting in the rim. Final scores use full-resolution floating-point renders. Saved PNGs are quantized afterward.
- Differential evolution followed by bounded Powell optimization uses fixed seeds 222–226. Bounds: field edge 0–3, band 0.03–1, core 0–0.18, rim 0–1; cap height 0.01–1.2, gap 0–1.5, band 0.03–1; blur σ 0–35 pixels in 0.5-pixel steps; broad rim width 1–12 pixels. The constrained field instead fits an edge fraction of `(0.98−core)/max(D,T)` using the documented derivative bounds. Gain/lighting extremes receive penalties. Some fits reach search boundaries; none is certified globally optimal.
- Initial searches showed that the starting optics could underperform the no-refraction baseline. Widening the edge/band search and separating narrow and broad highlights improved the fits. All current baselines were refitted with the same final material model.

### What this changes about the model

- **Strong edge refraction is supported by these references.** A largely neutral center can coexist with a narrow, strongly displaced boundary. The clear-circle fit requires a substantially narrower band than the previous default.
- A single “depth” or zoom multiplier is insufficient. Band width, displacement profile, center scaling, blur and lighting need independent controls. The toolbar fit differs substantially from the clear control.
- Broad blur can conceal a wrong optical map. The no-refraction baseline and separate rim score are necessary controls; a high whole-image similarity score would be misleading.
- No common Apple geometry, shader, pressure law or universal preset has been identified. These are three static, conditional reconstructions. The remaining reflection and rim errors are visible in the figures, rather than concealed by a claim of parity.

## Reproduce

Use Python 3.11+ with NumPy, SciPy, OpenCV and Pillow. Recorded environment: NumPy 2.4.6, SciPy 1.17.1, OpenCV 5.0.0, Pillow 13.0.0, Linux DejaVu Sans. Font selection affects captions/redrawn figures, not numeric scores. Other library versions may produce different registrations or optimizer results.

1. Download the two original HD videos from [the source manifest](experiments/reconstruction/sources.json) into a private directory as `219-hd.mp4` and `284-hd.mp4`. Do not commit the videos. The script rejects mismatched hashes and non-HD decoded frames.
2. From the repository root, run:

```sh
python3 docs/liquid-glass/experiments/reconstruction/verify.py
python3 docs/liquid-glass/experiments/reconstruction/reproduce.py /private/videos /private/results
python3 docs/liquid-glass/experiments/reconstruction/publish.py /private/results docs/liquid-glass/assets
python3 docs/liquid-glass/experiments/reconstruction/sensitivity.py /private/results
```

The default replay uses the committed parameters. Add `--fit` to rerun optimization, or `--case media`, `regular` or `slider` for one scene. Use a fresh output directory to avoid loading parameters from an earlier experiment. `publish.py` also refreshes the committed results record; review changed scores before committing it.

Verification checks 30 numerical-reference mappings (maximum absolute error **1.42 × 10⁻¹⁴**), identity scores, a known 25.5/255 brightness error, spatial-shift detection, excluded-pixel invariance, disjoint fitting/withheld masks and 1,000 samples of the constrained parameterization. These validate the tools, **not an Apple match**.

## Attribution and next comparisons

Apple reference frames © Apple Inc., reproduced here as attributed excerpts for rendering analysis. They are not original Localica assets or licensed app artwork. Source videos and private user references are not bundled. The right-hand glass computation and vector redraws are Localica experiments; their retained exterior context is still Apple imagery.

Before transferring these fits into production, compare frozen parameters against separate high-resolution captures with known backgrounds, multiple element sizes and pressed/released states. Reconstruct exterior shadows separately, then evaluate temporal stability and physical-device rendering cost. None of those unmeasured properties follows from a good fit to these three stills.

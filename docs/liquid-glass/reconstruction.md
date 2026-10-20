# Apple reference recreations

**Apple is on the left; our rendering is on the right.** Five native-HD studies have frozen, dimensionless mathematical recipes and a deterministic replay command. The clear playback control is the **current most-polished model**. These offline results are separate from the live lab and Android app.

[Mathematical specification](recreation-models.md) · [Parameters](experiments/reconstruction/recipes.json) · [Scores](#scores) · [Reproduce](#reproduce) · [Material roles](#material-roles) · [Guide](README.md)

## Clear playback control

Apple, [Meet Liquid Glass, 3:10](https://developer.apple.com/videos/play/wwdc2025/219/?time=190); uncovered photograph at 3:00. The selected radial map has a quiet center, stronger rim refraction and slight filtering after refraction. The play symbol's calculated color is blurred internally before sharp triangular coverage is applied.

![Apple playback control and settled reconstruction](assets/match-media.png)

- **Most polished:** width multiplier 4.5; gain 1; source/glass/icon blur σ respectively `0.5/490`, `1.5/490`, `3/490` times the shorter dimension. Dark saturation 0.2; highlight taper 0.5. All lengths, including lighting, scale with the element.
- [Complete equations and operation order](recreation-models.md#playback-radial-map-and-smooth-inner-transition). This is an empirical refraction map, not an identified Apple solid.
- The reference-derived triangle silhouette is reused; its **color is independently calculated**. It has no stroked outline. The response is photograph-specific, not a universal contrast algorithm.
- Distant exterior is retained; the contact band is estimated. Exterior shadows are not scored.

<details>
<summary>Full-resolution playback comparison</summary>

[![Full HD playback comparison](assets/match-media-full.png)](assets/match-media-full.png)

Each half retains 1920 × 1080 pixels.

</details>

<details>
<summary>Motion preview — full-size GIF and video</summary>

![Settled playback model translated over the recovered photograph](assets/playback-settled.gif)

[Full-HD video](assets/playback-settled.mp4). GIF: 7,960,291 bytes, 1920 × 1080, six seconds, 15 fps; video: 30 fps. Translation uses the same recipe without exterior shadow. These are offline renders, not device-performance measurements or a matched Apple motion clip.

</details>

## Archive and plus toolbar

Apple, [Meet Liquid Glass, 11:50](https://developer.apple.com/videos/play/wwdc2025/219/?time=710); uncovered text at 12:00. A rounded-rectangle tensor map combines normal displacement with tangential compression, widening text near top/bottom without changing central registration.

![Apple archive/plus toolbar and frozen tensor reconstruction](assets/match-regular.png)

![Upper-rim comparison with Apple on the left](assets/match-regular-rim.png)

- [Equations](recreation-models.md#toolbar-normal-displacement-and-tangential-compression); exact coefficients in the `regular` recipe.
- Source blur and its distance-dependent blend are independent of optical strength. The selected σ is `11/340` times the shorter dimension, superseding the older eight-pixel search ceiling.
- Exact archive/plus reference symbols are retained on both sides and excluded from glass scores; their foreground rendering is **not** independently recreated.
- Remaining differences include compressed-text blur, the final `d` stroke's curvature and inner lighting. This image field is not a verified physical lens.

<details>
<summary>Full-resolution toolbar comparison</summary>

[![Full HD toolbar comparison](assets/match-regular-full.png)](assets/match-regular-full.png)

</details>

## Active slider

Apple, [Build a UIKit app with the new design, 18:12](https://developer.apple.com/videos/play/wwdc2025/284/?time=1092); resting page at 18:10.5. The thumb uses a measured polynomial-oval outline and channel-separated tensor refraction. **No Gaussian blur is applied.**

![Apple active slider and reconstructed lens](assets/match-slider.png)

- [Equations](recreation-models.md#active-slider-oval-outline-and-channel-separated-map); exact coefficients in the `slider` recipe. This retains the stronger empirical map; the closed-body trial remains experimental.
- The recovered track has a 20-pixel reference height; its hidden blue endpoint is estimated. The upper dark ridge constrains the outline, with 54 fitted and 27 withheld columns. Bottom/right symmetry remains assumed.
- Colored rim detail supports testing channel-separated lookups, but does not prove dispersion: lighting, adaptive processing and video chroma subsampling are confounds.
- Remaining work: blue-track stretching onset, narrow shadow and broader blue-halo color/extent. The exterior shown is retained/estimated context, **not a recreated shadow**.

<details>
<summary>Full-resolution slider comparison</summary>

[![Full HD slider comparison](assets/match-slider-full.png)](assets/match-slider-full.png)

</details>

## Light and dark surfaces

Apple, [legibility demonstration, 15:15–15:20](https://developer.apple.com/videos/play/wwdc2025/219/?time=915). Each appearance has an independent material recipe over the same shape. [Equations](recreation-models.md#uniform-light-and-dark-surfaces).

![Apple light appearance and reconstruction](assets/match-regular-light.png)

![Apple dark appearance and reconstruction](assets/match-regular-dark.png)

These test tone, broad lighting and rim. Nearly uniform backdrops cannot identify refraction: high similarity here does not validate an optical model. Neither recipe uses Gaussian blur.

<details>
<summary>Full-resolution light and dark comparisons</summary>

[![Full HD light comparison](assets/match-regular-light-full.png)](assets/match-regular-light-full.png)

[![Full HD dark comparison](assets/match-regular-dark-full.png)](assets/match-regular-dark-full.png)

</details>

## Scores

| Scene | Glass RMSE | Rim RMSE | Interior RMSE | Withheld-block RMSE | SSIM |
| --- | ---: | ---: | ---: | ---: | ---: |
| Playback — most polished | 8.077 | 11.148 | 2.626 | 8.368 | 0.9727 |
| Archive / plus | 6.124 | 8.984 | 4.091 | 5.978 | 0.9805 |
| Active slider | 7.227 | 8.850 | 6.566 | 7.445 | 0.9718 |
| Regular light | 1.532 | 2.894 | 0.578 | 1.544 | 0.9991 |
| Regular dark | 0.871 | 1.664 | 0.298 | 0.886 | 0.9997 |

Playback icon-color RMSE: 3.228. Toolbar symbols are retained reference pixels and are not given foreground scores.

- Encoded RGB errors use a 0–255 scale. Lower RMSE is better; higher SSIM is better. **SSIM is not a fidelity percentage.** Scores use unquantized calculated glass; PNG rounding changes them slightly. Full metrics, counts and registration evidence: [settled-scores.json](experiments/reconstruction/settled-scores.json).
- Glass excludes the first 0.75 native pixel inward from the silhouette and symbol boxes. Rim means the first 20% of the shorter half-extent. Exterior shadows are excluded. Only playback has a separately scored, independently calculated foreground color.
- Omitted 32-pixel spatial blocks share the same frame, source estimate and manually chosen outline. They were inspected during calibration and are **not independent-scene validation**. Replay performs no optimization.
- SSIM uses mean encoded RGB, an 11 × 11 Gaussian window and an eroded mask. Registration uncertainty, inferred backdrops and different material freedoms limit cross-scene comparisons.

<details>
<summary>Playback progression, including earlier PR commits</summary>

| Stage | Glass RMSE | Rim RMSE | SSIM | Icon RMSE |
| --- | ---: | ---: | ---: | ---: |
| First published HD fit (7a8061b) | 10.182 | 11.956 | 0.9577 | — |
| Corrected published HD fit (b1636bb) | 8.617 | 11.692 | 0.9434 | — |
| Pass 01 | 8.690 | 11.792 | 0.9477 | — |
| Pass 02 | 8.690 | 11.792 | 0.9477 | 3.497 |
| Pass 03 | 8.690 | 11.792 | 0.9477 | 3.497 |
| Pass 04 | 8.676 | 11.853 | 0.9456 | 3.081 |
| Pass 05 | 8.058 | 11.115 | 0.9738 | 3.195 |
| Pass 06 | 8.070 | 11.138 | 0.9730 | 3.243 |
| Settled final | 8.082 | 11.152 | 0.9724 | 3.244 |

Rows re-score archived **8-bit outputs** using the same glass/rim masks. This differs slightly from the unquantized replay table. Earlier copied icons are unscored. Source/filter/material changes prevent attributing the entire improvement to optics. [Full values and provenance](experiments/reconstruction/progression.json).

</details>

## Material roles

| Context | Evidence and calibration rule |
| --- | --- |
| Media controls over photos/video | Clear preserves detail. Transfer the circular model first to other circular Clear controls; retain proportional filtering and validate foreground contrast. |
| Navigation/toolbars/text | Regular adapts blur/brightness. Fit displacement and scattering separately; uniform blur can conceal inverted rim detail. |
| Larger menus | Size/context can alter appearance; button coefficients need not remain fixed as it becomes a panel. |
| Active slider/switch thumb | Manipulation temporarily reveals a clear lens. This contact state is separate from page material choice. |
| Scroll-edge treatment | Additional effects beneath floating controls may belong to scrolling content rather than glass. |
| Appearance/accessibility | Record settings when known. An unlabeled screenshot does not establish universal defaults. |

Primary sources: [Materials HIG](https://developer.apple.com/design/human-interface-guidelines/materials), [variants/legibility](https://developer.apple.com/videos/play/wwdc2025/219/?time=828), [active controls](https://developer.apple.com/videos/play/wwdc2025/219/?time=250), [size adaptation](https://developer.apple.com/videos/play/wwdc2025/219/?time=360). [Localica material policy](specification.md#surface-roles) owns app implementation decisions.

## Reproduce

Use Python 3.11+ and the [pinned dependencies](experiments/reconstruction/requirements.txt) in a private virtual environment. Linux DejaVu Sans affects captions only. Download original HD videos privately as `219-hd.mp4` and `284-hd.mp4` using [sources.json](experiments/reconstruction/sources.json); replay checks hashes and decoded dimensions.

From the repository root:

```sh
python3 docs/liquid-glass/experiments/reconstruction/verify-recipes.py
python3 docs/liquid-glass/experiments/reconstruction/settled.py /private/videos /private/replay
python3 docs/liquid-glass/experiments/reconstruction/motion.py /private/replay /private/motion
```

The second command renders **all five frozen recipes**, native-resolution outputs and scores. Add `--assets docs/liquid-glass/assets` to update public comparison figures. The third recreates the playback translation video/GIF and requires ffmpeg. Neither refits or modifies the live lab; no GPU is needed. The portable specification is the [equations](recreation-models.md) and [dimensionless parameters](experiments/reconstruction/recipes.json); commands fix one sampling implementation. Codec/library versions can change encoded media bytes.

Background recovery uses SIFT/RANSAC registration for dune/text scenes and smooth uncovered-page fits elsewhere. Inputs are quantized to eight-bit intermediate images to match calibration. Hidden text/track endpoints remain estimates. Still materials integrate four samples per axis; sampling is bilinear, filter boundaries reflect, and coverage is separate.

The play mask contains coverage only. Toolbar glyph colors and distant exterior remain shared Apple imagery. A ten-pixel contact band uses the recovered source plus a normalized low-frequency exterior residual; it is unscored. Motion uses the playback recipe at two samples per axis and omits shadows. Archived MP4/GIF encoding is separate from numerical still replay.

Historical search tools and [results.json](experiments/reconstruction/results.json) retain alternative models; they are **not the selected recipes/current scores**. Search again only in a separate directory. Tool consistency, Apple similarity and Android performance require different checks.

## Attribution and limits

Apple excerpts © Apple Inc., reproduced for attributed rendering analysis. Original videos are 1920 × 1080 at 30000/1001 fps; URLs/hashes are recorded. Source videos, Apple fonts and private user feedback are not bundled.

Remaining validation: independent scenes, multiple sizes/contact states, temporal stability, reconstructed exterior shadows and device performance. Further lab work is preserved separately; these frozen studies do not replace the deployed lab or native renderer.

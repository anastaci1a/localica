# Apple reference recreations

Open a study to load its comparisons. **Wipe** shows Apple on the left and the reconstruction on the right; **Side by side** shows both panels. **Fullscreen** enlarges any image. The divider resets to the center when a view reopens. Motion captures load only when their dropdown is opened. The clear playback control is the **current most-polished model**. These offline results are separate from the live lab and Android app.

[Mathematical specification](recreation-models.md) · [Parameters](experiments/reconstruction/recipes.json) · [Scores](#scores) · [Reproduce](#reproduce) · [Material roles](#material-roles) · [Guide](README.md)

[Playback](#clear-playback-control) · [Toolbar](#archive-and-plus-toolbar) · [Slider](#active-slider) · [Light/dark](#light-and-dark-surfaces) · [Apple Music](#apple-music-studies) · [Switch](#active-switch)

## Clear playback control

Apple, [Meet Liquid Glass, 3:10](https://developer.apple.com/videos/play/wwdc2025/219/?time=190); uncovered photograph at 3:00. The selected radial map has a quiet center, stronger rim refraction and slight filtering after refraction. The play symbol's calculated color is blurred internally before sharp triangular coverage is applied.

![Apple playback control and current reconstruction](assets/playback-round-current.png)

- **Most polished:** width multiplier 4.5; gain 1; source/glass/icon blur σ respectively `0.5/490`, `1.6/490`, `3/490` times the shorter dimension. Dark saturation 0.2; highlight taper 0.48. All lengths, including lighting, scale with the element.
- [Complete equations and operation order](recreation-models.md#playback-radial-map-and-smooth-inner-transition). This is an empirical refraction map, not an identified Apple solid.
- The reference-derived triangle silhouette is reused; its **color is independently calculated**. It has no stroked outline. The response is photograph-specific, not a universal contrast algorithm.
- Short round, **3/3 verified passes**: shared material exposure ×1.005; post-refraction blur 1.6 native-reference pixels; highlight taper .48; radial edge-displacement amplitude ×1.001. Weighted RMS error decreases **9.10758 → 9.07967 → 9.06493 → 9.06475**. Current glass/icon errors are **8.04005/3.21105**; neither regresses in any pass. The third gain is small (0.002%). [Final recipe](experiments/reconstruction/studies/playback/review-round/pass-03.json) · [Independent replay/domain/visual checks](experiments/reconstruction/studies/playback/review-round/pass-03-verification.json).
- The current 4× comparison uses the registered clean photograph outside the lens; no photographed shadow is copied. Exterior shadow modeling remains incomplete. The older full-frame comparison below is historical; motion uses the final short-round recipe.

<details>
<summary>Full-resolution playback comparison</summary>

[![Full HD playback comparison](assets/match-media-full.png)](assets/match-media-full.png)

Each half retains 1920 × 1080 pixels.

</details>

<details>
<summary>Motion preview — full-size GIF and video</summary>

<video controls preload="none" playsinline width="1920" height="1080" aria-label="Settled playback control motion"><source src="assets/playback-settled.mp4" type="video/mp4"><a href="assets/playback-settled.mp4">Open video</a></video>

<img src="assets/playback-settled.gif" alt="Settled playback model translated over the recovered photograph" width="1920" height="1080" loading="lazy" decoding="async">

[Full-HD video](assets/playback-settled.mp4). GIF: 7,740,942 bytes, 1920 × 1080, about six seconds (5.99 s), 15 fps; video: six seconds at 30 fps. Both use the [final short-round recipe](experiments/reconstruction/studies/playback/review-round/pass-03.json), with no exterior shadow. Motion integrates 2×2 samples per output pixel, versus 4×4 for still verification. [Media hashes and encoding](experiments/reconstruction/studies/playback/review-round/motion.json). These are offline renders, not device-performance measurements or a matched Apple motion clip.

</details>

## Archive and plus toolbar

Apple, [Meet Liquid Glass, 11:50](https://developer.apple.com/videos/play/wwdc2025/219/?time=710); uncovered text at 12:00. Review revision 3 adds a shared intensity curve and common material adjustment; shape and refraction are unchanged. Weighted RMS error decreases **1.17%** from revision 2, with glass, exterior and both foreground checks passing.

![Apple archive/plus toolbar and current reconstruction](assets/match-regular.png)

- One clean background feeds the lens and exterior; the hidden paper region is estimated. A generated neutral Gaussian shadow replaces the earlier copied exterior context. Its width reaches the chosen bound, so the shadow tail remains provisional.
- The plus uses an original SF Symbols vector. The archive is a symmetric rounded outline trace, an approximation to the original symbol. Both are independently rendered; no screenshot glyph pixels are pasted. Glass scoring still excludes their fixed boxes, and foreground errors are reported separately.
- [Model and equations](recreation-models.md#toolbar-normal-displacement-and-tangential-compression) · [Recipe](experiments/reconstruction/studies/archive-plus/review-round/pass-03.json) · [Independent replay](experiments/reconstruction/studies/archive-plus/review-round/pass-03-verification.json) · [Native scores](experiments/reconstruction/studies/archive-plus/review-round/pass-03-scores.json).
- The final `d` stroke remains too straight, and underside reflections/lighting still differ. This empirical image field is not an identified physical lens or Apple shader. No second observed background is verified yet.

<details>
<summary>Upper rim and refracted lettering</summary>

![Upper-rim comparison with Apple on the left](assets/match-regular-rim.png)

![Refracted d-stroke comparison with Apple on the left](assets/toolbar-d-stroke-current.png)

![Underside comparison with Apple on the left](assets/toolbar-underside-current.png)

These fixed-position crops use the current 4× render. The upper-rim crop now extends downward by half its original height; the letter-refraction crop starts at its bottom edge. With these boundaries, visible-composite RMSE within the glass mask decreases from 7.403 to 7.303 in the upper rim and from 3.368 to 3.096 around the `d`. The underside worsens from **5.062 to 5.471**; its reflections remain too distinct. The [reframed diagnostics](experiments/reconstruction/studies/archive-plus/review-round/pass-03-crops-reframed.json) and [original boundaries/scores](experiments/reconstruction/studies/archive-plus/review-round/pass-03-crops.json) remain advisory and do not imply every region improved.

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

## Apple Music studies

References come from [Apple's original Music video](https://www.apple.com/newsroom/videos/2025/autoplay/06/apple-introduces-a-delightful-and-elegant-new-software-design/apple-wwdc25-liquid-glass-apple-music-dynamic-tab-bars/large_2x.mp4). Each study shares optics/material across two observed backgrounds. The reconstruction halves are evaluated at **4× resolution**, including fresh vector coverage; the Apple halves retain the original video's detail. Use **Fullscreen** to inspect the full-size pair.

### Expanded mini-player

Frame A: 0 seconds. Frame B: 6.033 seconds. Cover art is a separate high-resolution image; symbols and labels use vector geometry. Recovered background strips and registration limits are recorded with the measurements.

[![Expanded mini-player, background A: Apple left, reconstruction right](assets/study-music-mini-expanded-vector-paired-a.png)](assets/study-music-mini-expanded-vector-paired-a.png)

[![Expanded mini-player, background B: Apple left, reconstruction right](assets/study-music-mini-expanded-vector-paired-b.png)](assets/study-music-mini-expanded-vector-paired-b.png)

- [Shared recipe](experiments/reconstruction/studies/music-mini-expanded-vector/paired/accepted.json) · [Verification](experiments/reconstruction/studies/music-mini-expanded-vector/paired/verification.json) · [A diagnostics](experiments/reconstruction/studies/music-mini-expanded-vector/paired/a/diagnostics.json) · [B diagnostics](experiments/reconstruction/studies/music-mini-expanded-vector/paired/b/diagnostics.json).
<details>
<summary>Motion — background A</summary>

<video controls preload="none" playsinline width="5440" height="1000" aria-label="Expanded mini-player, background A motion"><source src="assets/study-music-mini-expanded-vector-paired-a.mp4" type="video/mp4"><a href="assets/study-music-mini-expanded-vector-paired-a.mp4">Open video</a></video>

<img src="assets/study-music-mini-expanded-vector-paired-a.gif" alt="Expanded mini-player motion on background A" width="2720" height="500" loading="lazy" decoding="async">

[Video](assets/study-music-mini-expanded-vector-paired-a.mp4) · [GIF](assets/study-music-mini-expanded-vector-paired-a.gif)

</details>

<details>
<summary>Motion — background B</summary>

<video controls preload="none" playsinline width="5440" height="1000" aria-label="Expanded mini-player, background B motion"><source src="assets/study-music-mini-expanded-vector-paired-b.mp4" type="video/mp4"><a href="assets/study-music-mini-expanded-vector-paired-b.mp4">Open video</a></video>

<img src="assets/study-music-mini-expanded-vector-paired-b.gif" alt="Expanded mini-player motion on background B" width="2720" height="500" loading="lazy" decoding="async">

[Video](assets/study-music-mini-expanded-vector-paired-b.mp4) · [GIF](assets/study-music-mini-expanded-vector-paired-b.gif)

</details>

### Compact mini-player

Frames A/B: 2.5/2.0 seconds. This tests the compact state of the same playback component. The exterior mask remains fixed while the silhouette is refined; it cannot move to conceal shadow error.

[![Compact mini-player, background A: Apple left, reconstruction right](assets/study-music-mini-compact-vector-paired-a.png)](assets/study-music-mini-compact-vector-paired-a.png)

[![Compact mini-player, background B: Apple left, reconstruction right](assets/study-music-mini-compact-vector-paired-b.png)](assets/study-music-mini-compact-vector-paired-b.png)

- [Shared recipe](experiments/reconstruction/studies/music-mini-compact-vector/paired/accepted.json) · [Verification](experiments/reconstruction/studies/music-mini-compact-vector/paired/verification.json) · [A diagnostics](experiments/reconstruction/studies/music-mini-compact-vector/paired/a/diagnostics.json) · [B diagnostics](experiments/reconstruction/studies/music-mini-compact-vector/paired/b/diagnostics.json).
<details>
<summary>Motion — background A</summary>

<video controls preload="none" playsinline width="3680" height="960" aria-label="Compact mini-player, background A motion"><source src="assets/study-music-mini-compact-vector-paired-a.mp4" type="video/mp4"><a href="assets/study-music-mini-compact-vector-paired-a.mp4">Open video</a></video>

<img src="assets/study-music-mini-compact-vector-paired-a.gif" alt="Compact mini-player motion on background A" width="1840" height="480" loading="lazy" decoding="async">

[Video](assets/study-music-mini-compact-vector-paired-a.mp4) · [GIF](assets/study-music-mini-compact-vector-paired-a.gif)

</details>

<details>
<summary>Motion — background B</summary>

<video controls preload="none" playsinline width="3680" height="960" aria-label="Compact mini-player, background B motion"><source src="assets/study-music-mini-compact-vector-paired-b.mp4" type="video/mp4"><a href="assets/study-music-mini-compact-vector-paired-b.mp4">Open video</a></video>

<img src="assets/study-music-mini-compact-vector-paired-b.gif" alt="Compact mini-player motion on background B" width="1840" height="480" loading="lazy" decoding="async">

[Video](assets/study-music-mini-compact-vector-paired-b.mp4) · [GIF](assets/study-music-mini-compact-vector-paired-b.gif)

</details>

### Detached Search control

Current round: **2/3** verified passes. Shared material updates: native E A7.230750→7.227914 and B7.124124→7.121138. Glass and foreground improve on both; exterior shadow stays fixed. Unknown exterior is completed from nearest defined source RGB for presentation only; scored pixels are unchanged. Motion below remains the previous calibration.

[Current recipe](experiments/reconstruction/studies/music-search-vector/review-round/pass-02.json) · [Independent verification](experiments/reconstruction/studies/music-search-vector/review-round/pass-02-verification.json).

Frames A/B: 2.5/2.0 seconds. The magnifier is a clean symmetric vector trace; color and opacity are fitted separately. The icon geometry does not contain video compression artifacts.

[![Search control, background A: Apple left, reconstruction right](assets/study-music-search-vector-paired-a.png)](assets/study-music-search-vector-paired-a.png)

[![Search control, background B: Apple left, reconstruction right](assets/study-music-search-vector-paired-b.png)](assets/study-music-search-vector-paired-b.png)

- [Shared recipe](experiments/reconstruction/studies/music-search-vector/paired/accepted.json) · [Verification](experiments/reconstruction/studies/music-search-vector/paired/verification.json) · [A diagnostics](experiments/reconstruction/studies/music-search-vector/paired/a/diagnostics.json) · [B diagnostics](experiments/reconstruction/studies/music-search-vector/paired/b/diagnostics.json).
<details>
<summary>Motion — background A</summary>

<video controls preload="none" playsinline width="960" height="940" aria-label="Search control, background A motion"><source src="assets/study-music-search-vector-paired-a.mp4" type="video/mp4"><a href="assets/study-music-search-vector-paired-a.mp4">Open video</a></video>

<img src="assets/study-music-search-vector-paired-a.gif" alt="Search control motion on background A" width="960" height="940" loading="lazy" decoding="async">

[Video](assets/study-music-search-vector-paired-a.mp4) · [GIF](assets/study-music-search-vector-paired-a.gif)

</details>

<details>
<summary>Motion — background B</summary>

<video controls preload="none" playsinline width="960" height="940" aria-label="Search control, background B motion"><source src="assets/study-music-search-vector-paired-b.mp4" type="video/mp4"><a href="assets/study-music-search-vector-paired-b.mp4">Open video</a></video>

<img src="assets/study-music-search-vector-paired-b.gif" alt="Search control motion on background B" width="960" height="940" loading="lazy" decoding="async">

[Video](assets/study-music-search-vector-paired-b.mp4) · [GIF](assets/study-music-search-vector-paired-b.gif)

</details>

### Navigation control

Current round: **1/3** verified passes. Native E: A1.979013→1.978978; B7.434360→7.434263. All16 foreground and exterior measurements are unchanged. The small gain comes from a shared outer-normal adjustment. Unknown exterior context uses nearest defined source RGB for presentation only; measured pixels are unchanged. Motion below remains the earlier calibration until this round finishes.

Frames A/B: 0/6.033 seconds. The study separates the outer glass, fixed gray selection backing, symbols and text. Original font outlines are used for labels; unresolved symbols use clean traces or a documented public vector. **The upper rim on B remains too dark.** The mostly white background A weakly constrains refraction.

[![Navigation control, background A: Apple left, reconstruction right](assets/study-music-navigation-paired-a.png)](assets/study-music-navigation-paired-a.png)

[![Navigation control, background B: Apple left, reconstruction right](assets/study-music-navigation-paired-b.png)](assets/study-music-navigation-paired-b.png)

- [Current recipe](experiments/reconstruction/studies/music-navigation/review-round/pass-01.json) · [Independent verification](experiments/reconstruction/studies/music-navigation/review-round/pass-01-verification.json) · [Historical calibration](experiments/reconstruction/studies/music-navigation/paired/accepted.json) · [A diagnostics](experiments/reconstruction/studies/music-navigation/paired/a/diagnostics.json) · [B diagnostics](experiments/reconstruction/studies/music-navigation/paired/b/diagnostics.json).
<details>
<summary>Motion — background A</summary>

<video controls preload="none" playsinline width="4320" height="1340" aria-label="Navigation control, background A motion"><source src="assets/study-music-navigation-paired-a.mp4" type="video/mp4"><a href="assets/study-music-navigation-paired-a.mp4">Open video</a></video>

<img src="assets/study-music-navigation-paired-a.gif" alt="Navigation control motion on background A" width="2160" height="670" loading="lazy" decoding="async">

[Video](assets/study-music-navigation-paired-a.mp4) · [GIF](assets/study-music-navigation-paired-a.gif)

</details>

<details>
<summary>Motion — background B</summary>

<video controls preload="none" playsinline width="4320" height="1340" aria-label="Navigation control, background B motion"><source src="assets/study-music-navigation-paired-b.mp4" type="video/mp4"><a href="assets/study-music-navigation-paired-b.mp4">Open video</a></video>

<img src="assets/study-music-navigation-paired-b.gif" alt="Navigation control motion on background B" width="2160" height="670" loading="lazy" decoding="async">

[Video](assets/study-music-navigation-paired-b.mp4) · [GIF](assets/study-music-navigation-paired-b.gif)

</details>

### Home control

Frames A/B: 2.5/2.0 seconds. The corrected study has 22 initial/refinement and 21 paired experiments, with one sparse source-brightness response and a clean Home vector. Both references pass the frozen native loss, whole-glass and foreground gates. The earlier pairing regressed the first-background result; its unchanged archived records do not count as current acceptance.

[![Home correction, background A: Apple left, reconstruction right](assets/study-music-home-vector-progress-a.png)](assets/study-music-home-vector-progress-a.png)

[![Home correction, background B: Apple left, reconstruction right](assets/study-music-home-vector-progress-b.png)](assets/study-music-home-vector-progress-b.png)

- [Recipe](experiments/reconstruction/studies/music-home-vector/paired/accepted.json) · [Single-to-paired boundary](experiments/reconstruction/studies/music-home-vector/paired/bridge.json) · [Independent native replay](experiments/reconstruction/studies/music-home-vector/paired/verification.json) · [Archived invalid chain](experiments/reconstruction/studies/music-home-vector/paired-invalid-boundary/status.json).
- A 0.08L inset keeps the source-color calculation on defined pixels. A narrow uniform-white incident field improves the outer contour; it is an empirical lighting assumption, and a dark upper-rim mismatch remains. The source-brightness law is tested only over these two backgrounds.
- These 4× captures show pass21. Missing background pixels are extended with nearby defined RGB for presentation, with [measured benchmark differences](experiments/reconstruction/home-gallery-progress.json). Motion uses the same completed, translated source inside and outside the lens; the extension is not photographic recovery. Four-second videos run at 30 fps; full-size GIFs stay below 10 MB by reducing frame rate.

<details>
<summary>Motion — background A</summary>

<video controls preload="none" playsinline width="940" height="960" aria-label="Home correction, background A motion"><source src="assets/study-music-home-vector-paired-a.mp4" type="video/mp4"><a href="assets/study-music-home-vector-paired-a.mp4">Open video</a></video>

<img src="assets/study-music-home-vector-paired-a.gif" alt="Home correction motion on background A" width="940" height="960" loading="lazy" decoding="async">

[Video](assets/study-music-home-vector-paired-a.mp4) · [GIF](assets/study-music-home-vector-paired-a.gif)

</details>

<details>
<summary>Motion — background B</summary>

<video controls preload="none" playsinline width="940" height="960" aria-label="Home correction, background B motion"><source src="assets/study-music-home-vector-paired-b.mp4" type="video/mp4"><a href="assets/study-music-home-vector-paired-b.mp4">Open video</a></video>

<img src="assets/study-music-home-vector-paired-b.gif" alt="Home correction motion on background B" width="940" height="960" loading="lazy" decoding="async">

[Video](assets/study-music-home-vector-paired-b.mp4) · [GIF](assets/study-music-home-vector-paired-b.gif)

</details>

## Active switch

Frames A/B: 9.4/10.5 seconds in [Apple's Details video](https://www.apple.com/newsroom/videos/2025/autoplay/06/apple-introduces-a-delightful-and-elegant-new-software-design/apple-wwdc25-liquid-glass-details/large_2x.mp4). These **4× comparisons** show review-round revision 6: joint refinement of the existing normal/cap profiles, center scaling, intensity response and shared material. Shape, source, blur, dispersion and shadow remain fixed. Weighted RMS falls **0.54% / 0.85%** on the two backgrounds; whole-glass, rim and exterior errors improve. Independent replay checks 50.9 million high-resolution transmission rays against defined source support. The 16-update fit ends at its budget, with gamma at the declared upper bound; it does not establish a global optimum. The 24 initial/24 paired historical experiments remain archived; their counts are not certification under the new improvement gate.

[![Switch, green track: Apple left, reconstruction right](assets/study-active-switch-paired-a.png)](assets/study-active-switch-paired-a.png)

[![Switch, gray track: Apple left, reconstruction right](assets/study-active-switch-paired-b.png)](assets/study-active-switch-paired-b.png)

- The hidden track is a symmetric, uniform-color estimate; scores are conditional on it. A [source-color sensitivity check](experiments/reconstruction/studies/active-switch/review-round/pass-06-source-sensitivity.json) gives E ranges **14.075–14.093 / 15.165–15.699** within the recorded exposed-color bounds. These are stress tests, not statistical confidence intervals. Frozen scoring inputs stay unchanged; source changes never count as optical gains.
- A shared source-color rule reduces the material’s green cast on neutral backgrounds without desaturating refracted RGB detail. Directional channel separation reproduces part of the inner fringe; upper-rim color and edge shape remain incomplete; there is no claim of a recovered Apple solid.
- [Current normalized recipe basis](experiments/reconstruction/studies/active-switch/review-round/pass-06.json) · [Independent verification](experiments/reconstruction/studies/active-switch/review-round/pass-06-verification.json) · [Historical recipe](experiments/reconstruction/studies/active-switch/paired/accepted.json) · [Snapshot measurements](experiments/reconstruction/switch-gallery-progress.json) · [Study PR #425](https://github.com/anastaci1a/localica/pull/425). [Native paired replay](experiments/reconstruction/studies/active-switch/paired/verification.json) reproduces the 24 historical paired checkpoints under their original nonregression gate. Both motion captures below show **historical pass24**, not the revised stills. The 4× masters are four seconds at 30 fps; GIF previews are half-size at 15 fps and under 10 MB.

<details>
<summary>Motion — green track</summary>

<video controls preload="none" playsinline width="3300" height="2360" aria-label="Active switch on green track motion"><source src="assets/study-active-switch-paired-a.mp4" type="video/mp4"><a href="assets/study-active-switch-paired-a.mp4">Open video</a></video>

<img src="assets/study-active-switch-paired-a.gif" alt="Active switch over a moving green track" width="1650" height="1180" loading="lazy" decoding="async">

[Video](assets/study-active-switch-paired-a.mp4) · [GIF](assets/study-active-switch-paired-a.gif) · [Encoding checks](experiments/reconstruction/studies/active-switch/paired/a/media.json) · [GIF checks](experiments/reconstruction/studies/active-switch/paired/a/gif-qa.json). Video is evaluated at 4×; the GIF uses half-size frames. This illustrates backdrop movement, not measured Apple interaction timing.

</details>

<details>
<summary>Motion — gray track</summary>

<video controls preload="none" playsinline width="3140" height="2280" aria-label="Active switch on gray track motion"><source src="assets/study-active-switch-paired-b.mp4" type="video/mp4"><a href="assets/study-active-switch-paired-b.mp4">Open video</a></video>

<img src="assets/study-active-switch-paired-b.gif" alt="Active switch over a moving gray track" width="1570" height="1140" loading="lazy" decoding="async">

[Video](assets/study-active-switch-paired-b.mp4) · [GIF](assets/study-active-switch-paired-b.gif) · [Encoding checks](experiments/reconstruction/studies/active-switch/paired/b/media.json) · [GIF checks](experiments/reconstruction/studies/active-switch/paired/b/gif-qa.json). Same shared model and normalized travel as the green-track capture.

</details>

<details>
<summary>Colored rim — localized crops and measurements</summary>

Current crops place Apple on the left and review-round revision 6 on the right. All four gray-background crops improve. Green-background upper-left and right-rim errors increase by 0.85% and 0.32%; its lower-left and center improve. These local measurements remain advisory. The dark inner contour, excess softness and colored fringes remain unresolved.

[![Current switch rim crops](assets/switch-round-crops.png)](assets/switch-round-crops.png)

[Before/after crop scores](experiments/reconstruction/studies/active-switch/review-round/pass-06-crops.json) · [Exterior bands](experiments/reconstruction/studies/active-switch/review-round/pass-06-shadow-tails.json). The older crop sheet below retains snapshot 24 for comparison. The gray-track reference contains a warm patch near RGB `(251,236,209)` and a cyan lower rim. The estimated track itself is near `(190,195,189)`. Those hue changes require investigation beyond ordinary shared-channel sampling of that estimate.

[![Switch rim details: Apple and reconstruction across six cropped regions](assets/switch-rim-detail.png)](assets/switch-rim-detail.png)

- A source-derived directional reflection reduces the unsupported dark inner contour at A/B lower left, independently of the exterior shadow. The supplementary lower-contour MSE falls from7511/10205 to1124/1688; native masks stay fixed. Thin inner colored bands at A lower left and B right remain calibration targets.
- [Rim measurements](experiments/reconstruction/switch-rim-analysis.json) include native RGB samples, resting controls and source assumptions. [Competing color models](experiments/reconstruction/switch-color-hypotheses.json) test channel-separated rays and directional chromatic lighting. Some improve native scores slightly; none reproduces the broad colored bands, and none is adopted. Their frozen baselines differ and are recorded separately.
- Apple describes separate [geometry-dependent highlights and ambient color spill](https://developer.apple.com/videos/play/wwdc2025/219/?time=631). Colored transmission, lighting/tint, video processing and source-estimation error remain possible contributors; these pixels do not identify physical dispersion or an exact Apple shader.

</details>

## Active segmented selector

**Visually rejected calibration.** Single-reference pass 20 has weighted loss308.506 and glass RMSE19.935, but introduces blur absent from the reference and has an incorrect refraction map. It is retained below for audit, not as an accepted reproduction. The next geometry study fixes source/output blur at zero. The source is Apple's Details video at 16.4 seconds. The earlier13 source-biased passes are [archived and excluded](experiments/reconstruction/studies/active-segmented-selector/invalid-track-registration/status.json); the corrected source is constrained by both distant track ends. An [intermediate caption proxy](experiments/reconstruction/studies/active-segmented-selector/invalid-lensed-caption-proxy/status.json) is also excluded; current caption geometry uses only independently unoccluded labels. A second observed position at 16.35 seconds is reserved for paired calibration; it was chosen by silhouette dimensions after the original 16.5s frame showed a different interaction shape. This is a posthoc matched-state test, not blind validation. No paired acceptance is claimed yet.

[![Active segmented selector: Apple left, reconstruction right](assets/study-active-segmented-selector-progress-a.png)](assets/study-active-segmented-selector-progress-a.png)

- The incident texture uses solid SF font outlines with selected red text; the exterior retains gray text. Hidden track geometry, font choice, recoloring before refraction and layout transfer are explicit estimates, not photographic recovery or a claim about Apple's rendering pipeline.
- [Current native measurements and source limits](experiments/reconstruction/active-segmented-selector-progress.json) · [Recipe and pass records](experiments/reconstruction/studies/active-segmented-selector/accepted.json). The reconstruction panel is evaluated at 4×; native scoring remains fixed.

<details>
<summary>Shape investigation — capsule, continuous crown and superellipse</summary>

The blue curves compare three geometric footprints against the same Apple rim. Columns are capsule, continuous crown and superellipse. These are contour diagnostics, not accepted new optical renders. The crown has lower held-out white-ridge error in both frames; the clear refraction trials still fail the retained image-score gate.

![Selector silhouette hypotheses on background A](assets/selector-contour-hypotheses-a.png)

![Selector silhouette hypotheses on background B](assets/selector-contour-hypotheses-b.png)

[Equations and limits](recreation-models.md#continuous-pill-footprint-candidate) · [Measurements](experiments/reconstruction/studies/active-segmented-selector/crown-diagnostics.json)

</details>

## Scores

Historical experiment counts below include rejected proposals, ties and improvements smaller than the current milestone. They are not counts of passes under the [current verification protocol](recreation-models.md#verified-improvement-protocol). New qualifying passes must reduce weighted RMS error by at least0.5% on every active observation, with the additional source, visual and nonregression checks.

### Music and switch studies

| Study | Weighted loss A / B | Glass RMSE A / B | Initial / paired experiments | Status |
| --- | ---: | ---: | ---: | --- |
| Expanded mini-player | 29.031 / 9.723 | 4.366 / 2.571 | 22 / 21 | Completed study |
| Compact mini-player | 24.899 / 23.369 | 4.370 / 4.325 | 29 / 21 | Completed study |
| Search | 52.284 / 50.753 | 6.794 / 6.592 | 28 / 21 | Completed study |
| Navigation | 3.916 / 55.270 | 1.611 / 6.044 | 20 / 24 | Completed study; rim mismatch |
| Home | 40.187 / 103.246 | 5.861 / 9.223 | 22 / 21 | Corrected boundary verified |
| Active switch, review 6 | 198.132 / 234.623 | 11.156 / 13.641 | 24 / 24 historical | Shared two-reference revision |

- Weighted loss is `0.7 × glass RMSE² + 0.3 × rim RMSE²`; each sample's loss and whole-glass RMSE must hold or improve. Rejected experiments retain the incumbent. Independent replay also checks that the first paired baseline protects the retained single-background result.
- These studies use two samples per output axis and fixed native-reference masks. They are not directly ranked against the earlier four-sample-per-axis studies below. Foreground errors and source-footprint diagnostics remain separate from glass scores.
- Both references come from the same video, not independent device validation. Native calibration uses the scored pixels; its checkerboard subset is not an independent holdout. Higher-resolution presentation does not create new source detail or change native scoring. GIFs may have lower resolution/frame rate than the 4× PNG/video masters; their encoding diagnostics are recorded with each study.

### Earlier HD studies

| Scene | Glass RMSE | Rim RMSE | Interior RMSE | Withheld-block RMSE | SSIM |
| --- | ---: | ---: | ---: | ---: | ---: |
| Playback — review 3 | 8.040 | 11.094 | 2.632 | 8.301 | 0.9733 |
| Archive / plus, review 3 | 5.796 | 8.678 | 3.683 | 5.526 | 0.9818 |
| Active slider | 7.227 | 8.850 | 6.566 | 7.445 | 0.9718 |
| Regular light | 1.532 | 2.894 | 0.578 | 1.544 | 0.9991 |
| Regular dark | 0.871 | 1.664 | 0.298 | 0.886 | 0.9997 |

Playback icon-color RMSE: **3.211** ([current review](experiments/reconstruction/studies/playback/review-round/pass-03-verification.json)); the historical settled score file below retains the original 3.228 calibration. Toolbar foreground errors are reported independently in its current native score record; the earlier calibration copied those glyphs and did not score them.

- Encoded RGB errors use a 0–255 scale. Lower RMSE is better; higher SSIM is better. **SSIM is not a fidelity percentage.** Scores use unquantized calculated glass; PNG rounding changes them slightly. Full metrics, counts and registration evidence: [settled-scores.json](experiments/reconstruction/settled-scores.json).
- Glass excludes the first 0.75 native pixel inward from the silhouette and symbol boxes. Rim means the first 20% of the shorter half-extent. Exterior shadows are excluded. Playback and the current toolbar revision report separately rendered foreground errors.
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

The second command renders **the five historical frozen recipes**, native-resolution outputs and scores; current toolbar/switch commands are linked in their model sections. Add `--assets docs/liquid-glass/assets` to update public comparison figures. The third recreates the playback translation video/GIF and requires ffmpeg. Neither refits or modifies the live lab; no GPU is needed. The portable specification is the [equations](recreation-models.md) and [dimensionless parameters](experiments/reconstruction/recipes.json); commands fix one sampling implementation. Codec/library versions can change encoded media bytes.

Background recovery uses SIFT/RANSAC registration for dune/text scenes and smooth uncovered-page fits elsewhere. Inputs are quantized to eight-bit intermediate images to match calibration. Hidden text/track endpoints remain estimates. Still materials integrate four samples per axis; sampling is bilinear, filter boundaries reflect, and coverage is separate.

The play mask contains coverage only. In that historical toolbar replay, glyph colors and distant exterior remain shared Apple imagery; the current revision uses independent vectors and a generated shadow. A ten-pixel contact band uses the recovered source plus a normalized low-frequency exterior residual; it is unscored. Motion uses the playback recipe at two samples per axis and omits shadows. Archived MP4/GIF encoding is separate from numerical still replay.

Historical search tools and [results.json](experiments/reconstruction/results.json) retain alternative models; they are **not the selected recipes/current scores**. Search again only in a separate directory. Tool consistency, Apple similarity and Android performance require different checks.

## Attribution and limits

Apple excerpts © Apple Inc., reproduced for attributed rendering analysis. Original frame sizes/rates, URLs and hashes are recorded with each study. Source videos, Apple fonts and private user feedback are not bundled.

Remaining validation: independent scenes, multiple sizes/contact states, temporal stability, reconstructed exterior shadows and device performance. Further lab work is preserved separately; these frozen studies do not replace the deployed lab or native renderer.

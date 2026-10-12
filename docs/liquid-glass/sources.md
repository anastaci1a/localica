# Apple evidence atlas

[Guide home](README.md) · [Localica specification](specification.md) · [Motion lab](lab.html)

Reviewed 12 October 2026. These are concise notes from Apple's transcripts and documentation, not a reproduction of the talks. Time links open the relevant discussion. The **Localica implications** are our interpretations; they are not claims about Apple's private renderer. Versioned WWDC sessions describe their year's system; living HIG pages may evolve.

## Interaction foundations

### Designing Fluid Interfaces · WWDC18

Apple makes responsiveness and interruption central to fluidity. Feedback starts at contact; activation waits for release and can be cancelled. During a drag, preserve the finger's attachment point. At release, velocity helps choose the intended destination. Soft boundaries communicate that input is still being received. Springs can be non-bouncy; bounce should serve momentum or a useful hint. The Music example distinguishes tapping to open Now Playing from swiping to dismiss it. Motion should preserve a consistent spatial relationship between states. Interactive prototypes expose problems that still images miss.

Watch: [response and redirection, 6:00](https://developer.apple.com/videos/play/wwdc2018/803/?time=360) · [boundaries, 17:01](https://developer.apple.com/videos/play/wwdc2018/803/?time=1021) · [spring character and Music, 34:00](https://developer.apple.com/videos/play/wwdc2018/803/?time=2040) · [projected intent, 41:00](https://developer.apple.com/videos/play/wwdc2018/803/?time=2460) · [gesture design, 50:00](https://developer.apple.com/videos/play/wwdc2018/803/?time=3000).

**Localica implication:** test interruptions and reversals as first-class paths. A release flourish cannot compensate for delayed tracking. See [input and motion](specification.md#input-and-motion).

### Animate with springs · WWDC23

Position continuity alone is insufficient: velocity must remain continuous when a gesture ends or a moving object receives a new target. Springs accept an initial velocity and can preserve it during retargeting. A spring need not overshoot. Apple presents duration and bounce as designer-facing parameters, distinguishing perceptual duration from the later point when residual movement becomes negligible. Do not delay useful actions until the entire settling tail finishes. Tune pacing first, then character, and keep related interactions consistent.

Watch: [continuity, 2:00](https://developer.apple.com/videos/play/wwdc2023/10158/?time=120) · [gesture handoff, 4:30](https://developer.apple.com/videos/play/wwdc2023/10158/?time=270) · [parameters, 9:00](https://developer.apple.com/videos/play/wwdc2023/10158/?time=540) · [retargeting, 14:30](https://developer.apple.com/videos/play/wwdc2023/10158/?time=870) · [settling, 16:00](https://developer.apple.com/videos/play/wwdc2023/10158/?time=960).

The 2018 talk's response/damping vocabulary and the 2023 duration/bounce API are different parameterizations, not a mandate to replay fixed-duration animations. **Localica implication:** retain motion state across target changes; keep semantic completion separate from visual settling.

### HIG · Motion and accessibility

Motion should explain state, remain brief, allow cancellation, and have alternatives. Input method can affect its emphasis. [Motion](https://developer.apple.com/design/human-interface-guidelines/motion).

Accessibility guidance calls for scalable readable text, sufficient contrast, non-color state cues, simple gestures and alternative ways to operate controls. Reduced Motion must change custom effects too. [Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility).

**Localica implication:** the material is optional feedback; action names, selection semantics, keyboard focus and tap alternatives remain functional without it. See [acceptance checks](specification.md#acceptance-checks).

## Material and hierarchy

### Meet Liquid Glass · WWDC25

Apple describes a designed digital material, not a literal physical simulation. Lensing defines its form; appearance transitions can vary refraction rather than simply fading opacity. Touch changes both illumination and shape. Size and environment affect scattering, tint, contrast and shadows. A button expanding into a menu retains its relationship to the origin. Small surfaces may change light/dark treatment; large ones avoid distracting wholesale flips. Regular and Clear serve different contexts. Apple cautions against glass throughout the content layer or stacked glass surfaces. Accessibility settings modify the material.

Watch: [dynamics, 1:29](https://developer.apple.com/videos/play/wwdc2025/219/?time=89) · [adaptivity, 6:00](https://developer.apple.com/videos/play/wwdc2025/219/?time=360) · [layers, 10:31](https://developer.apple.com/videos/play/wwdc2025/219/?time=631) · [placement and variants, 13:00](https://developer.apple.com/videos/play/wwdc2025/219/?time=780) · [accessibility, 18:00](https://developer.apple.com/videos/play/wwdc2025/219/?time=1080).

**Localica implication:** optics, geometry and interaction need a shared model. A translucent outline plus an unrelated scale animation cannot express all of these behaviors. See [surface roles](specification.md#surface-roles).

### HIG · Materials and color

Regular Liquid Glass blurs and adjusts background luminosity for readability. Clear emphasizes rich media and may need local dimming; it is not the universal default. Standard materials still organize the content layer. Custom glass should be selective. Semantic roles matter more than an apparent color in one screenshot. [Materials](https://developer.apple.com/design/human-interface-guidelines/materials).

Color distinguishes meaning and hierarchy; materials pick up surrounding content, so a chosen tint needs testing in context rather than treatment as a fixed swatch. [Color](https://developer.apple.com/design/human-interface-guidelines/color).

**Localica implication:** clear-to-frosted customization is useful, but zero blur does not guarantee readable controls. User-chosen glass on selected media is an explicit Localica departure from Apple's usual layering guidance, not an Apple rule.

### Get to know the new design system · WWDC25

Concentric geometry aligns nested curves and padding; fixed-radius shapes, capsules and container-relative shapes have distinct roles. Optical balance can require more than mathematical centering. Layout and functional grouping establish hierarchy. Related actions can share a surface, while important actions remain distinct. Presentations identify their source. Persistent navigation accessories differ from contextual actions. Scroll-edge treatments separate overlapping controls and content; they should not be added as arbitrary decoration or stacked repeatedly.

Watch: [geometry, 3:00](https://developer.apple.com/videos/play/wwdc2025/356/?time=180) · [structure, 6:16](https://developer.apple.com/videos/play/wwdc2025/356/?time=376) · [grouping, 9:00](https://developer.apple.com/videos/play/wwdc2025/356/?time=540) · [scroll edges, 11:00](https://developer.apple.com/videos/play/wwdc2025/356/?time=660).

**Localica implication:** one surface geometry should drive clipping, rims and shadows. Fix crowded layout at its source; adding a glass wrapper is insufficient. See [geometry and readability](specification.md#geometry-and-readability).

## Implementation evidence

### SwiftUI and UIKit adoption · WWDC25

SwiftUI's custom glass examples use a shared container so neighboring effects sample a consistent region; glass does not sample other glass. Stable effect identities connect morphing states. The sampling region extends beyond an element's bounds. [Build a SwiftUI app, 17:57](https://developer.apple.com/videos/play/wwdc2025/323/?time=1077).

UIKit demonstrates adapting corners, size-dependent appearance, coordinated foreground changes, effect-based materialization and container-controlled merging. It recommends removing overlapping floating controls when another glass surface expands over them. [Build a UIKit app, 19:15](https://developer.apple.com/videos/play/wwdc2025/284/?time=1155).

The custom-view documentation also warns that excessive separate containers/effects degrade performance. Container spacing controls when nearby shapes interact. [Applying Liquid Glass to custom views](https://developer.apple.com/documentation/swiftui/applying-liquid-glass-to-custom-views).

**Localica implication:** translate these responsibilities into Compose abstractions; SwiftUI/UIKit APIs do not run on Android. Sharing a backdrop does not require joining unrelated controls, and joining selections does not require rectangularly filling their entire bounding box. See [rendering](specification.md#rendering-and-measurement).

### Render-loop performance · Apple Tech Talks

A hitch means a frame misses its display deadline. Both application updates and later rendering can cause it. Apple measures the time lost across an interaction, not merely a best-case or average frame rate. [Explore UI animation hitches and the render loop, 3:00](https://developer.apple.com/videos/play/tech-talks/10855/?time=180).

Shadows, masks, rounded clipping and visual effects can incur offscreen work. Explicit shadow geometry and removing redundant masks can reduce it. Apple's concrete APIs concern Core Animation; the broader lesson is to inspect the actual rendering cost, rather than assume that GPU effects are free. [Demystify and eliminate hitches in the render phase, 3:00](https://developer.apple.com/videos/play/tech-talks/10857/?time=180).

**Localica implication:** capture frame timelines, allocation and rendered-area evidence for our Android implementation. Cache by geometric identity, keep foregrounds sharp, and measure representative devices. See [rendering and measurement](specification.md#rendering-and-measurement).

## Music controls and typography

Apple's symbol guide identifies the meanings of Music's playback, shuffle, repeat, list and sorting controls. It is a semantic reference, not a guarantee that a macOS screenshot matches every iPhone release. [Music symbol guide](https://support.apple.com/es-us/guide/music/mus131245dbe/mac).

The HIG describes San Francisco's variable optical sizing, weights and widths; text styles express hierarchy and scale with accessibility settings. It recommends avoiding very light weights for small text. [Typography](https://developer.apple.com/design/human-interface-guidelines/typography).

SF Symbols coordinates symbol weight/scale with typography and offers purposeful state-change animations. A symbol's visual footprint need not match its layout box. [SF Symbols](https://developer.apple.com/design/human-interface-guidelines/sf-symbols).

Tabs navigate top-level areas and preserve state. Toolbars provide contextual actions. Segmented controls group related choices and should retain a consistent type of operation. [Tab bars](https://developer.apple.com/design/human-interface-guidelines/tab-bars) · [Toolbars](https://developer.apple.com/design/human-interface-guidelines/toolbars) · [Segmented controls](https://developer.apple.com/design/human-interface-guidelines/segmented-controls).

**Localica implication:** retain licensed Inter and our attributed icon sources; match optical weight, spacing and meaning deliberately. This guide does not authorize copying Apple font or symbol files into an Android distribution. Configurable overflow tabs and hidden tabs on detail pages remain our product choices.

---

Next: [turn the evidence into testable Localica rules](specification.md). Return to [guide home](README.md) or [project docs](../README.md).

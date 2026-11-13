# Motion

All popup and control motion comes from one engine. A surface's progress is a damped spring that continues from its current position and velocity, so reversals and interruptions never restart or jump. Parameters were measured from Apple's popups and live in one spec. Duration does not depend on size. Edges stay anchored to the trigger. Reduced motion snaps.

- Three families share the engine: popups, finger-driven surfaces and control states.
- Screens never pass a law or a motion kind. The component decides it.
- An admission check rejects any parameter set whose overshoot leaves the allowed band.
- The speed setting and the system animation scale apply once, to the whole law.
- Status: Implemented and Released in v0.34.2 (#854, #855, #926 close law); #926 plain-close layering is Released in v0.34.4. Per-kind laws are Proposed; all kinds share one law today.

## The model

Progress follows a damped spring toward its target:

$$
\ddot{x} + 2\zeta\omega\,\dot{x} + \omega^{2}(x-q) = 0
$$

Progress runs from closed (0) to open (1). Retargeting changes the target only, preserving position/velocity. Shared spec parameters make motion size-independent with trigger-anchored edges. PopupMotionModel supplies trajectories and admission bounds.

## Popup laws

Revision `855-ios262-60fps-interim-r926` fits iOS 26.2 60 fps captures. Open uses frequency 28 rad/s, damping 0.78; close/gate use 20 and 1. Cross-axis opening uses 120 and 1. Every closing edge uses the main law (#926). Kinds are data read through one function.

### Admission

Finite frequencies span 1–400 rad/s and damping 0.5–4. Open overshoot stays within a 0.05 band; close/gate/cross laws do not overshoot from rest. All settle within two seconds from worst admitted state at slowest speed. Named cross-axis opening exemption permits its snap. Tests own admitted-boundary cases.

From-rest underdamped overshoot is:

$$
\exp\!\left(\frac{-\pi\zeta}{\sqrt{1-\zeta^{2}}}\right)
$$

## Families

Popups use shared springs. Finger-driven player, Queue, sheets, pinch and selectors follow contact then settle by velocity/distance. Control-state eases change appearance while input/state update immediately. Spec profiles at normal speed are 180/270 ms on/off, 220 ms selection/hover, 300 ms reveal/floating/artwork layout, 350 ms lyric emphasis and 165 ms selection panel. Reversal starts from displayed values.

## Deformation

One interruptible response couples footprint/depth to acceleration and pressure. Shared overdrag caps at 4 dp; held selectors keep cross-axis extent and concentric contact corners. Free corners flatten near contact; release couples width/height oppositely. Shape damping and Resting depth feed this response. Selector and pressure tests specify contours/rebound.

## Speed and Reduced motion

Animation speed (0.5×–1.5×) divides durations and scales spring frequency once. Extent/speed changes retain position/velocity. App Reduce motion or zero Android animator scale snaps motion, suppresses deformation and uses ellipses/jump scrolling. Native ownership receipts remain required. Changes apply live; restoring system animations resumes only if the app option is off.

## Rules for callers

Screens name roles, never timings/kinds/laws. New profiles belong in ui.spec. Owners observe frames in placement/drawing rather than broad composition. Moving-target gestures integrate deltas for velocity. [Browsing](../interface/browsing.md#long-scrolls) owns scroll presentation.

## Tests

[PopupPeakOvershootTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PopupPeakOvershootTest.kt).

[EffectiveMotionWindowTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/EffectiveMotionWindowTest.kt).

[EffectiveReducedMotionTest](../../app/src/test/java/io/github/anastaci1a/localica/ui/EffectiveReducedMotionTest.kt).

[PlatformDurationScaleWindowTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PlatformDurationScaleWindowTest.kt).

[ControlStateMotionTest](../../app/src/test/java/io/github/anastaci1a/localica/ui/ControlStateMotionTest.kt).

[SettingsMotionTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SettingsMotionTest.kt).

[SelectorCrossMotionTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SelectorCrossMotionTest.kt).

[SelectorFreeCornerPixelTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SelectorFreeCornerPixelTest.kt).

[SelectorReboundRecordingTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SelectorReboundRecordingTest.kt).

[GlassPressureTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/GlassPressureTest.kt).

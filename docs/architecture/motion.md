# Motion

All popup and control motion comes from one engine. A surface's progress is a damped spring that continues from its current position and velocity, so reversals and interruptions never restart or jump. Parameters were measured from Apple's popups and live in one spec. Duration does not depend on size. Edges stay anchored to the trigger. Reduced motion snaps.

- Three families share the engine: popups, finger-driven surfaces and control states.
- Screens never pass a law or a motion kind. The component decides it.
- An admission check rejects any parameter set whose overshoot leaves the allowed band.
- The speed setting and the system animation scale apply once, to the whole law.
- Status: Implemented and Released in v0.34.2 (#854, #855, #926). Per-kind laws are Proposed; all kinds share one law today.

## The model

Progress $x$ runs from 0 (closed, at the trigger) to 1 (open). It follows one damped spring toward its target $q$:

$$
\ddot{x} + 2\zeta\omega\,\dot{x} + \omega^{2}(x-q) = 0
$$

Here $\omega$ is the natural frequency and $\zeta$ the damping ratio. A new target changes only $q$. Position and velocity carry over, so a tap during opening closes from exactly where the surface is.

| Property | Why |
| --- | --- |
| Continues from current state | An interrupted motion never jumps or restarts. |
| Size independent | A small menu and a large dialog take the same time, as on the measured reference. |
| Anchored edges | The edge nearest the trigger stays attached, so the surface grows out of its source. |
| One spec | Trajectories, test references and admission bounds all derive from `PopupMotionModel` in `ui.spec`. |

## Popup laws

The stock model is revision `855-ios262-60fps-interim-r926`, fitted to 60 fps captures of iOS 26.2 menus.

| Channel | $\omega$ (rad/s) | $\zeta$ | Character |
| --- | --- | --- | --- |
| Open | 28 | 0.78 | A slight overshoot inside the band. |
| Close | 20 | 1 | Critically damped, no overshoot. |
| Gate | 20 | 1 | Same as close. |
| Cross-axis open (snap) | 120 | 1 | The short axis of a bottom menu arrives early. |

Close and gate have no cross-axis law: every edge closes on the main law (#926). Dialogs use the open law. Popup kinds (menu, row menu, bottom menu, dialog, selection pill) are data. The model reads laws only through one function, so a later revision can vary them by kind without touching callers.

### Admission

A parameter set is rejected before it can run unless all of this holds:

- Every law is finite, with $\omega$ from 1 to 400 and $\zeta$ from 0.5 to 4.
- The open overshoot stays inside the phase band of 0.05 beyond either end.
- Close, gate and cross laws never overshoot from rest.
- Every law settles within 2 seconds from its worst admitted state at the slowest speed.

The from-rest overshoot of an underdamped spring is:

$$
\exp\!\left(\frac{-\pi\zeta}{\sqrt{1-\zeta^{2}}}\right)
$$

The cross-axis open is exempt from the band by name, because its snap is intended. Exemptions are listed in the spec, never granted at a call site.

## Families

| Family | Surfaces | Rule |
| --- | --- | --- |
| Popup | Menus, dialogs, the selection pill, search | The spring laws above. |
| Finger-driven | Player, Queue drawer, sheets, pinch, selectors | Follow the finger exactly, then settle by release velocity and distance. Stay interruptible while settling. |
| Control state | Active, enabled, hover, reveal, floating controls | Short fixed eases from the motion profiles. State and input change at once; only appearance eases. |

Control-state profiles at normal speed:

| Profile | Duration |
| --- | --- |
| Control on / off | 180 ms / 270 ms |
| Selection, hover | 220 ms |
| Reveal, floating, artwork layout | 300 ms |
| Lyric emphasis | 350 ms |
| Selection panel | 165 ms |

A reversal continues from the visible appearance. Programmatic scrolling stops without overshoot, and touch cancels it. Long scroll styles are in [Browsing](../interface/browsing.md#long-scrolls).

## Deformation

Presses, morphs and selectors share one interruptible deformation driven by acceleration and held pressure. Position stops at the destination. Width, height and depth convey the impact at about constant volume. Idle elastic clocks stop.

- Overdrag: navigation and choice tracks stretch along travel within a shared 4 dp limit, whatever their length. Contents deform and realign in proportion.
- A held selector keeps its resting cross-axis extent. Its contact corners stay circular and concentric with the track.
- Free-end corners flatten along travel as the contact gap closes, then return to round.
- Release couples width and height in opposite phase, with bounded excursions.
- Touching a moving selector eases from the displayed contour.

The Shape damping and Resting depth settings feed this response. The same response drives footprint and lens depth.

## Speed and Reduced motion

The Animation speed setting runs from 0.5× to 1.5×. Every engine duration divides by it and every spring frequency multiplies by it. It applies once. A change of speed or of measured extent keeps current position and velocity.

Reduced motion is on when the app's setting is on or Android's animator duration scale is zero.

- Springs and eases snap to their result. Shared deformation, edge pressure and rebound are removed.
- Changes apply while a surface is open.
- Restoring Android animations resumes motion only when the app option is off.
- A snap still waits for required native ownership receipts, so no frame shows two owners.
- Long titles use an ellipsis. Programmatic scrolls jump.

## Rules for callers

- A screen names a role. It never passes a duration, easing, law or kind.
- New timing goes into `ui.spec` as a profile, never into a call site.
- Observe frame progress in the owning control or in the placement and draw phase, not in composition.
- Gesture velocity is measured from integrated deltas when the target itself moves.

## Tests

| Topic | Classes |
| --- | --- |
| Popup law and band | `PopupPeakOvershootTest`, `EffectiveMotionWindowTest` |
| Reduced motion and system scale | `EffectiveReducedMotionTest`, `PlatformDurationScaleWindowTest` |
| Control states | `ControlStateMotionTest`, `SettingsMotionTest` |
| Deformation | `SelectorCrossMotionTest`, `SelectorFreeCornerPixelTest`, `SelectorReboundRecordingTest`, `GlassPressureTest` |

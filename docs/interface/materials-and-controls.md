# Materials and controls

Floating controls are Liquid Glass. Panels are frost. Content is plain. Each surface type has one material, one set of shape tokens and one feedback rule, so a control looks and feels the same on every screen. Screens pick a role; the [UI system](../architecture/ui-system.md) supplies the rest.

- Glass is for controls that float over content. Selection uses a magnified backdrop with light frost, not glass.
- Corner radii come from five tokens. Touch targets are at least 48 dp.
- One successful activation gives one weak haptic pulse.
- Layouts stay usable in landscape, with large fonts and on short screens.
- Status: Implemented.

## Which surface uses which material

| Surface | Material |
| --- | --- |
| Floating pills, mini-player, filter and search bars, circular controls | Liquid Glass: curved refraction, illuminated edges, crisp foregrounds. |
| Popups and menus | The same glass, continuous with their trigger, with inherited [ink polarity](../architecture/rendering.md#ink-polarity-and-adaptive-contrast). |
| Queue drawer | One continuous, denser, textured neutral frost behind header and list. |
| Selected items and held drag cards | The backdrop region magnified with slight frost, inside the [shared outline](selection-and-reordering.md#the-outline). |
| System pickers and permission dialogs | Native. |

How glass is drawn, and what None mode replaces it with, is in [Glass](../architecture/glass.md).

## Shape

| Token | Radius |
| --- | --- |
| Small artwork | 8 dp |
| Cards | 16 dp |
| Inputs and menus | 20 dp |
| Large artwork | 24 dp |
| Panels | 28 dp |

Compact shelves and chips are pills. Transport controls are circles. The values live in `ui.spec`. The expanded player follows device-reported corner radii and stays square where none are reported.

- Icons start at 24 dp. The icon, label and surface together give a target of at least 48 dp. Feedback is clipped to that shape.
- Every two-button glass pill has a subtle vertical divider between its actions.
- Controls that open menus use deeper glass. The tab bar is flatter because it keeps its shape.
- Unavailable actions are omitted.
- Disabled action text uses the surface foreground at 38% opacity.

Icon style is defined in [Icon packs](../icon-packs.md#icon-style).

## Appearance

Browsing and settings are light, with a pink accent for the selected tab, one neutral grey for secondary text and inactive icons, and red for errors and destructive actions. Text uses Inter with compact tracking. Apple's system font is not bundled.

**Interface → Appearance** adjusts the grey, with a reset to the darker default. Broader themes are on the [roadmap](../roadmap.md).

## Controls

Choices, sliders and switches are shared controls. Their settings-page behaviour is in [Settings](settings.md#controls).

- Sort and settings choices share a moving clear lens, with tap and drag control.
- Touching any option previews the lens there without committing.
- Slider and switch thumbs are white at rest and morph into clear glass on contact.
- Text fields are shared glass fields. They wrap labels and keep native editing. A plain trailing X fades in for nonempty text; clearing keeps focus and the keyboard.
- Artwork taps and timed-lyric taps have no ripple.

## Haptics

One rule: feedback confirms something the user did, once.

| Event | Feedback |
| --- | --- |
| Successful tap, keyboard or accessible activation | One weak pulse at the input owner. |
| Owned drag begins on a selector, slider or switch | One weak engagement pulse. |
| Each distinct discrete step or Boolean transition during a drag | One weak tick. Continuous sliders have none. |
| Genuine release of an owned drag | One strong pulse. |
| Entering a cancel target (X) | One firm pulse; leaving gives a weak tick. |
| Cancellation, disabled actions, programmatic or animated state changes, parent scrolling | Silent. |

All feedback respects system haptic settings. Navigation has its own [selector rule](navigation.md#selector).

## Access

- Keep focus stable, and keep a tap or accessible alternative for every gesture.
- Adjacent glass actions keep natural label widths and logical order. Overflow offers explicit previous and next controls without shrinking text.
- Decorative shadow and rebound outsets never enlarge a touch area into neighbouring actions.
- Long titles pause, scroll and fade at their edges. Reduced motion uses an ellipsis.
- An initial cross-axis movement alone never disables later movement along a control's axis.
- Reduced motion is described in [Motion](../architecture/motion.md#speed-and-reduced-motion).

## Tests

| Topic | Classes |
| --- | --- |
| Materials and shadows | `GlassAppearanceTest`, `GlassShadowTest`, `GlassActionRowTest`, `SplitPillContactTest` |
| Shape | `PopupHomeShapeTest`, `DeviceCornersTest` |
| Haptics | `ActivationHapticsTest`, `SliderSwitchHapticsTest`, `NavbarHapticsTest`, `DragReleaseHapticsTest` |
| Text | `MarqueeTitleTest`, `RowTextScrollTest` |

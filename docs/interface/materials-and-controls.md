# Materials and controls

Floating controls are Liquid Glass. Panels are frost. Content is plain. Each surface type has one material, one set of shape tokens and one feedback rule, so a control looks and feels the same on every screen. Screens pick a role; the [UI system](../architecture/ui-system.md) supplies the rest.

- Glass is for controls that float over content. Selection uses a magnified backdrop with light frost, not glass.
- Corner radii come from five tokens. Touch targets are at least 48 dp.
- One successful activation gives one weak haptic pulse.
- Layouts stay usable in landscape, with large fonts and on short screens.
- Status: Implemented.


## Which surface uses which material

Floating controls and their popups share Liquid Glass. The Queue is one continuous neutral frost. Selected items and drag cards magnify their backdrop with slight frost inside the [shared outline](selection-and-reordering.md#the-outline). System pickers stay native. Renderer details belong to [Glass](../architecture/glass.md).

## Shape

Shape tokens are 8 dp for small artwork, 16 dp for cards, 20 dp for inputs and menus, 24 dp for large artwork and 28 dp for panels. Pills and circular transport controls keep at least 48 dp targets. Expanded players use device corner radii. Values live in `ui.spec`.

Icons follow [Icon style](../icon-packs.md#icon-style). Two-action pills have a divider. Unavailable actions are omitted; disabled text uses 38% foreground opacity. Feedback and decorative outsets stay within the control's input bounds.

## Appearance

Browsing and settings use light surfaces, a pink selection accent, neutral secondary text and red destructive/error states. Inter is bundled; Apple's font is not. **Interface → Appearance** adjusts secondary grey and resets to its darker default. Broader themes are [planned](../roadmap.md).

## Controls

Choices preview under contact and commit through the shared input owner. Slider and switch thumbs morph from white to clear glass on contact. [Settings](settings.md#controls) explains their draft model. Shared text fields keep native editing; clearing text keeps focus and keyboard. Artwork and timed-lyric taps omit ripples.

## Haptics

Feedback confirms user input once: weak for activation and drag engagement, weak ticks for discrete changes, strong for owned release. Entering a cancel target gives a firm pulse; leaving gives a weak tick. Continuous sliders have no step ticks. Cancellation, disabled actions, programmatic changes and parent scrolling are silent. System haptic settings apply.

The [navigation selector](navigation.md#selector) specializes the same input model. Haptic test classes below own its event matrix.

## Access

Keep focus stable and provide tap or accessible alternatives for gestures. Adjacent actions retain label widths and logical order; overflow uses explicit previous/next controls. Long titles pause, scroll and fade; [Reduced motion](../architecture/motion.md#speed-and-reduced-motion) uses ellipses. Initial cross-axis travel does not disable later movement along a control's axis.

## Tests

[GlassAppearanceTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/GlassAppearanceTest.kt).

[GlassShadowTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/GlassShadowTest.kt).

[GlassActionRowTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/GlassActionRowTest.kt).

[SplitPillContactTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SplitPillContactTest.kt).

[PopupHomeShapeTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PopupHomeShapeTest.kt).

[DeviceCornersTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/DeviceCornersTest.kt).

[ActivationHapticsTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ActivationHapticsTest.kt).

[SliderSwitchHapticsTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SliderSwitchHapticsTest.kt).

[NavbarHapticsTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/NavbarHapticsTest.kt).

[DragReleaseHapticsTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/DragReleaseHapticsTest.kt).

[MarqueeTitleTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/MarqueeTitleTest.kt).

[RowTextScrollTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/RowTextScrollTest.kt).

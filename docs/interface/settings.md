# Settings

Settings is a tree of pages built from one shared row. Every page aligns its text the same way, every row has its own icon, and one search on the root page reaches every control. A behaviour choice that has no single right answer becomes a setting here, with the current behaviour as its default.

- Categories, in order: Interface, Online features, Playback and queues, Library and tags, About and licenses.
- Search covers controls, choice values and synonyms across all pages.
- Sliders and switches are the shared controls, with one touch model.
- Saved values are preserved when an option is disabled.
- Status: Implemented.

## Structure

Category rows use icons, summaries and chevrons. PreferenceFrames supplies every row, with shared 40 dp leading slots and vertically centred trailing controls across font scales. Empty icon slots preserve alignment; each row has a distinct [settings icon ID](../icon-packs.md#icon-ids). Player settings live under Interface. Back reverses entrance.

## Search

The root page has global search; sub-pages omit it. Selecting a result scrolls to its row and briefly highlights it. A hidden option leads to the control that reveals it. There is no typo correction. Reduced motion scrolls immediately and shows a static highlight.

Search runs in the background against a catalog normalized once. Results stay visible while the next query computes; only its completed replacement is shown.

## Controls

Two/three-option choices use equal inline segments when labels fit; larger choices stage a draft until Select. Sliders expose accessible ranges and discrete increments; switches preview then commit once on release. All share the [seek touch model](player.md#seeking), coordinate geometry and cancellation target. Hovering X previews the original value; release keeps it. Control tests specify touch, accessibility and restoration.

Glass controls use the shared default material. Saved glass and navigation-selector preferences are removed on upgrade.

## Motion settings

Animation speed runs from 0.5× to 1.5×. Reduce motion snaps shared motion. Long scroll animation picks a [long scroll style](browsing.md#long-scrolls). Their effect is in [Motion](../architecture/motion.md#speed-and-reduced-motion).

## Export diagnostics

**About and licenses → Export diagnostics** shares one text file through Android, containing app/device identity and the [in-memory trail](../development/testing.md#field-diagnostics). Nothing leaves without a chosen share target; export has no network path.

## Online features

One consent root groups Last.fm and Artist photos; search reaches each control. Last.fm presents cached Overview, Matches and Data tabs, progressive sync, review decisions and explicit disconnect/delete controls. Failed decisions remain editable. [Online](../architecture/online.md) owns retention, matching and request rules; settings tests specify presentation.

## Tests

[SettingsRootSearchTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SettingsRootSearchTest.kt).

[SettingsSearchTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SettingsSearchTest.kt).

[SettingsRowAlignmentTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SettingsRowAlignmentTest.kt).

[SettingsOptionsTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SettingsOptionsTest.kt).

[ChoiceControlsTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ChoiceControlsTest.kt).

[SliderModelTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SliderModelTest.kt).

[InterfaceControlsTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/InterfaceControlsTest.kt).

[SwitchTapOpticsTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SwitchTapOpticsTest.kt).

[GlassRendererPreferenceTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/GlassRendererPreferenceTest.kt).

[GlassScrollHoldTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/GlassScrollHoldTest.kt).

[PinchDiagnosticsRecorderTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PinchDiagnosticsRecorderTest.kt).

[FrameStatsSettingsTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/FrameStatsSettingsTest.kt).

[LastFmSettingsTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/LastFmSettingsTest.kt).

[LastFmDashboardTest](../../app/src/test/java/io/github/anastaci1a/localica/lastfm/LastFmDashboardTest.kt).

[LastFmChoiceDialogTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/LastFmChoiceDialogTest.kt).

[LastFmMatchingBarTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/LastFmMatchingBarTest.kt).

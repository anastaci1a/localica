# Settings

Settings is a tree of pages built from one shared row. Every page aligns its text the same way, every row has its own icon, and one search on the root page reaches every control. A behaviour choice that has no single right answer becomes a setting here, with the current behaviour as its default.

- Categories, in order: Interface, Online features, Playback and queues, Library and tags, About and licenses.
- Search covers controls, choice values and synonyms across all pages.
- Sliders and switches are the shared controls, with one touch model.
- Saved values are preserved when an option is disabled.
- Status: Implemented.

## Structure

Category rows have icons, summaries and chevrons. Player settings live inside Interface. Both Back controls reverse the page's entrance direction.

Every row (link, switch, slider, choice or heading) uses the shared row in `ui/PreferenceFrames.kt`.

- The leading icon sits in a fixed 40 dp slot, centred vertically, at any line count, font scale or density. So does the trailing control.
- All rows on a page start their text at the same x. Pages whose rows carry icons keep the empty slot on rows without one.
- No two rows share an icon. The glyphs have `settings.*` [icon ids](../icon-packs.md#icon-ids).

## Search

The root page has global search; sub-pages omit it. Selecting a result scrolls to its row and briefly highlights it. A hidden option leads to the control that reveals it. There is no typo correction. Reduced motion scrolls immediately and shows a static highlight.

## Controls

| Control | Rule |
| --- | --- |
| Choice, two or three options | Equal inline segments, when the measured labels fit. |
| Choice, longer list | A staged draft until Select. Cancel discards it. |
| Slider | Shows label, value, endpoints and an accessible range. Runs from leading minimum to trailing maximum, in right-to-left layouts too. |
| Discrete slider | Matching ticks and accessible increments. Percentages use 5% steps. |
| Switch row | One accessible Boolean control. A drag previews, release commits once, cancellation restores. |

Every slider shares the [seek track's touch model](player.md#seeking). A locked drag raises an X target at the bottom of its menu or page. Hovering X eases back to the value the drag started from; releasing there keeps that value. When X would cover the slider, the page eases up during the drag.

Track and thumb share one coordinate model for fill, travel and wall contact. Haptics follow the [shared rule](materials-and-controls.md#haptics).

## Glass settings

**Interface → Glass** keeps Renderer on its root. Glass appearance, Glass advanced and Navigation selector are submenus. With Renderer set to None, glass options stay visible and disabled. Glass appearance has a draggable preview that morphs between a pill and a dialog.

| Control | Range and default |
| --- | --- |
| Glass renderer | Normal (default) or None. Older stored values migrate to Normal. Single-Tone Blur, a testing mode, is removed in v0.34.3. |
| Blur, white tint | Clear through frosted. Defaults are light. |
| Shape damping | 2–40, default 8. |
| Resting depth | 0.6–2, default 1.35. |
| Shadow strength, spread | Off through visible; tight through broad. Default: very subtle and broad. |
| Lift shadow strength | 50–150% of Glass shadow strength in 5% steps, default 100%. |
| Background brightness trim | 0–50%, default 25%. |
| Brightness weighting | 1–8, default 2, from even to highlight- and shadow-focused. |
| Navigation selector | Adjustable glass, Clear overlay (0–50% darkening, optional refraction) or Classic grey pill (opacity, default 10%). |
| Slider and switch expansion | 1–2×, default 1.75×. |

What these change is in [Glass](../architecture/glass.md) and [Rendering](../architecture/rendering.md#ink-polarity-and-adaptive-contrast). Glass always follows scrolling content; the retired scroll-freeze preference is removed during migration.

## Motion settings

Animation speed runs from 0.5× to 1.5×. Reduce motion snaps shared motion. Long scroll animation picks a [long scroll style](browsing.md#long-scrolls). Their effect is in [Motion](../architecture/motion.md#speed-and-reduced-motion).

## Export diagnostics

**About and licenses → Export diagnostics** opens a glass dialog, then the Android share sheet with one text file: app version, device model, Android version, export time and the diagnostics trail. Nothing leaves the device unless the user picks a share target. There is no network path. What the trail holds is in [Testing](../development/testing.md#field-diagnostics).

## Online features

Online features groups the consent switch, Last.fm and Artist photos. Search finds their individual controls. The rules are in [Online](../architecture/online.md).

### Last.fm pages

- The page opens with a sync ring, percent and live counts, and an archive card. Overview, Matches and Data are glass tabs.
- Overview opens with cached numbers and refreshes in the background. Uncached numbers use text placeholders with the final line height.
- Matches shows an exact-proportion colour strip with a tappable legend: Matched, Judgment calls, Needs review, Auto-matched editions and Not in your library.
- Every row opens one decision popup that can match any library song. Skip keeps the current match. A failed save keeps the popup open.
- Data offers background continuation, quick refresh, Full rescan, Disconnect and a confirmed Delete synced Last.fm data.

## Tests

| Topic | Classes |
| --- | --- |
| Structure and search | `SettingsRootSearchTest`, `SettingsSearchTest`, `SettingsRowAlignmentTest`, `SettingsOptionsTest` |
| Controls | `ChoiceControlsTest`, `SliderModelTest`, `InterfaceControlsTest`, `SwitchTapOpticsTest` |
| Glass | `GlassRendererSettingsTest`, `GlassRendererPreferenceTest`, `GlassScrollHoldTest` |
| Diagnostics | `PinchDiagnosticsRecorderTest`, `FrameStatsSettingsTest` |
| Last.fm | `LastFmSettingsTest`, `LastFmDashboardTest`, `LastFmChoiceDialogTest`, `LastFmMatchingBarTest` |

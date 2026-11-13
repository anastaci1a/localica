# Actions and editing

The player has a row of action slots the user arranges. Each action opens a shared popup: song information, the tag editor, the artwork viewer or repeat options. Editing is always a staged draft with a preview, a verified save and a way back. The safety rules behind it are in [File editing](../architecture/file-editing.md).

- Hold any slot to customize the row. Repeat keeps its own menu.
- Popups keep a fixed title and footer; only the body scrolls.
- Saving shows verified progress and can continue in the background.
- A draft survives Activity recreation for the same edit session.
- Status: Implemented.

## Action slots

Available actions: information, editing, artwork, lyrics, playlist, pin, collection links, shuffle, repeat, play count, ten-second seek and More. Default: Info and Add to playlist on the left, Repeat and Shuffle on the right.

- Customize by dragging from the available-action pool into slots, or back to the pool to remove. Settings also opens this editor.
- Tap and accessible alternatives exist for every drag. There is no disabled duplicate of a placed action.
- Layouts: Centered, 2 rows and Compact. Selected actions come first, in canonical order within each group.
- Intentional gaps and hidden assignments are preserved.

Mode icons show state without filled backgrounds: dim when off, full strength when active, a visible 1 for Repeat one, and distinct indicators for Stop, Next paused and Next queue.

## Song information

A bounded frosted popup with tappable artwork and divided sections: file and location, metadata, technical details, listening and dates. Whole Album, Artist, Album Artist and Location rows open their collections. The body scrolls with edge fades. Edit tags and Done stay together at the footer's trailing edge.

## Tag editor

A frosted sheet with a fixed header and footer. Only the fields scroll. Artwork is centred above the checked fields, with change and remove controls on the preview.

| Step | What the user sees |
| --- | --- |
| Edit | Check the fields to change. Individual and bulk edits change only checked fields. |
| Choose image | Rounded previews of readable previous choices, then Gallery and Files. Device-photo access is optional, with a short explanation. |
| Crop | Required for every newly chosen image: square, with pan, pinch, zoom and reset. |
| Review | A preview before anything is written. Write access is requested explicitly. |
| Save | A glass progress popup over the editor, with verified Saved counts. |

The player's artwork action edits only the current song. An album-wide change needs an explicit bulk selection. Limited or revoked photo grants are handled without asking for broad access.

### During and after a save

| Situation | Offered |
| --- | --- |
| Saving | Cancel, which shows Restoring counts and returns to the same draft and scroll position. |
| Saving without undo | Stop after current, in place of Cancel. |
| Any time | Continue in background, which closes the editor while its service continues. |
| Finished | Done, and Retry failed files. |
| Interrupted | Resume or Restore. A recorded cancellation offers Restore only. |

**Settings → Library and tags → Tag jobs and recovery → Tag editing jobs** shows active progress and recent per-file outcomes. A recovery notice stays until dismissed, recovered, or playback moves elsewhere.

## Artwork viewer

The viewer centres the fitted artwork across the full page. Close and Save controls overlay the image; Save writes the original file format to Downloads. Zoom is by pinch or the accessible Zoom actions. Pan bounds follow the fitted image, and released pans decelerate. Recreation preserves zoom and the visible centre.

## Repeat options

Tap Repeat for the ordinary cycle. Hold it for Simple and Advanced options, in one compact popup sized for both modes.

- Advanced has two buttons: **When a song ends** and **When a queue ends**. Each opens a non-scrolling child popup.
- Select stages a child's changes. Cancel or Back discards that child's draft.
- The parent's Apply saves the selected mode. The parent's Cancel discards both drafts.
- Labels keep their full text, system font scaling and 48 dp targets.
- Only when no complete arrangement fits, the popup asks the user to enlarge the window or reduce text size.

What the options do is in [Queues](../architecture/queues.md#repeat).

## Tests

| Topic | Classes |
| --- | --- |
| Slots and modes | `PlayerActionSlotsTest`, `GlobalPlaybackModesTest`, `RepeatOptionsEnvelopeTest` |
| Information | `SongInformationTest` |
| Editing | `TagEditorFlowTest`, `TagSaveProgressPopupTest`, `ArtworkCropTest`, `ArtworkEditRestorationTest`, `ImageChooserTest` |
| Viewer | `ArtworkViewerGestureTest`, `ArtworkSaveTest` |

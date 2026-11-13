# Actions and editing

The player has a row of action slots the user arranges. Each action opens a shared popup: song information, the tag editor, the artwork viewer or repeat options. Editing is always a staged draft with a preview, a verified save and a way back. The safety rules behind it are in [File editing](../architecture/file-editing.md).

- Hold any slot to customize the row. Repeat keeps its own menu.
- Popups keep a fixed title and footer; only the body scrolls.
- Saving shows verified progress and can continue in the background.
- A draft survives Activity recreation for the same edit session.
- Status: Implemented.


## Action slots

The user arranges information, editing, artwork, lyrics, playlist, pin, collection, playback-mode, count, seek and More actions. Defaults are Info/Add to playlist left, Repeat/Shuffle right. Hold opens customization; Settings provides another entry. Tap/accessibility alternatives preserve intentional gaps and hidden assignments across Centered, 2 rows and Compact layouts.

Mode icons express state without filled backgrounds. The action-slot and playback-mode tests specify assignments and indicators.

## Song information

One bounded popup groups file/location, metadata, technical details, listening and dates. Collection rows navigate. Edit and Done stay in its fixed footer; only the body scrolls.

## Tag editor

Editing is a checked-field draft with artwork preview. Every new image requires square cropping; Gallery/Files and limited photo grants remain optional. Review precedes explicit write permission and verified save. The player's artwork action targets only the current song; bulk editing requires selection.

### During and after a save

Cancel restores acquired writes and returns to the draft. Non-undo jobs offer Stop after current. Continue in background leaves the service running. Finished jobs offer Done/Retry; interrupted jobs offer Resume/Restore, with recorded cancellation restricted to Restore.

**Library and tags → Tag jobs and recovery** retains progress and per-file outcomes. [File editing](../architecture/file-editing.md) owns recovery safety; the linked UI tests specify each displayed transition.

## Artwork viewer

The full-page viewer fits artwork and saves its original format to Downloads. Pinch and accessible zoom actions share pan bounds. Recreation preserves zoom and centre; released pans decelerate.

## Repeat options

Tap cycles ordinary Repeat; hold opens Simple/Advanced options. Child drafts select song-end and queue-end choices. Parent Apply commits; Cancel/Back discard the relevant draft. Full labels, font scaling and 48 dp targets determine the envelope. [Queues](../architecture/queues.md#repeat) owns behavior; the envelope test covers non-fitting windows.

## Tests

[PlayerActionSlotsTest](../../app/src/test/java/io/github/anastaci1a/localica/ui/PlayerActionSlotsTest.kt).

[GlobalPlaybackModesTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/GlobalPlaybackModesTest.kt).

[RepeatOptionsEnvelopeTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/RepeatOptionsEnvelopeTest.kt).

[SongInformationTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SongInformationTest.kt).

[TagEditorFlowTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/TagEditorFlowTest.kt).

[TagSaveProgressPopupTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/TagSaveProgressPopupTest.kt).

[ArtworkCropTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtworkCropTest.kt).

[ArtworkEditRestorationTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtworkEditRestorationTest.kt).

[ImageChooserTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ImageChooserTest.kt).

[ArtworkViewerGestureTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtworkViewerGestureTest.kt).

[ArtworkSaveTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtworkSaveTest.kt).

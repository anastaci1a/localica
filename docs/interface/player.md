# Player

The mini-player and the full player are one surface. Tapping or swiping up expands a single outline that carries the glass, artwork, text and transport with it, and every part reverses along the same path. Expansion follows the finger and can be interrupted at any point. Transport controls stay visible throughout.

- The mini-player appears whenever the active queue has a current song, paused included.
- Layout order: artwork, metadata, seek, actions, transport, Queue drawer. There are no volume controls.
- Seeking previews under the finger and commits on release.
- Swipe down or Back collapses and restores the browse state underneath.
- Status: Implemented.

## Mini-player

Artwork and title are inset together inside the leading curve. The body is one rounded tap target; transport is independent. The second line defaults to Album Artist with Artist as fallback, and Artist-first is a setting. Title and artist use 13 and 11 sp.

## Transport icons

- Previous, Play/Pause and Next are filled black icons. Pause shows only while playing, or buffering with playback requested. A paused skip keeps Play.
- Play morphs into Pause. Skips scroll overlapping rounded triangles one step, with symmetric crossfades.
- Repeated skip presses keep the presented position and velocity.
- An imported icon pack shares the morph when its play icon is one shape and its pause icon is two. Other pack art crossfades.

## Expansion

| Rule | Detail |
| --- | --- |
| One outline | Glass, artwork, text and transport share it. The expanded player follows device corner radii. |
| Queue clipping | The Queue layer stays clipped inside the moving outline. Its lower edge appears only as the outline reaches it. |
| Glass | Grows blurrier through expansion. The artwork's colour continuation blends in late and becomes opaque near full size. |
| Timing of parts | Transport persists. Album metadata fades in. Seek and actions appear late; actions move and fade faster than seek. |
| Artwork | Grows at once along one reversible curve derived from geometry. |
| Handle | A 72 dp translucent handle appears late, below the safe top inset, in the page's ink. |

Main tabs leave downward. A song tap that starts playback expands the player by default; add-next and append do not. The handle's tap target and the top-left glass down arrow both collapse the player.

Thresholds: opening commits at 240 dp/s or about 22 dp of travel. Closing the player or the Queue commits at 300 dp/s or 72 dp. Otherwise progress decides. Both stay interruptible while settling. On collapse, artwork and its colour continuation resolve together before detaching, with no stretched backing and no dark flash.

## Layout

Metadata, seek, actions and lyrics have 32 dp side insets. The expanded artwork is centred between the top controls and the title, with 8 dp of padding to each.

| Artwork layout | On a short screen |
| --- | --- |
| Floating cover | Scales down to fit that band and never overlaps. |
| Full-background cover | May overlap both. Softens at its perimeter and merges into the continuation. |

Normal mode places metadata and controls below the cover, with balanced space above the Queue. Lyrics mode lowers them and centres transport between the seek track and the collapsed Queue shelf. See [Artwork and lyrics](artwork-and-lyrics.md).

## Seeking

The seek track uses the shared slider touch model.

| Contact | Result |
| --- | --- |
| Touch-down | Registers at once: the track thickens and the shown position eases to the finger. |
| Hold, or horizontal slide | Locks the scrub. Later vertical travel stays with the scrub. |
| Vertical travel first | Eases back without seeking, and moves the player. |
| Tap | Seeks to the tap point. |
| Release | Commits the finger position. The visual target holds through the asynchronous seek. |
| Release on X, or cancellation | Discards the preview. No seek and no pause. |

While scrubbing, the same X cancel target as list dragging shows above the Queue shelf. Entering and leaving X ease between live playback and the finger position. Haptics follow the [shared rule](materials-and-controls.md#haptics): no ticks for continuous position. An obsolete media generation cannot commit a seek.

## Titles

Title and artist use content-based text direction. During expansion and collapse they use long soft edge fades, never switching to an ellipsis mid-transition. A marquee pauses while the player moves and eases back to its start by the shortest path. After the player settles and the normal delay passes, it restarts. Each overflowing line keeps these phases on its own.

## Failures

Loading never shows a white backing, blanks the player or replaces artwork early. There is no routine loading or connection text. A genuine playback failure uses a rounded dismissible dialog with plain guidance and Retry or Skip.

## Tests

| Topic | Classes |
| --- | --- |
| Layout and expansion | `PlayerLayoutTest`, `PlayerExpansionMotionTest`, `PlayerTransitionTest`, `PlayerArtworkSpacingTest`, `QueuePlayerClipTest` |
| Gestures and seek | `PlayerGestureTest`, `SeekReleaseHandoffTest`, `SeekCancelExitTest`, `SeekCancelMotionTest`, `PlayerSeekHapticsTest` |
| Icons | `IconPackPlayPauseMorphTest` |

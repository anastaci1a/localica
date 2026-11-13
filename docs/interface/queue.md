# Queue drawer

The Queue is a frosted drawer attached to the bottom of the player. It shows one queue at a time and can browse a queue other than the one playing. The list starts attached to the current song and follows playback until the user scrolls away. A return bubble reattaches it.

- One neutral frost throughout, in the player's ink. No fixed dark tint.
- A gesture that starts opening the Queue can only close the Queue, never the player.
- Browsing another queue does not change playback until a song is chosen.
- Only the Queue marks the current song.
- Status: Implemented.


## Drawer

The Queue uses neutral textured frost with the player's [adaptive contrast](../architecture/rendering.md#ink-polarity-and-adaptive-contrast). Tap or upward swipe opens; body/handle pulls settle by velocity and distance. A fresh downward pull from a top-scrolled list closes it. Interruptions restore the open drawer.

During Queue return, a fresh downward swipe outside its presented hitbox immediately drags the player. That contact remains valid if the Queue closes before the finger begins moving. After release, the exposed player's controls accept taps.

The acquired list/drawer owner keeps contact until release; excess travel beyond an edge must be retraced. [Input ownership](../architecture/ui-system.md#input-ownership) prevents one gesture from moving both drawer and player.

## Header

Handle/title sit above down arrow, numbered selector and More. Sort, occurrence/count/runtime summary and playlist destination precede search and songs. Save offers new or existing playlists. Repeat/Shuffle remain in the player; there is no redundant play button.

## Selector

The selector distinguishes browsed and playing queues. Whole-row hold reorders with the shared [drag](selection-and-reordering.md#reordering); accessible move actions remain. Draft reorders commit on release or restore on cancellation/failure. Queue removal and retention rules belong to [Queues](../architecture/queues.md#managing-queues).

## Songs

Only the current Queue occurrence has the denser frost [outline](selection-and-reordering.md#the-outline). Song menus use drag rather than Move up/down. Information links close the player/Queue and open their collection without changing playback.

## Following

Following is either attached to the exact current occurrence or detached at the user's position. Opening positions attached content before first measurement. User scrolling detaches; reopening or Return reattaches. Reaching the corresponding first/last queue edge can reattach without motion.

Following never steals selection, drag, pinch, modal or hidden-page ownership. Touch exploration starts detached. Tests below specify playback, duplicate-occurrence and interruption sequences.

## Return bubble

Detached queues offer a directional Return even when the current item is visible. The command removes its control immediately, reattaches and targets below search where possible. Scoped search offers explicit Return without following. Filtered-out songs have no substitute; selection hides the control and drag owns its slot. The utility row keeps 12 dp clearance.

## Tests

[QueueScrollOwnershipTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/QueueScrollOwnershipTest.kt).

[QueueSelectorDragTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/QueueSelectorDragTest.kt).

[QueueSelectorUnifiedDragTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/QueueSelectorUnifiedDragTest.kt).

[QueueFollowingTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/QueueFollowingTest.kt).

[QueueFollowingPlaybackTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/QueueFollowingPlaybackTest.kt).

[QueueContourTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/QueueContourTest.kt).

[QueueHeaderSummaryTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/QueueHeaderSummaryTest.kt).

[CurrentSongHighlightGeometryTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/CurrentSongHighlightGeometryTest.kt).

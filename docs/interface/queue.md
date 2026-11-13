# Queue drawer

The Queue is a frosted drawer attached to the bottom of the player. It shows one queue at a time and can browse a queue other than the one playing. The list starts attached to the current song and follows playback until the user scrolls away. A return bubble reattaches it.

- One neutral frost throughout, in the player's ink. No fixed dark tint.
- A gesture that starts opening the Queue can only close the Queue, never the player.
- Browsing another queue does not change playback until a song is chosen.
- Only the Queue marks the current song.
- Status: Implemented.

## Drawer

The drawer is visually separate from the player artwork. Its frost uses blurred backdrop colours and fine texture, with the same [adaptive contrast](../architecture/rendering.md#ink-polarity-and-adaptive-contrast) as the player.

| Gesture | Result |
| --- | --- |
| Tap the label, or swipe up on the player | Opens. |
| Pull on body, handle or list | Settles by release velocity and distance. |
| Downward swipe on a header control | Goes to the drawer. |
| Fresh downward pull from a top-scrolled list | Closes. An interrupted pull restores the open drawer. |
| The header's down arrow | Minimizes. |

Once the list or the drawer acquires a contact, it keeps it until release. Finger travel past a list edge must be retraced before scrolling resumes. This is the shared [input rule](../architecture/ui-system.md#input-ownership).

## Header

Top to bottom: handle and Queue title; a row with down arrow, numbered selector and More; a row with sort, the occurrence index and count above the runtime, and add-to-playlist; search; then songs. Save to a new or existing playlist is offered. There is no redundant play button. Queue More omits list view options; Repeat and Shuffle stay in the player.

## Selector

A rounded popup lists the queues. It opens at the browsed queue when that differs from the playing one.

- Numbered rows show the browsing selection and a separate playback marker, with plain rename and delete icons.
- Whole-row long-press reorders queues, using the [song-list drag](selection-and-reordering.md#reordering). The handle has accessible move actions.
- A drag previews order until release. Cancellation or a failed save restores it, without changing browsing or playback.
- The selected queue's name moves as one element between the closed selector and its row.
- With more than one queue, Remove all other queues morphs from a button into a confirmation.

Queue semantics, including deletion and retention, are in [Queues](../architecture/queues.md#managing-queues).

## Songs

A subtly denser, textured frost slab marks the current occurrence, using the [shared outline](selection-and-reordering.md#the-outline). Other lists have no playback highlight. Occurrence menus omit Move up and down, because dragging is available. Information links close the player and Queue, then open the collection without changing playback.

## Following

| State | Behaviour |
| --- | --- |
| Attached (the start state) | Playback changes ease the list to the exact current occurrence, paused playback and duplicate songs included. |
| Detached | The list stays where the user left it. |

- A fresh opening positions the current occurrence before the first row is measured. There is no catch-up scroll.
- User scrolling, or return-to-top, detaches. Reopening a detached drawer reattaches.
- Reaching the queue's start with its first occurrence current, or its end with its last, reattaches without motion.
- Following never steals selection, drag, pinch, modal or hidden-page ownership.
- With touch exploration, the queue starts detached.

## Return bubble

A detached queue offers a directional return bubble, even when the current occurrence is visible. Return reattaches and eases the current song below the search pill, or as close as possible. The arrow points toward that destination.

- Activating Return removes its drawing and input at once, while the command owns the motion.
- Scoped Queue search keeps an explicit return without automatic following.
- A current song that is filtered out has no substitute target.
- Selection hides the bubble. A drag owns its slot.

Queue Return and the drag-cancel control share 12 dp of clearance below the circle.

## Tests

| Topic | Classes |
| --- | --- |
| Gestures | `QueueScrollOwnershipTest` |
| Selector | `QueueSelectorDragTest`, `QueueSelectorUnifiedDragTest` |
| Following | `QueueFollowingTest`, `QueueFollowingPlaybackTest` |
| Appearance | `QueueContourTest`, `QueueHeaderSummaryTest`, `CurrentSongHighlightGeometryTest` |

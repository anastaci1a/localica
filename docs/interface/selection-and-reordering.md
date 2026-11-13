# Selection and reordering

Selecting an item lifts it: its outline grows, a shadow deepens, and its backdrop shows magnified with light frost. One outline shape serves selection, the current queue song and dragged cards. Selection is a set of occurrences that survives navigation.

Actions on it come from one floating pill. In a manual list, a held selection can be dragged to a new place.

- Neighbouring selections merge into one outline.
- Library songs are selected by song. Playlist and queue entries are selected by entry, so repeats stay separate.
- Selection order is kept: actions apply in the order items were chosen.
- Reordering works only in a complete, manual, unfiltered queue or playlist.
- Status: Implemented.


## The outline

Selection, current queue occurrence and dragged cards share one outline. Padding is 3.5 dp; the outline radius is artwork radius plus padding. Rows without artwork use 10 dp. Artwork stays inside its outline. The geometry and pixel tests specify merging and boundary cases.

## Lift

One interruptible 220 ms ease raises the item through its retained recording. Growth is bounded by neighbour gaps and 4 dp per side, retaining at least 1 dp clearance. Glass shadow spread and Lift shadow strength control the shared shadow.

### Merging

Side-adjacent selections connect through continuous lobes and concave fillets. Frost and shadow follow that path. Diagonal neighbours and unselected holes stay separate; fillets cap at 4 dp within an 8 dp band.

## What is selected

Library membership uses song identity across routes. Playlist/queue membership uses container plus occurrence identity; neither selects the other. Selection survives navigation, queries, sorting and recreation, but fresh sessions start empty. Actions retain selection order; editing deduplicates physical files.

## Entering and leaving

Mutable song holds select and defer the pill until release so the gesture may continue into a drag. Other items use menu selection. Song taps toggle active selection; collection taps navigate. Close, Back or successful actions exit; failures and cancellation preserve selection.

## The selection pill

One glass pill owns count and actions, including collapse to a minimized count. More supplies playback, queue, editing, removal and range commands. Its list inset prevents occlusion without shortening Queue content. Accessible selection shows it immediately. The selection tests specify actions, restoration and inset interruptions.

## Reordering

Reordering requires one complete, manual, unfiltered source. Held selected occurrences keep source order and move as one stack with an insertion gap. Edge scrolling, landing and the X cancel target share the same owner.

A successful landing completes selection. Cancellation, interruption or failed saves restore order, selection and pill; playback cursors stay valid. Route or source-order changes cancel the drag, and a commit rejects intervening order changes. The drag tests cover pickup, motion, cancellation, refresh and handoff.

## Tests

[SelectionMergeGeometryTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SelectionMergeGeometryTest.kt).

[SelectionMergeSequenceTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SelectionMergeSequenceTest.kt).

[ItemOutlinePixelTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ItemOutlinePixelTest.kt).

[ConcaveFilletPathParityTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ConcaveFilletPathParityTest.kt).

[HoverSelectionTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/HoverSelectionTest.kt).

[LiftShadowTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/LiftShadowTest.kt).

[CollectionSelectionTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/CollectionSelectionTest.kt).

[CrossViewSelectionTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/CrossViewSelectionTest.kt).

[SelectionPillTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SelectionPillTest.kt).

[SelectionPanelRegressionTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SelectionPanelRegressionTest.kt).

[SelectionInsetScrollTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SelectionInsetScrollTest.kt).

[DragReorderTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/DragReorderTest.kt).

[KeyedDragTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/KeyedDragTest.kt).

[DragStackMaterialTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/DragStackMaterialTest.kt).

[DragHandoffArtworkTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/DragHandoffArtworkTest.kt).

[SongDragViewportTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SongDragViewportTest.kt).

[SharedKeyedDragRefreshTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SharedKeyedDragRefreshTest.kt).

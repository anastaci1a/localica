# Selection and reordering

Selecting an item lifts it: its outline grows, a shadow deepens, and its backdrop shows magnified with light frost. One outline shape serves selection, the current queue song and dragged cards. Selection is a set of occurrences that survives navigation. Actions on it come from one floating pill. In a manual list, a held selection can be dragged to a new place.

- Neighbouring selections merge into one outline.
- Library songs are selected by song. Playlist and queue entries are selected by entry, so repeats stay separate.
- Selection order is kept: actions apply in the order items were chosen.
- Reordering works only in a complete, manual, unfiltered queue or playlist.
- Status: Implemented.

## The outline

Selection, the current queue occurrence and held drag cards share one item outline. Its corners are concentric with the artwork's. Every item style uses one padding $d$ of 3.5 dp between content and outline:

$$
r_{\text{outline}} = r_{\text{artwork}} + d
$$

Artwork always stays inside, and the outline extends at most $d$ beyond its item. Rows without artwork use a 10 dp radius.

## Lift

- The lift is one 220 ms ease-out. Deselection reverses it, and interruptions continue from the current appearance.
- One uniform scale keeps corner centres. Growth takes at most a quarter of the gap to each neighbour's content, capped at 4 dp per side, and leaves at least 1 dp clear.
- Lifted items never overlap neighbours.
- The lift shadow uses the Glass shadow spread and the Lift shadow strength setting.
- A lifted item draws from its own retained recording, so no cover or label vanishes on select or deselect.

### Merging

Side-adjacent selections share one outline. Lobes and rounded concave fillets connect or part continuously. Fillets are capped at 4 dp, with an 8 dp connection band. Frost and shadow follow the same path. Diagonal gallery neighbours and unselected holes stay separate.

## What is selected

| Source | Identity |
| --- | --- |
| Library songs | The song. Shared across albums, artists, folders, Songs, search and subviews. |
| Playlist and queue entries | The container and the saved entry. Repeated songs are independent. |

Library membership never selects playlist or queue entries, or the reverse. Selection survives routes, tabs, queries, sorting, rotation and backgrounding. Select-all appends in collection order; reselecting appends last. A fresh session starts empty. Files are deduplicated for editing.

## Entering and leaving

- Holding a mutable playlist or queue song selects at once. Its pill waits for release, so moving into a drag never flashes it.
- Other items use Select song or Select all songs in their menu.
- While selection is active, a song tap toggles membership. Collection taps still navigate.
- Close, [Back](navigation.md#back) or a successful action exits. Cancel, failure or an empty selection keeps the mode.
- Accessible selection without a pointer shows the pill immediately.

## The selection pill

One compact glass pill floats above the mini-player: close, count, Play next, Add to playlist, More and a divided collapse. It follows the page's light or dark ink.

- More morphs into a menu: Play now, Add to queue, New queue, Edit tags, Remove from the source, Select all and Select range.
- Collapse morphs the same surface into a minimized count pill. A tap morphs it back.
- In the Queue the pill floats above the list without shortening it. The list end pads by the pill's occupancy.
- When the pill would cover a selected item, the list scrolls by exactly its added inset. Touch interrupts that scroll.
- Remove-from-source requires one editable source.

## Reordering

Hold-drag moves the held occurrence, or all selected occurrences from that source, keeping their source order. Cross-source selections and filtered views cannot reorder.

| Stage | What happens |
| --- | --- |
| Pickup | Selected rows fly from their hover into a stack. The first song is on top, with at most two empty cards and a count badge. |
| Drag | The stack tracks the finger. A one-row insertion gap opens, surrounding rows ease, and the list edge-scrolls. |
| Drop | Rows land on rendered item bounds and crossfade into normal rows. Selection finishes after a successful landing. |
| Cancel | Releasing on the X target cancels and restores the pill. |

- While dragging, the pill minimizes and cannot expand. A glass X circle stays above the stack in the bottom control row.
- Hovering X tints it red with one firm pulse. Every finger lift after a drag gives one strong pulse.
- Queue playback highlighting fades at the pickup location. It does not travel with the stack.
- A system interruption or failed save restores order, selection and the pill. Playback cursors are preserved.
- A route change or a source order change cancels the drag. A commit rejects an intervening order change.

## Tests

| Topic | Classes |
| --- | --- |
| Outline and merge | `SelectionMergeGeometryTest`, `SelectionMergeSequenceTest`, `ItemOutlinePixelTest`, `ConcaveFilletPathParityTest` |
| Lift | `HoverSelectionTest`, `LiftShadowTest` |
| Selection state | `CollectionSelectionTest`, `CrossViewSelectionTest` |
| Pill | `SelectionPillTest`, `SelectionPanelRegressionTest`, `SelectionInsetScrollTest` |
| Reordering | `DragReorderTest`, `KeyedDragTest`, `DragStackMaterialTest`, `DragHandoffArtworkTest`, `SongDragViewportTest`, `SharedKeyedDragRefreshTest` |

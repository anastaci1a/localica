# Search and sort

Search and Sort/View are both glass pills that expand into one frosted panel and return into the pill. Search is a draft until it is submitted. Sort and View share one draft until Save. A list can keep its own override, or follow the default for its type. Sorting a queue or playlist is different: it physically reorders the list.

- Global search opens from the bottom Search control. Scoped search is optional per list.
- Each completed search is a history step that Back retraces.
- Sort/View changes apply on Save, to both tabs at once.
- Queue and playlist ordering actions apply immediately and can be reverted.
- Status: Implemented.

## Global and scoped search

| Kind | Where | Scope |
| --- | --- | --- |
| Global | Bottom Search opens a search page. Library root has no top search box. | All types, whatever filters are visible. |
| Scoped | A quick-search pill in the list, enabled in the **View** tab. Album contents omit it. | The current list. Results omit subtitles. |

Neither is a main tab. A query or scope change starts results at the top. Initial results use the shared [loading reveal](browsing.md#loading).

### The search panel

- Search pills and filter bubbles share height and placement: 40 dp visible inside a 48 dp target. They grow together for large text.
- The pill expands at once into one frosted panel, with one rectangle animation for size and position. The field stays unscaled and at the top.
- The field takes focus as soon as layout is ready. The panel aims above the keyboard's reported destination, and retargets from its current rectangle as the real height arrives.
- A cached, hidden keyboard height is never borrowed.
- Only the visible vacated header space collapses, so the next row lands where the pill's top was. The original scroll offset is restored on return.
- A child menu freezes the parent's position until it returns.

### Draft and history

| Event | Result |
| --- | --- |
| Typing | Edits one draft. |
| Keyboard submit, or opening a result | Commits the draft as a completed search. |
| Back with text | First ends editing. |
| Empty Back, or outside tap | Dismisses: one direct return to the pill while the keyboard hides on its own. |
| Back after editing ends | Retraces completed searches without reopening the keyboard. |

A completed search keeps its query, scope, filters, sort, results position and identity. Opening a result or the player preserves the source. Actual dismissal clears a scoped session; temporary covering does not.

## The Sort/View panel

The whole divided pill expands into one panel that shows the tapped tab. A fixed icon-and-label tab header sits above the settings. The panel keeps one size across tabs and option states: it measures every alternative face and wraps the largest within the shared window cap.

Both tabs share one draft. Switching tabs keeps edits. Save applies both; Cancel discards them.

| Tab | Offers |
| --- | --- |
| Sort | Applicable fields: title, count, duration, year, dates, artist, Album Artist, composer, genre. Direction. |
| View | Presentation, list Size or gallery Items per row, extra information, scoped search where applicable. |

### Defaults

| List | Default order |
| --- | --- |
| Catalogs and search | Title, ascending. |
| Album songs | Disc and track. |
| Artist and Album Artist contents | Year, ascending. |
| Playlists and queues | Saved order. |
| Recent / Frequent / Pins | Newest / most played / manual. |

Date added and modified start newest-first. Most played and Longest listened start highest-first. All are reversible. Counting rules are in [Playback](../architecture/playback.md#qualified-plays).

### Extra information

Choices start with None, then Follow sort order. Unfiltered Library and global search also offer Automatic, their default: each row type inherits its own catalog's extra information and sort.

## Overrides

Collection and search panels show **Save only for this list** above Cancel and Save. Main catalog tabs have no override. A reset returns the list to inheritance. Search text never creates an override. Catalog tabs and the corresponding Library filters share preferences.

## Ordering queues and playlists

In queues and playlists the override switch belongs to View only. The Sort tab acts on the physical order:

| Control | Behaviour |
| --- | --- |
| Randomize | Immediate. |
| Revert | To the right of Randomize. Appears while the order differs from the order when the panel opened. |
| Reverse | Directly below. Immediate. |
| Method and direction | Staged, with Close and Apply. Apply stays open and disables once applied. |

The model behind these, including the Manual baseline, is in [Queues](../architecture/queues.md#sorting-and-the-manual-baseline).

After an order change, the viewport moves once:

- Successful randomization scrolls to the top.
- Queue sort and revert follow the active occurrence, otherwise the top, after matching rows publish.
- Unchanged or failed edits, and View-only saves, leave the viewport alone.
- New navigation or list input cancels pending movement.

## Tests

| Topic | Classes |
| --- | --- |
| Search | `ListSearchTest`, `SearchImeMotionTest`, `SearchDismissalLifecycleTest`, `SearchPendingReturnGeometryTest` |
| History | `ScopedSearchHistoryTest`, `SearchHistoryTest` |
| Sort/View | `BrowseOptionsFaceContractTest`, `InheritedInformationPreferencesTest`, `SearchSortResetTest` |
| Ordering | `BrowseOrderViewportTest`, `QueueOrderActionAdmissionTest`, `LiveOrderDialogTest` |

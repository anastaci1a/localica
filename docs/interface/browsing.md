# Browsing

Every list of songs or collections uses the same templates. A list has a view (a list size or a gallery), and a pinch steps between neighbouring views. Loading follows three rules: wait for the catalog and never for artwork bytes, match the final geometry, and reveal each thing once.

- Views run from Smallest list to a one-column gallery, in one fixed order.
- A pinch changes one adjacent view per gesture.
- Cached content appears at once and never replays its loading.
- Every overflowing list gets a scrollbar and an accessible scroll action.
- Status: Implemented.

## Views

| View | Layout |
| --- | --- |
| Smallest, Small | No artwork. Mutable lists show occurrence positions; albums show track numbers. Small keeps a 40 dp minimum inner row height. |
| Tracklist | Album, playlist and queue contents only. A track number beside the normal title and subtitle, without a cover. |
| Compact | Fixed row pitch and text size, with leading artwork sized to Normal's gap-to-art ratio. |
| Normal (default), Large | Covers match Gallery 5 and Gallery 3. Large uses 18 sp titles on up to two lines. |
| Gallery 1–5 | Items per row. Smaller text and tighter padding at three or more columns. |

- Dense sizes keep one line of extra information just before the overflow.
- Rows grow for larger system fonts. Long text ellipsizes.
- Every gallery uses one fixed 8 dp gap, between covers and from an item's lowest text line to the next cover. Outer margins are 16 dp.
- Album carousels are one dense gallery row with the same gap and margins.

Catalog tabs and the matching Library filters share sort and view preferences. Collections and search can hold an [override](search-and-sort.md#overrides).

## Pinch

Pinch outward to step through: Smallest → Small → Tracklist (where offered) → Compact → Normal → Large → Gallery 5 → 4 → 3 → 2 → 1. Pinch inward to reverse.

- One step per gesture. All fingers lift before another step.
- A 4% span change picks the direction. The first frame keeps the source, then geometry catches up to the fingers over 100 ms.
- A reversal reuses the frozen endpoints. A pinch during settlement keeps the shown progress.
- The scroll range anchors at the top or bottom when already there. Otherwise the content under the pinch midpoint stays put.
- Items that leave the viewport fade along their whole path, in exit order. Arrivals mirror departures.
- Selection lift, equal outer margins and decoded artwork carry through the transition.
- A modal interruption discards pending steps.

View controls are the accessible alternative. Reduced motion changes layout immediately. How snapshots are drawn is in [Rendering](../architecture/rendering.md#pinch-snapshots).

## Library filters

With no filter selected, Library combines collections: Albums, Album Artists and Playlists by default, optionally Artists and Folders. **Settings → Library and tags → Library filters** owns the visible filters, their order and the unfiltered collection set. Holding a filter opens a shortcut there.

- Selecting a filter bubble moves it to the far left. Tapping it again clears it and restores its place. There is no All button.
- A filter change starts the resulting list at the top. Previous results fade at once.
- New results appear only with their own saved sort and layout.
- Hiding the active filter clears the selection. Empty filter sets are valid.

## Loading

| Rule | Meaning |
| --- | --- |
| Wait for the catalog | The first view waits for every catalog source it uses, never for artwork bytes. |
| Match geometry | Skeletons fill the viewport with the final artwork, title and information shapes, on one shimmer phase. |
| Reveal once | Ready rows reveal at a fixed radial speed from the top-left. Cached returns do not replay loading. |

- Loading artwork is a gradient, never a music note. Only confirmed missing artwork shows the note.
- A placeholder uses its item's average artwork colour when known, otherwise neutral grey.
- Memory-cached covers show at once. Newly decoded covers fade in over 300 ms.
- While a row's artwork loads, its text shows skeleton bars that match each line, then crossfades in with the artwork.
- Row actions wait for loading artwork. Invisible actions take no input.
- Reduced motion removes shimmer, reveal and reflow motion.

### Covers

Artists and Album Artists use circles; albums and playlists use rounded squares. An automatic collection cover is the first displayed member under that collection's sort. Playlists and queues follow their saved order. A first member without artwork keeps the placeholder. A custom cover takes precedence.

A tapped cover is reused at once in its detail header, and its higher-resolution replacement crossfades in.

## Item actions

- Row ellipses open actions on tap, and select on hold.
- Gallery actions are on long-press. There are no overlaid menu buttons.
- Holding a collection or non-mutable song opens anchored actions. Holding a mutable song selects it; moving the same finger starts [reordering](selection-and-reordering.md#reordering).
- Actions: select songs, queue and playlist destinations, pinning. Playlists add image, rename and delete.

## Scrollbar

The scrollbar track runs between the header controls and the lowest bottom control, and follows them as they move.

- Movement fades the thumb in. It fades out 1.2 seconds after release and settle.
- Only the visible thumb takes touches, within at least 48 dp. Below half opacity, touches reach the list.
- The thumb captures its touch until release. Dragging moves the list by the finger's travel, never by jumping.
- Letter or year previews show only while dragging under title or year sort.

## Long scrolls

Return to top, Scroll to songs and return-to-current cross any distance in about 500 ms. **Settings → Interface → Motion → Long scroll animation** picks the style.

| Style | Behaviour |
| --- | --- |
| Fly-through (default) | Keeps the rush, lands a fresh region every few frames, defers artwork. |
| Jump, then glide | Jumps once near the target, then glides. |
| Fade | Dims the list for about 120 ms, jumps while hidden, fades in during the glide. |

Every style ends exactly on the target, stops early once the target is visible, and is cancelled by touch. Reduced motion jumps.

## Return controls

A scrolled song list offers a bottom-centre return-to-top control. It appears only away from the list start, and never clears filters. The active Queue uses its [following control](queue.md#following) instead. Return and drag-cancel controls sit in the utility row above the bottom chrome, and list-end padding includes them.

## Tests

| Topic | Classes |
| --- | --- |
| Geometry | `ListDensityGeometryTest`, `GalleryGeometryTest`, `SmallestTitleGapTest`, `CompactCollectionArtworkTest` |
| Pinch | `BrowsePinchTest`, `BrowsePinchTransactionTest`, `PinchOnsetTest`, `PinchSlowFrameTest`, `PinchDepartureFadeTest`, `PinchRetargetTest`, `PinchReleaseHandoffTest`, `PinchArtworkContinuityTest` |
| Filters | `LibraryFilterTest`, `LibraryFilterMotionTest`, `LibraryFilterPresentationTest`, `FilterSwitchResetTest` |
| Loading | `BrowseLoadingRevealTest`, `CollectionLoadingRevealTest`, `ArtworkRevealTest`, `ArtworkTextRevealTest`, `ArtworkTextSkeletonLinesTest`, `ArtworkTintPlaceholderTest`, `LoadingMaterialOpacityTest`, `GallerySkeletonGeometryTest`, `ArtworkLoadingFallbackTest` |
| Actions | `GalleryMenuInputTest`, `RowMenuRevealTest`, `ArtistCollectionActionsTest` |
| Scrollbar and scrolls | `ScrollbarBehaviourTest`, `ScrollbarContinuityTest`, `ScrollbarRowInputTest`, `ScrollbarScreenshotTest`, `FarScrollScrollbarTest`, `AutoScrollDrawnPlacementTest`, `ArtworkScrollLoadingTest` |
| Return controls | `ListReturnControlsTest`, `SearchReturnTopTest`, `FloatingControlMotionTest` |

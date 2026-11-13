# Browsing

Every list of songs or collections uses the same templates. A list has a view (a list size or a gallery), and a pinch steps between neighbouring views. Loading follows three rules: wait for the catalog and never for artwork bytes, match the final geometry, and reveal each thing once.

- Views run from Smallest list to a one-column gallery, in one fixed order.
- A pinch changes one adjacent view per gesture.
- Cached content appears at once and never replays its loading.
- Every overflowing list gets a scrollbar and an accessible scroll action.
- Status: Implemented.


## Views

Views run from Smallest, Small and Tracklist through Compact, Normal, Large and Gallery 5–1. Tracklist is limited to album, playlist and queue contents. Normal is the default. Geometry tests below specify row pitch, artwork correspondence and font sizing.

All galleries share 8 dp gaps and 16 dp outer margins. Rows grow for system fonts; long text ellipsizes. Dense views retain one information line before overflow. Catalog tabs and corresponding Library filters share sort/view preferences; details and search may [override](search-and-sort.md#overrides) them.

## Pinch

A pinch selects one adjacent view after a 4% span change; fingers must lift before another step. It preserves content beneath the midpoint or the reached list edge. Frozen endpoints allow reversal and interruption without jumps. Selection, margins and decoded artwork carry through. Modal interruption discards pending steps.

View controls provide the accessible alternative; Reduced motion changes immediately. [Rendering](../architecture/rendering.md#pinch-snapshots) owns snapshots. The pinch tests specify onset, departure, retargeting and settlement sequences.

## Library filters

Unfiltered Library combines Albums, Album Artists and Playlists by default, optionally Artists and Folders. **Library and tags → Library filters** owns visibility, order and the unfiltered set. Holding a filter opens that setting.

Selecting a bubble moves it left; tapping again clears it and restores its position. Filter changes start at the top and publish only matching saved sort/layout results. Hiding an active filter clears it; empty filter sets are valid.

## Loading

Loading waits for catalog sources, never artwork bytes. Skeletons use final geometry and one shimmer phase. Ready rows reveal once at a fixed radial speed; cached returns appear immediately. Artwork, text and actions share readiness so invisible controls cannot receive input.

Unknown artwork uses a gradient in its known average colour or neutral grey; only confirmed absence uses a note. New decodes fade in over 300 ms. Reduced motion removes shimmer, reveal and reflow. Loading test classes below specify each transition.

### Covers

Artist covers are circular; albums/playlists use rounded squares. Automatic covers come from the first displayed member under the collection's canonical sort; playlists and queues use saved order. A custom cover wins. Cached detail artwork sharpens by crossfade.

## Item actions

Row overflow taps open actions and holds select. Galleries use long-press without overlaid menus. Mutable song holds select and can continue into [reordering](selection-and-reordering.md#reordering); other holds open anchored actions. Shared collection actions own queue, playlist and pin commands.

## Scrollbar

The scrollbar spans the header-to-bottom-control viewport and follows those controls. Its visible thumb owns drag until release, moving by finger travel without jumping. It fades after 1.2 seconds of rest; below half opacity input reaches the list. Title/year previews appear only under matching sorts.

## Long scrolls

**Interface → Motion → Long scroll animation** chooses Fly-through (default), Jump then glide, or Fade. Each targets about 500 ms across any distance, stops when visible, lands exactly and cancels on touch. Reduced motion jumps. Scroll tests specify intermediate rendering.

## Return controls

A scrolled song list offers return-to-top without clearing filters. The active Queue uses [following](queue.md#following). Return and drag-cancel share the utility row above bottom chrome, included in list-end padding.

## Tests

[ListDensityGeometryTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ListDensityGeometryTest.kt).

[GalleryGeometryTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/GalleryGeometryTest.kt).

[SmallestTitleGapTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SmallestTitleGapTest.kt).

[CompactCollectionArtworkTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/CompactCollectionArtworkTest.kt).

[BrowsePinchTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/BrowsePinchTest.kt).

[BrowsePinchTransactionTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/BrowsePinchTransactionTest.kt).

[PinchOnsetTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PinchOnsetTest.kt).

[PinchSlowFrameTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PinchSlowFrameTest.kt).

[PinchDepartureFadeTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PinchDepartureFadeTest.kt).

[PinchRetargetTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PinchRetargetTest.kt).

[PinchReleaseHandoffTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PinchReleaseHandoffTest.kt).

[PinchArtworkContinuityTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PinchArtworkContinuityTest.kt).

[LibraryFilterTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/LibraryFilterTest.kt).

[LibraryFilterMotionTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/LibraryFilterMotionTest.kt).

[LibraryFilterPresentationTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/LibraryFilterPresentationTest.kt).

[FilterSwitchResetTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/FilterSwitchResetTest.kt).

[BrowseLoadingRevealTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/BrowseLoadingRevealTest.kt).

[CollectionLoadingRevealTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/CollectionLoadingRevealTest.kt).

[ArtworkRevealTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtworkRevealTest.kt).

[ArtworkTextRevealTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtworkTextRevealTest.kt).

[ArtworkTextSkeletonLinesTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtworkTextSkeletonLinesTest.kt).

[ArtworkTintPlaceholderTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtworkTintPlaceholderTest.kt).

[LoadingMaterialOpacityTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/LoadingMaterialOpacityTest.kt).

[GallerySkeletonGeometryTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/GallerySkeletonGeometryTest.kt).

[ArtworkLoadingFallbackTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtworkLoadingFallbackTest.kt).

[GalleryMenuInputTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/GalleryMenuInputTest.kt).

[RowMenuRevealTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/RowMenuRevealTest.kt).

[ArtistCollectionActionsTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtistCollectionActionsTest.kt).

[ScrollbarBehaviourTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ScrollbarBehaviourTest.kt).

[ScrollbarContinuityTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ScrollbarContinuityTest.kt).

[ScrollbarRowInputTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ScrollbarRowInputTest.kt).

[ScrollbarScreenshotTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ScrollbarScreenshotTest.kt).

[FarScrollScrollbarTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/FarScrollScrollbarTest.kt).

[AutoScrollDrawnPlacementTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/AutoScrollDrawnPlacementTest.kt).

[ArtworkScrollLoadingTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtworkScrollLoadingTest.kt).

[ListReturnControlsTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ListReturnControlsTest.kt).

[SearchReturnTopTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SearchReturnTopTest.kt).

[FloatingControlMotionTest](../../app/src/test/java/io/github/anastaci1a/localica/ui/FloatingControlMotionTest.kt).

# Rendering

Everything on screen that is not glass follows three rules. Decode and prepare off the main thread, into bounded caches. Keep showing the last complete picture until its replacement is ready. Give every drawing exactly one owner at a time. Text ink follows one page-wide polarity measured from the real background.

- Artwork: bounded caches, at most three decodes at once, and display-sized buckets.
- Recordings: retained drawings let pinch, lift and transitions reuse pixels without drawing twice.
- Ink polarity: one black-or-white choice per page, eased as one fraction.
- Shadows: one shape-aware field per group, in every renderer.
- Status: Implemented.


## Artwork pipeline

Cancellable background decodes share one request per cover/size bucket, with at most three concurrent decodes. Buckets are 128–768 px; the bounded cache uses one eighth of heap class within 12–48 MB. Cached smaller covers remain until replacements arrive. Revision checks reject stale publication; rescans/edits invalidate affected caches.

Viewport leases retain current tabs and two Back destinations. Long automatic scrolls defer uncached decoding. One browse-order snapshot serves cards, Home and details. The cache, concurrency and decode tests specify eviction and cancellation.

### Average colours

A separate modification-keyed index stores linear-light averages from 16×16 downscales. A single-flight worker prioritizes visible placeholders and player neighbours; decodes donate averages. Bulk work yields to foreground work/scans. Publishing colours never republishes the catalog and is limited to once per 400 ms.

### Player artwork

Current/next artwork and palette bundles are prepared together in bounded caches. The previous complete bundle stays until its replacement is ready. Android 12+ uses live blur; older devices blend prepared endpoints. Washes, frost and snapshots reuse a separable box filter.

## Recordings and readback

Retained drawings have one owner. Native copies serialize per source and retain parents until completion, even after caller cancellation. Cleanup stays on the UI thread; a deadline never releases pending native work. Generation checks prevent stale publication. Lifted items draw their own retained recording, while current-song grain is an immutable display-resolution bitmap.

## Pinch snapshots

Pinch freezes bounded source drawings before reflow and moves them to layout-only destinations. One frame value drives placement/drawing. Bitmap leases, anchors and endpoints survive reversal or replacement settlement; cleanup tokens belong to each settlement. Legacy atlases preserve alpha and stride. Offscreen headers beyond two viewports are omitted.

The lifetime and placement tests specify ownership transitions; [Browsing](../interface/browsing.md#pinch) owns the visible gesture.

## Player expansion

One progress drives geometry and masks. Navigation observes thresholds only; Queue moves a fixed viewport. Glyphs scale without font remeasurement. Cover masks share one offscreen layer and settled artwork draws directly.

## Ink polarity and adaptive contrast

One page-wide black/white decision follows the actual backing, including crossfades. Matching tone preparation must finish before a new decision publishes; otherwise the last verified decision remains, initially white. Ink changes do not retone the backing, and asynchronous presentation is not atomic.

One interpolated light-mix fraction serves text, glass and frost. Lyric emphasis keeps its own clock. Brightness trim preserves brightness order by limiting applied trim to the reciprocal of weighting. [Settings](../interface/settings.md#glass-settings) owns ranges. Prepared backdrops appear on expansion's first frame and remain until replaced.

## Shadows

Moving sibling groups share one shape-aware shadow; pages and modal descendants do not. It inherits owner transforms, fades and padded clips. Geometry invalidates its texture; translation reuses it. Popup/trigger handoff transfers exclusive ownership. Every renderer uses the same path. Shadow tests specify falloff, continuity and material parity.

## Tests

[ArtworkCacheTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtworkCacheTest.kt).

[ArtworkCacheSizingTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtworkCacheSizingTest.kt).

[ArtworkCacheConcurrencyTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtworkCacheConcurrencyTest.kt).

[CoverDecodeTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/CoverDecodeTest.kt).

[ArtworkColorStorageTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtworkColorStorageTest.kt).

[RetainedLayerReadbackTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/RetainedLayerReadbackTest.kt).

[NativeWindowCopyOwnershipTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/NativeWindowCopyOwnershipTest.kt).

[BitmapLifetimeStressTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/BitmapLifetimeStressTest.kt).

[PinchReadbackLifetimeTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PinchReadbackLifetimeTest.kt).

[BrowsePinchLayersTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/BrowsePinchLayersTest.kt).

[PinchItemPlacementLifecycleTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PinchItemPlacementLifecycleTest.kt).

[CollectionHeaderPinchFrameTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/CollectionHeaderPinchFrameTest.kt).

[PlayerBackgroundPolarityTest](../../app/src/test/java/io/github/anastaci1a/localica/ui/PlayerBackgroundPolarityTest.kt).

[PlayerContrastTest](../../app/src/test/java/io/github/anastaci1a/localica/ui/PlayerContrastTest.kt).

[PlayerBackdropGradingTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PlayerBackdropGradingTest.kt).

[BrightnessWeightingTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/BrightnessWeightingTest.kt).

[PlayerLyricsThemeEaseTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PlayerLyricsThemeEaseTest.kt).

[LyricsLineInkEaseTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/LyricsLineInkEaseTest.kt).

[PlayerExpandReadinessTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PlayerExpandReadinessTest.kt).

[GlassShadowTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/GlassShadowTest.kt).

[ShadowFalloffTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ShadowFalloffTest.kt).

[ShadowContinuityTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ShadowContinuityTest.kt).

[NativeShadowFieldConformanceTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/NativeShadowFieldConformanceTest.kt).

[LiftShadowTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/LiftShadowTest.kt).

[DragStackFrameTimeTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/DragStackFrameTimeTest.kt).

[GalleryLiftFrameTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/GalleryLiftFrameTest.kt).

[SelectionMotionRenderingTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SelectionMotionRenderingTest.kt).

[PinchRetainedHighlightTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PinchRetainedHighlightTest.kt).

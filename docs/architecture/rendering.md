# Rendering

Everything on screen that is not glass follows three rules. Decode and prepare off the main thread, into bounded caches. Keep showing the last complete picture until its replacement is ready. Give every drawing exactly one owner at a time. Text ink follows one page-wide polarity measured from the real background.

- Artwork: bounded caches, at most three decodes at once, and display-sized buckets.
- Recordings: retained drawings let pinch, lift and transitions reuse pixels without drawing twice.
- Ink polarity: one black-or-white choice per page, eased as one fraction.
- Shadows: one shape-aware field per group, in every renderer.
- Status: Implemented.

## Artwork pipeline

| Stage | Rule |
| --- | --- |
| Decode | Cancellable reads. At most three parallel decodes, one per cover and size bucket. Buckets are 128, 192, 256, 384 and 768 px. |
| Cache | Decoded covers take an eighth of the heap class, between 12 and 48 MB. Missing results are cached too. |
| Display | Cached artwork is read synchronously. A smaller cover of the same identity stays while a larger decode loads. |
| Invalidate | Rescans and edits invalidate artwork and lyric caches. Requests are revision-checked, so a stale decode never publishes. |

A long automatic scroll keeps cached covers but defers uncached decodes until it settles or is interrupted. Manual browsing loads normally. Artwork is never scraped.

Activity-local viewport leases hold bitmap references for the current tabs and two Back destinations. A recycled row never keeps another identity. One immutable browse-order snapshot resolves collection ordering for cards, Home and details, so no card reads preferences itself.

### Average colours

Loading placeholders use the item's average artwork colour. Colours live in the `track_artwork` table, keyed by track with the source modification time. A changed time marks the colour stale. Catalog writes never wait for colours, and colour writes never republish the catalog.

- The average is a 16×16 filtered downscale, averaged in linear light.
- One single-flight worker computes missing colours from 64 px decodes, without GPU upload.
- Visible placeholders and the player's current and next tracks queue first. Display decodes donate their averages.
- Bulk work waits for foreground decodes, and stops during a scan or while the app is stopped.
- The index republishes at most every 400 ms. Only loading placeholders observe it.

### Player artwork

The player keeps its complete previous artwork and palette until the next pair is ready. Bundles for the current song and its neighbours, with soft edges, are prepared off the main thread within a bounded cache. That budget excludes the decoded cache and live frames.

Covers blend with a colour continuation. Blur-through uses a live blur on Android 12+ and prepared soft-cover endpoints on older versions. Neither filters a fresh bitmap each frame. Washes, panel frost and older-device snapshots share one separable box-filter kernel.

## Recordings and readback

A recording is a retained drawing of one owner. Stable recording owners paint all text below all artwork.

- Readbacks serialize per source. The layer parent is retained until native copying completes, even after the caller cancels.
- A deadline cannot release pending native work. Release and cancellation checks run on the owning UI thread.
- A lifted item is drawn only from its own retained recording. The host adds the lifted drawing and never takes ownership, so no cover or label vanishes.
- The current-song texture is kept as an immutable bitmap at display resolution. Grain is never replayed during scrolling.
- Thumbnail captures join matching draw generations before copying. A stale generation cannot publish.

## Pinch snapshots

A pinch freezes bounded source drawings before reflow, then moves them to layout-only destinations.

- Progress updates inside the frame callback, so placement and drawing observe one value.
- Live and virtualized cells share fixed text and artwork endpoints.
- The source-viewport bitmap lease, drawings and midpoint anchor survive reversal, release and handoff.
- Each settle owns its cleanup token. Replacing it keeps the transaction's frozen endpoints.
- Atlases preserve exact pixels with separated regions, premultiplied alpha and row stride on legacy Android.
- A header more than two viewports away is offscreen. The pinch proceeds without it.

What the user sees is in [Browsing](../interface/browsing.md#pinch).

## Player expansion

One expansion progress controls artwork, title and transport geometry and masks. Root navigation observes threshold crossings only. The Queue moves a fixed viewport; its list is not remeasured during expansion. Fixed-size glyphs are scaled, never stepped through font sizes. Cover-edge masks combine in one offscreen layer, and settled artwork draws directly.

## Ink polarity and adaptive contrast

A page has one ink polarity: black or white for text and icons. Glass controls, panel frost, Queue frost and nested popups follow it.

- For the player, black and white ink are compared against the displayed backing behind title, artist and controls, including live crossfades.
- A choice is current only after matching tone preparation completes. Otherwise the last verified decision stays. White is used before any verification.
- Changing ink does not retone the backing.
- Preparation, sampling and presentation are asynchronous. Atomic presentation of ink and background is not guaranteed.

One fraction, `PlayerBackgroundPolarityState.lightMix`, drives foreground, glass ink and tone, frost, and backdrops that follow the player's ink. Surfaces read it instead of the discrete decision. Lyric lines take the interpolated ink uniformly; only each line's emphasis has its own clock.

Brightness trim darkens highlights under white ink and lifts shadows under dark ink. The applied trim is limited so that brightness order never inverts:

$$
\mathrm{trim}_{\mathrm{applied}} \le \frac{1}{w}
$$

Here $w$ is the weighting from 1 to 8. Ranges and defaults are in [Settings](../interface/settings.md#glass-settings).

With trim enabled, the current song's full-player backdrop is prepared while the player is minimized. Expanding draws the final backdrop from its first frame. A drawn backdrop stays until its replacement is ready. It never blanks.

## Shadows

- One shadow per group. Siblings that move together share a union: bottom chrome and filter strips. Whole pages and modal descendants never do.
- A shadow inherits its owner's transform, fade and clipping. Scrolling clips are padded for the gradient's reach.
- Textures are padded and exclude transparent interiors. A geometry change invalidates the texture; a translation reuses it.
- Falloff samples adapt to the physical spread, and reach the background smoothly without visible rings.
- Popup and trigger transfer exclusive shadow ownership at handoff.
- Shadows use the same path in every renderer.

The lift shadow follows the menu's theme: dark under dark-ink menus, a white glow under light-ink menus.

## Tests

| Topic | Classes |
| --- | --- |
| Artwork cache and decode | `ArtworkCacheTest`, `ArtworkCacheSizingTest`, `ArtworkCacheConcurrencyTest`, `CoverDecodeTest`, `ArtworkColorStorageTest` |
| Readback and lifetimes | `RetainedLayerReadbackTest`, `NativeWindowCopyOwnershipTest`, `BitmapLifetimeStressTest`, `PinchReadbackLifetimeTest` |
| Pinch layers | `BrowsePinchLayersTest`, `PinchItemPlacementLifecycleTest`, `CollectionHeaderPinchFrameTest` |
| Ink and contrast | `PlayerBackgroundPolarityTest`, `PlayerContrastTest`, `PlayerBackdropGradingTest`, `BrightnessWeightingTest`, `PlayerLyricsThemeEaseTest`, `LyricsLineInkEaseTest`, `PlayerExpandReadinessTest` |
| Shadows | `GlassShadowTest`, `ShadowFalloffTest`, `ShadowContinuityTest`, `NativeShadowFieldConformanceTest`, `LiftShadowTest` |
| Drag and selection drawing | `DragStackFrameTimeTest`, `GalleryLiftFrameTest`, `SelectionMotionRenderingTest`, `PinchRetainedHighlightTest` |

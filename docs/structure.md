# Technical structure

One Kotlin/Compose `app` module with constructor injection. Keep storage/playback independent of Compose and graphics; introduce interfaces or modules only for demonstrated boundaries. Toolchain and format compatibility belong in [Build](build.md); behavior belongs in [Product](product.md) and [Interface](interface.md).

## Code map

Paths below are under `app/src/main/java/io/github/anastaci1a/localica/`.

| Package | Responsibility |
| --- | --- |
| `library`, `browse` | Document access, indexing/Room, shared queries, grouping, filters and view preferences. |
| `scanner`, `editing`, `lyrics` | Background scans, recoverable writes/image selection, local lyric discovery/parsing. |
| `queue`, `playlist` | Durable occurrence-based collections and serialized commands/imports. |
| `playback`, `statistics` | Media3 service/controller, elapsed listening, durable qualified events. |
| `navigation`, `home` | Restorable typed routes/preferences; typed pins and manual order. |
| `ui` | Compose views, lifecycle-aware state, gestures and reusable materials. |

`app/src/test` holds fast behavior tests; `app/src/androidTest` covers real Android storage, UI and playback. Source code owns schema numbers, dependency versions, cache sizes and other tunable constants; docs retain their constraints and rationale.

## Persistence and commands

- **Library:** identity is provider authority + document ID; root membership stores access URI and relative path separately. Moves/renames retain identity only if the provider retains its ID. Scans transactionally replace one root; failure/cancellation preserves prior contents. Metadata failures keep filename fallbacks/per-root paths. Release removed grants after commit; try retained alternate grants for playback.
- **Queries/navigation:** `BrowsePresentationCache` owns bounded completed queries and atomic presentation publication; `BrowsePinch` owns gesture capture, reversal and commit. Warm upcoming tab/queue queries off Main, reusing results across layout-only changes. Queue creation receives every displayed occurrence and the selected index. Typed stacks, route keys and saved state retain positions. Filters respond immediately; normalized search runs against retained sorted catalogs off Main. Restore/reset only after matching results/layout. Future paging must preserve complete queue snapshots.
- **Collections:** Room entries have independent occurrence IDs and shared track snapshots. Controllers serialize transactions; cursor-only updates avoid rewriting entries. Visit history and planned cycle membership use separate typed rows, persisted incrementally. Playlist imports are bounded to 5 MiB/20,000 entries and commit after review. Preserve released migrations, IDs, references, grants and settings; missing snapshot fields remain unknown.
- **Playback:** only `MediaSessionService` owns ExoPlayer. Expose both Media3 and legacy MediaBrowser discovery; UI/system transport share the QueueController command channel/timeline. Start queues before persisting full contents; loaded selections seek without rebuilding/querying. Serialize writes and recover/retry from durable state. Capture cursors on events, every five seconds and at service destruction; abrupt termination restores the last committed checkpoint.
- **Traversal:** QueuePreferences owns modes; import legacy active-queue modes once, then leave compatibility columns inert. Feed a bounded window of tokened visits into Media3, using identity shuffle order because the saved sequence already incorporates shuffle. Capture native arrivals immediately, even during storage work; finish the callback batch before extending/persisting the timeline. [Traversal semantics](product.md#queues-and-playback).
- **Statistics:** measure monotonic playing time in the service. Serialized writes retry stable event IDs without double counting; a separate database survives collection deletion. [Counting rules](product.md#dates-and-listening-statistics).
- **Settings/selection:** typed preference boundaries retain explicit inheritance; shared enum decoding preserves unknown-value fallbacks. Pure ordering/move helpers are shared across collection types. Selection retains source occurrence IDs and clears only after command completion. Pins retain typed identity/manual order independently of tabs.

## File operations

Use persisted Storage Access Framework tree grants without broad storage permission. Enumerate off Main; never interpret document IDs as filesystem paths. Scans and tag writes share a file-operation lock, but retain separate cancellation/recovery lifecycles.

| Operation | Ownership and recovery |
| --- | --- |
| Scan | Explicit `dataSync` foreground service, bounded wake lock, app-owned progress/cancellation. Survive Activity recreation/backgrounding, honor service timeouts and report process interruption next launch. Notification permission is optional. |
| Tag batch | `dataSync` foreground service with bounded wake lock. Dismissing the editor leaves work running; stop after the current verified write. Keep eight local job outcomes and mark unfinished jobs interrupted on restart. |
| Tag transaction | Edit a private copy with Jaudiotagger; compare encoded audio/unrelated tags and verify requested values/artwork. Sync an original and atomic journal before overwrite. On failure attempt verified rollback; keep failed-file recovery separate from the durable batch-refresh journal. |
| Active-queue edit | Capture occurrence/cursor/playing state and release prepared sources, including duplicates; rebuild/restore after completion or recovery. Inactive-queue edits leave playback intact. Natural advancement uses one prepared timeline; invalidate obsolete preparation and leave current playback intact on preparation failure. |

Verified writers cover MP3/M4A/FLAC/Ogg Vorbis/WAV. Unsupported fields/formats/nonseekable providers stay read-only. Supplement platform metadata through granted seekable descriptors: bounded ID3 reads, tag-only FLAC/Ogg views and sparse MP4/WAV views avoid audio copies. Cap supplementary metadata at 16 MiB; delete temporary views after use/startup. RF64 supplementary tags remain unsupported.

Bound/orient imported images; the zoom viewer decodes separately. Resolve lyric sidecars through tree children and indexed relative folders; cap inputs at 2 MiB/10,000 lines. Media3's Vorbis-comment reader covers Opus. Persist source choices per song. [Editing rules](product.md#metadata-and-artwork-editing) · [Lyric policy](product.md#lyrics-sources).

- Bulk tag writes journal pending metadata refresh before touching bytes. Verify each file independently, then publish library metadata in one database transaction and refresh queue/playlist snapshots and artwork once after completion or stopping. Retain unfinished refreshes across restart; defer any file still awaiting recovery.

## Rendering and gesture boundaries

### Artwork and lists

- Replaceable cover providers use bounded caches (including missing results), cancellable serialized reads, stable collection-cover ordering and display-sized decodes. Rescans/edits invalidate artwork/lyric caches. Long automatic scrolls retain cached covers but defer uncached decode requests until settling or interruption; manual browsing keeps normal loading. No scraping.
- Read cached artwork synchronously; retain smaller same-identity covers while larger decodes load. Activity-local viewport leases hold shared bitmap references for current tabs and two Back destinations; artwork invalidation clears them. Home retains its completed pinned-item query alongside browse presentations. Recycled rows never retain another identity. Album detail seeds metadata and bounded color samples directly from cached entry content before asynchronous wash/high-resolution preparation. The player retains its complete previous artwork/palette until the next pair is ready.
- Prepare bounded current/neighbor artwork bundles and soft edges off Main. Artwork washes, panel frost and older-device glass snapshots share a separable box-filter kernel; callers retain their sampling, alpha and cancellation policies.
- One expansion progress controls persistent artwork/title/transport geometry and masks. Observe frame progress in its owning control or placement/draw phase; root navigation observes threshold crossings. Move a fixed Queue viewport instead of remeasuring its list throughout expansion. Scale fixed-size glyphs; avoid stepped font sizes. Combine cover-edge masks in one offscreen layer; settled artwork draws directly. Blend covers with color continuation and retain a snapshot source only during interruptible transitions. Blur-through uses a live blur on API 31+ and prepared soft-cover endpoints on older Android; neither filters fresh bitmaps each animation frame.
- Gesture ownership lasts until release; measure velocity from integrated deltas when the target itself moves. Artwork has diagonal priority over clearly vertical parent gestures. Pinch freezes bounded visible source drawings in one packed readback before destination reflow, retaining them through reversal/handoff so virtualization cannot erase or mutate the preview.
- `CollectionActions` owns song/collection menus across rows, artwork holds and Home, with source-specific removal appended. `GlassDragBubble` owns lifted/landing material for song stacks and action customization. Lifted items reuse the browse presentation and land on measured content bounds. Drag stacks update once per display frame with bounded absolute positions and drawing-layer translation, avoiding per-frame content recomposition/measurement. Edge scrolling uses elapsed frame time; stack, cancel target and list clipping have separate foreground layers.

### Surfaces and windows

- The theme owns modal lifetimes and retains caller state/locals through exit. Restore caller locals before creating each window so view/focus ownership stays correct; nested windows sample the completed parent panel. App-drawn scrims replace native dim layers, whose invisible surfaces can still block touches during return.
- Measure glass from foreground content, then fit its padded backdrop independently. Uniformly scale fixed menu layout into the moving viewport; search instead keeps its field unscaled and top-aligned while revealing results below. Release modal input at dismissal, retaining its drawing until the trigger acknowledges restoration. Trigger and popup share deformation, corner policy and optical parameters; blend their source recordings before handoff, without discarding unsettled rebound. One continuous material changes its scattering/tint; no endpoint surface swap. Trigger visibility invalidates recorded drawing, not only a child layer’s alpha.
- Morphs and controls share damped acceleration/contact/pressure response with coupled footprint/depth; idle elastic clocks stop. [Visible motion contract](interface.md#motion-and-access).
- Record browsing, panel material, lists, player and lyrics separately to prevent sampling cycles. Sampling leases retain shared scenes as offscreen rasters; scrolling lists keep display lists so narrow lens crops cull unseen rows without another full-viewport raster. Unreferenced scenes skip readback. Observe capture size/revision inside sampling coroutines, reuse completed samples across visibility changes and resolve source and lens coordinates from live placement during translation; nested panels provide their parent beyond the recorded bounds. Skip browsing pixels fully covered by the player. Inline lenses exclude themselves; modal windows use global coordinates. The navbar selector normally samples only bar material; an explicit overlay option includes labels/icons in the sampled scene without drawing them twice.
- Selection lenses read live placement and normalize translation before caching geometry/connectivity; unselected hosts do not observe the selection placement clock. Disconnected bodies have independent optical spans. Simple columns use analytic geometry; irregular shapes use a cached half-float normal/distance buffer from a separable [Euclidean distance transform](https://cs.brown.edu/people/pfelzens/dt/index.html), bypassing color conversion. Selected viewports share one content raster across all group atlases, which crop backing to the ray model’s conservative reach and keep content sharp. Retain immutable current-song texture at its exact display resolution instead of replaying grain primitives during scrolling.

### Optics and performance

Controls, panels and selections use one [lens model](liquid-glass/specification.md#geometry-and-readability), executed by platform-specific adapters. Share outline geometry across clipping, refraction, illumination and shadow. Page-owned ink polarity requires no GPU readback; both adapters use the same ordered brightness mapping. Older devices share bounded, asynchronously prepared blur/tint/tone endpoints. Active sliders and switches render one procedural scene, unchanged outside the lens and refracted inside; resting white thumbs release sampling leases and bypass optics. Active controls capture only the thumb neighborhood and replace pixels only within its silhouette; track, touch mapping and expanded wall contact share geometry.

On Android 13+, controls entirely over explicitly recorded panel material use an 8×8 local GPU average, then the shared tint/contrast mapping and outline. Morphs retain that averaged home source while admitting the destination. Never infer eligibility from color similarity or average a foreground-containing recording. Floating list controls sample the finished viewport after its fade and scrolling transforms, with the parent backing beneath transparent pixels. Keep those controls outside their own source recording.

| Platform / layer | Treatment |
| --- | --- |
| Android 13+ optics | Padded AGSL rounded-surface refraction and tone mapping; the page supplies foreground polarity without readback. |
| Older optics | Bounded meshes over prepared snapshots with the same curvature/tint model and sharp foreground content. Android 8–9 rasterize and retain the mesh because [hardware vertex drawing starts at API 29](https://developer.android.com/topic/performance/hardware-accel). |
| Live scattering | Bounded downsampled layers: frosted panels on Android 12+, refracting glass on Android 13+. |
| Prepared scattering | Shared cancellable snapshots on older adapters after scene changes/settled scrolling, never each gesture frame. |
| Foreground / clear glass | Full resolution; tint below icons/text. |
| Shadows | Retain padded textures excluding transparent interiors. Shadows inherit their owner's transform, fade and clipping; only siblings moving together share a union (bottom chrome/filter strips), never whole pages or modal descendants. Pad scrolling clips for the gradient's reach. Popup and trigger transfer exclusive ownership at handoff. Geometry changes invalidate the texture; translation reuses it. |

Keep composed backings within their viewport and guard unrecorded/released layers before readback. Floating-glass blur is independent of Queue frost. Graphics remain replaceable and independent of core data/playback.

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
- **Queries/navigation:** `BrowsePresentationCache` owns completed queries and atomic presentation publication; `BrowsePinch` owns gesture capture, reversal and commit. Run complete deterministic queries off Main; queue creation receives the full displayed occurrence list and selected index. Typed tab stacks, route keys and saved state retain browse positions. Respond to requested filters immediately; retain bounded sorted catalogs with normalized search text, filtering them off Main without rebuilding metadata per keystroke. Cache completed presentations and publish style/results atomically. Restore/reset only after matching results/layout. Future paging must preserve complete queue snapshots.
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
| Tag transaction | Edit a private copy with Jaudiotagger; compare encoded audio/unrelated tags and verify requested values/artwork. Sync an original and atomic journal before overwrite. On failure attempt verified rollback; retain recovery until library/playlist/queue refresh completes. |
| Active-queue edit | Capture occurrence/cursor/playing state and release prepared sources, including duplicates; rebuild/restore after completion or recovery. Inactive-queue edits leave playback intact. Natural advancement uses one prepared timeline; invalidate obsolete preparation and leave current playback intact on preparation failure. |

Verified writers cover MP3/M4A/FLAC/Ogg Vorbis/WAV. Unsupported fields/formats/nonseekable providers stay read-only. Supplement platform metadata through granted seekable descriptors: bounded ID3 reads, tag-only FLAC/Ogg views and sparse MP4/WAV views avoid audio copies. Cap supplementary metadata at 16 MiB; delete temporary views after use/startup. RF64 supplementary tags remain unsupported.

Bound/orient imported images; the zoom viewer decodes separately. Resolve lyric sidecars through tree children and indexed relative folders; cap inputs at 2 MiB/10,000 lines. Media3's Vorbis-comment reader covers Opus. Persist source choices per song. [Editing rules](product.md#metadata-and-artwork-editing) · [Lyric policy](product.md#lyrics-sources).

## Rendering and gesture boundaries

### Artwork and lists

- Replaceable cover providers use bounded caches (including missing results), cancellable serialized reads, stable collection-cover ordering and display-sized decodes. Rescans/edits invalidate artwork/lyric caches. No scraping.
- Read cached artwork synchronously; retain smaller same-identity covers while larger decodes load. Activity-local viewport leases hold shared bitmap references for current tabs and two Back destinations; artwork invalidation clears them. Home retains its completed pinned-item query alongside browse presentations. Recycled rows never retain another identity. Album detail seeds metadata and bounded color samples directly from cached entry content before asynchronous wash/high-resolution preparation. The player retains its complete previous artwork/palette until the next pair is ready.
- Prepare bounded current/neighbor artwork bundles and soft edges off Main. Artwork washes, panel frost and older-device glass snapshots share a separable box-filter kernel; callers retain their sampling, alpha and cancellation policies.
- One expansion progress controls persistent artwork/title/transport geometry and masks. Observe frame progress in its owning control or placement/draw phase; root navigation observes threshold crossings. Move a fixed Queue viewport instead of remeasuring its list throughout expansion. Scale fixed-size glyphs; avoid stepped font sizes. Blend covers with color continuation; flatten the displayed composite only on interruption and reuse textures during gestures. Blur-through uses a live blur on API 31+ and prepared soft-cover endpoints on older Android; neither filters fresh bitmaps each animation frame.
- Gesture ownership lasts until release; measure velocity from integrated deltas when the target itself moves. Artwork has diagonal priority over clearly vertical parent gestures. Pinch freezes bounded visible source drawings in one packed readback before destination reflow, retaining them through reversal/handoff so virtualization cannot erase or mutate the preview.
- `CollectionActions` owns song/collection menus across rows, artwork holds and Home, with source-specific removal appended. `GlassDragBubble` owns lifted/landing material for song stacks and action customization. Lifted items reuse the browse presentation and land on measured content bounds. Drag stacks update once per display frame with bounded absolute positions and drawing-layer translation, avoiding per-frame content recomposition/measurement. Edge scrolling uses elapsed frame time; stack, cancel target and list clipping have separate foreground layers.

### Surfaces and windows

- The theme owns modal lifetimes and retains caller state/locals through exit. Restore caller locals before creating each window so view/focus ownership stays correct; nested windows sample the completed parent panel. App-drawn scrims replace native dim layers, whose invisible surfaces can still block touches during return.
- Measure glass from foreground content, then fit its padded backdrop independently. Uniformly scale fixed menu layout into the moving viewport. Release modal input at dismissal, retaining its drawing until the trigger acknowledges restoration. Trigger and popup share deformation, corner policy and optical parameters; blend their source recordings before handoff, without discarding unsettled rebound. One continuous material changes its scattering/tint; no endpoint surface swap. Trigger visibility invalidates recorded drawing, not only a child layer’s alpha.
- Morphs and controls share damped acceleration/contact/pressure response with coupled footprint/depth; idle elastic clocks stop. [Visible motion contract](interface.md#motion-and-access).
- Record browsing content, panel material, lists, player and lyrics separately to prevent sampling cycles. Sampling leases retain referenced scenes as offscreen rasters so lenses reuse pixels instead of replaying the scene; unreferenced scenes keep only their display lists and skip readback. Observe capture size/revision inside sampling coroutines, reuse completed samples across visibility changes, and observe relative sampling coordinates during translation. Skip browsing pixels fully covered by the player. Inline lenses exclude themselves; modal windows use global coordinates. The navbar selector samples only the bar material; foreground labels remain sharp above it.
- Connected selection lenses query placement coordinates during drawing and normalize translations before caching geometry. Partition disconnected groups before evaluating optics: each body has its own local span, so other selections cannot change its refraction. Simple connected columns use analytic geometry; irregular connected shapes use a cached half-float normal/distance buffer from a separable [Euclidean distance transform](https://cs.brown.edu/people/pfelzens/dt/index.html). Raw geometry bypasses color conversion. A per-group input atlas crops backing to the ray model’s conservative reach and separates it from sharp selected-item content; translating a selection cannot rebuild its geometry or distort labels.

### Optics and performance

Controls, panels and selections use one [lens model](liquid-glass/specification.md#geometry-and-readability), executed by platform-specific adapters without retaining an older visual style. Share outline geometry across clipping, refraction, illumination and shadow. White tint uses the same monotonic tone curve on both adapters; readability records sharp foreground silhouettes separately. Older devices independently blend prepared blur/tint endpoints without filtering fresh bitmaps each frame.

Floating list controls sample the finished viewport after its fade and scrolling transforms, with the parent backing beneath transparent pixels. Keep those controls outside their own source recording.

| Platform / layer | Treatment |
| --- | --- |
| Android 13+ optics | Padded AGSL rounded-surface refraction; small shared scene samples guide foreground polarity, never per-lens readback. |
| Older optics | Bounded meshes over prepared snapshots with the same curvature/tint model and sharp foreground content. Android 8–9 rasterize and retain the mesh because [hardware vertex drawing starts at API 29](https://developer.android.com/topic/performance/hardware-accel). |
| Live scattering | Bounded downsampled layers: frosted panels on Android 12+, refracting glass on Android 13+. |
| Prepared scattering | Shared cancellable snapshots on older adapters after scene changes/settled scrolling, never each gesture frame. |
| Foreground / clear glass | Full resolution; tint below icons/text. |
| Shadows | Retain padded textures excluding transparent interiors; bottom chrome shares one union tracking deformation. Local and union shadows transfer exclusive ownership only when the popup surface is ready, retaining it until the trigger handoff completes. Geometry changes invalidate the texture; translation reuses it. |

Keep composed backings within their viewport and guard unrecorded/released layers before readback. Floating-glass blur is independent of Queue frost. Graphics remain replaceable and independent of core data/playback.

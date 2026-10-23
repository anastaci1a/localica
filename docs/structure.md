# Technical structure

One Kotlin/Compose `app` module with constructor injection. Keep storage/playback independent of Compose and graphics. Introduce interfaces or modules only for demonstrated boundaries. Toolchain and format compatibility belong in [Build](build.md). Behavior belongs in [Product](product.md) and [Interface](interface.md).

## Code map

Paths below are under `app/src/main/java/io/github/anastaci1a/localica/`.

| Package | Responsibility |
| --- | --- |
| `library`, `browse` | Document access, indexing/Room, shared queries, grouping, filters and view preferences. |
| `scanner`, `editing`, `lyrics` | Background scans, recoverable writes/image selection, local lyric discovery/parsing. |
| `queue`, `playlist` | Durable occurrence-based collections and serialized commands/imports. |
| `playback`, `statistics` | Media3 service/controller, elapsed listening, durable qualified events. |
| `navigation`, `home` | Restorable typed routes/preferences. Typed pins and manual order. |
| `ui` | Compose views, lifecycle-aware state, gestures and reusable materials. |

`app/src/test` holds fast behavior tests; `app/src/androidTest` covers real Android storage, UI and playback. Source code owns schema numbers, dependency versions, cache sizes and other tunable constants. Docs retain their constraints and rationale.

## Persistence and commands

### Library

Library identity combines provider authority and document ID. Root membership stores access URI and relative path separately. Moves/renames retain identity only if the provider retains its ID. Scans transactionally replace one root only after complete directory listings. Null cursors, provider loading/error results and failure/cancellation preserve prior contents for a later retry.

Metadata failures keep filename fallbacks/per-root paths. Release removed grants after commit. Try retained alternate grants for playback.

### Queries/navigation

Room snapshot loading prepares an immutable shared search-text index off Main, reusing unchanged metadata/path entries after commits. Statistics and sort changes retain text. Source revisions independently refresh ordering/results. Search preserves literal case-normalized substrings and occurrence order, with no persistent search database.

`BrowsePresentationCache` owns bounded completed queries and publishes only matching source/query/style revisions; `BrowsePinch` owns gesture capture, reversal and commit. Warm upcoming tab/queue queries off Main and share computational catalogs across navigation sessions/layout changes. Queue creation receives every displayed occurrence and the selected index. Typed stacks and saved state retain positions. Restore/reset only after matching results/layout.

Future paging must preserve complete queue snapshots.

### Collections

Room entries have independent occurrence IDs and shared track snapshots. Controllers serialize transactions. Cursor-only updates avoid rewriting entries or unchanged saved order. Visit history and planned cycle membership use separate typed rows, with incremental inserts and updates that reuse prepared statements.

Ordered traversal persists a namespace and real anchor token to keep projected neighbors stable after restore or history removal without inventing visits. Playlist imports cap input at 5 MiB and retain at most 20,000 entries while consuming lines, then commit after review.

Preserve released migrations, IDs, references, grants and settings. Missing snapshot fields remain unknown.

### Playback

Only `MediaSessionService` owns ExoPlayer. Expose both Media3 and legacy MediaBrowser discovery; UI/system transport share the QueueController command channel/timeline. A revisioned intent mailbox records accepted play/pause before actor suspension can delay it. Recheck atomically at native preparation/play, consume only applied requests, and keep passive restoration distinct from explicit intent.

Apply independent setting edits against actor-current preferences. Staged dialogs commit only their edited fields, while explicit resets replace the full settings. Start queues before persisting full contents. Loaded selections seek without rebuilding/querying. Serialize writes and recover/retry from durable state.

Capture cursors on events, every five seconds and at service destruction. Abrupt termination restores the last committed checkpoint.

### Traversal

QueuePreferences owns modes. Import legacy active-queue modes once, then leave compatibility columns inert. Feed a bounded window of tokened visits into Media3, using identity shuffle order because the saved sequence already incorporates shuffle. Capture native arrivals immediately, even during storage work. Finish the callback batch before extending/persisting the timeline. [Traversal semantics](product.md#queues-and-playback).

### Statistics

Measure monotonic playing time in the service. Serialized writes retry stable event IDs without double counting. A separate database survives collection deletion. [Counting rules](product.md#dates-and-listening-statistics).

### Settings/selection

Typed preference boundaries retain explicit inheritance. Shared enum decoding preserves unknown-value fallbacks. Pure ordering/move helpers are shared across collection types. Selection retains source occurrence IDs and clears only after command completion. Pins retain typed identity/manual order independently of tabs.

## File operations

Use persisted Storage Access Framework tree grants without broad storage permission. Enumerate off Main. Never interpret document IDs as filesystem paths. Scans and tag writes share a file-operation lock, but retain separate cancellation/recovery lifecycles.

| Operation | Ownership and recovery |
| --- | --- |
| Scan | Explicit `dataSync` foreground service, bounded wake lock, app-owned progress/cancellation. Survive Activity recreation/backgrounding, honor service timeouts and report process interruption next launch. Notification permission is optional. |
| Tag batch | `dataSync` foreground service with bounded wake lock. Dismissing the editor leaves work running. Stop after the current verified write. Keep eight local job outcomes and mark unfinished jobs interrupted on restart. |
| Tag transaction | Edit a private copy with Jaudiotagger. Compare encoded audio/unrelated tags and verify requested values/artwork. Sync an original and record the recovery journal before overwrite. On failure attempt verified rollback. Keep failed-file recovery separate from the durable batch-refresh journal. |
| Playback during edits | Own a cancellable scope before acquiring playback. Capture occurrence/cursor/playing state and clear affected prepared timelines, including duplicates. Hold later affected queue switches/attachments while unrelated playback continues. Abandoned acquisition cannot later stop playback. |
| Reader admission | An app-lifetime registry registers every native source before provider open, atomically blocks new admissions and awaits actual close outside the command actor before invoking the writer. Timeout/cancellation never starts the writer. Failed close remains unsafe for edits until process restart. |
| Writer handoff | The shared writer publishes verified-byte safety or retained quarantine before scope exit releases admission. Restore only affected playback and honor newer intent. |

The reader gate uses the library's authority + document ID identity across alternate tree grants. Arbitrary cross-provider aliases are not resolved. Media3 stop/clear and cancellation callbacks do not acknowledge descriptor closure.

The same registry starts fail-closed before service preparation and uses the existing single recovery journal: a valid pending identity blocks that source. Unreadable or mismatched recovery blocks all sources until it can be identified. Writer-mutex serialization prevents an old startup probe replacing a newer outcome.

Queue views clip at that authority without changing durable history or copying temporary repeat-off into settings. This retained-journal policy does not claim checked AtomicFile storage-commit or power-loss durability.

The separate metadata-refresh journal stores one atomic record per track, with insertion sequence and latest written URI. Each include updates only that record. Startup imports the old array idempotently before removing it. Retained records commit before cleanup, so interruption can replay already-published refreshes but cannot discard a still-pending track.

The controller's exclusive file-operation boundary owns access. Corruption fails closed rather than dropping entries.

Verified writers cover MP3/M4A/FLAC/Ogg Vorbis/WAV. Unsupported fields/formats/nonseekable providers stay read-only. Supplement platform metadata through granted seekable descriptors: bounded ID3 reads, tag-only FLAC/Ogg views and sparse MP4/WAV views avoid audio copies. Cap supplementary metadata at 16 MiB. Delete temporary views after use/startup. RF64 supplementary tags remain unsupported.

Artwork source read grants have one shared owner: retain recent choices (including canceled crops) and active crop/read leases. Release only obsolete grants acquired by that owner. Pre-existing/legacy grants and tree grants are never adopted or pruned. Record ownership only after successful acquisition. Failed cleanup remains retryable.

Grants left unrecorded by interruption remain unknown, never inferred to be owned. At the conservative app grant budget, use transient picker access to leave platform headroom. That source may need choosing again after restart. Crops are private files and committed artwork is embedded bytes, independent of the source grant.

Bound/orient imported images. The zoom viewer decodes separately. Crop loading belongs to the current source/composition: restoration starts a fresh load, cancellation cannot publish stale pixels, and decode failure leaves an explicit error rather than an indefinite spinner. Resolve lyric sidecars through tree children and indexed relative folders. Cap inputs at 2 MiB/10,000 lines.

Media3's Vorbis-comment reader covers Opus. Persist source choices per song. [Editing rules](product.md#metadata-and-artwork-editing) · [Lyric policy](product.md#lyrics-sources).

Bulk tag writes journal pending metadata refresh before touching bytes. Verify each file independently, then publish library metadata in one database transaction and refresh queue/playlist snapshots and artwork once after completion or stopping. Retain unfinished refreshes across restart. Defer any file still awaiting recovery.

## Rendering and gesture boundaries

### Artwork and lists

Replaceable cover providers use bounded caches (including missing results), cancellable serialized reads, stable collection-cover ordering and display-sized decodes. Rescans/edits invalidate artwork/lyric caches. Long automatic scrolls retain cached covers but defer uncached decode requests until settling or interruption. Manual browsing keeps normal loading. No scraping.

One immutable browse-order snapshot resolves inherited/overridden collection ordering for cards, Home and details. Sort changes invalidate their completed queries without per-card preference reads or repeated library indexing. Read cached artwork synchronously. Retain smaller same-identity covers while larger decodes load.

Activity-local viewport leases hold shared bitmap references for current tabs and two Back destinations. Artwork invalidation clears them. Home retains its completed pinned-item query alongside browse presentations. Recycled rows never retain another identity. Album detail seeds metadata and bounded color samples directly from cached entry content before asynchronous wash/high-resolution preparation.

The player retains its complete previous artwork/palette until the next pair is ready.

Prepare bounded current/neighbor artwork bundles and soft edges off Main. Artwork washes, panel frost and older-device glass snapshots share a separable box-filter kernel. Callers retain their sampling, alpha and cancellation policies.

One expansion progress controls persistent artwork/title/transport geometry and masks. Observe frame progress in its owning control or placement/draw phase. Root navigation observes threshold crossings. Move a fixed Queue viewport instead of remeasuring its list throughout expansion. Scale fixed-size glyphs. Avoid stepped font sizes. Combine cover-edge masks in one offscreen layer. Settled artwork draws directly.

Blend covers with color continuation and retain a snapshot source only during interruptible transitions. Blur-through uses a live blur on API 31+ and prepared soft-cover endpoints on older Android. Neither filters fresh bitmaps each animation frame.

Gesture ownership lasts until release. Measure velocity from integrated deltas when the target itself moves. Artwork has diagonal priority over clearly vertical parent gestures. Pinch freezes bounded visible source drawings in one packed readback before destination reflow, retaining them and the midpoint scroll anchor through release settlement and handoff.

Initial placement corrects the measured fractional anchor before presenting. The current-row decoration follows interpolated content bounds without changing lazy measurement or hit targets.

`CollectionActions` owns song/collection menus across rows, artwork holds and Home, with source-specific removal appended. `GlassDragBubble` owns lifted/landing material for song stacks and action customization. `BrowseCellGeometry` supplies live grid, pinch and lifted-card insets. Full-span headers retain viewport alignment. Lifted items reuse the browse presentation and land on measured content bounds.

Drag stacks update once per display frame with bounded absolute positions and drawing-layer translation, avoiding per-frame content recomposition/measurement. Edge scrolling uses elapsed frame time. Stack, cancel target and list clipping have separate foreground layers.

### Surfaces and windows

The theme owns modal lifetimes and retains caller state/locals through exit. Restore caller locals before creating each window so view/focus ownership stays correct. Nested windows sample the completed parent panel. App-drawn scrims replace native dim layers, whose invisible surfaces can still block touches during return.

Measure glass from foreground content, then fit its padded backdrop independently. Uniformly scale fixed menu layout into the moving viewport. Search instead keeps its field unscaled and top-aligned while revealing results below. Release modal input at dismissal, retaining its drawing until the trigger acknowledges restoration.

Trigger and popup share deformation, corner policy and optical parameters. Blend their source recordings before handoff, without discarding unsettled rebound. One continuous material changes its scattering/tint. No endpoint surface swap. Trigger visibility invalidates recorded drawing, not only a child layer’s alpha.

Morphs and controls share damped acceleration/contact/pressure response with coupled footprint/depth. Idle elastic clocks stop. [Visible motion contract](interface.md#motion-and-access).

Record browsing, panel material, lists, player and lyrics separately to prevent sampling cycles. Sampling leases retain shared scenes as offscreen rasters. Scrolling lists keep display lists so narrow lens crops cull unseen rows without another full-viewport raster. Unreferenced scenes skip readback.

Observe capture size/revision inside sampling coroutines, reuse completed samples across visibility changes and resolve source and lens coordinates from live placement during translation. Nested panels provide their parent beyond the recorded bounds. Skip browsing pixels fully covered by the player. Inline lenses exclude themselves. Modal windows use global coordinates.

The navbar selector normally samples only bar material. An explicit overlay option includes labels/icons in the sampled scene without drawing them twice.

Selection lenses read live placement and normalize translation before caching geometry/connectivity. Unselected hosts do not observe the selection placement clock. Disconnected bodies have independent optical spans. Simple columns use analytic geometry. Irregular shapes use a cached half-float normal/distance buffer from a separable [Euclidean distance transform](https://cs.brown.edu/people/pfelzens/dt/index.html), bypassing color conversion.

Selected viewports share one content raster across all group atlases, which crop backing to the ray model’s conservative reach and keep content sharp. Retain immutable current-song texture at its exact display resolution instead of replaying grain primitives during scrolling.

### Optics and performance

Platform adapters execute one shared lens model for controls, panels and selections. Share outline geometry across clipping, refraction, illumination and shadow. Page-owned ink polarity requires no GPU readback. Both adapters use the same ordered brightness mapping. Older devices share bounded, asynchronously prepared blur/tint/tone endpoints.

Active sliders and switches render one procedural scene, unchanged outside the lens and refracted inside. Resting white thumbs release sampling leases and bypass optics. Active controls capture only the thumb neighborhood and replace pixels only within its silhouette. Track, touch mapping and expanded wall contact share geometry.

On Android 13+, controls entirely over explicitly recorded panel material use an 8×8 local GPU average, then the shared tint/contrast mapping and outline. Morphs retain that averaged home source while admitting the destination. Never infer eligibility from color similarity or average a foreground-containing recording.

Floating list controls sample the finished viewport after its fade and scrolling transforms, with the parent backing beneath transparent pixels. Keep those controls outside their own source recording.

| Platform / layer | Treatment |
| --- | --- |
| Android 13+ optics | Padded AGSL rounded-surface refraction and tone mapping. The page supplies foreground polarity without readback. |
| Older optics | Bounded meshes over prepared snapshots with the same curvature/tint model and sharp foreground content. Android 8–9 rasterize and retain the mesh because [hardware vertex drawing starts at API 29](https://developer.android.com/topic/performance/hardware-accel). Software texture triangles may cyclically reorder their unchanged vertices to improve the legacy affine basis. Folds and genuinely singular mappings are not altered. |
| Live scattering | Bounded downsampled layers: frosted panels on Android 12+, refracting glass on Android 13+. |
| Prepared scattering | Shared cancellable snapshots on older adapters after scene changes/settled scrolling, never each gesture frame. |
| Foreground / clear glass | Full resolution. Tint below icons/text. |
| Shadows | Retain padded textures excluding transparent interiors. Shadows inherit their owner's transform, fade and clipping. Only siblings moving together share a union (bottom chrome/filter strips), never whole pages or modal descendants. Pad scrolling clips for the gradient's reach. Popup and trigger transfer exclusive ownership at handoff. Geometry changes invalidate the texture. Translation reuses it. |

Keep composed backings within their viewport and guard unrecorded/released layers before readback. Floating-glass blur is independent of Queue frost. Graphics remain replaceable and independent of core data/playback.

Legacy clear-overlay navigation selectors compose stable parent imagery and unmasked ink through current shell/selector rays and per-slot inverse transforms. Evaluate selection color at the refracted coordinate. Never cache moving shell geometry or selection masks into the ink atlas. Translation reuses mesh topology, while Android 8–9 still rasterize changed scenes into a bounded selector bitmap.

Modern selectors retain live padded recordings.

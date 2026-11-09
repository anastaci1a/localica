# Technical structure

One Kotlin/Compose `app` module with constructor injection. Keep storage/playback independent of Compose and graphics. Introduce interfaces or modules only for demonstrated boundaries. Toolchain and format compatibility belong in [Build](build.md). Behavior belongs in [Product](product.md) and [Interface](interface.md).

## Code map

Paths below are under `app/src/main/java/io/github/anastaci1a/localica/`.

| Package | Responsibility |
| --- | --- |
| `library`, `browse` | Document access, indexing/Room, shared queries, grouping, filters and view preferences. |
| `scanner`, `editing`, `lyrics` | Background scans, recoverable writes/image selection, local lyric discovery/parsing. |
| `queue`, `playlist` | Durable occurrence-based collections and serialized commands/imports. |
| `playback`, `statistics` | Media3 service/controller, elapsed listening, durable qualified events and provider-independent ranking counts. |
| `artist`, `artistphoto`, `lastfm`, `network` | Artist projections, photo providers/cache, read-only statistics sync and shared online consent. |
| `navigation`, `home` | Restorable typed routes/preferences; typed pins and manual order. |
| `ui` | Compose views, lifecycle-aware state, gestures and reusable materials. |

`app/src/test` holds fast behavior tests. `app/src/androidTest` covers real Android storage, UI and playback. Source code owns schema numbers, dependency versions, cache sizes and other tunable constants. Docs retain their constraints and rationale.

## Persistence and commands

### Library

Identity is provider authority + document ID. Root membership stores access URI and relative path separately. Moves/renames retain identity only if the provider retains its ID. Full scans replace a root only after complete listings. Optional startup discovery enumerates permitted folders, reads metadata only for unindexed identities, and transactionally inserts additions/memberships without overwriting cached rows.

Both serialize with file edits. Incomplete/loading/error listings and cancellation retain prior contents. Scan metadata reuses positive listing dates. Unknown dates and edit refresh read them afresh. Metadata failures keep filename fallbacks/per-root paths. Playback reads current queued-track metadata and locations transactionally. Selections exceeding SQL binding limits or with ambiguous sort ties use the canonical full read.

Release removed grants after commit. Recheck live alternate grants for playback. Folder availability refresh takes one off-Main grant snapshot and publishes only for the latest requested folder list.

### Queries/navigation

Room snapshot loading prepares an immutable shared search-text index off Main, reusing unchanged metadata/path entries after commits. Browse and playlist projections share a lazy, thread-safe current-track lookup per immutable library snapshot. Copies rebuild it on demand.

Playlist/queue detail queries resolve only the selected collection’s occurrences. Collection sorting prepares request-local metadata keys once per item, and grouping retains bounded count-independent order/membership plans per immutable metadata source. Only explicit statistics overlays share those plans. Ordinary snapshot copies start fresh. Results rebuild current track references/counts and folder access paths, and count-dependent ordering remains fresh.

Statistics overlays prepare off Main and reuse source tracks whose counts match. Statistics and sort changes retain search text while source revisions refresh ordering/results. Search preserves literal case-normalized substrings and occurrence order, with no persistent search database. Explicit sort reads remain fresh but skip search-index preparation, sharing the existing statistics overlay.

`BrowsePresentationCache` owns bounded completed queries, waits for the route’s required catalog sources before querying/warming and publishes only matching source/query/style revisions. `BrowsePinch` owns gesture capture, reversal and commit. Warm upcoming tab/queue queries off Main and share computational catalogs across navigation sessions/layout changes. Queue creation receives every displayed occurrence and the selected index.

The screen observes playback metadata/transport changes. Position/timestamp samples are read inside the player surface and at seek-action execution. Player projections reuse occurrence/metadata lookups across position updates, preserving first-match identity and distinct action/lyrics fallbacks for unavailable tracks.

Typed stacks and valid saved state retain route identity and positions. Shared whole-state restoration accepts current strings and legacy wrapped strings. Malformed or unusable navigation returns to its owning screen’s fresh root, without guessing collection types. Restore positions only after matching results/layout. Future paging must preserve complete queue snapshots.

### Collections

Room entries have independent occurrence IDs and shared track snapshots. Controllers serialize transactions. Cursor-only updates avoid rewriting entries or unchanged saved order, and unchanged visit/selection/position captures retain their queue objects without rebuilding occurrence IDs. Visit history and planned cycle membership use separate typed rows, with incremental inserts and updates that reuse prepared statements.

Ordered traversal persists a namespace and real anchor token to keep projected neighbors stable after restore or history removal without inventing visits. Playlist imports cap input at 5 MiB and retain at most 20,000 entries while consuming lines, then commit after review. Preserve released migrations, IDs, references, grants and settings. Missing snapshot fields remain unknown.

### Playback

Only `MediaSessionService` owns ExoPlayer. Expose both Media3 and legacy MediaBrowser discovery. UI and system play/pause, next/previous, shuffle and repeat use QueueController. System position/default-position and incremental seeks forward to the service-owned player.

A revisioned intent mailbox records accepted play/pause before actor suspension can delay it. Recheck atomically at native preparation/play, consume only applied requests, and keep passive restoration distinct from explicit intent. Apply independent setting edits against actor-current preferences. Staged dialogs commit only their edited fields, while explicit resets replace the full settings.

Start queues before persisting full contents. Loaded selections seek without rebuilding/querying. Serialize writes and recover/retry from durable state. Capture cursors on events, every five seconds and at service destruction.

After native activation, service the contiguous eligible transport prefix while its ordered save waits. Stop at the first structural command or an edit/recovery hold. Creation and structural success receipts still require their commits. Later cursor snapshots remain behind the initial receipt.

Controlled shutdown retains player cleanup until the final detach write completes, fails or reaches two seconds. Task removal defers stopping an inactive service through this barrier; ongoing playback keeps Media3’s policy. No disk wait blocks Main. Android destruction retains asynchronous cleanup ownership but cannot prevent process termination.

A successful final receipt makes that position restorable. Timeout or write failure falls back to the last committed checkpoint. Abrupt termination also restores the last committed checkpoint: five seconds is the capture cadence, not a disk deadline when the actor, scheduler or storage stalls.

Decoded PCM prefill targets 50 ms or native buffer capacity, whichever is smaller. Initial play and resume wait for accepted PCM, native backpressure or a 30 ms playback-loop deadline. Short end-of-stream tails start immediately. The deadline runs when that loop can execute; it is not a wall-clock scheduling guarantee.

Pause, flush and release cancel delayed starts. PCM samples, timestamps, write results and native buffer configuration remain unchanged. Encoded, offloaded and tunneled output keeps its existing start policy.

### Traversal

QueuePreferences owns modes. Import legacy active-queue modes once, then leave compatibility columns inert. Feed a bounded window of tokened visits into Media3, using identity shuffle order because the saved sequence already incorporates shuffle. Capture native arrivals immediately, even during storage work. Finish the callback batch before extending/persisting the timeline. [Traversal semantics](product.md#queues-and-playback).

### Statistics

Measure monotonic playing time in the service. Serialized writes retry stable event IDs without double counting. A separate database survives collection deletion. [Counting rules](product.md#dates-and-listening-statistics).

The artist data source prepares release groups, five ranked songs, current-year latest releases and playlist membership off Main. Release-type and compilation tags survive library storage and saved collection snapshots. Classification reports tagged, heuristic or uncertain confidence. Existing libraries acquire tags on full rescan.

Ranking windows use the caller's time zone and inclusive event boundaries. The caller refreshes the window when time advances. Duration-weighted totals use selected counts and distinct songs, treating missing duration as zero and saturating overflow. Albums and artists expose the same measure for future sort integration.

A provider interface keeps local and external counts separate. Typed settings select external counts with per-song local fallback; unsupported windows also fall back locally. Artist hub UI and additional sort controls remain separate work.

The Last.fm provider stores its snapshot, account selection, mappings and local counter baseline in a dedicated atomic file. Completed refreshes replace the remote base and local baseline together. Temporary qualified-play additions are derived from local counters; the provider never writes to local history.

Appears On and Similar Artists currently return empty through an enrichment interface.

### Settings/selection

Typed preference boundaries retain explicit inheritance. Shared enum decoding preserves unknown-value fallbacks. Pure ordering/move helpers are shared across collection types. Selection retains source occurrence IDs and clears only after command completion. Pins retain typed identity/manual order independently of tabs.

Artist photo requests run off Main and use fixed HTTPS hosts, disabled redirects, bounded responses and shared request pacing. Provider errors become fixed failures without URLs or transport messages. Metadata responses are limited to 512 KiB and pictures to 4 MiB. Searches inspect five artists and 25 releases per candidate; incomplete coverage falls back conservatively.

Artist photo storage re-encodes decoded images as WebP at the display dimension, capped at 1080 pixels, plus a 256-pixel thumbnail. Chosen photos live with persisted choices outside the evictable cache. Display leases protect visible images, and background reads do not advance LRU order. A promotion journal recovers interrupted choice commits without retiring the previous ready image.

Artist photo acquisition uses one unique WorkManager chain with connected-network constraints and a persistent per-artist queue. Completed artists checkpoint independently. A new pick supersedes an older generation without cancelling unrelated artists. Provider pacing and consent checks remain in the repository transport. Chooser candidates stream progressively and cache by artist request; decoded images retain the existing artwork fade owners.

Photo entries and consent use atomic file replacement. Match choices live separately from evictable images. A persisted attempt time throttles failed requests across process death. The default-off consent stays closed until its asynchronous load completes. Commons credits are bounded plain text; licence and source links remain structured metadata.

## Legacy MP3 transitions

On API 26, `LegacyMp3AudioRenderer` defers transition discontinuities only for `OMX.google.mp3.decoder`. Its final decoded buffer follows the stream-end timestamp. Notifying the sink earlier drains encoder padding before reconfiguration ([#30](https://github.com/anastaci1a/localica/issues/30)).

The next output-format configuration releases the deferred notification. Position resets and disposal clear it immediately. Delay and padding still come from Media3 metadata; no amplitude-based trimming or fixed sample subtraction is applied. Other decoders retain native behavior, and other API levels retain the native renderer.

## AAC edit-list trimming

`PlaybackExtractors` retains Media3 extraction and reads bounded MP4 metadata through the registered source. AAC edit handling has two narrow cases ([#28](https://github.com/anastaci1a/localica/issues/28)). Neither inspects audio amplitude or changes encoded media bytes.

### Shortened final sample

This correction supplies missing end padding. Packet timestamps, edit metadata and intentional silence remain unchanged.

| Required evidence | Constraint |
| --- | --- |
| Container | Known length, unfragmented MP4, one audio track, metadata within the inspection bound. |
| Codec | Unencrypted AAC-LC, 1,024 samples per access unit, explicit SBR-absent sync extension, no alternate frame length. |
| Timing | Media clock equals sample rate; frame durations are constant except the final duration. Counts and media duration agree. |
| Edit | One rate-one edit with an exact integral audible length ending at the declared media endpoint. |
| Existing trim | Delay equals the edit start; end padding is zero. |

The edit owns audible duration. Padding is the decoded frame extent beyond that endpoint:

$$
N_{\mathrm{audible}} = \frac{D_{\mathrm{edit}}\, f_{\mathrm{sample}}}{T_{\mathrm{movie}}},
\qquad
N_{\mathrm{padding}} = N_{\mathrm{frames}}\, N_{\mathrm{frame}} - N_{\mathrm{delay}} - N_{\mathrm{audible}}.
$$

The correction requires an exact integer result smaller than one frame. Other shortened-sample layouts retain native extraction, including existing nonzero padding and short AAC configurations. Fixture provenance and independent edit metadata live in `app/src/androidTest/assets/README.txt`.

### Rounded edit endpoint

A coarse movie clock can round a sole audio edit beyond the declared media endpoint. The native two-media-tick tolerance can then discard preroll packets and mark the stream as requiring preroll. This case preserves all declared access units and clamps the rounded edit endpoint to the existing media extent.

| Required evidence | Constraint |
| --- | --- |
| Container | Known length, bounded ordinary moov header, one unencrypted AAC audio track and one rate-one edit. |
| Headers | Version zero; movie, track and edit durations agree before correction. |
| Sample tables | Constant positive packet durations; complete size/chunk tables agree with known media duration and actual `mdat` payload bounds. |
| Rounding | Endpoint exceeds native tolerance but remains within half a movie-clock tick of the media extent. |
| Existing trim | No conflicting gapless metadata; native corrected end padding remains zero. |
| Output | Paired `PlaybackRenderersFactory` and `MediaEditAudioSink`; marked encoded bypass/offload is rejected. |

The same-width metadata overlay expresses movie, track and edit durations in media ticks. Source files remain untouched. Unsupported layouts retain native extraction. Unknown-length sources bypass inspection and add no reopen. The shortened-sample SBR-absence requirement remains unchanged.

The corrected format carries media-clock units through `Format.customData`. The sink binds them to actual decoded PCM rate and the first buffer's timestamp. It recomputes remaining leading trim after output reconfiguration or seek, rather than applying the full edit again. Foreign custom data and unmarked formats pass through unchanged.

$$
N_{\mathrm{remaining}} = \max\left(0,
\left\lfloor\frac{s_{\mathrm{edit}}\,f_{\mathrm{PCM}}}{T_{\mathrm{media}}}\right\rfloor
-\left\lceil\frac{\max(0,p_{\mathrm{PCM}}-o_{\mathrm{stream}})\,f_{\mathrm{PCM}}}{10^{6}}\right\rceil\right).
$$

PTS and stream offset use microseconds. Flooring the edit permits less than one output frame of rounding. Ceiling the truncated PTS recovers its represented frame. Invalid units, unsupported decoded formats and arithmetic overflow fail through the audio sink's error contract. No SBR absence or decoded frames per access unit is inferred.

## File operations

Use persisted Storage Access Framework tree grants without broad storage permission. Enumerate off Main. Never interpret document IDs as filesystem paths. Scans and tag writes share a file-operation lock, but retain separate cancellation/recovery lifecycles.

| Operation | Ownership and recovery |
| --- | --- |
| Scan | Explicit `dataSync` foreground service, bounded wake lock, app-owned progress/cancellation. Survive Activity recreation/backgrounding, honor service timeouts and report process interruption next launch. Notification permission is optional. |
| Tag batch | `dataSync` foreground service with bounded wake lock. Dismissing the editor leaves work running; stop after the current verified write. Keep eight local job outcomes and mark unfinished jobs interrupted on restart. |
| Tag transaction | Edit a private copy with Jaudiotagger; compare encoded audio/unrelated tags and verify requested values/artwork. Sync an original and record the recovery journal before overwrite. On failure attempt verified rollback; keep failed-file recovery separate from the durable batch-refresh journal. |
| Playback during edits | [Playback during edits rules](#playback-during-edits). |

### Playback during edits

Own a cancellable scope before acquiring playback. Capture occurrence/cursor/playing state and clear affected prepared timelines, including duplicates; hold later affected queue switches/attachments while unrelated playback continues. Abandoned acquisition cannot later stop playback.

An app-lifetime registry registers every native source before provider open, atomically blocks new admissions and awaits actual close outside the command actor before invoking the writer. Timeout/cancellation never starts the writer; failed close remains unsafe for edits until process restart.

The shared writer publishes verified-byte safety or retained quarantine before scope exit releases admission; restore only affected playback and honor newer intent.

The reader gate uses the library's authority + document ID identity across alternate tree grants. Arbitrary cross-provider aliases are not resolved. Media3 stop/clear and cancellation callbacks do not acknowledge descriptor closure.

The same registry starts fail-closed before service preparation and uses the existing single recovery journal: a valid pending identity blocks that source. Unreadable or mismatched recovery blocks all sources until it can be identified. Writer-mutex serialization prevents an old startup probe replacing a newer outcome.

Queue views clip at that authority without changing durable history or copying temporary repeat-off into settings. This retained-journal policy does not claim checked AtomicFile storage-commit or power-loss durability.

### Metadata refresh and readers

The separate metadata-refresh journal stores one atomic record per track, with insertion sequence and latest written URI. Each include updates only that record. Startup imports the old array idempotently before removing it. Retained records commit before cleanup, so interruption can replay already-published refreshes but cannot discard a still-pending track.

The controller's exclusive file-operation boundary owns access. Corruption fails closed rather than dropping entries.

Verified writers cover MP3/M4A/FLAC/Ogg Vorbis/WAV. Unsupported fields/formats/nonseekable providers stay read-only. Supplement platform metadata through granted seekable descriptors: bounded ID3 reads, tag-only FLAC/Ogg views and sparse MP4/WAV views avoid audio copies. Cap supplementary metadata at 16 MiB. Delete temporary views after use. Startup cleanup runs off Main under shared ownership that excludes active mirrors.

RF64 supplementary tags remain unsupported.

Artwork source read grants have one shared owner: retain recent choices (including canceled crops) and active crop/read leases. Release only obsolete grants acquired by that owner. Pre-existing/legacy grants and tree grants are never adopted or pruned. Record ownership only after successful acquisition. Failed cleanup remains retryable.

Grants left unrecorded by interruption remain unknown, never inferred to be owned. At the conservative app grant budget, use transient picker access to leave platform headroom. That source may need choosing again after restart. Crops are private files and committed artwork is embedded bytes, independent of the source grant.

Bound/orient imported images. The zoom viewer decodes separately. Crop loading belongs to the current source/composition: restoration starts a fresh load, cancellation cannot publish stale pixels, and decode failure leaves an explicit error rather than an indefinite spinner. Resolve lyric sidecars through tree children and indexed relative folders. Cap inputs at 2 MiB/10,000 lines.

Media3's Vorbis-comment reader covers Opus. Persist source choices per song. [Editing rules](product.md#metadata-and-artwork-editing) · [Lyric policy](product.md#lyrics-sources).

Bulk tag writes journal pending metadata refresh before touching bytes. Verify each file independently, then publish library metadata in one database transaction and refresh queue/playlist snapshots and artwork once after completion or stopping. Retain unfinished refreshes across restart. Defer any file still awaiting recovery.

## Rendering and gesture boundaries

### Artwork and lists

Replaceable cover providers use bounded caches (including missing results), cancellable reads (at most three parallel decodes; one per cover and size bucket), a decoded-cover budget of an eighth of the heap class (12-48 MB), 128/192/256/384/768 px buckets, stable collection-cover ordering and display-sized decodes.

Rescans/edits invalidate artwork/lyric caches. Long automatic scrolls retain cached covers but defer uncached decode requests until settling or interruption. Manual browsing keeps normal loading. No scraping.

Average artwork colours live in the library database's `track_artwork` table, keyed by track with the source `modifiedAt`. A changed `modifiedAt` marks a colour stale, and removing a track cascades its row. Catalog writes never wait for colours, and colour writes never republish the catalog.

The average is a 16 by 16 filtered downscale averaged in linear light. A URI-keyed index loads off Main and republishes at most every 400 ms. Only loading artwork placeholders observe it.

One single-flight worker computes missing or stale colours from 64 px decodes without GPU upload. Visible placeholders and the player's current and next tracks queue first, latest first, and display decodes donate their averages. Bulk work waits for foreground decodes to finish and stops while a scan runs or the app is stopped.

One immutable browse-order snapshot resolves inherited/overridden collection ordering for cards, Home and details. Sort changes invalidate their completed queries without per-card preference reads. Read cached artwork synchronously. Retain smaller same-identity covers while larger decodes load. Warm artwork hits bypass unrelated cold decodes. Misses decode at most three at a time and remain revision-checked.

Activity-local viewport leases hold shared bitmap references for current tabs and two Back destinations. Artwork invalidation clears them. Home retains completed pinned-item queries alongside browse presentations. Empty pin requests skip their catalog lookup. Recycled rows never retain another identity.

Album detail seeds metadata and bounded color samples directly from cached entry content before asynchronous wash/high-resolution preparation. The player retains its complete previous artwork/palette until the next pair is ready.

Prepare bounded current/neighbor artwork bundles and soft edges off Main. Revision invalidation drops cache ownership without recycling shared visible/outgoing bitmaps. The prepared cache budget excludes decoded-cache and live-frame ownership. Artwork washes, panel frost and older-device snapshots share a separable box-filter kernel.

Readbacks serialize per source under process-owned read jobs and retain a dedicated layer parent until native copying completes, including after caller cancellation or publication expiry. Deadlines cannot release pending native work. Release and cancellation checks run on the owning UI thread. Pinch atlases preserve exact pixels with separated regions.

Native copies pin sampling recordings. Visible producers draw live while a copy is pending; normal recording resumes when ownership ends. Explicit holds remain sampling-only.

One expansion progress controls persistent artwork/title/transport geometry and masks. Observe frame progress in its owning control or placement/draw phase. Root navigation observes threshold crossings. Move a fixed Queue viewport instead of remeasuring its list throughout expansion. Scale fixed-size glyphs. Avoid stepped font sizes. Combine cover-edge masks in one offscreen layer. Settled artwork draws directly.

Blend covers with color continuation and retain a snapshot source only during interruptible transitions. Blur-through uses a live blur on API 31+ and prepared soft-cover endpoints on older Android. Neither filters fresh bitmaps each animation frame.

Gesture ownership lasts until release. Measure velocity from integrated deltas when the target itself moves. Artwork has diagonal priority over clearly vertical parent gestures. Pinch publishes layout-only destinations synchronously and freezes bounded source drawings before reflow, preserving premultiplied alpha and row stride on legacy Android. Stable recording owners paint all text below all artwork.

Live and virtualized cells share fixed text/artwork endpoints. Retain their source-viewport bitmap lease, drawings and midpoint anchor through reversal, release and handoff. Placement resolves active pinch ownership in the layer phase, reverting to identity after retirement even before recomposition.

Pinch progress updates inside the frame callback so placement and drawing observe one value. Each settle owns its cleanup token; replacing it preserves the transaction's frozen endpoints.

Collection headers resolve beyond-bounds measurement through Compose’s public modifier-node ancestor lookup. Attached header and item nodes provide the current grid ancestor; detaching a node removes its lookup, so virtualized headers use live items without retaining a stale provider.

That measurement reaches only two viewports of items. A header beyond it in either the source or the destination view is offscreen, so the pinch proceeds without it instead of waiting for geometry that can't arrive.

Initial placement corrects the measured fractional anchor. Current-row decoration follows interpolated content bounds without changing lazy measurement or hit targets. Retained outgoing menu drawings are non-interactive. Empty gallery slots never consume pointer input.

`CollectionActions` owns song/collection menus across rows, artwork holds and Home, with source-specific removal appended. `ActionMenuItem` requires an explicit icon independent of its label. Song-information links share an off-Main lookup that rejects dismissed, replaced-source or superseded requests. `HoverDragCard` owns song-stack material; `GlassDragBubble` owns action-customization material.

Browse rows compose `CollectionActions` from their first opening and keep it with the row. Gallery artwork publishes its menu origin only when opening. Resting cells keep coordinate references instead of per-frame global-position callbacks; only the loading reveal follows positions while it runs.

`BrowseCellGeometry` and `BrowseItemMetrics` share grid insets and row layout across browsing, pinch and lifted cards. Full-span headers retain viewport alignment. Lifted items reuse the browse presentation and land on measured content bounds. Drag stacks update once per display frame with bounded absolute positions and drawing-layer translation, avoiding per-frame content recomposition/measurement.

Drag start publishes its foreground owner in the same event that removes the source rows. During landing, material and shadow may fade, but covers and text remain opaque until the destination rows take over.

Edge scrolling uses elapsed frame time. Stack, cancel target and list clipping have separate foreground layers.

### Surfaces and windows

The theme retains modal state/locals through exit. Restore caller locals before creating each window to preserve view/focus ownership. Nested windows sample the completed parent panel. One logical modal stack cancels and quarantines lower windows' native pointer streams, stops registered scroll motion and rejects stale queued flings independently of window recreation or exit drawing.

Pinch and scrollbar requests carry cancellation generations. Geometry restoration remains permitted. Popup foreground and optical tone inherit explicit trigger ink, then a detached callback's launching appearance, otherwise ambient ink. The callback appearance follows its mounted source and retains the last value after disposal. App-drawn scrims replace native dim layers, whose invisible surfaces can still block touches during return.

Measure glass from foreground content, then fit its padded backdrop independently. Uniformly scale fixed menu layout into the moving viewport. Search keeps its field unscaled and top-aligned, with one opening rectangle animation and captured rendered geometry for reversible closing.

`GlassMorphContent` shares the glass spring for in-surface size and face transitions. Only the destination's children are placed; outgoing faces retain drawing without input. The selection panel uses the same spring for its visible occupancy and retains its complete material and shadow during dismissal.

Its own window's reported IME target or safe-bottom compatibility destination drives size and placement together. Retargets retain current position and velocity. Release modal input at dismissal, retaining its drawing until the trigger acknowledges restoration. Trigger and popup share deformation, corner policy and optical parameters. Blend their source recordings before handoff, without discarding unsettled rebound.

One continuous material changes its scattering/tint. No endpoint surface swap. Trigger visibility invalidates recorded drawing, not only a child layer's alpha.

Morphs and controls share damped acceleration/contact/pressure response with coupled footprint/depth. Idle elastic clocks stop. [Visible motion contract](interface.md#motion-and-access).

Record browsing, panel material, lists, player and lyrics separately to prevent sampling cycles. The lyrics return source composites clipped/faded lyric documents over their artwork backing, excluding the return control. Thumbnail captures join matching outer, mask and composite draw generations before copying. Stale generations cannot publish.

Sampling leases retain shared scenes as offscreen rasters. Scrolling lists keep display lists so narrow lens crops cull unseen rows without another full-viewport raster. Unreferenced scenes skip readback. On older Android, progressively halve changed recordings into shared 32×32 thumbnails. Reuse completed samples across visibility changes.

Resolve source and consumer in one live relative recording-local basis, including fit/nonuniform scale and scrolling. Common ancestors cancel and separate windows share the screen frame. Use that same mapping for crop coverage and parent-boundary clipping.

Morph home sampling captures the trigger’s affine footprint on opening and follows its live footprint on return (last measured footprint after disposal), independently of logical animation bounds. Reject non-finite, singular or unsupported projective mappings rather than substituting translation. Nested panels provide their parent beyond the recorded bounds. Skip browsing pixels fully covered by the player.

Inline lenses exclude themselves. Modal windows use global coordinates. The navbar selector normally samples only bar material. An explicit overlay option includes labels/icons in the sampled scene without drawing them twice.

Hover selections read live placement and normalize translation before caching outlines/connectivity. Lazy owners provide current native item keys, since attached prefetched coordinates do not prove placement. Unselected hosts do not observe the selection placement clock.

Each merged group draws one shadow and magnified backdrop surface before its uniformly scaled foreground. Normal uses a cropped GPU blur on Android 13+. None uses a solid average. Browser artwork/text passes share the item transform. Pinch snapshots retain hover geometry separately from content and playback frost, preserving one drawing owner for each.

Retain immutable current-song texture at its exact display resolution instead of replaying grain primitives during scrolling. Procedural recordings use stable density and independent draw scopes, never a mutable node scope as its own density.

### Optics and performance

Controls and panels use one [lens model](interface.md#materials-and-controls), in Normal mode on Android 13+. Share outline geometry across clipping, refraction, illumination and shadow. Page-owned ink polarity requires no GPU readback. Normal and None use the same ordered brightness mapping. Android versions below 13 use average-colour rendering without optical preparation.

When Normal is requested, active sliders and switches render one procedural scene, unchanged outside the lens, with refraction or fallback average colour inside. Resting white thumbs release sampling leases and bypass optics. Active controls capture only the thumb neighborhood and replace pixels only within its silhouette. Track, touch mapping and expanded wall contact share geometry.

When None is requested, slider and switch knobs remain white 28 dp circles during contact, travel and cancellation. They draw without source leases, lens captures or reductions. Only their private elastic bodies restart across requested-mode changes; gesture and position owners remain mounted.

In Normal mode on Android 13+, controls entirely over explicitly recorded panel material use an 8×8 local GPU average, then the shared tint/contrast mapping and outline. Morphs retain that averaged home source while admitting the destination. Never infer eligibility from color similarity or average a foreground-containing recording.

None uniformly averages the backdrop over each surface's own full bounds, including pills and panels. Slider and switch knobs use the separate white resting policy. Refraction and blur do not change that region.

The mean is coverage-weighted over the scene the lens samples: the element's own source, with its parent chain beyond that source's box. Floating controls therefore follow the text and artwork passing behind them. No element chooses a different source or region.

What None samples inside a finished panel material (frosted panels, the Queue drawer, popup bodies) already carries that panel's tint and tone, so None does not apply them again there.

The region stops where the recorded scene ends, so material bounds reaching past a screen or window edge never make a fill translucent. Opaque panels also ignore transparent pixels inside their source.

On Android 13+, None maps the exact footprint onto the full power-of-two reduction texture. This avoids transparent padding that amplifies channel rounding differently for different material sizes.

None replaces only the optical fill. Floating, panel, lift and selection shadows use the same appearance settings and drawing path in both renderers, so a renderer switch never changes a shadow.

Below Android 13, material boundaries inside thumbnail cells retain those cells' mean colours. Excluding raster quantization, mean error is bounded by boundary-cell area fraction multiplied by their maximum channel contrast. Shared readback remains 32×32 pixels; no full-resolution CPU capture is required.

Only the nearest parent supplies the region beyond a source's box below Android 13. Until that parent has a snapshot, None averages the available part.

Floating list controls sample the finished viewport after its fade and scrolling transforms, with the parent backing beneath transparent pixels. Keep those controls outside their own source recording.

| Platform / layer | Treatment |
| --- | --- |
| Android 13+ optics | Padded AGSL rounded-surface refraction and tone mapping; the page supplies foreground polarity without readback. |
| Live scattering | Bounded downsampled layers: frosted panels on Android 12+, refracting glass on Android 13+. |
| Average-only rendering | Progressive GPU box reduction over the covered footprint. Below Android 13, integrate shared 32×32 cells by overlap area. No refraction or blur. |
| Foreground / clear glass | Full resolution; tint below icons/text. |
| Shadows | [Shadows rules](#shadows). |

#### Shadows

Retain padded textures excluding transparent interiors; use rounded primitives for one valid uniform body and union paths otherwise. Shadows inherit their owner's transform, fade and clipping; only siblings moving together share a union (bottom chrome/filter strips), never whole pages or modal descendants. Pad scrolling clips for the gradient's reach. Popup and trigger transfer exclusive ownership at handoff.

Adapt falloff samples to the physical spread and quantize cumulative opacity. Composite the live shadow as one transparent field to match the retained texture. Both paths resolve the interior cutout in destination coordinates before isolating the field. Keep popup/home receiver responses native; software accumulation changes their nonlinear shading.

Geometry changes invalidate the texture; translation reuses it.

#### Backings and navigation selectors

Keep composed backings within their viewport and guard unrecorded/released layers before readback. Floating-glass blur is independent of Queue frost. Graphics remain replaceable and independent of core data/playback.

Navigation selectors retain live padded recordings. The same shared material path supplies Normal optics or None averages.

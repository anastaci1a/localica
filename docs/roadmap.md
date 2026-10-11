# Roadmap

## Released baseline

The library foundation supports multiple folders, overlap deduplication, album/artist/album-artist/folder views, retained per-view queries, and richer metadata, with an in-place migration from v0.1.3. Manual playlists preserve ordering, duplicate occurrences, and unavailable file references. Multi-selection exposes explicit queue and playlist actions. Persistent queues include saved positions, editing, per-view song-tap overrides, configurable switching, and optional confirmed retention limits. Embedded artwork and comment reading extend the metadata foundation. The development APK workflow covers Android 8 and 16; production distribution signing remains a separate decision.

Configurable Home/Library navigation, saved per-tab routes and Back history, an expanding mini-player, and a queue overlay shipped in v0.5.0. The layout uses shared components with simple materials.

Shared browsing, scoped/global search, sorting overrides, contextual selection, cached artwork, and basic manually ordered pins shipped in v0.6.0.

Recoverable individual/bulk tag and artwork editing, image selection, and zoomable artwork viewing shipped in v0.7.1. Local embedded/sidecar lyrics, configurable artwork gestures, synced following, tap-to-seek, and fading viewport edges shipped in v0.8.1. Unsupported write formats/providers remain read-only.

Reference-based layouts, continuous player/Queue gestures, tab swiping, restored search/list history, configurable player actions, outlined session selection, cancelable multi-song drag stacks, richer metadata, independent queue browsing, and next-track preloading shipped in v0.9.0. The behavioral contract is in [Interface](interface.md).

The v0.9.1 patch isolates live route rendering/focus, prevents scrollbar/menu overlap, distinguishes gallery densities, expands compact titles, and makes the top drag card follow the finger directly.

The v0.10.1 player adds aligned floating navigation/utility/mini-player pills, filled transport icons, uncropped artwork backgrounds or floating covers over a matte gradient, and a shared blended lyrics layout with controls that ease downward.

The v0.11.3 organization checkpoint adds scanner/background operation, dedicated folder/error pages, structured settings/list options, and reviewed playlist import. It also restores light browsing and full-width galleries, lets lists extend beneath floating controls, and replaces restarted drag-card springs with frame-driven placement in the foreground.

Library filters and All’s collection types are configurable. Songs retain first-added dates; playlists retain edit dates; qualified local listening supports song/collection sorting and a Most played tab with durable playlist-origin attribution. See [Interface](interface.md#personalized-home-and-statistics) for date/count semantics.

## Device review

The v0.15.0 APK is available for review. It adds a continuous frosted Queue drawer, directional return buttons, smoother artwork/player transitions, friendly playback failure dialogs, and Library filter/sort fixes. README screenshots and the navigation GIF reflect this release; the demo library is retained locally. Development is paused for device feedback.

## Visual design system

The Queue drawer and directional return buttons now share a reusable frosted material, with live scene scattering on Android 12+ and a snapshot fallback on older devices. Review its appearance and motion on real devices before extending Apple Music-inspired liquid glass elsewhere. Preserve crisp controls, continuous gestures, accessibility, and frame performance as the material develops. Keep the design system in this repository initially; consider extraction only after demonstrated reuse.

## Subsequent functionality

These capabilities remain planned but do not gate the start of visual work:

- **Metadata parsing:** a dedicated settings section for configurable title rules and separate Artist/Album Artist splitting, with previews, name exceptions, and consistent reindexing that preserves original tags ([#74](https://github.com/anastaci1a/localica/issues/74)).
- **Personalized Home:** configurable carousels using the existing first-added dates, qualified play counts, and pins. Support mixed sources, manual carousel order, sorting, presentation, and preview counts while preserving offline operation.
- **Optional Last.fm integration:** account connection, track/artist equivalency matching with review for ambiguous results, and opt-in scrobbling with durable pending submissions. Implement after local statistics; keep local counts independent of matching and service availability. See [Product](product.md#optional-lastfm-integration).
- **Later playback and presentation:** position-preserving pinch between list/gallery sizes, one step per gesture and excluding queues ([#75](https://github.com/anastaci1a/localica/issues/75)), configurable crossfades, additional shuffle modes, sleep timer, equalizer, and multilingual UI.
- **Visualizers:** a player destination compatible with Poweramp presets. First review MilkDrop v1/v2 files, shader conversion, textures, Poweramp extensions/packaging, licensing, and device performance with compatibility fixtures. [Primary specification](https://github.com/maxmpz/powerampapi/blob/master/poweramp_vis_presets_example/readme.md).
- **Lyric editing:** a manual `.lrc` text editor and a playback-assisted mode that timestamps a line when the user taps it. Include correction, preview, and safe save/export; follow the local lyric reader and tag-write foundations.

Each milestone needs a usable, verified release. Track actionable acceptance criteria in [GitHub Issues](https://github.com/anastaci1a/localica/issues), update this document when outcomes ship, and avoid accumulating a parallel ticket list here.

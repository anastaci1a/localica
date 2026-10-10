# Roadmap

## Released baseline

The library foundation supports multiple folders, overlap deduplication, album/artist/album-artist/folder views, retained per-view queries, and richer metadata, with an in-place migration from v0.1.3. Manual playlists preserve ordering, duplicate occurrences, and unavailable file references. Multi-selection exposes explicit queue and playlist actions. Persistent queues include saved positions, editing, per-view song-tap overrides, configurable switching, and optional confirmed retention limits. Embedded artwork and comment reading extend the metadata foundation. The development APK workflow covers Android 8 and 16; production distribution signing remains a separate decision.

Configurable Home/Library navigation, saved per-tab routes and Back history, an expanding mini-player, and a queue overlay shipped in v0.5.0. The layout uses shared components with simple materials.

Shared browsing, scoped/global search, sorting overrides, contextual selection, cached artwork, and basic manually ordered pins shipped in v0.6.0.

Recoverable individual/bulk tag and artwork editing, image selection, and zoomable artwork viewing shipped in v0.7.1. Local embedded/sidecar lyrics, configurable artwork gestures, synced following, tap-to-seek, and fading viewport edges shipped in v0.8.1. Unsupported write formats/providers remain read-only.

Reference-based layouts, continuous player/Queue gestures, tab swiping, restored search/list history, configurable player actions, outlined session selection, cancelable multi-song drag stacks, richer metadata, independent queue browsing, and next-track preloading shipped in v0.9.0. The behavioral contract is in [Interface](interface.md).

The v0.9.1 patch isolates live route rendering/focus, prevents scrollbar/menu overlap, distinguishes gallery densities, expands compact titles, and makes the top drag card follow the finger directly.

The v0.10.0 player adds aligned floating navigation/utility/mini-player pills, filled transport icons, uncropped artwork backgrounds or floating covers over a matte gradient, and a shared blended lyrics layout with controls that ease downward.

## Reviewable release checkpoints

1. **v0.11.0 — organization and settings:** reference-based settings/list options, scanner dialog and background operation, dedicated folder/error pages, and playlist creation/import menus.

Publish a verified APK at each checkpoint for device feedback; address regressions before advancing. These steps precede glass rendering.

## Visual design system

Review the v0.9.0 layout and interactions on real devices, including drag haptics, before starting the glass layer. Apply the Apple Music-inspired liquid-glass presentation to the established layout and interactions after the functional foundation is proven. Test accessibility, frame performance, and fallbacks across supported Android versions. Keep the design system in this repository initially; consider extraction only after demonstrated reuse.

## Subsequent functionality

These capabilities remain planned but do not gate the start of visual work:

- **Personalized Home and local playback statistics:** configurable carousels, first-added dates, and qualified listening counts with album totals and playlist attribution. Preserve independent queue/playlist identities and offline operation. Local statistics take priority over Last.fm integration.
- **Optional Last.fm integration:** account connection, track/artist equivalency matching with review for ambiguous results, and opt-in scrobbling with durable pending submissions. Implement after local statistics; keep local counts independent of matching and service availability. See [Product](product.md#optional-lastfm-integration).
- **Later playback and presentation:** configurable crossfades, advanced repeat behavior, sleep timer, equalizer, and multilingual UI.
- **Visualizers:** a player destination compatible with Poweramp presets. First review MilkDrop v1/v2 files, shader conversion, textures, Poweramp extensions/packaging, licensing, and device performance with compatibility fixtures. [Primary specification](https://github.com/maxmpz/powerampapi/blob/master/poweramp_vis_presets_example/readme.md).
- **Lyric editing:** a manual `.lrc` text editor and a playback-assisted mode that timestamps a line when the user taps it. Include correction, preview, and safe save/export; follow the local lyric reader and tag-write foundations.

Each milestone needs a usable, verified release. Track actionable acceptance criteria in [GitHub Issues](https://github.com/anastaci1a/localica/issues), update this document when outcomes ship, and avoid accumulating a parallel ticket list here.

# Roadmap

## Released baseline

The library foundation supports multiple folders, overlap deduplication, album/artist/album-artist/folder views, retained per-view queries, and richer metadata, with an in-place migration from v0.1.3. Manual playlists preserve ordering, duplicate occurrences, and unavailable file references. Multi-selection exposes explicit queue and playlist actions. Persistent queues include saved positions, editing, per-view song-tap overrides, configurable switching, and optional confirmed retention limits. Embedded artwork and comment reading extend the metadata foundation. The development APK workflow covers Android 8 and 16; production distribution signing remains a separate decision.

Configurable Home/Library navigation, saved per-tab routes and Back history, an expanding mini-player, and a queue overlay shipped in v0.5.0. The layout uses shared components with simple materials.

## Next three releases

Complete and verify these three APK checkpoints, then stop for review before starting liquid-glass implementation.

### v0.6.0: shared browsing interactions

Finish the shared list/gallery presentation, artwork loading, dedicated scoped/global search, sorting inheritance/overrides, contextual selection, and basic pins already underway, according to [Interface](interface.md).

### v0.7.0: safe tag and artwork editing

Deliver individual and bulk editing of the fields and embedded artwork listed in [Product](product.md#tag-editing). Include image selection from files/gallery, a quick recent-image chooser, and an artwork viewer with zoom. Validate writes and recovery for supported formats and provider capabilities; keep unsupported combinations read-only. Changes must flow back into grouping, searching, queues, and playlists without destroying their identities. Tag and artwork editing take priority over Home customization and statistics.

### v0.8.0: lyrics and player interactions

Deliver local synced/unsynced lyrics, embedded and matching `.lrc` sources, configurable artwork tap/hold actions, and the integrated fading lyrics panel specified in [Interface](interface.md#artwork-and-lyrics). Verify the shared screen structure, Back behavior, accessibility, and material boundaries across browsing, editing, image selection/viewing, and playback so liquid-glass work can begin from usable layouts.

## Visual design system

Apply the Apple Music-inspired liquid-glass presentation to the established layout and interactions after the functional foundation is proven. Test accessibility, frame performance, and fallbacks across supported Android versions. Keep the design system in this repository initially; consider extraction only after demonstrated reuse.

## Subsequent functionality

These capabilities remain planned but do not gate the start of visual work:

- **Personalized Home and local playback statistics:** configurable carousels, first-added dates, and qualified listening counts with album totals and playlist attribution. Preserve independent queue/playlist identities and offline operation. Local statistics take priority over Last.fm integration.
- **Optional Last.fm integration:** account connection, track/artist equivalency matching with review for ambiguous results, and opt-in scrobbling with durable pending submissions. Implement after local statistics; keep local counts independent of matching and service availability. See [Product](product.md#optional-lastfm-integration).
- **Lyric editing:** a manual `.lrc` text editor and a playback-assisted mode that timestamps a line when the user taps it. Include correction, preview, and safe save/export; follow the local lyric reader and tag-write foundations.

Each milestone needs a usable, verified release. Track actionable acceptance criteria in [GitHub Issues](https://github.com/anastaci1a/localica/issues), update this document when outcomes ship, and avoid accumulating a parallel ticket list here.

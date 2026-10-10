# Roadmap

## Released baseline

The library foundation supports multiple folders, overlap deduplication, album/artist/album-artist/folder views, retained per-view queries, and richer metadata, with an in-place migration from v0.1.3. Manual playlists preserve ordering, duplicate occurrences, and unavailable file references. Multi-selection exposes explicit queue and playlist actions. Persistent queues include saved positions, editing, per-view song-tap overrides, configurable switching, and optional confirmed retention limits. Embedded artwork and comment reading extend the metadata foundation. The development APK workflow covers Android 8 and 16; production distribution signing remains a separate decision.

## Next: lasting navigation and player

Replace the tab-number scaffold with configurable Home/Library navigation, an expanding mini-player, queue overlay, and explicit Back history. Establish shared visual components using simple materials.

## Shared browsing interactions

Unify list/gallery presentation, artwork, dedicated scoped/global search, sorting inheritance/overrides, and contextual selection according to [Interface](interface.md).

## Personalized Home

Deliver configurable carousels, pins, first-added dates, and qualified listening statistics. Preserve independent queue/playlist identities and offline operation.

## Safe file tag editing

Prioritize bulk editing of all fields and embedded artwork listed in [Product](product.md). Validate writes and recovery for supported formats and provider capabilities; keep unsupported combinations read-only. Changes must flow back into grouping, searching, queues, and playlists without destroying their identities.

## Visual design system

Apply the Apple Music-inspired liquid-glass presentation to the established layout and interactions after the functional foundation is proven. Test accessibility, frame performance, and fallbacks across supported Android versions. Keep the design system in this repository initially; consider extraction only after demonstrated reuse.

Each milestone needs a usable, verified release. Track actionable acceptance criteria in [GitHub Issues](https://github.com/anastaci1a/localica/issues), update this document when outcomes ship, and avoid accumulating a parallel ticket list here.

# Roadmap

## Released baseline

The library foundation supports multiple folders, overlap deduplication, retained search/filter/sort choices, and richer organization metadata, with an in-place migration from v0.1.3. Persistent multiple queues now include saved positions, editing, configurable song-tap and switching behavior, and optional confirmed retention limits. Embedded artwork and comment reading extend the metadata foundation. The development APK workflow covers Android 8 and 16; production distribution signing remains a separate decision.

## Next: library views and manual playlists

Build album, artist, album artist, folder, and playlist navigation on the shared queries. Add manual playlist management and consistent multi-selection/context actions. All lists use the same queue policies and preserve their sort/filter preferences.

## Safe file tag editing

Prioritize bulk editing of all fields and embedded artwork listed in [Product](product.md). Validate writes and recovery for supported formats and provider capabilities; keep unsupported combinations read-only. Changes must flow back into grouping, searching, queues, and playlists without destroying their identities.

## Visual design system

Replace the disposable testing UI with the Apple Music-inspired liquid-glass presentation after the functional foundation is proven. Test accessibility, frame performance, and fallbacks across supported Android versions. Keep the design system in this repository initially; consider extraction only after demonstrated reuse.

Each milestone needs a usable, verified release. Track actionable acceptance criteria in [GitHub Issues](https://github.com/anastaci1a/localica/issues), update this document when outcomes ship, and avoid accumulating a parallel ticket list here.

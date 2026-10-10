# Roadmap

## Released baseline

v0.1.3 provides a persistent single-folder library, single-track playback, background/system controls, and a verified development APK release workflow. Android 8 and 16 are covered by device checks. Production distribution signing remains a separate decision.

## Next: durable library and queries

Support multiple selected roots without losing the existing library during migration. Preserve other roots and the last successful scan when a root fails or loses access. Add album artist, disc/track, and other organization metadata, stable track references, and shared search/filter/sort queries. Deliver simple folder management and query controls so the foundation is usable and testable before broader visual work.

## Persistent multiple queues

Make this the first major listening improvement after the library foundation. The playback service owns durable queue state and Media3 playback. Ship a basic queue screen and selector, saved positions, editing, and the configurable song-tap and retention behavior defined in [Product](product.md). Verify process recreation and interactions with scanning and unavailable files.

## Library views and manual playlists

Build album, artist, album artist, folder, and playlist navigation on the shared queries. Add manual playlist management and consistent multi-selection/context actions. All lists use the same queue policies and preserve their sort/filter preferences.

## Safe file tag editing

Prioritize batch editing and artwork alongside ordinary metadata fields. Validate writes and recovery for supported formats and provider capabilities; keep unsupported combinations read-only. Changes must flow back into grouping, searching, queues, and playlists without destroying their identities.

## Visual design system

Develop the Apple Music-inspired presentation and glass-like Compose components after the functional foundation is proven. Test accessibility, frame performance, and fallbacks across supported Android versions. Keep the design system in this repository initially; consider extraction only after demonstrated reuse.

Each milestone needs a usable, verified release. Track actionable acceptance criteria in [GitHub Issues](https://github.com/anastaci1a/localica/issues), update this document when outcomes ship, and avoid accumulating a parallel ticket list here.

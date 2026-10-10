# Structure and decisions

Use one `app` module and ordinary constructor injection. Extract modules only when a real boundary needs it; avoid committing to a framework for dependency injection or navigation at this size.

| Location | Responsibility |
| --- | --- |
| `app/.../library/` | Track models, recursive scanning, Android document access, Room persistence, shared queries, grouping, and per-view preferences. |
| `app/.../playlist/` | Durable manual playlists with independent occurrence identities and serialized edits. |
| `app/.../queue/` | Persistent listening sessions, entry identity, serialized commands, and typed listening settings. |
| `app/.../playback/` | Media3 service and controller; the service owns the player independently of screens. |
| `app/.../ui/` | Compose screens and a lifecycle-aware view model; renders immutable state and sends actions. |
| `app/src/test/` | Fast behavior tests using fake document sources. |
| `app/src/androidTest/` | Database, UI, and playback checks on Android. |
| `docs/` | Current product goals, structure, and milestone outcomes. |
| `agents/` | Working instructions and documentation maintenance. |

## Baseline

- Kotlin, Compose, Android 8+ (API 26), compile/target API 36, JDK 17, and a pinned Gradle wrapper. The minimum supports modern storage and media APIs without excessive compatibility branches.
- Media3 ExoPlayer and a MediaSessionService provide local playback, audio focus, headset disconnection handling, and system controls. The service attaches its player to the queue command gateway; app transport and queue edits share a serial command channel. System controls use the same Media3 timeline. No decoder extensions or network permission. MP3 and AAC in M4A are primary targets; WAV, FLAC, Ogg/Vorbis, and Opus use device decoders where available. See [supported formats](https://developer.android.com/media/media3/exoplayer/supported-formats).
- The [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files) grants read access to user-selected folder trees without broad storage permission. Persist each grant, enumerate off the main thread, and rescan roots independently. Unsupported root locations follow the system picker's restrictions.
- Library Room schema 3 adds comments and migrates the released single-folder database in place. Metadata belongs to a track identified by provider authority and document ID; each root has its own membership, access URI, and relative path. Overlapping roots share a track. Identity survives rescans and alternate root access, but depends on the provider retaining its document ID across moves or renames.
- A scan commits one root transactionally. Failed/cancelled scans preserve its last successful contents and all other roots. Metadata failures retain a filename fallback. Removing a root releases its grant after database commit and never deletes audio files. Missing permissions leave indexed tracks visible; playback tries another retained grant when available.
- Shared queries return complete, deterministic ordered song rows using text search, root filtering, and metadata sorting. Work runs off the main thread; typed SharedPreferences retain search/filter/sort choices per view, preserving the original Songs preferences. Albums, artists, album artists, and parent folders share this contract. The full displayed result and selected occurrence index supply new queue snapshots. Large-library paging and indexed search remain future optimizations.
- A separate Room playlist database stores named collections and ordered entries. Entries share the queue snapshot codec, with unique occurrence IDs independent of library membership. PlaylistController serializes transactional edits and publishes committed state. Rescans refresh displayed metadata by track ID; unavailable files retain their stored references. Search and sorting preserve occurrence IDs and never rewrite manual order. Existing library and queue schemas remain unchanged.
- A separate Room queue database stores named queues, ordered entries, and the active queue. Entries have unique IDs and serialized track snapshots, independent of library rows, so duplicate songs and removed roots are preserved. Metadata-only cursor writes avoid rewriting entry lists. Creation, cleanup, and edits commit transactionally before the player changes.
- QueueController is an in-process gateway shared by the app and service; only the service attaches a player. It serializes commands, refreshes available document locations, and applies Media3 playlist edits without restarting the current song. Capture the cursor on player events, every five seconds, and on service destruction. Abrupt termination restores the last committed checkpoint. Restore paused; never autoplay on launch.
- SharedPreferences stores typed song-tap, queue-switching, and optional count-limit settings. Queue state exposes observable settings to screens. Each song-list scope has an optional tap override with explicit inheritance/reset. Selection actions bypass tap preferences and insert complete batches in displayed order.
- Embedded covers load on demand off the main thread and stay out of Room. Decoding is sampled to at most 768 pixels on either axis; absent, invalid, or oversized artwork uses a placeholder. [Media3 Inspector](https://developer.android.com/media/media3/inspector/retrieve-metadata) supplements platform tags with comments and format-specific tag mappings. Tagged MP3, M4A, FLAC, Ogg, and Opus fixtures cover the requested text fields. Tag writing remains a separate milestone.

## Planned foundations

Evolve the current module through explicit library-query, queue-command, settings, and tag-writer boundaries with constructor injection. Keep storage and playback logic independent of Compose. Add interfaces at these real boundaries as they become necessary.

- Library and queries: retain complete-result queue snapshots and independent occurrence identities if the UI later adopts paging. Tag changes must refresh grouping and saved references without losing playlist or queue identity.
- Settings: extend the typed preference boundaries as capabilities arrive. Defaults and user choices are part of the product contract, not scattered screen logic.
- Tags: separate format editing from Android document access. Use recoverable writes, verification, and per-file outcomes. Provider capabilities and grants determine editability. A failed write must not be presented as a successful library update.
- Presentation: implement a reusable Compose design system inside the app first. Glass rendering must not become a dependency of scanning, playback, or persistence. API 26 remains supported through solid-material fallbacks; Android blur APIs have newer platform requirements.

Verification includes v1 migrations, failed/cancelled scans, multiple roots, deterministic queries, durable queue identities and positions, mixed-value batch edits, storage failures, and accessibility. Physical-device feedback supplements CI before major visual work.

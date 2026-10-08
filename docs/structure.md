# Structure and decisions

Use one `app` module and ordinary constructor injection. Extract modules only when a real boundary needs it; avoid committing to a framework for dependency injection or navigation at this size.

| Location | Responsibility |
| --- | --- |
| `app/.../library/` | Track models, recursive scan rules, Android document access, and Room persistence. |
| `app/.../playback/` | Media3 service and controller; the service owns the player independently of screens. |
| `app/.../ui/` | Compose screens and a lifecycle-aware view model; renders immutable state and sends actions. |
| `app/src/test/` | Fast behavior tests using fake document sources. |
| `app/src/androidTest/` | Database, UI, and playback checks on Android. |
| `docs/` | Current product goals, structure, and milestone outcomes. |
| `agents/` | Working instructions and documentation maintenance. |

## Baseline

- Kotlin, Compose, Android 8+ (API 26), compile/target API 36, JDK 17, and a pinned Gradle wrapper. The minimum supports modern storage and media APIs without excessive compatibility branches.
- Media3 ExoPlayer and a MediaSessionService provide local playback, audio focus, headset disconnection handling, and system controls. Each selection replaces the single media item; repeat is off. No decoder extensions or network permission. MP3 and AAC in M4A are primary targets; WAV, FLAC, Ogg/Vorbis, and Opus use device decoders where available. See [supported formats](https://developer.android.com/media/media3/exoplayer/supported-formats).
- The [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files) grants read access to a user-selected folder tree without broad storage permission. Persist the grant, enumerate off the main thread, and allow rescans. Unsupported root locations follow the system picker's restrictions.
- Room stores the selected folder and library as one transaction. A failed or cancelled scan keeps the previous library. Metadata failures retain a filename fallback. Folder replacement releases the obsolete grant only after the new library is committed.
- Screens do not own playback. Library selection and playback are separate so later queue behavior can be added without changing scanning or persistence. No automatic playback on app launch.

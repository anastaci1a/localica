# Product

## Goal and principles

Build a dependable, offline Android player for audio already on the device. Take functional inspiration from [Musicolet](https://play.google.com/store/apps/details?id=in.krosbits.musicolet) and visual inspiration from Apple Music, with Localica's own interface. No account, streaming, discovery service, or network permission is required.

Finding music, starting playback, and changing the current queue should take few steps. Accessibility, large libraries, and background playback are core requirements. Provide sensible defaults and meaningful settings: listening behavior should be personalizable. File access and edits must be explicit and recoverable where possible.

## Library and organization

Combine multiple user-selected folder trees. Browse songs, albums, artists, album artists, folders, and playlists using shared search, filter, and sort rules. Retain view preferences. Album and album-artist metadata must remain distinct from individual track artists, including compilations.

Group albums by title and album artist, falling back to track artist when album artist is absent. Match names without case or surrounding whitespace; missing names have distinct unknown groups. Artist views use track artists. Folder views show each root and relative parent directory, retaining alternate access paths for overlapping roots. Group names are alphabetical; query sorting controls songs within each group.

Manual playlists support creation, naming, ordering, adding/removing tracks, and deletion. Smart playlists are a later possibility. Playlists describe saved collections; queues preserve listening sessions independently of subsequent playlist or library changes.

Each playlist occurrence has its own identity, including repeated songs. Available songs reflect rescanned library metadata; unavailable songs retain their last saved metadata and file reference. Removing a library folder never removes playlist entries. Playlist order is the default; search and other sorts change the displayed result without rewriting that order. Reordering controls apply when the complete saved order is displayed.

## Queue behavior

Every song list follows the same configurable behavior, including album, artist, album artist, folder, playlist, and search results. The default song tap creates a new queue containing the complete displayed list in its current filtered/sorted order and starts at the tapped song. Earlier songs remain in the queue and can be revisited. The queue is a snapshot, not a live query; a later sort, filter, or collection edit does not rewrite it.

A queue screen shows its songs with a queue selector at the top. Queues can be renamed, reordered, appended to, and deleted; individual entries can be inserted or removed. Allow repeated occurrences of a song. Preserve each queue's order, current entry, and playback position across restarts. Switching queues restores the chosen queue paused by default. Never start audio merely because the app launches.

Queues remain until deleted and the default count is unlimited. A user can set a maximum. Creating a queue at that limit opens a dialog proposing the oldest inactive queue for removal, naming it before confirmation. The user can choose another queue or multi-select queues instead. Protect the active queue from this cleanup. Provide "Remove all except current". Cancelling cleanup cancels creation and leaves playback intact.

## Personalization decisions

Maintain this inventory as capabilities are added; these are target settings, not a claim that all exist today.

| Setting | Default and choices |
| --- | --- |
| Song tap | New queue from displayed list; alternatives: insert selected song after current and play it, add next, or append at end. |
| Tap setting scope | App-wide default with optional overrides for song, album, artist, album artist, folder, playlist, and search lists. |
| Queue retention | Unlimited; optional maximum with the cleanup dialog described above. |
| Queue switching | Restore paused; make immediate resume and preservation of playback state selectable alternatives. |
| Sort and presentation | Defaults by list type with optional collection overrides; explicit reset/inherit. |
| Navigation and player | Custom tabs, tab history, exit confirmation, automatic player expansion, and secondary metadata; defaults in [Interface](interface.md). |
| Home | Configurable carousel sources, order, sorting, presentation, and preview counts. |
| Playback | Shuffle and repeat controls arrive with queue playback; defaults off. |
| Appearance | System/light/dark themes, reduced motion/transparency, and a legible solid-material fallback. |

In selection mode, tapping changes the selection instead of executing the song-tap preference. Explicit contextual actions remain available regardless of the tap default.

Search results use the search tap override when a text query is present. Each view retains its own text, folder filter, and song sort; reset restores that view's defaults. Removing a tap override restores inheritance from the app default.

## Tag editing

Read and edit these audio-file tags: name (song title), album, artist, album artist, year, disc number, track number, and comment. Read embedded artwork for playback; support replacing and removing it in both individual and bulk editing. Batch editing is a priority: long press begins multi-selection, visible selection controls support accessibility, and a contextual action menu offers batch tag editing. Unchanged and mixed values must not be silently overwritten.

Aim for write support across all playback formats. Editability depends on the actual container and storage provider: a file that cannot safely accept the requested change remains playable and read-only, with an explanation. Obtain write permission explicitly, retain recovery information, verify the result, and report per-file outcomes for batches. No silent app-only metadata fallback.

## Visual direction and later capabilities

The next releases establish the lasting layout and interaction structure described in [Interface](interface.md). Polish that structure before applying glass graphics. Create reusable Compose components inside Localica, with glass-like materials concentrated on navigation and playback controls. Follow [Apple's material guidance](https://developer.apple.com/design/human-interface-guidelines/materials) on hierarchy and legibility. Extract a separate graphics package only when real reuse justifies it. Older devices retain a usable solid-material presentation.

Gapless playback, sleep timers, lyrics, equalizers, bookmarks, favorites, widgets, Android Auto, and backup/export remain later candidates. They do not delay the [ordered roadmap](roadmap.md).

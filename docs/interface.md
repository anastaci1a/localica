# Interface contract

Build the lasting layout and interactions before applying liquid-glass materials. This is the agreed target; the roadmap identifies the next delivery checkpoint.

## Navigation and player

Default main tabs are Home and Library. Users can add, remove, and reorder Home, Library, Songs, Albums, Artists, Album Artists, Folders, Playlists, Queues, and Most-played Songs; retain at least one and disallow duplicates. Uniform icons are primary, with small labels and accessible touch targets. Fit tabs normally; scroll only on overflow. Whole-library Search stays fixed at the right. Settings/customization belongs in the top bar. Library has a floating create-playlist button above playback controls.

Keep per-tab routes and scroll positions. Back dismisses the keyboard or topmost menu/dialog first, then queue overlay, selection, or expanded player as applicable; browsing Back returns from search/details, then retraces tab visits. Tab-history navigation defaults on; exit confirmation defaults off. Disabling tab history does not disable detail/search Back. Closing the app screen leaves playback running. Activity recreation restores navigation; a fresh launch starts at Home if configured, otherwise the first tab. Re-selecting the current tab adds no history.

The mini-player floats above the bottom-connected tab shelf when an active queue has a current song, including while paused. Both shelves have the same content height (initially 64 dp), excluding system insets. Icons start at 24 dp with at least 48 dp touch targets. Artwork sits left, title and secondary metadata centrally, and previous/play-pause/next icons right. Secondary metadata defaults to album artist with artist fallback; artist-first is selectable. Long titles pause, scroll left, and fade at the edges; reduced motion disables automatic scrolling.

Tapping the mini-player body or swiping upward expands it; transport buttons do not. One expansion progress drives the player surface, the downward exit of the main tabs, and appearance of the player's floating shelf. Full player contains artwork, metadata, seek bar/times, and previous/play-pause/next icons. Its Queue button opens an overlay sheet with the queue selector. Swipe down or Back collapses the player without losing underlying query, filter, route, or scroll position. Song taps that start playback expand by default, with a stay-browsing setting; append/add-next do not expand.

Use simple surfaces for the initial implementation. Shared visual-material boundaries will later provide floating translucency and the expanded player's more opaque, artwork-tinted blurred gradient. Preserve older-device and reduced-motion fallbacks.

## Browsing, search, and selection

Use shared templates for catalogs, collection contents, search, and expanded Home collections. Support list and compact/standard/large gallery presentation, retained by list type with optional collection overrides. Library initially mixes albums and playlists; horizontally scrolling single-choice filters select that mix or songs, albums, playlists, artists, album artists, or folders.

Show embedded covers in list/gallery modes. Artist and album-artist images use representative album artwork; playlists initially use artwork from their contents. Choose covers deterministically, load appropriately sized images on demand through a bounded cancellable cache, and invalidate on rescan. No scraping. Keep a cover-provider boundary for later custom playlist images; assignment is a later capability.

Replace inline persistent search fields with top-bar collection Search. The fixed shelf Search opens the whole library. Search is a route, never a main tab. Global results include songs and collections with type filters; scoped results match items eligible in the originating view. Retain query, scope, order, and position when opening results/player. A new queue from mixed results contains only the displayed song occurrences, in displayed order.

Every applicable list has an unobtrusive sort icon. Changing sort normally changes the list-type default: album catalogs and album contents are distinct types, so changing one album's song sort changes all album contents. A checkbox can override the default for a specific collection; reset restores inheritance. Navigation/search text is independent of persistent sort preferences. Defaults: catalogs/search title ascending; album contents disc then track; playlists/queues saved order; recent collections newest; frequent collections most-played; pins manual. Offer meaningful fields only. Display sorting never silently rewrites saved playlist/queue order.

Hide checkboxes and bulk controls normally. Long-press a song to select it and open an anchored context popup. Dismissing the popup retains selection mode; subsequent song taps toggle selection. Show checkboxes and a compact count/select-all/cancel/Actions toolbar in that mode. Successful actions exit; canceled dialogs preserve selection. Deselecting the final song, leaving the list, or changing its query exits selection. Provide equivalent screen-reader actions. Queue/playlist actions work now; add bulk tag actions only when functional. Collection context menus support pin/unpin, but mixed song/collection bulk selection is outside this milestone.

## Artwork and lyrics

In the expanded player, tapping artwork toggles a lyrics panel by default. Fade it into the player's own layout and background so it feels part of the screen; retain transport access. Use [Better Lyrics](https://github.com/better-lyrics/better-lyrics) as strong visual inspiration for readable active-line emphasis and fluid following. Fade text at the top and bottom viewport edges as lines enter and leave. Respect reduced motion and keep accessibility focus stable.

Synced lyrics follow the real playback position, including seeks and queue changes. Highlight the current line and allow tapping a timed line to seek. Manual scrolling temporarily releases automatic following; provide a return-to-current-line action. Touch exploration starts with following disabled to keep reading focus stable. Unsynced lyrics use ordinary scrolling without invented timestamps. Standard LRC supports line timing; richer word timing may be used only when present in a supported source. The initial reader displays line timing, including millisecond-based ID3 SYLT; frame-count SYLT timing remains unsupported. Show honest empty/error states and expose available source choices from the [local source policy](product.md#lyrics).

Holding artwork opens an anchored popup by default with **View artwork**, **Change artwork**, and **Configure tap action**. View artwork opens a dedicated viewer with pinch zoom/pan and an accessible reset; Back returns to the same player state. Change artwork opens the shared image chooser and edits the current song only through the verified tag-write path. The chooser offers quick recent-image thumbnails and a full files/gallery picker, handling partial access and cancellation. Bulk artwork editing remains an explicit selection action.

Configure tap and hold independently in Settings: toggle lyrics, show the artwork menu, view artwork, change artwork, or do nothing. The default menu provides the tap shortcut; Settings and accessible player actions remain available even when gestures are remapped. Back closes menus/pickers/viewer first, then the visible lyrics panel, then collapses the player. Preserve the underlying browse state and prevent artwork gestures from unintentionally expanding/collapsing the player.

## Personalized Home and statistics

This remains the target after tag/artwork editing and lyrics; local statistics precede optional Last.fm integration. These later capabilities do not block starting liquid-glass work on the established layouts.

Default carousels are pinned mixed items, frequently played mixed items, recently added albums, and recently added songs. Titles with chevrons open the full collection. Allow add/remove/reorder, source, sorting, presentation, and preview-count customization (ten items initially). Carousel choices override view-type defaults. Pins support songs/albums/playlists with manual order.

Record the first successful indexing date per song; rescans retain it. Album added date is the earliest date of its current songs. Existing tracks receive the migration date. Do not invent previous history.

A qualified play requires actual listening for 30 seconds or half the track duration, whichever is shorter, once per playback traversal. Paused time and seek jumps do not count. Album counts aggregate song plays; playlist counts attribute qualified songs to the playlist from which their queue entries originated, including after switching queues. Statistics are local and initially all-time. Empty collections have honest empty states.

## Delivery and verification

Use typed restorable destinations, typed media references, per-type preferences/collection overrides, occurrence-aware queue commands with optional source attribution, shared expansion state, and replaceable material components. Preserve existing libraries, playlists, queues, and listening preferences.

Verify Back precedence/settings, tab overflow, player gestures and transport, restored queries/positions, selection dismissal/cancellation/accessibility, sorting inheritance, duplicate occurrences, complete queue snapshots, artwork reuse/invalidation, pins/dates/play counts, migrations, small/landscape windows, large fonts, reduced motion, and Android 8/16. Editing and lyrics checkpoints also cover safe tag writes and partial failures, single-song artwork scope, recent-image access/cancellation, zoom and gesture remapping, embedded/sidecar precedence, malformed lyrics, and synchronization after seeking or changing songs. Each milestone produces a verified development APK.

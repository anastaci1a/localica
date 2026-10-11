# Interface contract

Use the supplied `localica-reference` screenshots for structure: Apple Music navigation/player, Musicolet lists/queues/scanner, and Poweramp action customization. This document owns visible behavior and UI defaults; [Product](product.md) owns media/playback semantics and [Structure](structure.md) owns implementation.

## Shared controls and motion

- Reuse icon-led popups, consistent bottom action panels, confirmations and titled settings/editor pages. Omit redundant, unavailable and placeholder actions. Settings categories: Interface, Playback and queues, Library and tags, About/licenses; use icons, summaries and chevrons, with search only at the root.
- Corner tokens: small art 8 dp, cards/inputs 16, menus 20, large art 24, panels 28. Compact shelves/chips use pills; transport uses circles. Uniform icons start at 24 dp; a unified icon/label/surface target is at least 48 dp. Clip press feedback to the control's shape. Artwork and timed lyric taps have no ripple.
- Selected choices use a rounded full-row highlight/outline. Ascending/Descending is a labeled segmented control with an animated selected background. Long titles pause, scroll and fade at their edges; reduced motion uses ellipsis.
- Gestures track the finger through slow drags, reversals and interruptions, then settle by distance/velocity. Keep the original owner until release: sliders, lyrics, reordering, carousels and overflow bars must not also swipe the parent, even at an edge. Short-screen scrolling takes precedence. One gesture changes one surface level.
- Default motion: surfaces about 400 ms, menus 300 ms, lyrics 350 ms; offer faster/reduced motion. Programmatic scrolling eases to its measured destination without visible overshoot. Keep focus stable, provide accessible/tap alternatives, and accommodate landscape, large fonts and short screens.
- Browsing/settings stay light during current visual work, including held player collapse. The Queue drawer and directional return circles share continuous frosted material: actual backdrop colors, soft scattering, subtle non-tiled grain/light/edge highlights, crisp foreground controls. Broader themes/material work is [planned](roadmap.md).

## Navigation and Back

| Input/state | Behavior |
| --- | --- |
| Tabs | Default Home + Library. Allow unique ordered choices from Home, Library, Songs, Albums, Artists, Album Artists, Folders, Playlists, Queues, Most played; retain at least one. Icons are primary with small labels; scroll only on overflow. |
| Swipe tabs | Follow configured order without wrapping; preserve each tab's route/position. Commit visit history only after settling. |
| Tap any tab button | Return to its root and ease to the top, retaining root filter/presentation. Re-selecting adds no history. |
| Detail/search Back | Android and in-menu Back restore the same parent query, first visible item and pixel offset. Never restore into temporary empty results. |
| Back priority | Keyboard/topmost popup or dialog → visible queue/playlist selection → queue overlay, artwork viewer or lyrics as applicable → expanded player → detail/search history → tab visits → exit. |
| Preferences/lifecycle | Tab history on, exit confirmation off; disabling history still permits detail/search Back. Recreation restores state; fresh launch prefers Home, otherwise the first tab. Closing the Activity leaves audio running. |

Three aligned floating pills follow the Apple Music reference: full-width mini-player above flexible tabs at left and unlabeled Search/More at right. Search is immediately left of More; More offers Scan music, Settings, Help/About and Close. Lists draw behind these controls, with enough content padding to reveal final items. Library/Playlists have an aligned circular + module matching the lower pills' height, offering Create playlist and Import playlist from file ([import rules](product.md#library-and-playlists)).

## Browsing and search

- Use shared catalog/content/search templates. List/gallery settings inherit by list type with optional collection overrides and reset. Album catalogs and album contents are separate types. Gallery **Items per row** snaps to 1–5; preserve legacy presets until changed. Use smaller text/tighter padding at three or more columns; retain full-width galleries with an overlaid scrollbar.
- Library **All** contains collections only: default Albums, Album Artists, Playlists; optionally Artists/Folders. Settings → Library and tags → Library filters owns visible filters/order and All's contents. Holding a filter opens a shortcut there. Keep one visible filter; an empty All is valid. Hiding the active filter selects the first remaining one. Global search retains all types. Labels use **Album Artist / Album Artists**.
- Covers: circles for Artist/Album Artist, rounded squares for albums/playlists; representative member artwork for collections. Share a subtle loading sweep and quick 160 ms reveal, with static missing-cover placeholders and reduced-motion support. Gallery actions are on long-press, without overlaid menu buttons.
- Library root has no top search box: bottom Search opens a global-search page. Filters float above a soft list-edge fade; initial content clears them. Publish selected filter, saved sort/layout and results together, preventing transient alphabetical/old arrangements.
- Other lists can enable scoped quick search in **List View Options**. Its floating trigger resembles a slim search pill (40 dp visible, 48 dp target); tapping opens a rounded popup over the retained list. Initially show the focused input, then eligible matching rows. The underlying list fades at the top and rests below the pill. Global/local search are not main tabs.
- Opening either search immediately focuses input and raises the keyboard. Typing edits one draft; IME submission or opening a result commits it. Each completed search retains query, scope, filters, sort, results position and identity. Back retraces searches in order without reopening the keyboard. Opening results/player preserves the source and popup positions. Mixed-result queues include only displayed song occurrences, in order.
- Distinct Sort and View controls use shared menus. Defaults: catalogs/search title ascending; album songs disc/track; playlists/queues saved order; recent newest; frequent most played; pins manual. Offer applicable title/count/duration/year/date/artist/Album Artist/composer/genre fields. Date added/modified start newest-first; Most played highest-first, all reversible. Rules for dates/counts: [Product](product.md#dates-and-listening-statistics).
- List gears open the reference-based English **List View Options** subpage: presentation, Items per row, extra information, applicable grouping and scoped search, retaining inheritance/overrides. Overflowing lists of at least 40 items get a narrow edge scrollbar and accessible scroll action; show letter/year previews only during dragging under title/year sort.

## Selection and reordering

- Hide bulk controls until a song is held. Select it and show the shared action panel above the mini-player, retaining tabs; collection holds instead open actions including Select all songs. Normal collection taps navigate. Selected items have rounded outlines; partially selected collections have dashed outlines, never checkboxes.
- Retain source occurrence identities and selection order across routes, tabs, queries, sorting, rotation and backgrounding. Select-all appends in collection order; reselect appends last. Preserve distinct occurrences for playlists/queues and deduplicate files for editing. Fresh sessions start empty.
- Panel: All, Range, count, actions and explicit close; wrap/scroll within a bounded height. Close, mutable-list Back or successful actions exit. Cancel/failure/empty selection retains the mode; collapsing the panel retains selection. Remove-from-source requires one editable source. No separate selection-basket screen. Playlist chooser marks existing membership accessibly while allowing duplicates.
- In a complete, manual, unfiltered queue/playlist, hold-drag moves the held occurrence or selected occurrences from that source, preserving their source order even when nonconsecutive. Cross-source selections and sorted/filtered views cannot reorder.
- Lift visible selected rows into a shadowed stack above all selection controls/list clipping. The first source-order song is on top, trailing cards slightly offset, with a count badge for multiple items. Track the finger directly; keep a one-row insertion gap, ease surrounding rows, edge-scroll and smoothly unpack on release.
- A foreground X circle remains above the stack. Hover turns it red with one firm haptic; leaving gives a lighter tick, honoring system settings. Release there cancels. Cancel/failure restores order and selection; a successful move clears selection after landing and preserves playback cursors.

## Player and Queue drawer

The mini-player appears whenever an active queue has a current song, including paused playback. Inset its artwork/title together inside the leading curve; use black filled Previous/Play/Pause/Next icons. Secondary metadata defaults to Album Artist with Artist fallback; Artist-first is configurable. The body is one rounded tap target; transport remains independent.

Tap/swipe up expands the same artwork, text and transport elements. Metadata/seek/actions/lyrics have 32 dp side insets. Order: artwork → metadata → seek/times → customizable actions → transport → Queue drawer; no volume controls. Album metadata fades in and seek/actions appear late, while transport persists throughout. Main tabs leave downward. Playback-starting song taps expand by default (configurable); add-next/append do not. Swipe down/Back restores underlying browse state. During collapse, artwork and its color continuation resolve together before detaching; no stretched backing cover or dark browse flash.

| Queue element | Behavior |
| --- | --- |
| Drawer | Bottom-attached, visually separate from player artwork, with one frosted surface throughout expansion. Whole label and upward player swipes open it. A downward pull from its top-scrolled list closes it; scrolling/flings/reordering retain ownership. |
| Header | Boxed numbered selector + delete X; play/pause and sort left, occurrence index/count above total duration center, playlist-save/More right. Save to new/existing playlist; Play resumes the browsed queue's cursor. |
| Selector | Rounded popup: numbered queues, highlighted browsing selection, separate playback marker, rename pencil, delete X, reorder and Remove all other queues. Selected long title marquees; other rows, closed selector and search prompts use single-line ellipsis. |
| Songs/actions | Tinted full-row current-occurrence outline, occurrence menus and drag ordering. Queue More omits List view options; global Repeat/Shuffle stay in the player. Browsing/deletion behavior: [Product](product.md#queues-and-playback). |
| Return control | When current occurrence is offscreen, fade in a frosted circle pointing toward it. Ease it to the top or closest possible position. During selection/drag, slide/fade this down so selection actions occupy the bottom. |

Retain the current cover and background while the next artwork/palette loads; fade the completed pair together without blanking or a neutral-color flash. No routine loading/connection status text. Genuine playback failures use a rounded dismissible dialog with plain-language guidance and relevant Retry/Skip actions.

## Player actions

Hold an **empty slot** or use Settings to customize; occupied buttons never open the editor. With all slots filled, use Settings. Provide a slot preview, scrollable icon palette, smooth direct drag/drop with haptics, and tap alternatives. Centered layout uses balanced columns; non-centered uses a horizontal slot carousel. Two rows/Small change spaces; retain intentional gaps and hidden assignments when changing layout.

Offer functional information, editing, art, lyrics, playlist, pin, collection links, shuffle/repeat, count, ten-second seek and More actions. Default: Info/Add to playlist left, Repeat/Shuffle right. Info uses circled i; playlist uses list-plus. Information shows art, file/location, known technical details, metadata links and Edit tags; the shared tag editor puts artwork first.

Mode icons visibly reflect state: dim when off, highlighted when active, visible 1 for Repeat one, distinct Stop/Next paused/Next queue indicators and accessible announcements. Hold Repeat for Simple/Advanced options; tapping returns to the ordinary cycle. Playback semantics live in [Product](product.md#queues-and-playback); keep the destination shelf extensible.

## Artwork and lyrics

- Default full-background cover: scale uniformly to screen width without side cropping, retain its vertical center, keep roughly the upper two thirds clear. Feather the lower third into broad curved color regions preserving left/right cover colors, extending above/below with gentle control shading. Avoid a straight fade to one average color. Alternative: floating cover over an artwork-colored matte/acrylic gradient with subtle non-tiled texture.
- Both layouts animate into one integrated lyrics state: no boundary or Lyrics heading; blend lyrics into the upper screen, lower metadata/transport, fade/slide extra actions away. Keep metadata above seek and transport accessible. Artwork must reappear during held collapse, before release.
- Default artwork tap toggles lyrics; hold opens View artwork / Change artwork / Configure tap action. Independently map tap/hold to lyrics, menu, viewer, change or nothing in Settings. Keep accessible alternatives. Viewer supports pinch/pan/reset and returns to the same player state; changes use the [shared single-song editor](product.md#metadata-and-artwork-editing).
- Follow real playback/seeks/queue changes with active-line emphasis, fluid movement and fading viewport edges, inspired by [Better Lyrics](https://github.com/better-lyrics/better-lyrics). Manual scrolling suspends following; a frosted directional circle returns to the current line. Touch exploration starts with following off. Plain lyrics scroll normally. Keep source selection and honest empty/error states, without loading text/progress or tap ripples. [Source policy](product.md#lyrics-sources).

## Scanner and folders

More → Scan music opens the shared rounded dialog: Scan now, live progress/song count, Folders to scan and Metadata read failures. Its gear opens scanner configuration under Library and tags; Settings contains configuration, not manual scanning. Both routes share the folders page with names/paths, removal and Add folder. Metadata failures appear here, never as a persistent queue warning.

After starting, offer Continue in background and Cancel with completion/failure state. Explain actual permission/background restrictions and link directly to Localica's relevant Android settings page; do not promise to scroll another app's settings. Configuration: subfolders, minimum duration, per-folder relative exclusions and opt-in launch scanning. Adding a folder only grants access. Safety/lifecycle guarantees: [Structure](structure.md#file-operations).

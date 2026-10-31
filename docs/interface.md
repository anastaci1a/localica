# Interface contract

## Shared controls and motion

### Materials and controls

Floating pills, mini-player, filter/search bars and circular controls share Liquid Glass: curved refraction, illuminated edges and crisp foregrounds. The Queue uses one continuous denser textured neutral-frost surface behind its header and list. Popups use the same material and inherited adaptive contrast.

Related controls morph into their panels and return as one visible surface: creation, More, sorting, search and queue/lyric-source selectors. Hidden sources accept no input. a visibly restored source can immediately reverse the current return motion with one owner, without a settling lock or cooldown.

Selected media have a clear, untinted rounded lens, fading and scaling in/out with a short ease-out in either direction. Interrupted transitions retain their current appearance. Side-adjacent selections merge. Diagonal gallery neighbors and unselected holes stay separate. Partial groups retain accessible state without a pane/checkmark. Inset album choices and preserve readable baselines.

Sort/settings choices share a moving clear lens with tap/drag control. Settings sliders and switches follow the [shared control specification](#settings-controls). Use deeper glass for controls that morph into menus, carrying the same depth into their flyouts. The tab bar stays flatter because it retains its shape.

Search/More follows the deeper profile because its surface opens a menu. Separate the actions in every two-button glass pill with a subtle vertical divider. Use rounded Apple Music-style icons, one shared original record glyph for albums, and attributed open-source transport artwork. Omit unavailable actions.

Action-menu width fits measured labels, icons and padding within the screen. Returning flyouts gradually restore the shared trigger foreground with its original ink/tint. Keep system pickers/permissions native and artwork/timed-lyric taps ripple-free.

Corner tokens: small art 8 dp, cards 16, inputs/menus 20, large art 24, panels 28. Compact shelves/chips use pills. Transport uses circles. Icons start at 24 dp with a unified icon/label/surface target of at least 48 dp. Clip feedback to that shape.

Scrollable popup bodies fade content only at edges hiding more content, fading the affordance away as each boundary is reached. Bodies that fit have no edge fade. Keep action-menu gutters inside the scroll viewport so child shadows reach the rounded shell without straight inset cutoffs.

Keep the shell and fixed title/footer outside the body mask. Oversized chrome may scroll when necessary to keep actions reachable.

### Motion and access

Gestures track slow drags, reversals and interruptions, settling by distance/velocity. Controls show contact immediately but defer movement ownership: fast drags along an available parent scroll axis scroll without activating/changing the control. Slow deliberate adjustment or a held drag retains control ownership. Perpendicular scrolling remains available.

Once resolved, retain the owner through reversal and edges. Cancellation never commits. Opening a modal cancels background contacts and flings, rolls back uncommitted pinch/reorder previews, and discards their later releases. Dismissal permits fresh gestures, never resumes old fingers. Only the top modal accepts interaction, including nested or separately declared dialogs.

Shared action buttons, transport/player actions and filter bubbles give exactly one weak system-respecting pulse per successful activation, including accessible activation. Disabled actions, cancelled presses and programmatic state changes stay silent. Multi-select media and artist album choices retain scrolling.

Lyrics, reordering, carousels and overflow bars must not swipe the parent once acquired. One gesture changes one surface level.

Long scroll animation (Settings, Motion) sets how Return to top, Scroll to songs and return-to-current cross a distance in about 500 ms on one curve. Fly-through (default) keeps the rush but lands a fresh region only every few frames, with artwork deferred.

Jump, then glide jumps once near the target, then glides. Fade dims the list for about 120 ms, jumps while hidden, then fades in during the glide.

Every long scroll style ends exactly on the target, stops early once the target is visible, and Reduce motion overrides the choice by jumping straight there. A scrollbar drag defers artwork like a far journey.

Horizontal/vertical choices and fitting navigation preview the lens at any touched option without committing or pulsing. A deliberate hold or owned axial drag continues from that contact. Selectors, settings sliders and switch thumbs give weak owned engagement, one weak tick per distinct discrete step/Boolean transition, and a strong genuine release. Cancellation has no release pulse.

Ordinary tap, keyboard and accessible activation give one weak pulse at the input owner, including the full-width switch row. Continuous sliders have no step ticks. Pending contact, parent scrolling and external/animated state changes stay silent. Parent scrolling cancels preview/activation and retains ownership through direction changes.

Initial cross-axis travel alone must not disable later axial manipulation when no competing scroll wins.

Presses, morphs and selectors share interruptible deformation driven by acceleration and held pressure. Position stops at the destination. Width/height/depth convey impact at approximately constant volume.

Navigation and horizontal/vertical choice tracks share overdrag geometry: the track stretches along travel within a shared 4 dp limit independent of length, its contents deform and realign proportionally, and the selector’s gap to the moving wall progressively closes.

A held selector retains its resting cross-axis extent. Touching an already moving selector eases from the displayed contour. Contact corners stay circular and concentric with the enclosing track. The free end absorbs compression. Contact flattens the lens.

Release couples width and height in opposite phase with bounded physical excursions. The enclosing wall and lens share the configured timing and retain their visible contour on re-touch. One selector presentation owns the released geometry while optical depth continues independently.

Either the app’s Reduced motion setting or Android’s disabled animator duration scale removes shared deformation, edge pressure and rebound. Changes apply while open. Restoring Android animations resumes motion only when the app option is off.

Glass shape morphs remain visible for roughly 500 ms at normal speed and 350 ms with Faster animations. Reduce motion removes the morph. Other surfaces use about 300 ms and lyrics 260 ms. Retain exit drawing until handoff, but dismissed menus immediately release input.

Reopening a retained, prepared menu immediately reclaims its surface and shadow. Newly mounted windows prepare before hiding the trigger. Full settings/editor/artwork pages slide/fade. Programmatic scrolling stops without overshoot. Long journeys virtualize each frame’s crossed distance along one continuous ease and finish within about 500 ms. Touch cancels scrolling.

Active/inactive and enabled/disabled control appearances activate over 180 ms and deactivate over 270 ms (120/180 ms with Faster animations), with no onset delay. Icon badges crossfade in fixed bounds. State, accessibility and input availability change immediately, reversals continue from the visible appearance, and Reduce motion snaps to the result.

Keep existing transport morphs and gesture-driven travel.

Keep focus stable, accessible/tap alternatives available, and layouts usable in landscape, large fonts and short screens. Adjacent glass actions retain natural label widths and logical order. Overflow offers explicit previous/next controls without changing button-owned drags or shrinking text. Decorative shadow/rebound outsets must not enlarge a strip’s touch viewport into neighboring actions.

Long titles pause, scroll and fade at their edges. Reduced motion uses ellipsis. Shared glass text fields wrap labels and retain native editing semantics. A plain trailing X fades in only for nonempty text. Clearing keeps or restores focus and the keyboard.

### Appearance and settings

Browsing/settings stay light, with a pink selected-tab accent, shared neutral gray for secondary text/inactive icons, and red for errors/destructive actions. Use Inter with compact tracking and bold mini-player titles. Do not bundle Apple's system font. **Interface → Appearance** persistently adjusts gray, with reset to the darker default. Broader themes remain [planned](roadmap.md).

Settings categories are Interface, Playback and queues, Library and tags, About/licenses, with icons, summaries and chevrons. Search appears only at the root. Both Back controls reverse the page entrance direction.

**Interface → Glass** provides live persistent controls and reset, with a draggable preview that compresses at its edges and morphs between a pill and dialog:

| Control | Range / treatment |
| --- | --- |
| Glass renderer | Automatic (default), Modern on Android 13+, or Legacy. Changes apply across glass surfaces without restarting. Unsupported restored Modern stays saved while using Legacy, with an explanation; Cancel preserves the saved choice. Reset returns to Automatic. |
| Blur / white tint | Clear through frosted; defaults use light scattering/tint. |
| Shape damping / resting depth | Damping 2–40 (default 8); depth 0.6–2 (default 1.35). Footprint and lensing share the same response. |
| Shadow strength / spread | Off through visible; tight through broad; default very subtle and broad. |
| Adaptive contrast | [Adaptive contrast rules](#adaptive-contrast). |
| Navigation selector | Adjustable glass has independent blur/tint; Clear overlay has 0–50% darkening (default off) and optional icon/text refraction (default off). Classic gray pill has adjustable opacity (default 10%) and no glass deformation. |
| Sliders and switches | White opaque resting thumbs morph into clear unblurred glass on contact; independent expansion 1–2× (default 1.75×). |

#### Adaptive contrast

Page-wide ink polarity; background brightness trim 0–50% (default 25%) and weighting from even to highlight/shadow-focused. White icons darken highlights; dark icons lift shadows in the general glass treatment. Player wash retains its palette-directed trim independently of the ink.

Foreground and media selections remain unfiltered; Player/Queue ink follows the measured background behind controls rather than the cover’s middle band. The player’s blurred artwork extension uses the same trim/weight, without a separate bottom-darkening gradient.

Adaptive contrast also applies to player color continuations, Queue frost and nested panels. They share one page-wide polarity. For sampleable live player backgrounds, black and white ink are compared against the current displayed contributions of the existing palette-directed backing behind title, artist and controls, including live crossfades. Changing ink does not retone that backing.

A choice is current only after matching tone preparation completes. Unmeasured, frozen, native-blurred, moving-cover and partial-player backgrounds retain the last verified decision (white ink before any verification), explicitly without claiming current coverage. Tone preparation, sampling, composition and native presentation are asynchronous, so this does not guarantee atomic ink and background presentation.

Blur, texture and the artwork continuation remain unchanged.

### Settings controls

Use equally sized inline segments for two or three choices when measured labels fit. Longer lists stage a draft until Select. Cancel discards it. An enclosing editor retains its separate Save action.

Sliders expose a label, value, endpoints and accessible range. Horizontal values run from logical leading minimum to trailing maximum, including right-to-left layouts. Vertical ranges run from bottom minimum to top maximum.

Discrete settings use matching ticks and accessible increments. Percentage settings use 5% increments. Continuous ranges retain their precision. A continuous settings slider keeps its last valid value on cancellation.

Switch rows expose one accessible Boolean control. A drag previews the destination. Release commits once and cancellation restores the committed value. Tap and keyboard alternatives produce the same state.

Track and thumb share one coordinate model for fill, travel and boundary contact. Render identity outside the active lens and refraction inside it. Foreground labels remain sharp.

The [appearance settings](#appearance-and-settings) own thumb finish and expansion. The [motion rules](#motion-and-access) own pickup, haptics and reduced motion. Playback retains its separate [scrub cancellation](#player-and-queue-drawer).

## Navigation and Back

### Destinations

Default Home + Library. Keep at least one unique ordered tab. Available: Home, Library, Songs, Albums, Artists, Album Artists, Folders, Playlists and Queues. Most played remains a sort/statistic, not a tab. Four fit the normal phone shelf. Wider sets or large fonts overflow without shrinking touch targets.

Fitted cells accommodate the widest measured label with capsule padding.

### Selector

One lens responds immediately to taps and magnetically follows manual page swipes. Large icons/readable labels share an accent mask under the lens. Direct taps, including reselection, and owned drags use the shared selector haptics. Cancellation is silent. Page swipes, programmatic/settling tracking and overflow scrolling produce no haptics.

A different tab preserves its route/position. Reselecting the settled current tab returns to its root and eases to the top, without history. Library reselection first scrolls to the top while retaining its filter. a separate tap while already at the top clears the filter.

### Dragging

Fitting navigation uses the shared selector pickup above. Overflowing navigation permits lens dragging only from its visible moving lens. Other origins scroll hidden tabs, while taps still select. Clip scrolling contents to the capsule. Shared overdrag fixes the opposite wall. Overflow keeps its undeformed viewport.

Contact, keyboard focus and settling raise the complete tab pill above neighboring utility controls without changing hit bounds.

### Page swipes

Require at least twice as much horizontal as vertical intent, without changing carousel recognition/ownership. Follow tab order without wrapping. Preserve each tab's route, scroll, completed content and artwork. Headings travel with their pages, aligned to search/filter insets. Detail headings crossfade. Commit visit history after settling.

### Back

First end focused text editing and hide keyboard/cursor while preserving the draft. Then dismiss topmost popup/dialog → visible queue/playlist selection → queue overlay, artwork viewer or lyrics → player → current main-tab list to the top → applicable list filter reset → detail/search history → tab visits → exit.

Each press takes one applicable step. List scrolling/filter reset also applies within details, without returning to the tab root. Detail/search return restores the parent query, first item and pixel offset after matching results load.

### Lifecycle

Tab history defaults on. Exit confirmation off. Disabling tab history preserves detail/search Back. Recreation restores state. Fresh launch prefers Home, otherwise the first tab. Activity closure leaves playback running.

Tabs are 64 dp high. The mini-player and standalone circular controls are four fifths of that height (51.2 dp), excluding system insets. Mini-player artwork is 32 dp, vertically centered with a 16 dp leading inset.

Its lens and menu-opening controls are deeper than the tab shelf/selector (1.3× versus 0.72× the configured resting depth). Its compact title/artist use 13/11 sp with a 17 dp separation between line origins. Bottom floating surfaces share one shape-aware shadow without stacked overlap.

Three aligned floating pills follow the Apple Music reference: full-width mini-player above flexible tabs at left and a divided Search/More pill with 28 dp icons at right. Search is immediately left of More. More offers Scan music and Settings. About/licenses lives only in Settings.

Lists draw behind these controls, with enough content padding to reveal final items. Inside album, artist, Album Artist, playlist and folder details, tabs and utility controls leave. The mini-player moves down and remains available.

Library/Playlists have an aligned circular + matching the mini-player's height. It morphs into its frosted creation menu and returns on dismissal, offering Create playlist and Import playlist from file ([import rules](product.md#library-and-playlists)).

## Browsing and search

Use shared catalog/content/search templates. Dedicated catalog tabs and corresponding Library filters share sort/view preferences, without page overrides. Existing Library filter overrides take precedence during migration. Collections and search retain optional overrides and reset. Search text does not create another override. Album catalogs and contents are separate types.

List **Size** offers Smallest, Small, Compact, Normal (default), and Large (18 sp titles). Album, playlist and queue contents also offer Tracklist between Small and Compact. Tracklist uses a track number (displayed position outside albums) beside the normal title/subtitle layout, without a cover. Normal/Large covers match Gallery 5/3 respectively.

Compact keeps its fixed row pitch and text size, sizing leading artwork to Normal’s gap-to-art ratio up to the inner row height, with proportionate corner rounding. Small/Smallest replace artwork with physical occurrence positions in mutable lists or track numbers in albums. Elsewhere they omit artwork.

Smallest uses 30% of the ordinary between-row padding while keeping its text, inner row height and horizontal spacing. These three dense sizes keep one-line extra information immediately before the overflow, with smaller single-line titles. Metadata moves between trailing and subtitle positions through the shared layout animation. Long text ellipsizes, and larger system fonts grow rows.

Normal/Large halve the inter-cover gap, retaining cover sizes and horizontal geometry. Gallery **Items per row** snaps to 1–5. Preserve legacy presets until changed. Use smaller text/tighter padding at three or more columns, with an overlaid scrollbar.

In all lists, including queues and album details, pinch outward through Smallest list → Small list → Tracklist where offered → Compact list → Normal list → Large list → Gallery 5 → 4 → 3 → 2 → 1, and inward to reverse.

Change one adjacent view per gesture, requiring all fingers to lift before another step. Pinch distance linearly targets geometry through a quick interruptible ease-out. Reversing restores items virtualized out by the destination. All text draws below a separate artwork pass.

Departing items reach their scale endpoint with their faster fade. Live items retain ordinary geometry progression. Elements appear/disappear with short reversible fades (120 ms for view controls), ahead of geometry settlement. Keep source-viewport decoded artwork through capture, reflow and release. Resolution upgrades blend without exposing the background. Release settles before saving.

Anchor the scroll range at the top/bottom when already there. Otherwise preserve the content under the pinch midpoint. Album/Artist covers and headers share the presented scroll coordinate through reflow, reversal, offscreen retention and committed/cancelled handoff.

Header action material uses that presented coordinate for live sampling without adding an input or accessibility owner. Missing live-draw readiness retains intact captured content rather than exposing a hole. Keep equal outer margins and selection across modes. View controls remain the accessible alternative. Reduced motion changes layout immediately.

With no filter selected, Library combines collections: default Albums, Album Artists, Playlists. Optionally Artists/Folders. Settings → Library and tags → Library filters owns visible filters, resting order and **Unfiltered Library collections**. Holding a filter opens a shortcut there. Empty filter/collection sets are valid. Hiding the active filter clears selection. Global search retains all types.

Labels use **Album Artist / Album Artists**.

Initial browsing waits for every catalog source used by the current view, never for artwork bytes. Fill the viewport with geometry-matched neutral artwork/title/information skeletons using one shimmer phase. Omit unsupported artwork/information slots. On Artist/Album Artist/Album/Playlist/Queue backgrounds, loading material alone uses 50% opacity.

Loading artwork is gradient-only, never a music note. Loaded content and the final missing-art fallback retain their normal appearance. Artist-only views start circular. Reveal ready rows at a fixed radial speed from the top-left, fading text/artwork over fading shapes. Mixed-view artist placeholders and artwork share one eased square-to-circle contour.

Gallery title/information shapes use one measured normal text-line height and its normal vertical spacing. List shapes are unchanged. Separately reserve measured multiline height through the complete row reveal, then ease away only unused space. Cached returns do not replay loading. Reduced motion removes shimmer/reveal/reflow motion.

Covers: circles for Artist/Album Artist, rounded squares for albums/playlists. Automatic collection covers use the first displayed member under that collection’s saved sort override or inherited contents sort, including album grouping. Playlist/queue covers follow their persisted occurrence order. Explicit custom covers take precedence.

A first member without artwork keeps the placeholder instead of borrowing a later member’s cover. Reuse a tapped cover immediately in its detail header and crossfade its higher-resolution replacement. Keep the visible artwork of each tab and the previous two Back destinations.

Show memory-cached covers immediately. Newly decoded covers use a subtle loading sweep and 300 ms reveal, with static missing-cover placeholders and reduced-motion support. Gallery actions are on long-press, without overlaid menu buttons. Collection/nonmutable-song holds open the anchored actions. Mutable-song holds select, then moving the same finger starts reordering.

Plain row ellipses tap to open actions and hold for the same selection/actions as their row. Actions include: select songs, queue/playlist destinations and pinning. Playlists additionally offer image, rename and delete. The image action sets a custom cover when absent and removes it when present, restoring representative member artwork.

Playlist detail repeats these actions in its floating overflow control.

Scrolled song lists offer a bottom-center return-to-top control without clearing filters or changing the current list. Home returns its vertical list without resetting the pinned carousel. Active Queue uses its current-occurrence following control instead (below). Return and song-drag cancel controls use the collection utility row above bottom chrome/mini-player.

Queue return and foreground song-drag cancel controls share 12 dp clearance below the circle. Activating Return removes its drawing and input immediately while that return command owns readiness or motion. Cancellation restores normal visibility from the current list state. Return and drag/seek-cancel controls otherwise fade in while rising, continuing through reversals.

Faster animations shortens the entrance and Reduce motion snaps. Moving cancel hit regions follow the visible target, and hidden or replaced controls immediately relinquish input.

Library root has no top search box: bottom Search opens a global-search page. Selecting a glass filter bubble moves it to the far left. The previous selection returns to its configured position, with neighbors yielding progressively and scrolling following the selected bubble.

Tapping the selected filter clears it and restores its position. There is no All button. Keep configured order separate from this temporary arrangement. Start movement on tap. Immediately fade away the previous results. Reveal the new results only with their matching saved sort/layout, independently of bubble movement.

Album contents omit scoped search and its setting. Other lists can enable scoped quick search in the **View** tab of browse options. Search pills and filter bubbles share their height and vertical placement (40 dp visible inside a 48 dp target, 4 dp outer vertical inset). They grow together for large text.

Both float above a nonlinear list-edge fade with initial content below them. Search immediately expands the pill into one frosted panel. Visible list-slot collapse and restoration follow its placement/return motion.

Collapse only visible vacated header space so an immediately following row lands at the old visible pill top, preserving row padding. The top fade disappears and returns with that same placement motion. Restore the original scroll offset. Already-scrolled content stays put. Global/local search are not main tabs.

Scoped search focuses its field as soon as layout is ready and expands while moving from the start, with one rectangle animation for size and position. Aim above the actual reported keyboard destination. When unavailable, begin toward the safe bottom while expanding, then retarget from the current rectangle as live keyboard height arrives.

Do not borrow a cached hidden keyboard height. Keep the field at the top without scaling. Actual dismissal by empty Back or outside tap begins one direct return to the home pill while the keyboard hides independently. Nonempty Back first ends editing. The retained panel centers and adds 20% height within safe margins.

Child menus freeze the parent’s position until return. Actual dismissal clears the scoped session. Temporary covering/result navigation preserves it. Scoped results omit subtitles. Initial search results and filter changes reveal visible rows sequentially. Typing edits one draft. Its editor session survives completed-query-to-draft transitions while result/history state remains route-owned.

Back or actual dismissal retires the editor and its callbacks. IME submission or opening a result commits the draft. Each completed search retains query, scope, filters, sort, results position and identity. After editing ends, Back retraces searches without reopening the keyboard. Opening results/player preserves the source and popup positions.

Mixed-result queues include only displayed song occurrences, in order.

The entire divided Sort/View pill expands into one frosted panel, initially showing the tapped tab. A fixed icon-and-label glass tab header separates navigation from settings below. Keep the panel size constant across tabs and supported option states.

Measure inert faces for the complete body, title and action-footer alternatives. Wrap their maximum natural width within the shared window cap. Reserve title/footer space before bounding body height. Do not use a separate screen-percentage cap. Both tabs share one draft: switching preserves edits. Save applies both and Cancel discards them.

Choice controls follow the [shared staged/inline rules](#settings-controls). Defaults: catalogs/search title ascending. Album songs disc/track. Playlists/queues saved order. Recent newest. Frequent most played. Pins manual. Offer applicable title/count/duration/year/date/artist/Album Artist/composer/genre fields. Date added/modified start newest-first. Most played highest-first, all reversible. Rules for dates/counts: [Product](product.md#dates-and-listening-statistics).

Extra information starts with None, then Follow sort order. Unfiltered Library and global search additionally offer Automatic between them and default to it when no choice is saved. Automatic inherits each row type’s catalog/filter extra information and, for Follow sort order, that type’s own sort.

Changes to those settings refresh mixed rows. Explicit saved choices retain their meaning.

### Overrides and mutable lists

Successful changed queue/playlist randomization scrolls to the top. Queue sort/revert follows the active playing or paused occurrence, otherwise the top, after matching rows publish. Unchanged or failed edits and View-only saves leave the viewport unchanged. New navigation or list input cancels pending movement.

Collection/search overrides use **Save only for this list** above Cancel/Save. Main catalog tabs have no override. In queues/playlists the switch belongs only to View. Their Sort tab offers immediate Randomize with Revert to its right and Reverse directly below, then a divider and a staged method/direction, with Close/Apply. Apply stays open and disables once applied.

Revert uses shared glass emergence/disappearance in a reserved slot and disappears whenever the physical order matches the opening snapshot, including after two reversals. Manual restores the saved baseline. Sort by and Extra information use the same staged selector trigger and frosted dialog.

View offers presentation, list Size or gallery Items per row, extra information and applicable scoped search. Overflowing lists of at least 40 items get a narrow edge scrollbar and accessible scroll action. Show letter/year previews only during dragging under title/year sort.

## Collection details

Playlist headers use their existing manual cover or canonical automatic cover, independent of displayed sorting/filtering. The shared Play/Shuffle pill acts on displayed occurrences. The bottom-right menu retains playlist-specific edits and mutable semantics. Scoped Search remains optional in View. With Search hidden, reaching the actual top removes Return and lets the next Back leave the detail.

Album, artist and playlist pages use a full-width artwork header beginning below the status bar, with a blurred extension above and a feathered color continuation behind the songs. Artwork travels at 65% of content speed with a continuous offset after the lazy header leaves view. Reduced motion disables parallax.

Choose black or white foregrounds from the artwork brightness. Sort and visibility share a glass pill where both are present. The whole pill opens the shared tabbed panel. Collection overflow opens the requested tab in one window and returns to the persistent three-dot anchor. Empty lists show a quiet centered inset-style icon.

Back and collection overflow are glass controls. The top color fade depends on scrolling past the artwork and is absent at the top.

Detail entry immediately uses the cached cover and its colors, title and artist. Crossfade the higher-resolution artwork/color continuation together. Newly loaded tracks reveal in a bounded sequential fade sweep, disabled by reduced motion.

Album details default to Tracklist presentation and ascending disc/track order, with Sort/View and pinch alternatives. Their bottom-right overflow shares playlist collection actions, excluding playlist-only edits. Album headers show the album/artist and a large Play control using the configured song-tap behavior. Smallest/Small/Tracklist album rows use muted track numbers instead of covers.

Compact/Normal/Large/Gallery show ordinary covers without track numbers. Existing album Normal preferences migrate once to Tracklist. An explicit new Normal choice is retained. Keep regular song titles and quiet horizontal ellipses. Song-row overflow remains plain until its menu opens. Top-level utility menus retain vertical dots.

Artist and Album Artist headers follow the first displayed song, including the active album filter. No artwork scraping. Sort/View sits at top right and collection actions at bottom right. Below the header, a horizontal album multiselect filters contiguous album groups in the same order as the carousel.

Artist/Album Artist contents default to Year Ascending. The retired Album/disc/track choice migrates to it while other explicit choices remain. Sort direction changes album order only. Inner disc/track stays ascending. Year/Added use earliest known values, Modified latest, Duration/Most played distinct-track sums, and Folder the earliest case-folded nonblank relative path.

Missing aggregates stay last. Ties use album title then canonical album ID ascending. Short tap focuses only that album. Long press toggles only that album with one recognition haptic and no trailing tap. All mode highlights both All albums and every cover.

Completing the available multi-album set or removing the final member restores All. a short-tapped single album remains explicitly focused, even for a one-album artist. Reconcile membership after library changes. Play, Shuffle and collection menu actions share the displayed ordered song scope.

When filtering shortens the list, ease only the excess scroll to the measured new range. Retain valid offsets and user gesture ownership. A combined glass Play/Shuffle pill acts on that displayed list. Shuffle randomizes the new queue’s saved order without enabling global traversal shuffle.

The Add to playlists panel combines covered existing-playlist rows with an iconless inline new-name field using normal text padding. Add or keyboard submission applies all chosen destinations. Duplicate names disable Add, using the [playlist rules](product.md#library-and-playlists).

## Selection and reordering

Holding a mutable playlist/queue song selects immediately. Its new action panel waits for release, so continued movement into dragging never flashes it. Floating utility controls never select obscured rows. Existing panels retain their state. Accessibility selection without a pointer shows the panel immediately.

Other items use Select song / Select all songs in their shared menu. The bulk action panel eases/fades above the mini-player over 220 ms, independently of immediate item selection, retaining tabs. Normal collection taps navigate. Song taps toggle membership while selection is active. Use the [shared selection treatment](#materials-and-controls).

Retain source occurrence identities and selection order across routes, tabs, queries, sorting, rotation and backgrounding. Select-all appends in collection order. Reselect appends last. Preserve distinct occurrences for playlists/queues and deduplicate files for editing. Fresh sessions start empty.

Panel: All, Range, count, actions and a divided collapse/close glass pill. Wrap/scroll within a bounded height. Close, mutable-list Back or successful actions exit. Cancel/failure/empty selection retains the mode. Collapsing the panel retains selection. Remove-from-source requires one editable source. No separate selection-basket screen. Playlist chooser marks existing membership accessibly while allowing duplicates.

In a complete, manual, unfiltered queue/playlist, hold-drag moves the held occurrence or selected occurrences from that source, preserving their source order even when nonconsecutive. Cross-source selections and filtered views cannot reorder. Applying a saved-order sort does not disable subsequent dragging.

Lift visible selected rows into a shadowed Liquid Glass stack above selection controls/list clipping, sharing its material with Player actions dragging. The first source-order song is on top. At most two empty glass layers trail it, with a count badge for multiple items.

Lifted content retains its actual list/gallery layout and lands on the rendered item bounds. Material and trailing actions crossfade without shrinking the content or jumping at handoff. Track the finger directly. Keep a one-row insertion gap, ease surrounding rows, edge-scroll and smoothly unpack on release.

Playback checkpoints and unrelated refreshes retain an owned contact. Route or source occurrence-order changes cancel it, and commits reject an intervening source-order change.

Hide the action panel throughout dragging/landing. Floating controls share its animated visible occupancy, with an 8 dp gap above the panel, and return above remaining bottom chrome as it leaves. A Liquid Glass foreground X circle remains above the stack in the shared bottom control row.

Hover tints it red with one firm haptic. Leaving gives a lighter tick, honoring system settings. Release there cancels and restores the panel. Other drops, including an unchanged insertion position, finish selection after successful landing.

System interruption or failed persistence restores order, selection and the panel. All landings crossfade into normal rows and preserve playback cursors.

## Player and Queue drawer

The mini-player appears whenever an active queue has a current song, including paused playback. Inset its artwork/title together inside the leading curve. Use black filled Previous/Play/Pause/Next icons. Pause appears only while playing or buffering with playback requested. Paused skips retain Play.

The settled Play triangle uses a single filled contour. It morphs into Pause. Skips use two rounded triangles without an end bar, with a staggered directional nudge. Secondary metadata defaults to Album Artist with Artist fallback. Artist-first is configurable. The body is one rounded tap target. Transport remains independent.

Tap/swipe up expands one shared outline containing glass, artwork, text and transport. The expanded player follows device-reported corner radii. Absent or unavailable physical geometry stays square. Queue keeps the shared rounded panel contour for its material, content clip and border throughout opening, closing and reversal, independently of physical window clipping.

The entire Queue layer stays clipped inside the player’s moving rounded outline during expansion, collapse and interruption. Its lower edge is revealed only as that outline reaches it. Glass grows blurrier through expansion. The final artwork color continuation blends in later and becomes opaque near full size, without passing through a darker average-color backing.

Full-background covers soften at their perimeter while keeping the center sharp, then merge into the continuation. Floating covers keep defined edges. The artwork feather never drops away at the final handoff. All blends reverse with the gesture. Artwork stays below title and artist text, including transitions.

Metadata/seek/actions/lyrics have 32 dp side insets. Order: artwork → metadata → seek/times → customizable actions → transport → Queue drawer. No volume controls. Album metadata fades in and seek/actions appear late, while transport persists throughout. Title and transport retain their delivered trajectories, including selected lyrics.

Seek and actions use distinct mixtures between those unchanged references, with actions moving/fading faster than seek. Reversals retrace the presented geometry without changing settled positions. Artwork grows immediately along one reversible, geometry-derived curve, aiming its bottom-right at the title’s top-left without moving existing trajectories or settled positions.

Layout, lyrics and aspect changes use presented geometry without a separate curvature clock. When corner clearance is infeasible, retain continuous bounded best-effort growth rather than claiming collision avoidance or switching abruptly to ordinary easing.

Normal metadata and controls sit below the cover with balanced space above Queue. Lyrics mode lowers them further and centers transport between the seek track itself and the collapsed Queue shelf’s top.

While scrubbing, show the same foreground X target as mutable-list dragging, above the Queue shelf. Entering and leaving X ease symmetrically between live playback and the current finger position, continuing through reversals. Faster animations shortens the transition and Reduce motion snaps.

An owned seek drag gives one weak engagement pulse and a strong genuine release outside X, with no continuous-position ticks. Entering X gives the shared firm pulse and leaving gives a weak tick. Releasing there or cancelling discards the preview without a seek, pause or release pulse. Tap and accessible changes give one weak activation.

Pending contact, parent scrolling, unrelated keys and live/programmatic playback tracking stay silent. Feedback honors system settings. Other genuine releases commit the finger position and retain that visual target through asynchronous seek acknowledgement, without exposing a stale playback or contact-start frame. Cancellation and obsolete media generations cannot commit it.

Title and artist default to content-based text direction, preserving explicit direction overrides, and use independently sized, longer soft edge fades throughout player expansion/collapse, never switching to ellipses mid-transition. Reduce motion retains the static ellipsis treatment. Pause ordinary marquee during movement. If offset, immediately ease to the beginning by the shortest path (including forward wrap).

Keep the leading fade fully present until arrival, then ease it out. After the player settles and the normal delay passes, ease the leading fade in before restarting marquee. Preserve these phases independently for each overflowing line.

A 72 dp translucent handle appears late in expansion below the safe top inset, using the same ink polarity as text and icons. During collapse it keeps the same inset from the moving top edge and fades early, avoiding control overlap. Its accessible tap target and the top-left glass down arrow collapse the player.

Main tabs leave downward. Playback-starting song taps expand by default (configurable). Add-next/append do not. Swipe down/Back restores underlying browse state. Opening commits at 240 dp/s or about 22 dp of travel. Player and Queue closing share 300 dp/s or 72 dp, with progress deciding otherwise.

Both remain interruptible during settling. Player collapse slows into its resting position. During collapse, artwork and its color continuation resolve together before detaching. No stretched backing cover or dark browse flash.

| Queue element | Behavior |
| --- | --- |
| Drawer | [Queue Drawer rules](#queue-drawer). |
| Selector | [Queue Selector rules](#queue-selector). |
| Header | Handle and Queue title; down/numbered-selector/More row; sort/occurrence-index-and-count-above-runtime/add-to-playlist row; search, then songs. Save to new/existing playlists and omit a redundant play button. Share outer margins and compact spacing while leaving shadows unclipped. |
| Songs/actions | [Queue Songs/actions rules](#queue-songsactions). |
| Following | [Queue Following rules](#queue-following). |
| Return control | [Queue Return control rules](#queue-return-control). |

### Queue Drawer

Bottom-attached, visually separate from player artwork, with one neutral frosted surface throughout expansion: blurred backdrop colors and fine texture, using the same configurable adaptive contrast and artwork-selected ink as the player and nested popups. No fixed dark tint or expansion-dependent darkening. The queue header’s down arrow minimizes it; deletion lives in Queue actions.

Whole label and upward player swipes open it; body/handle/list pulls settle by release velocity and distance. Once a gesture starts opening Queue, reversing only closes Queue and cannot dismiss the player. A fresh downward pull from its already top-scrolled list closes it; an interrupted pull restores the open drawer.

Once either the list or drawer acquires a contact, it keeps that contact through edges and reversals until release; switching requires a fresh gesture. Excess finger travel past a list edge must be retraced before scrolling resumes. Flings/reordering retain ownership.

### Queue Selector

Rounded popup opens already positioned at the playback queue. Numbered rows show browsing selection, a separate playback marker, plain rename/delete icons and shared whole-row long-press reordering, with accessible move actions on the handle. Dragging previews order until release; cancellation or a failed save restores it without changing browsing/playback.

Short popups omit the repeated heading to retain a usable list. When more than one queue exists, Remove all other queues morphs from a glass button into confirmation; each confirmation name uses a single-line ellipsis. Selected long title marquees with an unfaded leading edge during its pause; other rows, closed selector and search prompts use single-line ellipsis.

### Queue Songs/actions

A subtly denser, textured frosted slab marks only the current queue occurrence, matching the selection lens's bounds and rounding with a faint material edge; other browsing lists have no playback highlight. Occurrence menus omit Move up/down; drag ordering remains available; information links close player/queue overlays and open the collection without changing playback.

Queue More omits List view options; global Repeat/Shuffle stay in the player. Browsing/deletion behavior: [Product](product.md#queues-and-playback).

### Queue Following

The active queue starts attached, except with touch exploration. Fresh drawer openings position the exact current occurrence before the first row measurement without reattaching a detached route; recreation retains its viewport and follow intent. Natural advancement and manual/external transport ease to the exact current occurrence, including paused playback and duplicate songs.

User scrolling or return-to-top navigation detaches; retain that intent with the list. Following never steals selection, drag, pinch, modal or hidden-page ownership. Order actions keep their one-shot destinations without changing attachment.

### Queue Return control

Opening/reopening unfiltered Queue places its exact current occurrence before the first row draw, without catch-up scrolling or changing ongoing follow attachment. Retained-route recreation without a new opening preserves its viewport. A detached queue offers its directional return bubble even when the current occurrence is visible.

Return reattaches and eases below the search pill or to the closest possible position. Scoped Queue search keeps its explicit offscreen-current return without automatic following. Selection hides the bubble, drag owns its slot, and exit retains its last direction. A filtered-out current occurrence has no substitute or top target.

### Player artwork transitions

Retain the current complete visual while the next artwork/palette loads. Prepare the actual playback neighbors without blocking audio. Album-art swiping can be disabled independently in Settings → Interface → Player (enabled by default). Enabled horizontal cover swipes follow the finger, feathering adjacent covers through their blended color continuation.

A short peek returns. Sufficient travel or a directional flick commits the previewed visit once on release. Re-grabbing a settling carousel preserves its displayed position. Neither settling nor artwork loading disables horizontal ownership. Right swipes choose the actual previous visit independently of the Previous restart preference.

Hold the prepared destination until playback/artwork catches up, then hand it over without a second wash. Interrupted touches and invalidated previews return safely.

Buttons and autoplay default to a symmetric blur-through: soften the outgoing cover, blend near peak blur, then resolve the incoming cover. Settings offers the perspective carousel as an alternative. Its edge softness follows travel continuously in both directions.

Each playback visit animates, including identical decoded covers. Cached pixels may be shared without skipping the transition. Use matching cached artwork immediately, retain the ready frame while its replacement loads, and show only the shared gradient on a cold start, without a music icon or white backing.

Prepare up to four visits in each direction from actual queue traversal, including shuffle/history, within the bounded prepared-artwork cache.

Full-background covers feather into their blended color continuation. Floating covers keep separate edges while their backdrops crossfade. Reduced motion uses a short fade without blur or travel.

Lyrics remain open across songs and crossfade their documents. Missing local lyrics show an unobtrusive empty indicator. Loading never exposes a white backing surface, blanks the player or replaces artwork prematurely. No routine loading/connection status text. Genuine playback failures use a rounded dismissible dialog with plain-language guidance and relevant Retry/Skip actions.

## Player actions

Hold any slot/action to customize, except Repeat, which retains its dedicated menu. Settings also opens the editor. Drag from the reflowing available-action pool into slots, or return an assignment to the pool to remove it. Keep tap/accessible alternatives, haptics and animated landing without a disabled duplicate.

Centered, 2 rows and Compact use independent Library-style filter bubbles: selected first, canonical order within both groups. Compact adds spaces without shrinking icons. Centered layout balances columns. Non-centered playback stops at the rightmost visible assignment. Preserve intentional gaps and hidden assignments.

Offer functional information, editing, art, lyrics, playlist, pin, collection links, shuffle/repeat, count, ten-second seek and More actions. Default: Info/Add to playlist left, Repeat/Shuffle right. Info uses circled i. Playlist uses list-plus.

Information: shared bounded frosted popup with tappable artwork and divided file/location, metadata, known technical details and listening/date sections. Leading icons align beside label/value blocks. Entire Album, Artist, Album Artist and Location rows open their existing collections. The body scrolls with directional overflow fades while Edit tags and Done stay together at the footer’s trailing edge.

Popup controls and secondary text share the material’s light/dark palette.

Tag editor: frosted sheet with shared title, perimeter and fixed header/footer. Only the fields scroll. Center artwork above checked fields, with change/remove controls on the preview. Follow [editing and crop rules](product.md#metadata-and-artwork-editing). The cropper offers pan, pinch, zoom and reset.

Image picker: rounded previews of readable previous choices. Optional device-photo access has a short explanation and glass permission button. An evenly spaced divider separates these from icon-bearing glass Gallery/Files actions.

Bulk jobs: Continue in background and Stop after current file. **Settings → Library and tags → Tag jobs and recovery → Tag editing jobs** shows progress/recent per-file outcomes.

The artwork viewer centers fitted artwork across the full page. Safe Close and contextual Zoom/Reset controls overlay the image. Pan bounds follow the fitted image. Released pans decelerate. Re-grab, reset, zoom or reduced motion stops travel. Recreation preserves zoom and the visible image center.

Mode icons visibly reflect state without filled hitboxes or ripples: dim when off, full strength when active, visible 1 for Repeat one, distinct Stop/Next paused/Next queue indicators and accessible announcements. Hold Repeat for Simple/Advanced options. Tapping returns to the ordinary cycle.

Both modes share a compact, screen/font-aware popup: measure Simple for the common body height and both modes for width.

Advanced uses **When a song ends** and **When a queue ends** buttons, matching Extra information. Each opens a non-scrolling configuration popup. Select stages its changes, while Cancel or Back discards that child draft.

Parent Apply saves the selected mode. Parent Cancel discards both mode drafts. Children measure complete shared-control layouts at the actual safe width, height and font scale. Keep the shared title and perimeter. Wrap labels and use compact body roles when needed. Queue choices may sit beside the complete warning, with both switches above.

Preserve full labels, system font scaling and 48 dp targets. Only when no complete arrangement fits, show visible guidance to enlarge the window or reduce text size, with Cancel. Resizing back restores the child draft.

Keep the parent’s mode tabs and actions fixed when they fit. Playback semantics live in [Product](product.md#queues-and-playback). Keep the destination shelf extensible.

## Artwork and lyrics

Default full-background cover: scale uniformly to screen width without side cropping, retain its vertical center, keep roughly the upper two thirds clear. Feather the lower third into broad curved color regions preserving left/right cover colors, extending above/below with gentle control shading. Avoid a straight fade to one average color.

Alternative: floating cover over an artwork-colored matte/acrylic gradient with subtle non-tiled texture.

Both layouts animate into one integrated lyrics state: no boundary or Lyrics heading. Blend lyrics into the upper screen, lower metadata/transport, fade/slide extra actions away. Keep metadata above seek and transport accessible. Fade the artwork and its feathered edges uniformly.

Selected lyrics mode determines all target positions throughout held player movement, rather than switching at release. The lyrics viewport keeps its upper screen anchor until clipped by the player. Its bottom follows the presented title with the normal gap.

Lyrics and their empty placeholder drift more slowly inside those same faded/clipped bounds, retaining content geometry and scroll state. They fade out early in collapse and fade in late on the inverse expansion. At fully expanded held progress they are fully visible and artwork is gone. Artwork returns continuously during held collapse before release.

Album-only extra actions remain hidden throughout lyrics-mode movement, including intermediate frames. Partial reversals retain lyric scroll/source state. Keep every lyrics entry point available even without local lyrics.

The top-right lyrics bubble widens into a divided pill: its persistent lyrics icon moves left to select sources, and a crossed-out eye on the right hides lyrics. Source selection first separates it into two circles, then expands the left circle into its popup. Return restores that circle before rejoining.

Keep the right action visible, carry contact/rebound through each boundary, and let only one surface own the left material and shadow. Cancelling or reversing preparation retraces it. Source/track changes close the outgoing menu without applying stale choices. Sort/View retains its direct whole-pill morph.

Default artwork tap toggles lyrics. Hold opens View artwork / Change artwork / Configure tap action. Independently map tap/hold to lyrics, menu, viewer, change or nothing in Settings. Keep accessible alternatives. Viewer supports pinch/pan/reset and returns to the same player state.

Change artwork opens image selection directly, then the shared artwork-only review/write flow for the current song in the [shared single-song editor](product.md#metadata-and-artwork-editing).

Follow real playback/seeks/queue changes with active-line emphasis, fluid movement and fading viewport edges, inspired by [Better Lyrics](https://github.com/better-lyrics/better-lyrics). Manual scrolling suspends following. a Liquid Glass directional bubble returns to the current line. Touch exploration starts with following off. Plain lyrics scroll normally. Keep source selection without loading text/progress or tap ripples.

Show the shared quiet empty state only after the current lookup finds no usable lyrics without reporting a read error. Never during loading, failed reads or a real lyric document's arrival. Available lyrics remain visible when another source fails.

Keep its inner position fixed during lyrics-mode changes. Player travel uses the same slower content motion and viewport as real lyrics. [Source policy](product.md#lyrics-sources).

## Scanner and folders

More → Scan music and Settings → Library and tags → Scanner share glass **Full scan** / **Quick scan** actions. The popup reserves progress/status/action space so starting or finishing does not resize it. Progress uses the navigation accent. Continue in background and Cancel remain available during a scan.

Explain actual background restrictions and link directly to Localica’s Android settings, without promising to scroll another app.

Scanner owns subfolders, minimum duration, Metadata read failures, and independent off-by-default Quick scan / Full scan startup switches. Enabling both runs all quick discovery before full refresh. Folders to scan remains in Library and tags, with per-folder exclusions, removal and Add folder. Duplicate basenames use stable fractions such as Media (1/2). Unique names stay plain.

Adding a folder only grants access. Metadata failures are never a persistent queue warning. From a shortcut’s initial settings page, Android Back returns to the caller. The header Back still visits the settings parent. Safety/lifecycle guarantees: [Structure](structure.md#file-operations).

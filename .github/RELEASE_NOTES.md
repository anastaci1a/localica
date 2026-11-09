<!-- Release: v0.33.14 -->

# Release v0.33.14

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This is an optimized build (not debuggable, minified with R8, with a baseline profile). It updates the existing app in place.

## Changes

- Last.fm matching is automatic: artist, album, title, duration and MusicBrainz clues resolve clear matches silently, so the review list shrinks to the few genuine ambiguities. Turning Last.fm off keeps the archive and your match choices; Disconnect keeps the archive too, and only a confirmed Delete synced Last.fm data removes it. Refreshes fetch recent plays with an explicit Full rescan, and local plays after a sync count temporarily until the next refresh.
- Settings has an Online features page: the privacy switch (off by default) with Last.fm and Artist photos sub-pages, all reachable through settings search.
- Artist photos now show in Artist and Album Artist rows, gallery tiles, Home pins and the artist header, crossfading over the album art when Online features is on. Artist photos settings show sources and cache use with Clear cache and Refresh all; an artist's ⋯ menu can choose or clear its photo and show the picture's credits.
- The return-to-top button in Search and browse lists hides at the top, retires after a tap or a manual return, and keeps its gap below the floating popup control.
- Queues popup: the Remove all other queues trash icon stays in place while the Delete queues? confirmation opens and closes.
- Icons: one horizontal ellipsis for every More button, one Save icon, standardized arrow and chevron identifiers, and uniform line spacing and stroke weight for the list icons. Icon packs that still map the old identifiers import unchanged.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/684 - Last.fm matching: automatic multi-clue matching, a small review list and the retained archive lifecycle (Part A; the review UI and background sync follow).
- https://github.com/anastaci1a/localica/issues/685 - Online features settings page with Last.fm and Artist photos sub-pages; artist photos displayed.
- https://github.com/anastaci1a/localica/issues/690 - Search and browse return-to-top hides at the measured list top, retires after a tap or manual return, and keeps the floating gap below the popup control.
- https://github.com/anastaci1a/localica/issues/692 - Queues popup: keep the Remove all other queues trash icon in place through the confirmation.
- https://github.com/anastaci1a/localica/issues/697 - Icon registry: standardized arrows, chevrons and ellipsis identifiers with legacy pack aliases.

## Known issues

- Diagnostics added, not fixed yet: ArtworkEditRestorationTest timeouts (https://github.com/anastaci1a/localica/issues/530).
- The whole-list freeze after a fling and pinch in subviews may still occur in rare cases. If it happens, use Export diagnostics (https://github.com/anastaci1a/localica/issues/535).

## Install

1. Download `localica-v0.33.14-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Builds before v0.30.5 cannot open the schema-7 library database, and original schema-6 builds cannot reopen schema-7 queues.

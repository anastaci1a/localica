# Icon packs

Import a pack in **Settings → Interface → Icon packs**. Each pack stays in internal storage after import; its original ZIP is no longer needed. Tap a pack to apply it throughout the app immediately. The built-in set is always available.

The list shows each pack's name, version, author, replacement count and preview icons. **View icons** compares every registered id with its built-in image, grouped by area. Missing images are labeled **Built-in (fallback)**. Unknown ids and rejected images appear separately.

Importing the same name and version offers to update the existing entry. Cancel leaves it unchanged. Deleting a pack requires confirmation; deleting the active pack restores built-in icons. Selecting built-in icons keeps the library. An existing single pack migrates into the library and remains active.

**Export built-in icons as a template** writes every built-in icon, ready to edit.

## Storage

Imported packs live under `filesDir/icon-packs/<id>/`, with their manifest, accepted images, import date and validation report. Updates stage a complete directory before replacing an entry. The previous directory remains recoverable until replacement succeeds. Decoded images are retained in memory; switching never reopens source documents or decodes stored images.

Cached masks use at most 192 × 192 pixels per icon. Accepted source images retain their original resolution in storage.

## Format

A pack is a `.zip` with:

- `pack.json`: `{ "name": "My pack", "version": "1", "author": "Optional" }`. `name` and `version` are required.
- `icons/<id>.png` or `icons/<id>.webp` for each icon you want to replace.

Rules:

- A pack may contain any subset of the ids below. Missing ids silently keep the built-in icon; that is never an error.
- Images are square, at least 96 px and at most 1024 px, at most 2 MB each.
- Images are alpha masks: only transparency matters. Draw in any single color on a transparent background.
- The app tints them with its theme colors, so packs follow light and dark appearance, the accent and the page ink.
- Unknown ids are ignored and listed in the import report.
- Images that are not square, too small or large, or not decodable are rejected individually and listed; the rest of the pack still applies.
- A pack without a valid `pack.json`, or with no usable icon, is refused and the current icons stay.
- Notification small icons are system-rendered and cannot be replaced.
- Fit the glyph inside the image with a small margin; the built-in icons use about 10 percent.

## Icon ids

| Id | Used for |
| --- | --- |
| `tab.home` | Home tab |
| `tab.library` | Library tab |
| `tab.songs` | Songs tab and missing-artwork placeholder |
| `tab.albums` | Albums tab |
| `tab.artists` | Artists and Album Artists tabs |
| `tab.folders` | Folders tab |
| `tab.playlists` | Playlists tab |
| `tab.queues` | Queues tab and queue actions |
| `transport.play` | Play |
| `transport.pause` | Pause |
| `transport.previous` | Previous |
| `transport.next` | Next |
| `menu.pin` | Pin to Home |
| `menu.trash` | Remove, Delete |
| `menu.playlist_add` | Add to playlist (song menu) |
| `menu.play_next` | Play next |
| `menu.queue_add` | Add to queue |
| `menu.info` | Information |
| `menu.download` | Download (reserved) |
| `menu.share` | Share (reserved) |
| `menu.station` | Station (reserved) |
| `menu.favorite` | Favorite (reserved) |
| `menu.suggest_less` | Suggest less (reserved) |
| `action.edit` | Edit |
| `action.close` | Close and clear |
| `action.back` | Back |
| `action.forward` | Forward (reserved) |
| `action.down` | Collapse and select |
| `action.arrow_down` | Return to position |
| `action.search` | Search |
| `action.more` | More |
| `action.ellipsis` | Item actions |
| `action.plus` | Add |
| `action.sort` | Sort |
| `action.eye` | Show |
| `action.eye_off` | Hide |
| `action.drag` | Reorder handle |
| `action.revert` | Revert order |
| `action.reverse` | Reverse order |
| `action.save` | Save |
| `action.save_artwork` | Save artwork to Downloads (artwork viewer) |
| `action.settings` | Settings |
| `action.shuffle` | Shuffle |
| `action.repeat` | Repeat |
| `action.seek_back` | Back 10 seconds |
| `action.seek_forward` | Forward 10 seconds |
| `action.lyrics` | Lyrics |
| `action.info` | Information (player and dialogs) |
| `action.trash` | Delete (other places) |
| `action.playlist_add` | Add to playlist (player) |

The `transport.*` ids are the rest-state glyphs; Play and Pause also provide the end states of the play/pause animation. Ids and their built-in files are tracked in `ui/AppIcons.kt`; every id must appear in this table.

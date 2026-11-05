# Icon packs

An icon pack replaces any of the app's icons. Import one in **Settings → Interface → Appearance → Icon pack**; the change applies immediately. **Reset to built-in icons** removes it. **Export built-in icons as a template** writes a pack containing every icon, ready to edit.

## Format

A pack is a `.zip` with:

- `pack.json`: `{ "name": "My pack", "version": "1", "author": "Optional" }`. `name` and `version` are required.
- `icons/<id>.png` or `icons/<id>.webp` for each icon you want to replace.

Rules:

- A pack may contain any subset of the ids below. Missing ids silently keep the built-in icon; that is never an error.
- Images are square, at least 96 px and at most 1024 px, at most 2 MB each.
- Images are alpha masks: only transparency matters. Draw in any single color on a transparent background.
- The app tints them with its theme colors, so packs follow light and dark appearance, the accent and the page ink.
- Unknown ids are ignored and listed in the import summary.
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

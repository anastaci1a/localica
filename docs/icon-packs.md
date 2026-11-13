# Icon packs

Import a pack in **Settings → Interface → Icon packs**. Each pack stays in internal storage after import; its original ZIP is no longer needed. Tap a pack to apply it throughout the app immediately. The built-in set is always available.

The list shows each pack's name, version, author, replacement count and preview icons. **View icons** compares every registered id with its built-in image, grouped by area. Missing images are labeled **Built-in (fallback)**. Unknown ids and rejected images appear separately.

Importing the same name and version offers to update the existing entry. Cancel leaves it unchanged. Deleting a pack requires confirmation; deleting the active pack restores built-in icons. Selecting built-in icons keeps the library. An existing single pack migrates into the library and remains active.

**Export built-in icons as a template** writes every built-in icon as SVG paths, ready to edit.

## Storage

Imported packs live under `filesDir/icon-packs/<id>/`, with their manifest, accepted icons, import date and validation report. Updates stage a complete directory before replacing an entry. The previous directory remains recoverable until replacement succeeds. Parsed vectors and decoded images stay in memory; switching never reopens source documents or parses stored icons.

Vectors retain their paths at every density. Legacy raster masks use at most 192 × 192 pixels in memory; accepted source images retain their resolution in storage.

## Format

A pack is a `.zip` with:

- `pack.json`: `{ "name": "My pack", "version": "1", "author": "Optional" }`. `name` and `version` are required.
- `icons/<id>.svg` for each icon you want to replace. Vectors are the standard format.
- Legacy `icons/<id>.png` and `icons/<id>.webp` images remain supported. For the same id, an accepted SVG wins over either raster format, regardless of ZIP order.

Rules:

- A pack may contain any subset of the ids below. Missing ids silently keep the built-in icon; that is never an error.
- Raster images are square, 96–1024 px per side, at most 2 MB each.
- Icons are tintable masks: only transparency matters. Draw in any single color on a transparent background.
- The app tints them with its theme colors, so packs follow light and dark appearance, the accent and the page ink.
- Unknown ids are ignored and listed in the import report.
- Raster images that are not square, too small or large, or not decodable are rejected individually and listed; the rest of the pack still applies.
- A pack without a valid `pack.json`, or with no usable icon, is refused and the current icons stay.
- Notification small icons are system-rendered and cannot be replaced.
- Fit the glyph inside the image with a small margin; the built-in icons use about 10 percent.
- Centre the glyph by its bounds; the app applies optical centring.

## Optical centring

Every glyph, built-in or imported, moves vertically half-way from its bounding-box centre toward its ink-weighted centre. Bottom-heavy glyphs such as Save rise, top-heavy glyphs drop, and symmetric glyphs keep their exact position. Moves below 0.05 dp are ignored and never push ink past the icon edge. Raster masks move by whole pixels.

Imported vectors are measured from their paths before publication and stay paths. Built-in offsets come from a generated table; JVM checks bind every entry to the resources and optical rule. Drawing never measures glyphs. Template exports contain uncorrected geometry, so a re-imported template matches the built-in icons.

To regenerate the table, set `LOCALICA_ICON_OPTICAL_REPORT` to an output directory and run `OpticalCentringPacksTest.builtinPackFollowsTheRuleAndSymmetricGlyphsStay`. Copy its `BuiltinIconOffsets.kt` into the icons package, then run the complete class to verify the table. Qualified built-in resource variants require an explicit table-selection policy.

## SVG subset

Use UTF-8 XML with an `svg` root and a numeric `viewBox`, usually `0 0 24 24`. Optional numeric `width` and `height` accept pixels. Paths become the same Compose vector trees used by built-in icons; import never rasterizes them.

The viewBox fits the square icon canvas uniformly and stays centered. Non-square viewBoxes keep their proportions and receive padding on the shorter axis.

Supported elements and attributes:

- `path`: `d`, using SVG moveto, line, horizontal/vertical line, cubic/quadratic curve, smooth curve, elliptical arc and close commands.
- `g`: nested groups with ordered `translate`, `scale` and `rotate` transforms. Rotation may specify a pivot.
- Inherited paint: `fill`, `stroke`, `stroke-width`, `stroke-linecap`, `stroke-linejoin`, `stroke-miterlimit`, `fill-rule`, `fill-opacity` and `stroke-opacity`.
- Paint values: `none`, `currentColor`, `black`, `white`, three-digit or six-digit hexadecimal colors. Theme tint replaces RGB; opacity remains.
- Caps: `butt`, `round`, `square`; joins: `miter`, `round`, `bevel`; fill rules: `nonzero`, `evenodd`.

Other elements, attributes and transforms are rejected with a per-icon reason. Scripts, CSS, text, external references, entities, document types, raster images, gradients, clipping and processing instructions are unsupported. A malformed SVG can fall back to a valid raster entry for the same id.

Limits keep import and drawing bounded: 256 KB per SVG, 256 paths, 1,024 elements, nesting depth 16, and 16,384 total path tokens. Coordinates are finite and bounded; viewBox dimensions must be at least 0.01. Combined transforms are bounded before rendering, with at most 32 nested transform operations.

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
| `menu.list_create` | Create a list |
| `menu.list_add_next` | Insert next in a list |
| `menu.list_add_last` | Append to a list |
| `menu.info` | Information |
| `menu.download` | Download and downloading state (tray with a down arrow) |
| `menu.share` | Share (reserved) |
| `menu.station` | Station (reserved) |
| `menu.favorite` | Favorite (reserved) |
| `menu.suggest_less` | Suggest less (reserved) |
| `action.edit` | Edit |
| `action.close` | Close and clear |
| `action.check` | Confirm a selection |
| `action.refresh` | Sync now and quick refresh |
| `action.rescan` | Full rescan |
| `action.chevron_left` | Back |
| `action.chevron_right` | Right chevron |
| `action.chevron_up` | Up chevron |
| `action.arrow_right` | Forward |
| `action.arrow_left` | Left arrow |
| `action.arrow_up` | Up arrow |
| `action.chevron_down` | Collapse and select |
| `action.arrow_down` | Return to position |
| `action.search` | Search |
| `action.ellipsis` | More and item actions (horizontal ellipsis) |
| `action.plus` | Add |
| `action.sort` | Sort |
| `action.eye` | Show |
| `action.eye_off` | Hide |
| `action.drag` | Reorder handle |
| `action.revert` | Revert order |
| `action.reverse` | Reverse order |
| `action.save` | Save, including artwork to Downloads |
| `action.settings` | Settings |
| `action.shuffle` | Shuffle |
| `action.repeat` | Repeat |
| `action.repeat_one` | Repeat one; missing replacements use the built-in dedicated glyph |
| `action.seek_back` | Back 10 seconds |
| `action.seek_forward` | Forward 10 seconds |
| `action.lyrics` | Lyrics |
| `action.info` | Information (player and dialogs) |
| `action.trash` | Delete (other places) |
| `action.list_add_last` | Append to a playlist |
| `settings.interface` | Settings: Interface |
| `settings.appearance` | Settings: Appearance |
| `settings.icon_packs` | Settings: Icon packs |
| `settings.glass` | Settings: Glass |
| `settings.navigation` | Settings: Navigation |
| `settings.player` | Settings: Player |
| `settings.motion` | Settings: Motion |
| `settings.library_filters` | Settings: Library filters |
| `settings.metadata_failures` | Settings: Metadata read failures |
| `settings.tag_jobs` | Settings: Tag jobs and recovery |

The `transport.*` ids are the rest-state glyphs; Play and Pause also provide the end states of the play/pause animation. Ids and their built-in files are tracked in `ui/AppIcons.kt`; every id must appear in this table.

Imported transport contours share the existing timing, interruption handling, tint and availability fades. Arbitrary play/pause contours crossfade; skip icons travel through clipped cycles. Missing endpoints use their built-in icons.

Repeat One uses its own vector, never a text overlay on a pack's Repeat icon. Missing `action.repeat_one` entries use the dedicated built-in glyph. Repeat state changes crossfade with the shared control motion.

## Legacy ids

Older packs and installed files may use these aliases. Settings and template exports show only the canonical ids. A valid canonical mapping wins over its alias, across image formats and ZIP order. An invalid canonical image may fall back to a valid alias. Existing stored files are read without rewriting them.

| Legacy id | Canonical id |
| --- | --- |
| `action.back` | `action.chevron_left` |
| `action.forward` | `action.arrow_right` |
| `action.down` | `action.chevron_down` |
| `action.save_artwork` | `action.save` |
| `action.more` | `action.ellipsis` |
| `menu.playlist_add` | `menu.list_create` |
| `menu.play_next` | `menu.list_add_next` |
| `menu.queue_add` | `menu.list_add_last` |
| `action.playlist_add` | `action.list_add_last` |

## Icon style

Built-in icons use 24 dp vectors with a 1.8 dp stroke, rounded caps and rounded joins. Keep imported artwork optically consistent. Download is a tray with a downward arrow.

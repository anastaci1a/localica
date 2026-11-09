# Icon packs

Import a pack in **Settings → Interface → Icon packs**. Each pack stays in internal storage after import; its original ZIP is no longer needed. Tap a pack to apply it throughout the app immediately. The built-in set is always available.

The list shows each pack's name, version, author, replacement count and preview icons. **View icons** lists each unique image with its mapped ids, grouped by area. Missing images are labeled **Built-in (fallback)**. Unknown ids and rejected images appear separately.

Importing the same name and version offers to update the existing entry. Cancel leaves it unchanged. Deleting a pack requires confirmation; deleting the active pack restores built-in icons. Selecting built-in icons keeps the library. An existing single pack migrates into the library and remains active.

**Export built-in icons as a template** writes unique built-in SVG paths and a v2 manifest, ready to edit.

## Storage

Imported packs live under `filesDir/icon-packs/<id>/`, with their manifest, accepted icons, import date and validation report. Updates stage a complete directory before replacing an entry. The previous directory remains recoverable until replacement succeeds. Parsed vectors and decoded images stay in memory; switching never reopens source documents or parses stored icons.

Vectors retain their paths at every density. Legacy raster masks use at most 192 × 192 pixels in memory; accepted source images retain their resolution in storage.

## Format

The [interface contract](interface.md#icon-pack-format) defines the versioned manifest, ID groups, shared image mappings and migration policy.

Rules:

- A pack may contain any subset of the ids below. Missing ids silently keep the built-in icon; that is never an error.
- Raster images are square, 96–1024 px per side, at most 2 MB each.
- Icons are tintable masks: only transparency matters. Draw in any single color on a transparent background.
- The app tints them with its theme colors, so packs follow light and dark appearance, the accent and the page ink.
- Unknown ids are ignored and reported with the closest current id suggested.
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
| `nav.home` | Home tab |
| `nav.library` | Library tab |
| `nav.songs` | Songs tab and missing-artwork placeholder |
| `nav.albums` | Albums tab |
| `nav.artists` | Artists and Album Artists tabs |
| `nav.folders` | Folders tab |
| `nav.playlists` | Playlists tab |
| `nav.queues` | Queues tab and queue actions |
| `transport.play` | Play |
| `transport.pause` | Pause |
| `transport.previous` | Previous |
| `transport.next` | Next |
| `action.pin` | Pin to Home |
| `action.remove` | Remove, Delete |
| `action.create_list` | Create a list |
| `action.add_next` | Insert next in a list |
| `action.add_to_queue` | Append to a list |
| `action.show_info` | Information |
| `action.download` | Download and downloading state (tray with a down arrow) |
| `action.share` | Share (reserved) |
| `action.start_station` | Station (reserved) |
| `action.favorite` | Favorite (reserved) |
| `action.suggest_less` | Suggest less (reserved) |
| `action.edit` | Edit |
| `action.close` | Close and clear |
| `selection.confirm` | Confirm a selection |
| `action.refresh` | Sync now and quick refresh |
| `action.rescan` | Full rescan |
| `nav.chevron_left` | Back |
| `nav.chevron_right` | Right chevron |
| `nav.chevron_up` | Up chevron |
| `nav.arrow_right` | Forward |
| `nav.arrow_left` | Left arrow |
| `nav.arrow_up` | Up arrow |
| `nav.chevron_down` | Collapse and select |
| `nav.arrow_down` | Return to position |
| `action.search` | Search |
| `action.more` | More and item actions (horizontal ellipsis) |
| `action.add` | Add |
| `sort.choose` | Sort |
| `view.show` | Show |
| `view.hide` | Hide |
| `action.reorder` | Reorder handle |
| `sort.revert` | Revert order |
| `sort.reverse` | Reverse order |
| `action.save` | Save, including artwork to Downloads |
| `nav.settings` | Settings |
| `transport.shuffle` | Shuffle |
| `transport.repeat` | Repeat |
| `transport.repeat_one` | Repeat one; missing replacements use the built-in dedicated glyph |
| `transport.seek_back` | Back 10 seconds |
| `transport.seek_forward` | Forward 10 seconds |
| `view.lyrics` | Lyrics |
| `view.info` | Information (player and dialogs) |
| `action.delete` | Delete (other places) |
| `action.add_to_playlist` | Append to a playlist |
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

Repeat One uses its own vector, never a text overlay on a pack's Repeat icon. Missing `transport.repeat_one` entries use the dedicated built-in glyph. Repeat state changes crossfade with the shared control motion.

## Legacy ids

Aliases keep old packs usable under the [manifest resolution policy](interface.md#icon-pack-format). Existing installed files are read without rewriting them.

| Legacy id | Canonical id |
| --- | --- |
| `tab.home` | `nav.home` |
| `tab.library` | `nav.library` |
| `tab.songs` | `nav.songs` |
| `tab.albums` | `nav.albums` |
| `tab.artists` | `nav.artists` |
| `tab.folders` | `nav.folders` |
| `tab.playlists` | `nav.playlists` |
| `tab.queues` | `nav.queues` |
| `menu.pin` | `action.pin` |
| `menu.trash` | `action.remove` |
| `menu.list_create` | `action.create_list` |
| `menu.list_add_next` | `action.add_next` |
| `menu.list_add_last` | `action.add_to_queue` |
| `menu.info` | `action.show_info` |
| `menu.download` | `action.download` |
| `menu.share` | `action.share` |
| `menu.station` | `action.start_station` |
| `menu.favorite` | `action.favorite` |
| `menu.suggest_less` | `action.suggest_less` |
| `action.check` | `selection.confirm` |
| `action.chevron_up` | `nav.chevron_up` |
| `action.chevron_down` | `nav.chevron_down` |
| `action.chevron_left` | `nav.chevron_left` |
| `action.chevron_right` | `nav.chevron_right` |
| `action.arrow_up` | `nav.arrow_up` |
| `action.arrow_down` | `nav.arrow_down` |
| `action.arrow_left` | `nav.arrow_left` |
| `action.arrow_right` | `nav.arrow_right` |
| `action.ellipsis` | `action.more` |
| `action.plus` | `action.add` |
| `action.sort` | `sort.choose` |
| `action.eye` | `view.show` |
| `action.eye_off` | `view.hide` |
| `action.drag` | `action.reorder` |
| `action.revert` | `sort.revert` |
| `action.reverse` | `sort.reverse` |
| `action.settings` | `nav.settings` |
| `action.shuffle` | `transport.shuffle` |
| `action.repeat` | `transport.repeat` |
| `action.repeat_one` | `transport.repeat_one` |
| `action.seek_back` | `transport.seek_back` |
| `action.seek_forward` | `transport.seek_forward` |
| `action.lyrics` | `view.lyrics` |
| `action.info` | `view.info` |
| `action.trash` | `action.delete` |
| `action.list_add_last` | `action.add_to_playlist` |
| `action.back` | `nav.chevron_left` |
| `action.forward` | `nav.arrow_right` |
| `action.down` | `nav.chevron_down` |
| `action.save_artwork` | `action.save` |
| `menu.playlist_add` | `action.create_list` |
| `menu.play_next` | `action.add_next` |
| `menu.queue_add` | `action.add_to_queue` |
| `action.playlist_add` | `action.add_to_playlist` |

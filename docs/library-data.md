# Library data files

Localica exports and imports its own library data as one versioned JSON file. Tools outside the app can read an export, change supported fields and give the file back to Localica. Version 1.x imports only the date each song was added.

**Settings → Library and tags → Library data** owns **Export library data** and **Import library data**. Both use the system file picker. Status: implemented in [#720](https://github.com/anastaci1a/localica/issues/720).

## File

Export writes plain UTF-8 JSON. Import also accepts the same JSON compressed with gzip, detected from the file content rather than its name.

```json
{
  "format": "localica-library-data",
  "version": "1.2",
  "exportedAt": 1762650000000,
  "app": "Localica 0.33.16",
  "tracks": [
    {
      "id": "content://com.android.externalstorage.documents/document/primary%3AMusic%2Fa.flac",
      "uri": "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fa.flac",
      "locations": [{ "root": "content://…/tree/primary%3AMusic", "rootName": "Music", "path": "Artist/Album/a.flac", "uri": "content://…" }],
      "sizeBytes": 31457280, "durationMs": 245300,
      "title": "Song", "artist": "Artist", "album": "Album", "albumArtist": "Artist",
      "trackNumber": 1, "discNumber": 1,
      "addedAt": 1700000000000, "firstSeenAt": 1762000000000,
      "modifiedAt": 1762500000000, "firstSeenModifiedAt": 1761900000000
    }
  ]
}
```

| Field | Type and unit | Meaning |
| --- | --- | --- |
| `format` | String | Always `localica-library-data`. Other values are refused. |
| `version` | String `major.minor` | Format version. This release writes `1.2` (adds modification evidence); `1.0` and `1.1` files still import. |
| `exportedAt` | Integer, epoch milliseconds (UTC) | When the export was written. Informational. |
| `app` | String | Exporting app version. Informational. |
| `tracks` | Array of objects | One record per indexed song. Entries that are not objects are skipped and counted. |

## Song records

Export writes every field below for every song, using `null` when Localica has no value. Import treats every field as optional. Only `addedAt` is applied.

| Field | Type and unit | Role |
| --- | --- | --- |
| `id` | String | Localica song identity: the document URI independent of the granted folder. First matching key. |
| `uri` | String | Document URI Localica plays from. Location key. |
| `locations` | Array | Every indexed place of the song. Location key. |
| `locations[].root` | String | Music folder tree URI. |
| `locations[].rootName` | String | Music folder display name. |
| `locations[].path` | String | Path inside that folder, separated by `/`. |
| `locations[].uri` | String | Document URI of that place. |
| `sizeBytes` | Integer, bytes | File size reported by the provider at export. Identity only; never matched. |
| `durationMs` | Integer, milliseconds | Indexed duration. Metadata key. |
| `title`, `artist`, `album` | String | Indexed tags. Metadata keys. |
| `albumArtist`, `trackNumber`, `discNumber` | String, integer, integer | Indexed tags. Identity only; never matched. |
| `addedAt` | Integer, epoch milliseconds (UTC) | Date added. Imported. Must be a positive whole number. |
| `firstSeenAt` | Integer, epoch milliseconds (UTC) | When Localica first indexed the song. Since 1.1. Exported only; import never changes it. |
| `modifiedAt` | Integer, epoch milliseconds (UTC) | Latest indexed file modification time. Since 1.2. Exported only; import ignores it. |
| `firstSeenModifiedAt` | Integer, epoch milliseconds (UTC) | File modification time observed at first insert, including unknown. Since 1.2. Immutable; import ignores it. |

For tracks indexed before database version 11, `firstSeenModifiedAt` copies the stored `modifiedAt` during upgrade: **captured late**, not the original first-sight observation. Exports do not distinguish these rows; recovery tools must allow for that uncertainty. Neither modification field is a creation date or date added.

## Included fields

| Data | Decision | Reason |
| --- | --- | --- |
| Date added | Exported and imported. | Localica owns it; audio files do not store it. Sort by date added uses it. |
| First seen | Exported, never imported. | A historical fact: once overwritten it cannot be rebuilt. Date added starts equal to it, so an import that replaces date added can be undone by importing first seen as date added. |
| File modification evidence | Exported, never imported. | Keeps the latest indexed timestamp and a fixed observation for possible recovery. It never changes date added automatically. |
| Identity keys | Exported, used only for matching. | Tags and locations come from files and scans; import never rewrites them. |
| Play counts and history | Not included in 1.0. | Counts derive from individual qualified plays in a separate database. A number cannot recreate those plays, and one transaction cannot cover both databases. |
| Playlists | Not included in 1.0. | Playlists have their own M3U import and export. Entries keep saved song snapshots rather than one value per song. |

A later minor version may add fields. Readers ignore fields they do not know.

## Matching

Import matches each record to at most one indexed song. The first stage that finds any candidate decides the result.

| Order | Stage | Rule |
| --- | --- | --- |
| 1 | ID | `id` equals a song's Localica id. |
| 2 | Location | `uri` or any `locations[].uri` equals a song's document URI, or a `locations[].path` equals an indexed path. A present `root` or `rootName` must also equal that location's folder. |
| 3 | Metadata | `title`, `artist` and `album` are equal, and both durations are within 1,000 ms. `title` and `durationMs` are required. |

Metadata text is compared exactly after trimming and Unicode NFC normalization. Case matters, and a blank value equals a missing one.

A stage with several candidates makes the record ambiguous. Several records that match the same song are all ambiguous. Ambiguous and unmatched records are skipped and counted, never guessed.

## Applying an import

1. Localica reads the file and plans the import against the current library.
2. A confirmation lists records, matches per stage, unmatched and ambiguous counts. Each field shows matched, changed, unchanged, unmatched, ambiguous and invalid counts.
3. **Apply** plans again under the scan lock and writes every change in one database transaction.
4. The result shows the applied counts.

Nothing changes when the file is refused or the confirmation is cancelled. Later Quick Scans, Full scans and tag edits keep imported dates. Removing a song or its folder and indexing it again keeps both dates, as described in [Library](architecture/library.md#dates).

## Versions

| File version | Result |
| --- | --- |
| `1.x` | Read. Unknown fields are ignored. |
| `2.0` or newer | Refused before any change, with a message to update Localica. |
| Missing, unreadable or below `1.0` | Refused as not a supported library data file. |

A major version changes only when an existing field changes meaning or a reader must not ignore a new field. New optional fields raise the minor version.

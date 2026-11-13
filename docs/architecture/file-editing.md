# File editing

Localica changes audio files only through explicit, verified, recoverable writes. Every write edits a private copy, verifies it, records how to undo it, and only then replaces the original. One lock serializes scans and writes. A song whose bytes are uncertain is held out of playback until it is recovered. Nothing is ever substituted silently.

- Access uses Storage Access Framework tree grants. There is no broad storage permission.
- A batch keeps full original files until Done or a verified cancellation.
- An interrupted batch needs an explicit Resume or Restore choice.
- Unsupported formats, fields and providers stay playable and read-only, with an explanation.
- Status: Implemented. Records survive process interruption; arbitrary power-loss durability is not claimed.

## What can be edited

Title, Album, Artist, Album Artist, Year, Disc number, Track number, Comment, Genre, Composer, Lyricist and embedded artwork. File modification dates are read, never edited.

- Individual and bulk edits change only checked fields.
- A blank checked value removes the user value, and keeps track and disc totals and technical comments.
- Unchecked and mixed values are preserved. Physical songs are deduplicated for writes.
- App-only tags are never substituted for a failed file write.

Verified writers cover MP3, M4A, FLAC, Ogg Vorbis and WAV. RF64 supplementary tags are unsupported.

## The lock

Scans and tag writes share one file-operation lock, with separate cancellation and recovery lifecycles.

| Operation | Ownership and recovery |
| --- | --- |
| Scan | A `dataSync` foreground service with a bounded wake lock. Survives Activity recreation, honours service timeouts and reports a process interruption at next launch. |
| Tag batch | One controller worker and a `dataSync` foreground service. Explicit background continuation keeps ownership. Eight recent job outcomes are kept. |
| Tag transaction | The single-file write described below. |

Notification permission is optional for both services.

## One file: the transaction

1. Edit a private copy with Jaudiotagger.
2. Compare encoded audio and unrelated tags; verify the requested values and artwork.
3. Sync an original and record the recovery journal.
4. Overwrite the file.
5. On failure, attempt a verified rollback. Failed-file recovery stays separate from the batch journal.

## Many files: the durable batch

Batches live in app-files `tag-batches`, beside single-file `tag-recovery`.

| Part | Rule |
| --- | --- |
| Contents | Immutable ordered targets, the patch, copied artwork and the editor draft come before per-file records. |
| Header | A small atomic header owns phase, background intent and the admitted-entry watermark. A missing admitted entry fails closed. |
| Originals | Captured and hashed in full before first replacement. Each write is bound to that hash. |
| Identity | Canonical document identities coalesce tree-grant aliases. |
| Commit | The verified batch outcome commits before single-file recovery is discarded. |

### Space

Preflight reserves all undo originals, two largest-file working copies, bounded tag and artwork growth, and at least 64 MiB of headroom. Headroom is rechecked before each file. With too little space the user chooses Save without undo, or Back. A no-undo save keeps current-file recovery and offers Stop after current.

### Cancel, Done and interruption

- Cancel closes next-file admission at once, persists its intent, then restores every attempted entry and verifies complete file hashes.
- A failed restoration keeps recovery and never reports cancellation complete.
- External changes, missing originals and uncertain outcomes keep recovery. They never authorize replacement or cleanup.
- Done and Cancel arbitrate through durable terminal decisions. Headers are removed last.
- An interrupted batch offers Resume or Restore. A recorded cancellation is restore-only.
- If a cancellation cannot be recorded, further writes are refused in that session.

## Playback during edits

A file must be closed by every reader before it is replaced.

- An app-lifetime registry registers every native source before the provider opens it.
- Before a write, the registry blocks new admissions and awaits the actual close. Timeout or cancellation never starts the writer.
- A failed close stays unsafe for edits until the process restarts.
- The reader gate uses the library identity across alternate grants. Arbitrary cross-provider aliases are not resolved.
- Only affected playback is held. Unrelated songs keep playing, and newer intent is honoured afterwards.

### Quarantine

A song awaiting byte recovery is unavailable to playback, startup and preload included. Its occurrences and history are kept. Playback stops at its boundary without skipping or wrapping.

The registry starts fail-closed before the service prepares. A valid pending identity blocks that source. Unreadable or mismatched recovery blocks all sources until it is identified. Manual recovery loads the restored song paused, unless a newer Play arrives during recovery.

## Metadata refresh

A bulk write journals the pending metadata refresh before touching bytes. Each file is verified independently. Library metadata then publishes in one database transaction, and queue snapshots, playlist snapshots and artwork refresh once.

The refresh journal stores one atomic record per track. Retained records commit before cleanup, so an interruption can replay a published refresh but cannot drop a pending one. Corruption fails closed.

Finish tag editing, recovery and pending refreshes before downgrading: older builds cannot read these records.

## Readers

Platform metadata is supplemented through granted seekable descriptors. Bounded ID3 reads, tag-only FLAC and Ogg views, and sparse MP4 and WAV views avoid copying audio. Supplementary metadata is capped at 16 MiB. Temporary views are deleted after use.

## Artwork sources and grants

Artwork can be replaced or removed. Every newly chosen image passes through a required square crop, exported at up to 2048 pixels.

- One shared owner holds artwork read grants: recent choices and active crop leases.
- Only obsolete grants acquired by that owner are released. Pre-existing and tree grants are never adopted or pruned.
- A grant left unrecorded by an interruption stays unknown, never assumed owned.
- At the app's conservative grant budget, transient picker access is used. That source may need choosing again after restart.
- Crops are private files. Committed artwork is embedded bytes, independent of the source grant.

The editing flow the user sees is in [Actions and editing](../interface/actions-and-editing.md#tag-editor).

## Tests

| Topic | Classes |
| --- | --- |
| Transactions and batches | `TagWritingTest`, `TagBatchControllerTest`, `TagBatchTransactionTest` |
| Recovery | `TagRecoveryLaunchTest`, `QuarantinedSourceReopenTest`, `MetadataRefreshJournalTest` |
| Readers and playback | `NativeReaderAdmissionTest`, `QueueFileEditTest` |
| Artwork | `ArtworkGrantsTest`, `ArtworkCropTest`, `ArtworkEditRestorationTest`, `ImageChooserTest` |

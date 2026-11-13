# File editing

Localica changes audio files only through explicit, verified, recoverable writes. Every write edits a private copy, verifies it, records how to undo it, and only then replaces the original. One lock serializes scans and writes. A song whose bytes are uncertain is held out of playback until it is recovered. Nothing is ever substituted silently.

- Access uses Storage Access Framework tree grants. There is no broad storage permission.
- A batch keeps full original files until Done or a verified cancellation.
- An interrupted batch needs an explicit Resume or Restore choice.
- Unsupported formats, fields and providers stay playable and read-only, with an explanation.
- Status: Implemented. Records survive process interruption; arbitrary power-loss durability is not claimed.


## What can be edited

Checked metadata fields and embedded artwork are edited; unchecked/mixed values survive. Blank checks remove user values while preserving totals/technical comments. Physical files are deduplicated. Verified writers cover MP3, M4A, FLAC, Ogg Vorbis and WAV; RF64 supplementary tags remain unsupported. File dates are read-only.

## The lock

Scans and batches share one file-operation lock with independent cancellation/recovery. Foreground dataSync services retain ownership through recreation/background continuation and honour timeouts. Notification permission is optional. Batch controllers keep eight recent outcomes.

## One file: the transaction

Edit a private copy, verify requested values plus unchanged audio/unrelated tags, sync an original and journal recovery, then replace. Failure attempts verified rollback. Single-file recovery remains separate from the batch record. TagWritingTest owns format/field verification cases.

## Many files: the durable batch

Immutable ordered targets, patch, artwork and draft precede per-file records. An atomic header owns phase, background intent and admission watermark. Originals are fully hashed before replacement; writes bind to that hash. Canonical identities coalesce tree aliases. Verified outcomes commit before single-file cleanup; headers disappear last.

### Space

Reserve originals, two largest-file working copies, bounded growth and 64 MiB headroom, rechecked per file. Insufficient space offers Back or Save without undo, which retains current-file recovery and Stop after current.

### Cancel, Done and interruption

Persist cancellation before closing admission and restoring attempted files with complete hash checks. Failed/uncertain recovery, external changes and missing originals retain evidence and prohibit cleanup. Done/Cancel use durable terminal decisions. Interrupted jobs offer Resume/Restore; cancelled jobs are restore-only. Failed cancellation recording refuses further writes. Batch tests specify arbitration and interruption points.

## Playback during edits

An app-lifetime registry admits sources before provider opens. Writes block new readers and await actual closure; timeout/cancellation cannot authorize replacement. Failed close stays unsafe until restart. Library identity covers alternate grants, not arbitrary provider aliases. Only affected playback is held; newer intent remains authoritative.

### Quarantine

Pending recovery blocks playback/preload without deleting occurrences/history or skipping its boundary. Registry starts fail-closed before preparation. Unreadable/mismatched records block all sources until identified. Manual recovery restores paused unless a newer Play arrives.

## Metadata refresh

Journal refresh before writing bytes. Verify files individually, then publish metadata in one database transaction and refresh saved snapshots/artwork once. Atomic per-track records commit before cleanup; replay may repeat publication but cannot lose pending work. Corruption fails closed. Finish all recovery/refresh before downgrading to builds that cannot read these records.

## Readers

Granted descriptors provide bounded tag-only/sparse views without copying audio. Supplementary metadata caps at 16 MiB; temporary views are removed. Reader tests specify admission and lifetime safety.

## Artwork sources and grants

Required square crops export up to 2048 px. One owner tracks recent-choice grants and crop leases, releasing only its obsolete grants. Unknown/pre-existing/tree grants are never adopted or pruned. Budget exhaustion uses transient access, possibly requiring re-pick after restart. Embedded committed bytes are independent of source grants. [Actions and editing](../interface/actions-and-editing.md#tag-editor) owns the workflow.

## Tests

[TagWritingTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/TagWritingTest.kt).

[TagBatchControllerTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/TagBatchControllerTest.kt).

[TagBatchTransactionTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/TagBatchTransactionTest.kt).

[TagRecoveryLaunchTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/TagRecoveryLaunchTest.kt).

[QuarantinedSourceReopenTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/QuarantinedSourceReopenTest.kt).

[MetadataRefreshJournalTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/MetadataRefreshJournalTest.kt).

[NativeReaderAdmissionTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/NativeReaderAdmissionTest.kt).

[QueueFileEditTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/QueueFileEditTest.kt).

[ArtworkGrantsTest](../../app/src/test/java/io/github/anastaci1a/localica/editing/ArtworkGrantsTest.kt).

[ArtworkCropTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtworkCropTest.kt).

[ArtworkEditRestorationTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtworkEditRestorationTest.kt).

[ImageChooserTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ImageChooserTest.kt).

# Scanner and folders

Music enters the library through folders the user grants and scans the user starts. Adding a folder only grants access. A scan is what indexes songs. Both scan types are available from the More menu and from Settings, and a running scan can continue in the background or be cancelled.

- **Quick Scan** finds new files. **Full scan** also refreshes changed files and removes missing ones.
- Startup scans are two independent switches, both off by default.
- A changed scanning rule applies to indexed songs only after a Full scan.
- Status: Implemented. What each scan guarantees is in [Library](../architecture/library.md#scans).

## Scanning

**More → Scan music** and **Settings → Library and tags → Scanner** share the two glass actions. The popup reserves space for progress, status and actions, so starting or finishing does not resize it. Continue in background and Cancel stay available during a scan.

When Android restricts background work, the popup explains the actual restriction and links to Localica's Android settings page.

## Scanner options

| Option | Effect |
| --- | --- |
| Include subfolders | Applies to every chosen folder. Off scans only that folder. On includes nested folders, except excluded paths. |
| Minimum duration | Shorter files are skipped. |
| Metadata read failures | A page of files with incomplete metadata. Failures are never a persistent queue warning. |
| Quick Scan at startup, Full scan at startup | Off by default. With both on, all quick discovery runs before the full refresh. |

## Folders

**Library and tags → Folders to scan** owns Add folder, removal and, per folder, Excluded subfolders. Folders with the same name are labelled with stable fractions, such as Media (1/2). Unique names stay plain. Removing a folder never deletes audio.

## Library data

The Library and tags page ends with **Export library data** and **Import library data**, which use the system file picker. Import confirms per-field counts in a glass dialog before applying, then shows the applied counts. The file format is in [Library data](../library-data.md).

## Tests

[IncompleteScanTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/IncompleteScanTest.kt).

[FolderPickerTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/FolderPickerTest.kt).

[FolderLabelsTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/FolderLabelsTest.kt).

[LibraryDataExchangeTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/LibraryDataExchangeTest.kt).

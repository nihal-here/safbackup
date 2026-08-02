# SAF Backup

Tiny Android one-way folder backup utility, designed for backing up
[Aegis Authenticator](https://getaegis.app/) encrypted JSON exports to
Google Drive via Android's Storage Access Framework (SAF).

## Security model

- **No** `MANAGE_EXTERNAL_STORAGE`
- **No** `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE`
- **No** `INTERNET` permission
- **No** analytics, telemetry, crash reporting, or network libraries
- Source and destination are chosen through Android's SAF folder picker
- The app only retains access to the directory trees explicitly granted by the user
- Destination-only files are **never** deleted

Architecture:

    Local Aegis backup folder
          ↓
    Android SAF (persistent tree permission, READ only)
          ↓
    SAF Backup (copies new files)
          ↓
    Android SAF (persistent tree permission, READ + WRITE)
          ↓
    Google Drive DocumentsProvider

Google Drive's Android app handles networking.
SAF Backup itself does not connect to any server.

## Backup semantics

This is a **one-way append-only backup**, not synchronization.

1. Enumerate source files (top-level only, no recursion).
2. If the same filename already exists at the destination → **skip**.
3. If it doesn't exist → **copy**, then verify integrity.
4. **Never** delete destination-only files.
5. **Never** overwrite an existing destination file.
6. Deletions are **not** propagated.

### Integrity verification

After copying each file:
- Source SHA-256 is computed before the copy.
- Destination file size is verified after the copy.
- Destination SHA-256 is verified on a best-effort basis.
  (Some providers, e.g. Google Drive, may async-upload and return stale
  data on immediate readback. Size match is accepted in that case.)
- If verification fails, only the file created by **this** run is deleted.
  Pre-existing destination files are never touched.

## Build

Open the project in a current Android Studio and let Gradle sync, or:

    ./gradlew assembleDebug

(Gradle wrapper is intentionally not bundled in this generated source archive.)

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Use

1. Choose **source folder**: the local Aegis automatic-backup folder.
2. Choose **destination folder**: Google Drive → your desired backup folder.
3. Tap **Back up now** and verify the file appears.
4. Enable **daily backup**.
5. Optionally enable **Wi-Fi only** and/or **Only while charging**.

## Observability

The app shows after each backup run:
- Timestamp of the last backup
- Number of files copied, skipped (already existed), and failed
- Error details for any failures

This status persists across app restarts.

## Scheduling

WorkManager periodic work is intentionally inexact. "Daily" means Android
will run it approximately once per day when constraints permit, not at an
exact clock time. Android may delay periodic work because of Doze mode
and battery optimization.

Constraints (Wi-Fi only, charging only) are persisted and applied to
scheduled work. Battery-not-low and storage-not-low are always required.

## Known limitations

- **10-minute execution limit**: Standard WorkManager background jobs
  have an execution time limit. For Aegis JSON backups (typically a few KB),
  this is not a concern. If you point this at a very large directory,
  Android may kill the worker before it finishes. Reruns are idempotent
  (already-copied files are skipped).

- **Google Drive readback**: Google Drive's SAF provider may upload files
  asynchronously. SHA-256 verification of the destination reads from the
  provider's local cache, which should be correct immediately after write,
  but this is not guaranteed by the SAF contract.

- **DocumentFile performance**: `DocumentFile` makes per-file IPC calls.
  For directories with thousands of files this would be slow. Aegis backup
  directories are typically small, so this is not a practical concern.

- **No bidirectional sync**: By design.

- **No file watching**: Backups run on schedule or manually. There is no
  real-time trigger when Aegis creates a new backup file.

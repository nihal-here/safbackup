# SAF Backup

[![Build & Release](https://github.com/nihal-here/safbackup/actions/workflows/build-apk.yml/badge.svg)](https://github.com/nihal-here/safbackup/actions/workflows/build-apk.yml)

Tiny, safe, one-way Android folder backup utility built around Android's
Storage Access Framework.

SAF Backup is designed for local folders that you already control, such as
[Aegis Authenticator](https://getaegis.app/) encrypted exports, documents, or
photo folders. Pick a source folder, pick a destination folder, and SAF Backup
copies new content without deleting or overwriting existing destination data.

## What It Does

- Creates multiple independent backup jobs.
- Copies top-level files or recursively preserves subfolder structure.
- Runs jobs manually, individually, or with **Back Up All Now**.
- Supports optional automatic daily scheduling through WorkManager.
- Can require Wi-Fi and/or charging for scheduled backup jobs.
- Reports success, failures, conflicts, copied files, skipped files, and failed files.
- Keeps last attempt and last successful backup timestamps separate.

## Safety Model

SAF Backup is a **one-way append-only backup tool**, not a sync engine.

- Destination-only files and folders are never deleted.
- Existing destination files are never overwritten.
- Matching files are skipped after size and SHA-256 verification.
- Different files at the same relative path are reported as conflicts and left untouched.
- File-vs-folder path conflicts are reported without deleting anything.
- Partial files created by a failed copy are cleaned up only for that failed run.

## Privacy And Permissions

SAF Backup intentionally avoids broad storage and network access.

- No `MANAGE_EXTERNAL_STORAGE`
- No `READ_EXTERNAL_STORAGE` or `WRITE_EXTERNAL_STORAGE`
- No `INTERNET` permission
- No Google Account, Google Drive API, rclone, cloud SDK, telemetry, or analytics
- Uses only folders explicitly selected through Android's SAF folder picker
- Persists only the SAF tree grants needed for configured backup jobs

When backing up to Google Drive or another cloud-backed provider, that provider's
Android DocumentsProvider handles the sync. SAF Backup itself does not connect
to the network.

```text
Source folder
    -> Android SAF read grant
    -> SAF Backup copy engine
    -> Android SAF read/write grant
    -> Destination folder or cloud-backed DocumentsProvider
```

## Notifications

Each job can choose how noisy it should be:

- **Failures & conflicts only**: alert only when attention is needed.
- **Every backup**: send a result notification after each automatic run.
- **Off**: keep the job silent.

On Android 13 and newer, result notifications require `POST_NOTIFICATIONS`.
Backups continue to run even if notification permission is denied.

## Releases

Release builds are produced from version tags such as `v0.2.3`.

The GitHub Actions release workflow derives:

- `versionName` from the tag without the `v` prefix.
- `versionCode` as `major * 1000000 + minor * 1000 + patch`.

For example, `v0.2.3` becomes `versionName = 0.2.3` and
`versionCode = 2003`.

## Build

Open the project in Android Studio and sync Gradle, or run:

```bash
gradle test assembleDebug
```

Debug APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

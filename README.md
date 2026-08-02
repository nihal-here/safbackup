# SAF Backup (V2)

Tiny, safe, one-way Android folder backup utility, designed for backing up
local folders (such as [Aegis Authenticator](https://getaegis.app/) encrypted exports)
to Google Drive or other local/cloud directories via Android's Storage Access Framework (SAF).

## Security Model

- **NO** `MANAGE_EXTERNAL_STORAGE`
- **NO** `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE`
- **NO** `INTERNET` permission
- **NO** Google Account / Drive API integration
- **NO** rclone, cloud SDKs, telemetry, or analytics
- Source and destination folders are chosen through Android's SAF folder picker.
- The app only retains access to the directory trees explicitly granted by the user.
- Destination-only files and directories are **never** deleted.

```
Local Aegis / Documents / Photos folder
      ↓
Android SAF (persistent tree permission, READ only)
      ↓
SAF Backup V2 (copies new files, preserves folder structures)
      ↓
Android SAF (persistent tree permission, READ + WRITE)
      ↓
Google Drive DocumentsProvider
```

Google Drive's Android app handles all networking.
SAF Backup itself does not connect to any network.

## V2 Capabilities

### 1. Multiple Independent Backup Jobs
- Configure multiple independent backup tasks (e.g. Aegis, Important Documents, Photos).
- Each job has its own source/destination tree, recursion setting, constraints, notification policy, and daily schedule.
- Run jobs individually or tap **Back Up All Now**.
- Deleting a job cancels its schedule and safely releases SAF permissions only if no other job requires them.

### 2. Recursive Subfolder Backup (Optional per job)
- **Disabled**: Only processes top-level files directly inside the source directory.
- **Enabled**: Recursively traverses the source tree and reproduces the directory structure under destination.
- Empty directories in the source folder are preserved at the destination.

### 3. Backup Notifications
- **Failures & conflicts only** (default): Sends alert notifications if a backup fails or encounters conflicts/revoked permissions.
- **Every backup**: Sends a notification after every automatic run summarizing copied/skipped counts.
- **Off**: Disables result notifications for that job.
- Requires `POST_NOTIFICATIONS` on Android 13+. If denied, backups continue operating normally.

## Backup Semantics & Safety

This is a **one-way append-only backup**, NOT synchronization.

1. **Never delete**: Destination-only files and directories are never deleted.
2. **Never overwrite**: Existing destination files with identical relative path, size, and SHA-256 hash are skipped.
3. **Conflict protection**: Existing destination files with differing size or hash are marked as conflicts (`PARTIAL / CONFLICTS`) and left untouched.
4. **Type conflict protection**: If source has a directory `foo/` but destination has a file `foo`, or source has a file `bar` but destination has a directory `bar/`, a conflict is logged without deleting the destination entry.
5. **Partial copy cleanup**: On copy failure, only the destination file created by *that specific run* is cleaned up.

## Daily Scheduling

WorkManager periodic work is inexact. Daily backup timing is managed by Android based on device Doze mode and constraints (Wi-Fi only, charging only).
Each job tracks both `Last attempt` and `Last success` timestamps separately so failed attempts do not obscure the last known good backup time.

## Build

Open the project in Android Studio and sync Gradle, or run:

```bash
./gradlew assembleDebug
```

Output APK: `app/build/outputs/apk/debug/app-debug.apk`

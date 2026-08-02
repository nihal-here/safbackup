package dev.safbackup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Copies files from a SAF source tree to a SAF destination tree.
 *
 * Semantics (append-only backup, NOT sync):
 *  - Only top-level files are processed (Aegis backups are flat).
 *  - If a filename exists at the destination AND matches the source (size + SHA-256),
 *    it is skipped as already backed up.
 *  - If a filename exists but does NOT match the source, it is reported as a
 *    conflict. The destination file is never deleted or overwritten automatically.
 *  - Destination-only files are never touched.
 *  - On copy failure, only the file THIS run created is cleaned up.
 *  - SHA-256 is computed for integrity verification of both existing and
 *    newly-copied files.
 *
 * Error handling:
 *  - CancellationException is always rethrown (structured concurrency).
 *  - SecurityException / config errors → Result.failure().
 *  - Per-file errors are recorded in BackupResult but don't abort the entire run.
 *  - Result is always success() or failure(); retry() is never used.
 *    Note on WorkManager periodic work: returning Result.failure() finishes the
 *    current execution attempt without triggering retry backoff, but WorkManager
 *    keeps the PeriodicWorkRequest enqueued for the next scheduled daily interval.
 *    The actual outcome (copied/skipped/failed & error list) is persisted in
 *    BackupResult for UI observability.
 *
 * Execution limits:
 *  - Aegis JSON backups are tiny (KB). The 10-minute WorkManager limit is
 *    not a concern for this use case. No foreground service is used.
 *  - The run is idempotent: reruns skip already-verified files.
 */
class BackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val sourceUri = prefs.getString("source", null)
        val destUri = prefs.getString("dest", null)

        if (sourceUri == null || destUri == null) {
            return@withContext failPermanently("Source or destination not configured.")
        }
        if (sourceUri == destUri) {
            return@withContext failPermanently("Source and destination are the same folder.")
        }

        val source: DocumentFile
        val dest: DocumentFile
        try {
            source = DocumentFile.fromTreeUri(ctx, Uri.parse(sourceUri))
                ?: return@withContext failPermanently("Source folder URI is invalid.")
            dest = DocumentFile.fromTreeUri(ctx, Uri.parse(destUri))
                ?: return@withContext failPermanently("Destination folder URI is invalid.")

            if (!source.canRead()) {
                return@withContext failPermanently(
                    "Cannot read source folder. Permission may have been revoked."
                )
            }
            if (!dest.canWrite()) {
                return@withContext failPermanently(
                    "Cannot write to destination folder. Permission may have been revoked."
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: SecurityException) {
            return@withContext failPermanently(
                "SAF permission denied. Re-select folders in the app."
            )
        } catch (e: Exception) {
            return@withContext failPermanently("Cannot access folders: ${e.message}")
        }

        var copied = 0
        var skipped = 0
        var failed = 0
        val errors = mutableListOf<String>()

        try {
            val sourceFiles = source.listFiles()

            // Build a map of destination filename → DocumentFile for O(1) lookup.
            val destFilesByName = mutableMapOf<String, DocumentFile>()
            for (df in dest.listFiles()) {
                val n = df.name
                if (n != null && df.isFile) destFilesByName[n] = df
            }

            if (sourceFiles.isEmpty()) {
                // Empty source is not an error — no new backups yet.
                saveResult(ctx, copied = 0, skipped = 0, failed = 0, errors = emptyList())
                return@withContext Result.success()
            }

            for (src in sourceFiles) {
                ensureActive()

                val name = src.name
                if (name == null || !src.isFile) continue

                val existing = destFilesByName[name]
                if (existing != null) {
                    // A file with this name exists at the destination.
                    // Verify it matches the source before skipping.
                    try {
                        val verdict = verifyExisting(src, existing)
                        when (verdict) {
                            VerifyResult.MATCH -> {
                                skipped++
                                continue
                            }
                            VerifyResult.MISMATCH -> {
                                // Conflict: destination file differs from source.
                                // Do NOT delete or overwrite. Report to user.
                                failed++
                                errors.add("$name (destination differs from source — possible corrupt partial copy)")
                                continue
                            }
                            VerifyResult.UNREADABLE -> {
                                // Can't read one side for verification.
                                // Conservatively report rather than blindly skip.
                                failed++
                                errors.add("$name (destination exists but could not be verified)")
                                continue
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                        errors.add("$name (verification error: ${e.message})")
                        continue
                    }
                }

                // File does not exist at destination. Copy it.
                try {
                    val ok = copySingleFile(src, dest, name)
                    if (ok) {
                        copied++
                    } else {
                        failed++
                        errors.add("$name (copy failed)")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    failed++
                    errors.add("$name (permission denied)")
                } catch (e: Exception) {
                    failed++
                    errors.add("$name (${e.message})")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: SecurityException) {
            return@withContext failPermanently("Permission denied listing source files.")
        }

        saveResult(ctx, copied, skipped, failed, errors)

        // Always return success or failure, never retry.
        // For periodic work: the next scheduled run is the retry.
        // For one-time work: the user sees the result and can re-trigger.
        // This avoids hammering a failing provider with exponential backoff
        // retries that serve no purpose when the next daily run is imminent.
        Result.success()
    }

    // ── Existing-file verification ───────────────────────────────────────────────

    private enum class VerifyResult { MATCH, MISMATCH, UNREADABLE }

    /**
     * Compare an existing destination file against the source.
     *
     * For small Aegis JSON files, this computes SHA-256 of both sides.
     * Returns MATCH if they are identical, MISMATCH if they differ,
     * or UNREADABLE if either side can't be read for verification.
     */
    private fun verifyExisting(src: DocumentFile, dst: DocumentFile): VerifyResult {
        val srcSize = src.length()
        val dstSize = dst.length()

        // Quick size check: if both are readable and sizes differ, mismatch.
        if (srcSize > 0 && dstSize > 0 && srcSize != dstSize) {
            return VerifyResult.MISMATCH
        }

        // Full content verification via SHA-256.
        val srcHash = sha256(src.uri) ?: return VerifyResult.UNREADABLE
        val dstHash = sha256(dst.uri) ?: return VerifyResult.UNREADABLE

        return if (srcHash.contentEquals(dstHash)) {
            VerifyResult.MATCH
        } else {
            VerifyResult.MISMATCH
        }
    }

    // ── Single-file copy with integrity ──────────────────────────────────────────

    /**
     * Copy one file from source to destination with integrity checking.
     *
     * Strategy:
     *  1. SHA-256 hash the source.
     *  2. Create a new file at the destination.
     *  3. Stream-copy source → destination.
     *  4. Verify destination size matches source size.
     *  5. Best-effort: re-read destination and verify SHA-256 matches.
     *     If the provider returns inconsistent data on immediate readback
     *     (e.g. Google Drive async upload), size match alone is accepted.
     *  6. On any failure, delete ONLY the file we just created.
     *
     * Returns true on success, false on failure.
     */
    private fun copySingleFile(
        src: DocumentFile,
        destDir: DocumentFile,
        name: String
    ): Boolean {
        val resolver = applicationContext.contentResolver
        val mime = src.type ?: "application/octet-stream"
        val sourceSize = src.length()

        // 1. Hash the source file.
        val sourceHash = sha256(src.uri) ?: return false

        // 2. Create destination file.
        val dst = destDir.createFile(mime, name) ?: return false

        var success = false
        try {
            // 3. Stream-copy.
            resolver.openInputStream(src.uri).use { input ->
                resolver.openOutputStream(dst.uri, "w").use { output ->
                    if (input == null || output == null) return false
                    input.copyTo(output)
                    output.flush()
                }
            }

            // 4. Size verification.
            val destSize = dst.length()
            if (sourceSize > 0 && destSize > 0 && destSize != sourceSize) {
                return false // cleaned up in finally
            }

            // 5. Best-effort hash verification.
            //    Google Drive's provider may async-upload and return stale data
            //    on immediate readback. If the hash read succeeds and mismatches,
            //    we reject. If it fails (null), we accept on size match alone —
            //    the provider is responsible for upload integrity.
            val destHash = sha256(dst.uri)
            if (destHash != null && !destHash.contentEquals(sourceHash)) {
                return false // cleaned up in finally
            }

            success = true
            return true
        } finally {
            if (!success) {
                // Clean up only the file THIS operation created.
                // Never delete a pre-existing destination file.
                try {
                    dst.delete()
                } catch (_: Exception) {
                    // Best effort. If delete fails, a corrupt partial file
                    // remains. The next run will detect the mismatch via
                    // verifyExisting() and report it as a conflict rather
                    // than silently skipping it.
                }
            }
        }
    }

    // ── Utilities ────────────────────────────────────────────────────────────────

    /** SHA-256 a file via SAF content resolver. Returns null on any error. */
    private fun sha256(uri: Uri): ByteArray? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    digest.update(buf, 0, n)
                }
            } ?: return null
            digest.digest()
        } catch (_: Exception) {
            null
        }
    }

    /** Record a permanent failure and return Result.failure(). */
    private fun failPermanently(message: String): Result {
        saveResult(applicationContext, 0, 0, 0, listOf(message))
        return Result.failure()
    }

    private fun saveResult(
        ctx: Context,
        copied: Int,
        skipped: Int,
        failed: Int,
        errors: List<String>
    ) {
        BackupResult.save(ctx, BackupResult(
            timestampMillis = System.currentTimeMillis(),
            copied = copied,
            skipped = skipped,
            failed = failed,
            errors = errors
        ))
    }
}

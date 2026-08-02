package dev.safbackup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.WorkerParameters
import dev.safbackup.data.AppDatabase
import dev.safbackup.data.BackupJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class BackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val jobId = inputData.getString(KEY_JOB_ID) ?: return@withContext Result.success()

        val db = AppDatabase.getInstance(ctx)
        val job = db.backupJobDao().getById(jobId) ?: return@withContext Result.success()

        val now = System.currentTimeMillis()

        val source: DocumentFile?
        val dest: DocumentFile?
        try {
            source = DocumentFile.fromTreeUri(ctx, Uri.parse(job.sourceUri))
            dest = DocumentFile.fromTreeUri(ctx, Uri.parse(job.destUri))

            if (source == null || dest == null || job.sourceUri == job.destUri) {
                return@withContext markJobFailed(
                    db, job, now,
                    listOf("Invalid source or destination folder configuration.")
                )
            }

            if (!source.canRead()) {
                return@withContext markJobFailed(
                    db, job, now,
                    listOf("Cannot read source folder. Permission may have been revoked.")
                )
            }
            if (!dest.canWrite()) {
                return@withContext markJobFailed(
                    db, job, now,
                    listOf("Cannot write to destination folder. Permission may have been revoked.")
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            return@withContext markJobFailed(
                db, job, now,
                listOf("SAF permission denied. Re-select folder permissions in app.")
            )
        } catch (e: Exception) {
            return@withContext markJobFailed(
                db, job, now,
                listOf("Cannot access folders: ${e.message}")
            )
        }

        var copied = 0
        var skipped = 0
        var failed = 0
        val errors = mutableListOf<String>()

        try {
            if (job.isRecursive) {
                val stats = copyDirectoryRecursive(source, dest, "", 0)
                copied += stats.copied
                skipped += stats.skipped
                failed += stats.failed
                errors.addAll(stats.errors)
            } else {
                val stats = copyTopLevelFilesOnly(source, dest)
                copied += stats.copied
                skipped += stats.skipped
                failed += stats.failed
                errors.addAll(stats.errors)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            failed++
            errors.add("Permission denied during file traversal.")
        } catch (e: Exception) {
            failed++
            errors.add("Traversal error: ${e.message}")
        }

        val finalStatus = when {
            failed > 0 && copied == 0 && skipped == 0 -> BackupJob.STATUS_FAILED
            failed > 0 -> BackupJob.STATUS_PARTIAL_CONFLICTS
            else -> BackupJob.STATUS_SUCCESS
        }

        val newSuccessfulTime = if (finalStatus == BackupJob.STATUS_SUCCESS) {
            now
        } else {
            job.lastSuccessfulTime
        }

        val updatedJob = job.copy(
            lastAttemptTime = now,
            lastSuccessfulTime = newSuccessfulTime,
            lastStatus = finalStatus,
            lastCopiedCount = copied,
            lastSkippedCount = skipped,
            lastFailedCount = failed,
            lastErrors = errors.joinToString("\n")
        )

        db.backupJobDao().update(updatedJob)

        NotificationHelper.maybeNotifyResult(
            ctx, updatedJob, copied, skipped, failed, finalStatus, errors
        )

        Result.success()
    }

    private data class CopyStats(
        val copied: Int,
        val skipped: Int,
        val failed: Int,
        val errors: List<String>
    )

    private suspend fun copyTopLevelFilesOnly(
        sourceDir: DocumentFile,
        destDir: DocumentFile
    ): CopyStats {
        var copied = 0
        var skipped = 0
        var failed = 0
        val errors = mutableListOf<String>()

        val destChildren = destDir.listFiles()
            .filter { it.name != null }
            .associateBy { it.name!! }

        val sourceFiles = sourceDir.listFiles().filter { it.isFile }

        for (src in sourceFiles) {
            coroutineContext.ensureActive()
            val name = src.name ?: continue

            val existing = destChildren[name]
            if (existing != null) {
                if (existing.isDirectory) {
                    failed++
                    errors.add("Cannot copy file '$name' because destination contains a directory with that name.")
                    continue
                }

                when (verifyExisting(src, existing)) {
                    VerifyResult.MATCH -> skipped++
                    VerifyResult.MISMATCH -> {
                        failed++
                        errors.add("$name (destination differs from source — conflict / possible corrupt copy)")
                    }
                    VerifyResult.UNREADABLE -> {
                        failed++
                        errors.add("$name (destination exists but could not be read for verification)")
                    }
                }
            } else {
                val ok = copySingleFile(src, destDir, name)
                if (ok) copied++ else {
                    failed++
                    errors.add("$name (copy failed)")
                }
            }
        }

        return CopyStats(copied, skipped, failed, errors)
    }

    private suspend fun copyDirectoryRecursive(
        srcDir: DocumentFile,
        destDir: DocumentFile,
        relPath: String,
        depth: Int
    ): CopyStats {
        if (depth > MAX_RECURSION_DEPTH) {
            return CopyStats(0, 0, 1, listOf("Max directory depth reached at $relPath"))
        }

        var copied = 0
        var skipped = 0
        var failed = 0
        val errors = mutableListOf<String>()

        val destChildren = destDir.listFiles()
            .filter { it.name != null }
            .associateBy { it.name!! }

        val sourceItems = srcDir.listFiles()

        for (srcItem in sourceItems) {
            coroutineContext.ensureActive()
            val name = srcItem.name ?: continue
            val itemPath = if (relPath.isEmpty()) name else "$relPath/$name"

            val existing = destChildren[name]

            if (srcItem.isDirectory) {
                var targetDestSubdir: DocumentFile? = null

                if (existing != null) {
                    if (!existing.isDirectory) {
                        failed++
                        errors.add("Cannot create directory '$itemPath' because destination contains a file with that name.")
                        continue
                    }
                    targetDestSubdir = existing
                } else {
                    val created = destDir.createDirectory(name)
                    if (created == null || !created.isDirectory) {
                        failed++
                        errors.add("Failed to create destination directory '$itemPath'.")
                        continue
                    }
                    if (created.name != name) {
                        failed++
                        errors.add("Destination provider mutated directory name '${created.name}' instead of '$name'.")
                        try { created.delete() } catch (_: Exception) {}
                        continue
                    }
                    targetDestSubdir = created
                }

                val subStats = copyDirectoryRecursive(srcItem, targetDestSubdir, itemPath, depth + 1)
                copied += subStats.copied
                skipped += subStats.skipped
                failed += subStats.failed
                errors.addAll(subStats.errors)

            } else if (srcItem.isFile) {
                if (existing != null) {
                    if (existing.isDirectory) {
                        failed++
                        errors.add("Cannot copy file '$itemPath' because destination contains a directory with that name.")
                        continue
                    }

                    when (verifyExisting(srcItem, existing)) {
                        VerifyResult.MATCH -> skipped++
                        VerifyResult.MISMATCH -> {
                            failed++
                            errors.add("$itemPath (destination differs from source — conflict / possible corrupt copy)")
                        }
                        VerifyResult.UNREADABLE -> {
                            failed++
                            errors.add("$itemPath (destination exists but could not be read for verification)")
                        }
                    }
                } else {
                    val ok = copySingleFile(srcItem, destDir, name)
                    if (ok) copied++ else {
                        failed++
                        errors.add("$itemPath (copy failed)")
                    }
                }
            }
        }

        return CopyStats(copied, skipped, failed, errors)
    }

    private enum class VerifyResult { MATCH, MISMATCH, UNREADABLE }

    private fun verifyExisting(src: DocumentFile, dst: DocumentFile): VerifyResult {
        val srcSize = src.length()
        val dstSize = dst.length()

        if (srcSize > 0 && dstSize > 0 && srcSize != dstSize) {
            return VerifyResult.MISMATCH
        }

        val srcHash = sha256(src.uri) ?: return VerifyResult.UNREADABLE
        val dstHash = sha256(dst.uri) ?: return VerifyResult.UNREADABLE

        return if (srcHash.contentEquals(dstHash)) {
            VerifyResult.MATCH
        } else {
            VerifyResult.MISMATCH
        }
    }

    private fun copySingleFile(
        src: DocumentFile,
        destDir: DocumentFile,
        name: String
    ): Boolean {
        val resolver = applicationContext.contentResolver
        val mime = src.type ?: "application/octet-stream"
        val sourceSize = src.length()

        val sourceHash = sha256(src.uri) ?: return false
        val dst = destDir.createFile(mime, name) ?: return false

        if (dst.name != name) {
            // Provider mutated name (e.g. name (1).json). Clean up.
            try { dst.delete() } catch (_: Exception) {}
            return false
        }

        var success = false
        try {
            resolver.openInputStream(src.uri).use { input ->
                resolver.openOutputStream(dst.uri, "w").use { output ->
                    if (input == null || output == null) return false
                    input.copyTo(output)
                    output.flush()
                }
            }

            val destSize = dst.length()
            if (sourceSize > 0 && destSize > 0 && destSize != sourceSize) {
                return false
            }

            val destHash = sha256(dst.uri)
            if (destHash != null && !destHash.contentEquals(sourceHash)) {
                return false
            }

            success = true
            return true
        } finally {
            if (!success) {
                try {
                    dst.delete()
                } catch (_: Exception) {}
            }
        }
    }

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

    private suspend fun markJobFailed(
        db: AppDatabase,
        job: BackupJob,
        now: Long,
        errors: List<String>
    ): Result {
        val updatedJob = job.copy(
            lastAttemptTime = now,
            lastStatus = BackupJob.STATUS_FAILED,
            lastFailedCount = errors.size,
            lastErrors = errors.joinToString("\n")
        )
        db.backupJobDao().update(updatedJob)
        NotificationHelper.maybeNotifyResult(
            applicationContext, updatedJob, 0, 0, errors.size, BackupJob.STATUS_FAILED, errors
        )
        return Result.failure()
    }

    companion object {
        const val KEY_JOB_ID = "job_id"
        const val MAX_RECURSION_DEPTH = 30

        fun getWorkName(jobId: String): String = "backup_daily_$jobId"

        fun buildConstraints(wifiOnly: Boolean, chargingOnly: Boolean): Constraints {
            return Constraints.Builder().apply {
                if (wifiOnly) setRequiredNetworkType(NetworkType.UNMETERED)
                if (chargingOnly) setRequiresCharging(true)
                setRequiresBatteryNotLow(true)
                setRequiresStorageNotLow(true)
            }.build()
        }
    }
}

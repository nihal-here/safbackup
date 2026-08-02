package dev.safbackup.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "backup_jobs")
data class BackupJob(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val sourceUri: String,
    val destUri: String,
    val isRecursive: Boolean = false,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val autoDaily: Boolean = true,
    val notificationPref: String = NOTIF_FAILURES_ONLY,
    val lastAttemptTime: Long? = null,
    val lastSuccessfulTime: Long? = null,
    val lastStatus: String = STATUS_NEVER_RUN,
    val lastCopiedCount: Int = 0,
    val lastSkippedCount: Int = 0,
    val lastFailedCount: Int = 0,
    val lastErrors: String = ""
) {
    companion object {
        const val NOTIF_FAILURES_ONLY = "FAILURES_ONLY"
        const val NOTIF_EVERY_BACKUP = "EVERY_BACKUP"
        const val NOTIF_OFF = "OFF"

        const val STATUS_NEVER_RUN = "NEVER_RUN"
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_PARTIAL_CONFLICTS = "PARTIAL_CONFLICTS"
        const val STATUS_FAILED = "FAILED"
    }

    fun getErrorList(): List<String> {
        if (lastErrors.isBlank()) return emptyList()
        return lastErrors.split("\n").filter { it.isNotBlank() }
    }
}

package dev.safbackup.data

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.safbackup.BackupWorker
import java.util.UUID
import java.util.concurrent.TimeUnit

object V1Migration {

    suspend fun checkAndMigrate(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("v1_migrated", false)) return

        val sourceUri = prefs.getString("source", null)
        val destUri = prefs.getString("dest", null)

        if (sourceUri != null && destUri != null && sourceUri.isNotBlank() && destUri.isNotBlank()) {
            val wifiOnly = prefs.getBoolean("wifi_only", true)
            val chargingOnly = prefs.getBoolean("charging_only", false)

            val importedJob = BackupJob(
                id = UUID.randomUUID().toString(),
                name = "Imported backup",
                sourceUri = sourceUri,
                destUri = destUri,
                isRecursive = false,
                wifiOnly = wifiOnly,
                chargingOnly = chargingOnly,
                autoDaily = true,
                notificationPref = BackupJob.NOTIF_FAILURES_ONLY
            )

            val db = AppDatabase.getInstance(context)
            db.backupJobDao().insert(importedJob)

            // Cancel old V1 daily backup worker
            WorkManager.getInstance(context).cancelUniqueWork("daily_backup")

            // Schedule new V2 WorkManager job for imported job
            val req = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
                .setConstraints(BackupWorker.buildConstraints(wifiOnly, chargingOnly))
                .setInputData(androidx.work.workDataOf(BackupWorker.KEY_JOB_ID to importedJob.id))
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                BackupWorker.getWorkName(importedJob.id),
                ExistingPeriodicWorkPolicy.UPDATE,
                req
            )
        }

        prefs.edit().putBoolean("v1_migrated", true).apply()
    }
}

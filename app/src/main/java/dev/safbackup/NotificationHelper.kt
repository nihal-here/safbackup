package dev.safbackup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.safbackup.data.BackupJob

object NotificationHelper {
    const val CHANNEL_ID = "backup_results_channel"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notification_channel_desc)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    fun maybeNotifyResult(
        context: Context,
        job: BackupJob,
        copied: Int,
        skipped: Int,
        failed: Int,
        status: String,
        errors: List<String>
    ) {
        if (job.notificationPref == BackupJob.NOTIF_OFF) return

        val hasIssues = failed > 0 || status == BackupJob.STATUS_FAILED || status == BackupJob.STATUS_PARTIAL_CONFLICTS
        if (job.notificationPref == BackupJob.NOTIF_FAILURES_ONLY && !hasIssues) return

        createNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            job.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "${job.name} — ${context.getString(R.string.app_name)}"
        val message = when (status) {
            BackupJob.STATUS_SUCCESS -> {
                context.getString(R.string.notif_success_body, copied, skipped)
            }
            BackupJob.STATUS_PARTIAL_CONFLICTS -> {
                context.getString(R.string.notif_conflict_body, failed)
            }
            else -> {
                val err = errors.firstOrNull() ?: context.getString(R.string.notif_failed_generic)
                context.getString(R.string.notif_failed_body, err)
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(job.id.hashCode(), notification)
        } catch (_: SecurityException) {
            // Permission denied by user — backup continues working normally without notification
        }
    }
}

package dev.safbackup

import android.net.Uri
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.safbackup.data.BackupJob
import dev.safbackup.databinding.ItemBackupJobBinding
import java.util.Date

class BackupJobAdapter(
    private val onRunNow: (BackupJob) -> Unit,
    private val onEdit: (BackupJob) -> Unit,
    private val onDelete: (BackupJob) -> Unit
) : ListAdapter<BackupJob, BackupJobAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(val b: ItemBackupJobBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBackupJobBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val job = getItem(position)
        val ctx = holder.itemView.context
        val b = holder.b

        b.jobName.text = job.name
        b.jobSummary.text = "${friendlyName(job.sourceUri)} → ${friendlyName(job.destUri)}"

        b.badgeDaily.text = if (job.autoDaily) ctx.getString(R.string.badge_daily_on) else ctx.getString(R.string.badge_daily_off)
        b.badgeRecursive.text = if (job.isRecursive) ctx.getString(R.string.badge_recursive_on) else ctx.getString(R.string.badge_recursive_off)

        val statusText = when (job.lastStatus) {
            BackupJob.STATUS_SUCCESS -> ctx.getString(R.string.status_success)
            BackupJob.STATUS_PARTIAL_CONFLICTS -> ctx.getString(R.string.status_partial_conflicts)
            BackupJob.STATUS_FAILED -> ctx.getString(R.string.status_failed)
            else -> ctx.getString(R.string.status_never_run)
        }

        b.statusBadge.text = statusText
        val badgeColor = when (job.lastStatus) {
            BackupJob.STATUS_SUCCESS -> ContextCompat.getColor(ctx, android.R.color.holo_green_dark)
            BackupJob.STATUS_PARTIAL_CONFLICTS -> ContextCompat.getColor(ctx, android.R.color.holo_orange_dark)
            BackupJob.STATUS_FAILED -> ContextCompat.getColor(ctx, android.R.color.holo_red_dark)
            else -> ContextCompat.getColor(ctx, android.R.color.darker_gray)
        }
        b.statusBadge.setTextColor(badgeColor)

        val attemptStr = if (job.lastAttemptTime != null) {
            val dateStr = formatDateTime(ctx, job.lastAttemptTime)
            ctx.getString(R.string.last_attempt_fmt, dateStr)
        } else {
            ctx.getString(R.string.last_attempt_none)
        }
        b.lastAttemptText.text = attemptStr

        val successStr = if (job.lastSuccessfulTime != null) {
            val dateStr = formatDateTime(ctx, job.lastSuccessfulTime)
            ctx.getString(R.string.last_success_fmt, dateStr)
        } else {
            ctx.getString(R.string.last_success_none)
        }
        b.lastSuccessText.text = successStr

        b.countsText.text = ctx.getString(
            R.string.counts_fmt,
            job.lastCopiedCount,
            job.lastSkippedCount,
            job.lastFailedCount
        )

        val errors = job.getErrorList()
        if (errors.isNotEmpty()) {
            b.errorListText.visibility = View.VISIBLE
            b.errorListText.text = errors.joinToString("\n") { "• $it" }
        } else {
            b.errorListText.visibility = View.GONE
        }

        b.runNowButton.setOnClickListener { onRunNow(job) }
        b.editButton.setOnClickListener { onEdit(job) }
        b.deleteButton.setOnClickListener { onDelete(job) }
    }

    private fun friendlyName(uriString: String): String {
        return try {
            val uri = Uri.parse(uriString)
            Uri.decode(uri.lastPathSegment ?: uriString)
        } catch (_: Exception) {
            uriString
        }
    }

    private fun formatDateTime(context: android.content.Context, millis: Long): String {
        val d = Date(millis)
        return DateFormat.getDateFormat(context).format(d) + " " + DateFormat.getTimeFormat(context).format(d)
    }

    object DiffCallback : DiffUtil.ItemCallback<BackupJob>() {
        override fun areItemsTheSame(oldItem: BackupJob, newItem: BackupJob): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: BackupJob, newItem: BackupJob): Boolean =
            oldItem == newItem
    }
}

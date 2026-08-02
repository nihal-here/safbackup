package dev.safbackup

import android.content.Context
import android.net.Uri
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
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
    private val onDelete: (BackupJob) -> Unit,
    private val onToggleDaily: (BackupJob) -> Unit
) : ListAdapter<BackupJob, BackupJobAdapter.ViewHolder>(DiffCallback) {

    private val runningJobIds = mutableSetOf<String>()

    fun setRunningJobs(jobIds: Set<String>) {
        runningJobIds.clear()
        runningJobIds.addAll(jobIds)
        notifyDataSetChanged()
    }

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
        b.sourceLabel.text = friendlyName(job.sourceUri)
        b.destLabel.text = friendlyName(job.destUri)

        // Status badge: Icon + Text + Color
        val (statusText, statusColor, statusIcon) = when (job.lastStatus) {
            BackupJob.STATUS_SUCCESS -> Triple(
                ctx.getString(R.string.status_success),
                ContextCompat.getColor(ctx, R.color.status_success),
                R.drawable.ic_status_success
            )
            BackupJob.STATUS_PARTIAL_CONFLICTS -> Triple(
                ctx.getString(R.string.status_partial_conflicts),
                ContextCompat.getColor(ctx, R.color.status_warning),
                R.drawable.ic_status_warning
            )
            BackupJob.STATUS_FAILED -> Triple(
                ctx.getString(R.string.status_failed),
                ContextCompat.getColor(ctx, R.color.status_error),
                R.drawable.ic_status_error
            )
            else -> Triple(
                ctx.getString(R.string.status_never_run),
                ContextCompat.getColor(ctx, R.color.status_neutral),
                R.drawable.ic_status_neutral
            )
        }

        b.statusText.text = statusText
        b.statusText.setTextColor(statusColor)
        b.statusIcon.setImageResource(statusIcon)
        b.statusIcon.setColorFilter(statusColor)

        // Timestamps: Last Successful vs Last Attempt
        val successStr = if (job.lastSuccessfulTime != null) {
            formatDateTime(ctx, job.lastSuccessfulTime)
        } else {
            ctx.getString(R.string.timestamp_never)
        }
        b.lastSuccessText.text = ctx.getString(R.string.fmt_last_success, successStr)

        if (job.lastAttemptTime != null && (job.lastStatus == BackupJob.STATUS_FAILED || job.lastStatus == BackupJob.STATUS_PARTIAL_CONFLICTS)) {
            b.lastAttemptText.visibility = View.VISIBLE
            val attemptStr = formatDateTime(ctx, job.lastAttemptTime)
            b.lastAttemptText.text = ctx.getString(R.string.fmt_last_attempt, attemptStr, statusText)
        } else {
            b.lastAttemptText.visibility = View.GONE
        }

        b.countsText.text = ctx.getString(
            R.string.counts_fmt,
            job.lastCopiedCount,
            job.lastSkippedCount,
            job.lastFailedCount
        )

        // Badges
        b.badgeDaily.text = if (job.autoDaily) ctx.getString(R.string.badge_daily_on) else ctx.getString(R.string.badge_daily_off)
        b.badgeRecursive.text = if (job.isRecursive) ctx.getString(R.string.badge_recursive_on) else ctx.getString(R.string.badge_recursive_off)
        b.badgeWifi.text = if (job.wifiOnly) ctx.getString(R.string.badge_wifi_on) else ctx.getString(R.string.badge_wifi_off)

        // Errors list
        val errors = job.getErrorList()
        if (errors.isNotEmpty()) {
            b.errorListText.visibility = View.VISIBLE
            b.errorListText.text = errors.joinToString("\n") { "• $it" }
        } else {
            b.errorListText.visibility = View.GONE
        }

        // Primary action: Back up now (with Running state)
        val isRunning = runningJobIds.contains(job.id)
        if (isRunning) {
            b.runNowButton.isEnabled = false
            b.runNowButton.text = ctx.getString(R.string.btn_backing_up)
        } else {
            b.runNowButton.isEnabled = true
            b.runNowButton.text = ctx.getString(R.string.btn_run_now)
        }

        b.runNowButton.setOnClickListener { onRunNow(job) }

        // Overflow menu for less-common actions
        b.overflowButton.setOnClickListener { view ->
            showOverflowMenu(ctx, view, job)
        }
    }

    private fun showOverflowMenu(context: Context, anchor: View, job: BackupJob) {
        val popup = PopupMenu(context, anchor)
        popup.menu.add(0, 1, 0, context.getString(R.string.menu_edit))
        val toggleDailyText = if (job.autoDaily) {
            context.getString(R.string.menu_disable_daily)
        } else {
            context.getString(R.string.menu_enable_daily)
        }
        popup.menu.add(0, 2, 1, toggleDailyText)
        popup.menu.add(0, 3, 2, context.getString(R.string.menu_delete))

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                1 -> { onEdit(job); true }
                2 -> { onToggleDaily(job); true }
                3 -> { onDelete(job); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun friendlyName(uriString: String): String {
        return try {
            val uri = Uri.parse(uriString)
            Uri.decode(uri.lastPathSegment ?: uriString)
        } catch (_: Exception) {
            uriString
        }
    }

    private fun formatDateTime(context: Context, millis: Long): String {
        val d = Date(millis)
        return DateFormat.getDateFormat(context).format(d) + ", " + DateFormat.getTimeFormat(context).format(d)
    }

    object DiffCallback : DiffUtil.ItemCallback<BackupJob>() {
        override fun areItemsTheSame(oldItem: BackupJob, newItem: BackupJob): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: BackupJob, newItem: BackupJob): Boolean =
            oldItem == newItem
    }
}

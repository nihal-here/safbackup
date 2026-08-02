package dev.safbackup

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.work.*
import dev.safbackup.databinding.ActivityMainBinding
import java.util.Date
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    // ── Two independent pickers: no shared mutable state survives process death ──

    private val sourcePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) handlePickedUri(uri, isSource = true)
        }

    private val destPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) handlePickedUri(uri, isSource = false)
        }

    // ── Lifecycle ────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Restore persisted checkbox state.
        b.wifiOnly.isChecked = prefs.getBoolean("wifi_only", true)
        b.chargingOnly.isChecked = prefs.getBoolean("charging_only", false)

        b.sourceButton.setOnClickListener { sourcePicker.launch(null) }
        b.destButton.setOnClickListener { destPicker.launch(null) }
        b.backupNowButton.setOnClickListener { enqueueNow() }
        b.scheduleButton.setOnClickListener { scheduleDaily() }
        b.disableScheduleButton.setOnClickListener { cancelDaily() }

        // Persist checkbox changes immediately.
        b.wifiOnly.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("wifi_only", checked).apply()
        }
        b.chargingOnly.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("charging_only", checked).apply()
        }

        refreshConfig()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshConfig()
        refreshStatus()
    }

    // ── SAF URI handling ─────────────────────────────────────────────────────────

    private fun handlePickedUri(uri: Uri, isSource: Boolean) {
        // Source only needs READ; destination needs READ + WRITE.
        val flags = if (isSource) {
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        } else {
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        }

        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            b.statusText.text = getString(R.string.error_permission_denied)
            return  // Do NOT save a URI we can't persist.
        }

        val key = if (isSource) "source" else "dest"
        prefs.edit().putString(key, uri.toString()).apply()
        refreshConfig()
    }

    // ── UI refresh ───────────────────────────────────────────────────────────────

    private fun refreshConfig() {
        b.sourceText.text = describeUri(prefs.getString("source", null), needsWrite = false)
        b.destText.text = describeUri(prefs.getString("dest", null), needsWrite = true)
    }

    private fun describeUri(uriString: String?, needsWrite: Boolean): String {
        if (uriString == null) return getString(R.string.not_selected)
        val uri = Uri.parse(uriString)
        val hasPermission = contentResolver.persistedUriPermissions.any { perm ->
            perm.uri == uri && perm.isReadPermission && (!needsWrite || perm.isWritePermission)
        }
        return if (hasPermission) {
            friendlyName(uri)
        } else {
            getString(R.string.permission_revoked)
        }
    }

    /** Extract a short readable label from a SAF tree URI. */
    private fun friendlyName(uri: Uri): String {
        return try {
            Uri.decode(uri.lastPathSegment ?: uri.toString())
        } catch (_: Exception) {
            uri.toString()
        }
    }

    private fun refreshStatus() {
        val result = BackupResult.load(this) ?: run {
            b.statusText.text = getString(R.string.status_no_backups)
            return
        }

        val timeStr = DateFormat.getDateFormat(this).format(Date(result.timestampMillis)) +
                " " + DateFormat.getTimeFormat(this).format(Date(result.timestampMillis))

        val sb = StringBuilder()
        sb.appendLine(getString(R.string.status_last_run, timeStr))
        sb.appendLine(getString(R.string.status_copied, result.copied))
        sb.appendLine(getString(R.string.status_skipped, result.skipped))
        sb.append(getString(R.string.status_failed, result.failed))
        if (result.errors.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine()
            sb.appendLine(getString(R.string.status_errors))
            for (err in result.errors) {
                sb.appendLine("  • $err")
            }
        }
        b.statusText.text = sb.toString()
    }

    // ── Work scheduling ──────────────────────────────────────────────────────────

    private fun constraints(): Constraints = Constraints.Builder().apply {
        if (b.wifiOnly.isChecked) setRequiredNetworkType(NetworkType.UNMETERED)
        if (b.chargingOnly.isChecked) setRequiresCharging(true)
        setRequiresBatteryNotLow(true)
        setRequiresStorageNotLow(true)
    }.build()

    private fun enqueueNow() {
        if (!validateConfig()) return

        val req = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints())
            .build()

        val wm = WorkManager.getInstance(this)
        wm.enqueue(req)

        // Observe this specific work request for live UI updates.
        wm.getWorkInfoByIdLiveData(req.id).observe(this) { info ->
            if (info == null) return@observe
            when (info.state) {
                WorkInfo.State.ENQUEUED -> b.statusText.text = getString(R.string.status_queued)
                WorkInfo.State.RUNNING  -> b.statusText.text = getString(R.string.status_running)
                WorkInfo.State.SUCCEEDED,
                WorkInfo.State.FAILED   -> refreshStatus()
                else -> { /* BLOCKED, CANCELLED — no action */ }
            }
        }

        b.statusText.text = getString(R.string.status_queued)
    }

    private fun scheduleDaily() {
        if (!validateConfig()) return

        val req = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints())
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            DAILY_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            req
        )
        b.statusText.text = getString(R.string.status_daily_enabled)
    }

    private fun cancelDaily() {
        WorkManager.getInstance(this).cancelUniqueWork(DAILY_WORK_NAME)
        b.statusText.text = getString(R.string.status_daily_disabled)
    }

    // ── Validation ───────────────────────────────────────────────────────────────

    private fun validateConfig(): Boolean {
        val sourceUri = prefs.getString("source", null)
        val destUri = prefs.getString("dest", null)

        if (sourceUri == null || destUri == null) {
            b.statusText.text = getString(R.string.error_select_both)
            return false
        }
        if (sourceUri == destUri) {
            b.statusText.text = getString(R.string.error_same_folder)
            return false
        }

        val sourceParsed = Uri.parse(sourceUri)
        val destParsed = Uri.parse(destUri)

        val sourceOk = contentResolver.persistedUriPermissions.any {
            it.uri == sourceParsed && it.isReadPermission
        }
        val destOk = contentResolver.persistedUriPermissions.any {
            it.uri == destParsed && it.isReadPermission && it.isWritePermission
        }

        if (!sourceOk || !destOk) {
            b.statusText.text = getString(R.string.error_permissions_revoked)
            return false
        }

        return true
    }

    companion object {
        const val DAILY_WORK_NAME = "daily_backup"
    }
}

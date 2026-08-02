package dev.safbackup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.safbackup.data.AppDatabase
import dev.safbackup.data.BackupJob
import dev.safbackup.databinding.ActivityEditJobBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

class EditJobActivity : AppCompatActivity() {

    private lateinit var b: ActivityEditJobBinding
    private val db by lazy { AppDatabase.getInstance(this) }

    private var editingJobId: String? = null
    private var currentSourceUri: String? = null
    private var currentDestUri: String? = null

    private val sourcePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) handlePickedUri(uri, isSource = true)
        }

    private val destPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) handlePickedUri(uri, isSource = false)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityEditJobBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Apply WindowInsets for status bar (top) and navigation bar (bottom)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val sysBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            b.contentLayout.updatePadding(
                top = sysBars.top,
                bottom = sysBars.bottom
            )
            insets
        }

        editingJobId = intent.getStringExtra(EXTRA_JOB_ID)

        setupNotifSpinner()

        b.sourceButton.setOnClickListener { sourcePicker.launch(null) }
        b.destButton.setOnClickListener { destPicker.launch(null) }
        b.saveButton.setOnClickListener { saveJob() }
        b.cancelButton.setOnClickListener { finish() }

        if (editingJobId != null) {
            title = getString(R.string.title_edit_job)
            loadExistingJob(editingJobId!!)
        } else {
            title = getString(R.string.title_add_job)
            refreshUriTexts()
        }
    }

    private fun setupNotifSpinner() {
        val options = arrayOf(
            getString(R.string.notif_pref_failures_only),
            getString(R.string.notif_pref_every_backup),
            getString(R.string.notif_pref_off)
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        b.notifSpinner.adapter = adapter
        b.notifSpinner.setSelection(0)
    }

    private fun loadExistingJob(jobId: String) {
        lifecycleScope.launch {
            val job = withContext(Dispatchers.IO) { db.backupJobDao().getById(jobId) }
                ?: return@launch finish()

            b.nameInput.setText(job.name)
            currentSourceUri = job.sourceUri
            currentDestUri = job.destUri
            b.recursiveCheckBox.isChecked = job.isRecursive
            b.wifiOnlyCheckBox.isChecked = job.wifiOnly
            b.chargingOnlyCheckBox.isChecked = job.chargingOnly
            b.autoDailyCheckBox.isChecked = job.autoDaily

            val selection = when (job.notificationPref) {
                BackupJob.NOTIF_EVERY_BACKUP -> 1
                BackupJob.NOTIF_OFF -> 2
                else -> 0
            }
            b.notifSpinner.setSelection(selection)

            refreshUriTexts()
        }
    }

    private fun handlePickedUri(uri: Uri, isSource: Boolean) {
        val flags = if (isSource) {
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        } else {
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        }

        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.error_permission_denied, Toast.LENGTH_LONG).show()
            return
        }

        if (isSource) {
            currentSourceUri = uri.toString()
        } else {
            currentDestUri = uri.toString()
        }
        refreshUriTexts()
    }

    private fun refreshUriTexts() {
        b.sourceText.text = friendlyName(currentSourceUri)
        b.destText.text = friendlyName(currentDestUri)
    }

    private fun friendlyName(uriString: String?): String {
        if (uriString == null) return getString(R.string.not_selected)
        val uri = Uri.parse(uriString)
        return try {
            Uri.decode(uri.lastPathSegment ?: uri.toString())
        } catch (_: Exception) {
            uri.toString()
        }
    }

    private fun saveJob() {
        val name = b.nameInput.text.toString().trim()
        val source = currentSourceUri
        val dest = currentDestUri

        if (name.isEmpty()) {
            Toast.makeText(this, R.string.error_enter_name, Toast.LENGTH_SHORT).show()
            return
        }
        if (source == null || dest == null) {
            Toast.makeText(this, R.string.error_select_both, Toast.LENGTH_SHORT).show()
            return
        }
        if (source == dest) {
            Toast.makeText(this, R.string.error_same_folder, Toast.LENGTH_SHORT).show()
            return
        }

        val notifPref = when (b.notifSpinner.selectedItemPosition) {
            1 -> BackupJob.NOTIF_EVERY_BACKUP
            2 -> BackupJob.NOTIF_OFF
            else -> BackupJob.NOTIF_FAILURES_ONLY
        }

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val existing = if (editingJobId != null) db.backupJobDao().getById(editingJobId!!) else null

                val job = existing?.copy(
                    name = name,
                    sourceUri = source,
                    destUri = dest,
                    isRecursive = b.recursiveCheckBox.isChecked,
                    wifiOnly = b.wifiOnlyCheckBox.isChecked,
                    chargingOnly = b.chargingOnlyCheckBox.isChecked,
                    autoDaily = b.autoDailyCheckBox.isChecked,
                    notificationPref = notifPref
                ) ?: BackupJob(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    sourceUri = source,
                    destUri = dest,
                    isRecursive = b.recursiveCheckBox.isChecked,
                    wifiOnly = b.wifiOnlyCheckBox.isChecked,
                    chargingOnly = b.chargingOnlyCheckBox.isChecked,
                    autoDaily = b.autoDailyCheckBox.isChecked,
                    notificationPref = notifPref
                )

                if (existing != null) {
                    val allJobs = db.backupJobDao().getAll()
                    if (existing.sourceUri != source) {
                        releaseGrantIfUnused(existing.sourceUri, isSource = true, allJobs, job.id)
                    }
                    if (existing.destUri != dest) {
                        releaseGrantIfUnused(existing.destUri, isSource = false, allJobs, job.id)
                    }
                }

                db.backupJobDao().insert(job)

                val workName = BackupWorker.getWorkName(job.id)
                if (job.autoDaily) {
                    val req = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
                        .setConstraints(BackupWorker.buildConstraints(job.wifiOnly, job.chargingOnly))
                        .setInputData(androidx.work.workDataOf(BackupWorker.KEY_JOB_ID to job.id))
                        .build()

                    WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                        workName,
                        ExistingPeriodicWorkPolicy.UPDATE,
                        req
                    )
                } else {
                    WorkManager.getInstance(applicationContext).cancelUniqueWork(workName)
                }
            }

            finish()
        }
    }

    private fun releaseGrantIfUnused(
        uriString: String,
        isSource: Boolean,
        allJobs: List<BackupJob>,
        currentJobId: String
    ) {
        val uri = Uri.parse(uriString)
        val otherJobs = allJobs.filter { it.id != currentJobId }
        val inUseByOther = if (isSource) {
            otherJobs.any { it.sourceUri == uriString }
        } else {
            otherJobs.any { it.destUri == uriString }
        }

        if (!inUseByOther) {
            val flags = if (isSource) {
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            } else {
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            }
            try {
                contentResolver.releasePersistableUriPermission(uri, flags)
            } catch (_: Exception) {}
        }
    }

    companion object {
        const val EXTRA_JOB_ID = "extra_job_id"
    }
}

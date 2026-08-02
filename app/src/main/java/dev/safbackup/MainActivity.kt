package dev.safbackup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.safbackup.data.AppDatabase
import dev.safbackup.data.BackupJob
import dev.safbackup.data.V1Migration
import dev.safbackup.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val db by lazy { AppDatabase.getInstance(this) }
    private lateinit var adapter: BackupJobAdapter
    private val activeRunningJobs = mutableSetOf<String>()

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            updateNotificationBanner(!isGranted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Apply WindowInsets for status bar (top) and navigation bar (bottom)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val sysBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            b.headerContainer.updatePadding(top = sysBars.top)
            b.bottomBar.updatePadding(bottom = sysBars.bottom)
            insets
        }

        setupRecyclerView()

        b.addJobButton.setOnClickListener { openAddJob() }
        b.emptyAddButton.setOnClickListener { openAddJob() }
        b.backupAllButton.setOnClickListener { enqueueBackupAll() }
        b.notifSettingsButton.setOnClickListener { openAppNotificationSettings() }

        checkNotificationPermission()

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                V1Migration.checkAndMigrate(applicationContext)
            }
            observeJobs()
        }
    }

    private fun openAddJob() {
        startActivity(Intent(this, EditJobActivity::class.java))
    }

    private fun setupRecyclerView() {
        adapter = BackupJobAdapter(
            onRunNow = { job -> enqueueBackupJob(job) },
            onEdit = { job ->
                val intent = Intent(this, EditJobActivity::class.java).apply {
                    putExtra(EditJobActivity.EXTRA_JOB_ID, job.id)
                }
                startActivity(intent)
            },
            onDelete = { job -> confirmDeleteJob(job) },
            onToggleDaily = { job -> toggleDailyBackup(job) }
        )
        b.recyclerView.layoutManager = LinearLayoutManager(this)
        b.recyclerView.adapter = adapter
    }

    private fun observeJobs() {
        db.backupJobDao().getAllLiveData().observe(this) { jobs ->
            adapter.submitList(jobs)
            adapter.setRunningJobs(activeRunningJobs)

            if (jobs.isEmpty()) {
                b.emptyView.visibility = View.VISIBLE
                b.recyclerView.visibility = View.GONE
                b.backupAllButton.visibility = View.GONE
            } else {
                b.emptyView.visibility = View.GONE
                b.recyclerView.visibility = View.VISIBLE
                b.backupAllButton.visibility = View.VISIBLE
            }
        }
    }

    private fun enqueueBackupJob(job: BackupJob) {
        if (activeRunningJobs.contains(job.id)) return

        val req = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(BackupWorker.buildConstraints(job.wifiOnly, job.chargingOnly))
            .setInputData(workDataOf(BackupWorker.KEY_JOB_ID to job.id))
            .build()

        activeRunningJobs.add(job.id)
        adapter.setRunningJobs(activeRunningJobs)

        val wm = WorkManager.getInstance(this)
        wm.enqueue(req)

        wm.getWorkInfoByIdLiveData(req.id).observe(this) { info ->
            if (info == null) return@observe
            if (info.state == WorkInfo.State.SUCCEEDED ||
                info.state == WorkInfo.State.FAILED ||
                info.state == WorkInfo.State.CANCELLED) {
                activeRunningJobs.remove(job.id)
                adapter.setRunningJobs(activeRunningJobs)
            }
        }

        Toast.makeText(this, getString(R.string.toast_job_queued, job.name), Toast.LENGTH_SHORT).show()
    }

    private fun enqueueBackupAll() {
        lifecycleScope.launch {
            val jobs = withContext(Dispatchers.IO) { db.backupJobDao().getAll() }
            if (jobs.isEmpty()) return@launch

            for (job in jobs) {
                enqueueBackupJob(job)
            }
            Toast.makeText(applicationContext, R.string.toast_backup_all_queued, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleDailyBackup(job: BackupJob) {
        lifecycleScope.launch {
            val updated = job.copy(autoDaily = !job.autoDaily)
            withContext(Dispatchers.IO) {
                db.backupJobDao().update(updated)
                val workName = BackupWorker.getWorkName(job.id)
                if (updated.autoDaily) {
                    val req = androidx.work.PeriodicWorkRequestBuilder<BackupWorker>(24, java.util.concurrent.TimeUnit.HOURS)
                        .setConstraints(BackupWorker.buildConstraints(updated.wifiOnly, updated.chargingOnly))
                        .setInputData(workDataOf(BackupWorker.KEY_JOB_ID to updated.id))
                        .build()
                    WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                        workName,
                        androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                        req
                    )
                } else {
                    WorkManager.getInstance(applicationContext).cancelUniqueWork(workName)
                }
            }
        }
    }

    private fun confirmDeleteJob(job: BackupJob) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_delete_title, job.name))
            .setMessage(R.string.dialog_delete_msg)
            .setPositiveButton(R.string.btn_delete) { _, _ -> deleteBackupJob(job) }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun deleteBackupJob(job: BackupJob) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                db.backupJobDao().delete(job)

                WorkManager.getInstance(applicationContext)
                    .cancelUniqueWork(BackupWorker.getWorkName(job.id))

                val remainingJobs = db.backupJobDao().getAll()
                releaseGrantIfUnused(job.sourceUri, isSource = true, remainingJobs)
                releaseGrantIfUnused(job.destUri, isSource = false, remainingJobs)
            }
            Toast.makeText(applicationContext, getString(R.string.toast_job_deleted, job.name), Toast.LENGTH_SHORT).show()
        }
    }

    private fun releaseGrantIfUnused(uriString: String, isSource: Boolean, remainingJobs: List<BackupJob>) {
        val uri = Uri.parse(uriString)
        val inUse = if (isSource) {
            remainingJobs.any { it.sourceUri == uriString }
        } else {
            remainingJobs.any { it.destUri == uriString }
        }

        if (!inUse) {
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

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                updateNotificationBanner(false)
            }
        }
    }

    private fun updateNotificationBanner(show: Boolean) {
        b.notifBanner.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun openAppNotificationSettings() {
        val intent = Intent().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            } else {
                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                data = Uri.fromParts("package", packageName, null)
            }
        }
        startActivity(intent)
    }
}

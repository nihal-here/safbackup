package dev.safbackup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.OneTimeWorkRequestBuilder
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

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) {
                b.notifBannerText.visibility = View.VISIBLE
            } else {
                b.notifBannerText.visibility = View.GONE
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupRecyclerView()

        b.addJobButton.setOnClickListener {
            startActivity(Intent(this, EditJobActivity::class.java))
        }

        b.backupAllButton.setOnClickListener {
            enqueueBackupAll()
        }

        checkNotificationPermission()

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                V1Migration.checkAndMigrate(applicationContext)
            }
            observeJobs()
        }
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
            onDelete = { job -> deleteBackupJob(job) }
        )
        b.recyclerView.layoutManager = LinearLayoutManager(this)
        b.recyclerView.adapter = adapter
    }

    private fun observeJobs() {
        db.backupJobDao().getAllLiveData().observe(this) { jobs ->
            adapter.submitList(jobs)
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
        val req = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(BackupWorker.buildConstraints(job.wifiOnly, job.chargingOnly))
            .setInputData(workDataOf(BackupWorker.KEY_JOB_ID to job.id))
            .build()

        WorkManager.getInstance(this).enqueue(req)
        Toast.makeText(this, getString(R.string.toast_job_queued, job.name), Toast.LENGTH_SHORT).show()
    }

    private fun enqueueBackupAll() {
        lifecycleScope.launch {
            val jobs = withContext(Dispatchers.IO) { db.backupJobDao().getAll() }
            if (jobs.isEmpty()) return@launch

            for (job in jobs) {
                val req = OneTimeWorkRequestBuilder<BackupWorker>()
                    .setConstraints(BackupWorker.buildConstraints(job.wifiOnly, job.chargingOnly))
                    .setInputData(workDataOf(BackupWorker.KEY_JOB_ID to job.id))
                    .build()

                WorkManager.getInstance(applicationContext).enqueue(req)
            }
            Toast.makeText(applicationContext, R.string.toast_backup_all_queued, Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteBackupJob(job: BackupJob) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                // Delete from DB
                db.backupJobDao().delete(job)

                // Cancel WorkManager schedule
                WorkManager.getInstance(applicationContext)
                    .cancelUniqueWork(BackupWorker.getWorkName(job.id))

                // Release SAF permissions ONLY if no other job uses them
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
                b.notifBannerText.visibility = View.GONE
            }
        }
    }
}

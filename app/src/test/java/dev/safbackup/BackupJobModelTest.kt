package dev.safbackup

import dev.safbackup.data.BackupJob
import org.junit.Assert.*
import org.junit.Test

class BackupJobModelTest {

    @Test
    fun testDefaultBackupJobValues() {
        val job = BackupJob(
            name = "Test Job",
            sourceUri = "content://source",
            destUri = "content://dest"
        )

        assertEquals("Test Job", job.name)
        assertFalse(job.isRecursive)
        assertTrue(job.wifiOnly)
        assertFalse(job.chargingOnly)
        assertTrue(job.autoDaily)
        assertEquals(BackupJob.NOTIF_FAILURES_ONLY, job.notificationPref)
        assertEquals(BackupJob.STATUS_NEVER_RUN, job.lastStatus)
        assertNull(job.lastAttemptTime)
        assertNull(job.lastSuccessfulTime)
        assertTrue(job.getErrorList().isEmpty())
    }

    @Test
    fun testGetErrorListSplitsNewlines() {
        val job = BackupJob(
            name = "Test Job",
            sourceUri = "content://source",
            destUri = "content://dest",
            lastErrors = "Error 1: File missing\nError 2: Permission denied\n"
        )

        val errors = job.getErrorList()
        assertEquals(2, errors.size)
        assertEquals("Error 1: File missing", errors[0])
        assertEquals("Error 2: Permission denied", errors[1])
    }
}

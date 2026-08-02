package dev.safbackup

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkNameGeneratorTest {

    @Test
    fun testUniqueWorkNameFormat() {
        val jobId = "a1b2c3d4-e5f6-7890"
        val expected = "backup_daily_a1b2c3d4-e5f6-7890"
        val actual = BackupWorker.getWorkName(jobId)
        assertEquals(expected, actual)
    }

    @Test
    fun testDifferentJobsHaveDifferentWorkNames() {
        val name1 = BackupWorker.getWorkName("job1")
        val name2 = BackupWorker.getWorkName("job2")
        assert(name1 != name2)
    }
}

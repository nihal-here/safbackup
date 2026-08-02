package dev.safbackup

import android.view.LayoutInflater
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LayoutInflationTest {
    @Test
    fun polishedLayoutsInflateUnderSafBackupTheme() {
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(),
            R.style.Theme_SAFBackup
        )
        val inflater = LayoutInflater.from(context)

        inflater.inflate(R.layout.activity_main, null, false)
        inflater.inflate(R.layout.activity_edit_job, null, false)
        inflater.inflate(R.layout.item_backup_job, null, false)
    }
}

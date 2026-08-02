package dev.safbackup

import android.content.Context

/**
 * Holds the result of a backup run for display in the UI.
 * Persisted in SharedPreferences so the user can see status across app restarts.
 */
data class BackupResult(
    val timestampMillis: Long,
    val copied: Int,
    val skipped: Int,
    val failed: Int,
    val errors: List<String>
) {
    companion object {
        private const val PREFS_NAME = "backup_status"

        fun save(context: Context, result: BackupResult) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong("timestamp", result.timestampMillis)
                .putInt("copied", result.copied)
                .putInt("skipped", result.skipped)
                .putInt("failed", result.failed)
                .putString("errors", result.errors.joinToString("\n"))
                .apply()
        }

        fun load(context: Context): BackupResult? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val ts = prefs.getLong("timestamp", -1L)
            if (ts == -1L) return null
            return BackupResult(
                timestampMillis = ts,
                copied = prefs.getInt("copied", 0),
                skipped = prefs.getInt("skipped", 0),
                failed = prefs.getInt("failed", 0),
                errors = (prefs.getString("errors", "") ?: "")
                    .split("\n")
                    .filter { it.isNotBlank() }
            )
        }
    }
}

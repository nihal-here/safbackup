package dev.safbackup

import org.junit.Assert.*
import org.junit.Test

class ConflictDecisionTest {

    enum class EntryType { FILE, DIRECTORY }
    enum class CompareVerdict { MATCH, MISMATCH, CONFLICT_TYPE_MISMATCH }

    private fun evaluateConflict(
        srcType: EntryType,
        dstType: EntryType?,
        srcSize: Long,
        dstSize: Long,
        srcHash: String?,
        dstHash: String?
    ): CompareVerdict {
        if (dstType == null) return CompareVerdict.MISMATCH
        if (srcType != dstType) return CompareVerdict.CONFLICT_TYPE_MISMATCH

        if (srcType == EntryType.DIRECTORY) return CompareVerdict.MATCH

        if (srcSize > 0 && dstSize > 0 && srcSize != dstSize) {
            return CompareVerdict.MISMATCH
        }

        if (srcHash != null && dstHash != null) {
            return if (srcHash == dstHash) CompareVerdict.MATCH else CompareVerdict.MISMATCH
        }

        return CompareVerdict.MATCH
    }

    @Test
    fun testFileVsDirectoryConflict() {
        val verdict = evaluateConflict(
            srcType = EntryType.DIRECTORY,
            dstType = EntryType.FILE,
            srcSize = 0, dstSize = 100,
            srcHash = null, dstHash = null
        )
        assertEquals(CompareVerdict.CONFLICT_TYPE_MISMATCH, verdict)
    }

    @Test
    fun testIdenticalFileMatch() {
        val verdict = evaluateConflict(
            srcType = EntryType.FILE,
            dstType = EntryType.FILE,
            srcSize = 1024, dstSize = 1024,
            srcHash = "abc123hash", dstHash = "abc123hash"
        )
        assertEquals(CompareVerdict.MATCH, verdict)
    }

    @Test
    fun testModifiedFileMismatch() {
        val verdict = evaluateConflict(
            srcType = EntryType.FILE,
            dstType = EntryType.FILE,
            srcSize = 1024, dstSize = 2048,
            srcHash = "hash1", dstHash = "hash2"
        )
        assertEquals(CompareVerdict.MISMATCH, verdict)
    }

    @Test
    fun testRelativePathConstruction() {
        fun buildPath(parent: String, child: String): String {
            return if (parent.isEmpty()) child else "$parent/$child"
        }

        assertEquals("a.txt", buildPath("", "a.txt"))
        assertEquals("FolderA/b.txt", buildPath("FolderA", "b.txt"))
        assertEquals("FolderA/Nested/c.txt", buildPath("FolderA/Nested", "c.txt"))
    }
}

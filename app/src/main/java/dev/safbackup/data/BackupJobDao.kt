package dev.safbackup.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface BackupJobDao {
    @Query("SELECT * FROM backup_jobs ORDER BY name ASC")
    fun getAllLiveData(): LiveData<List<BackupJob>>

    @Query("SELECT * FROM backup_jobs ORDER BY name ASC")
    suspend fun getAll(): List<BackupJob>

    @Query("SELECT * FROM backup_jobs WHERE id = :id")
    suspend fun getById(id: String): BackupJob?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: BackupJob)

    @Update
    suspend fun update(job: BackupJob)

    @Delete
    suspend fun delete(job: BackupJob)

    @Query("DELETE FROM backup_jobs WHERE id = :id")
    suspend fun deleteById(id: String)
}

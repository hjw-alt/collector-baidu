package com.tigercode.evcollector.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ScanTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: ScanTaskEntity)

    @Query(
        """
        SELECT * FROM scan_tasks
        WHERE status = 'pending' AND attempts < maxAttempts
        ORDER BY createdAt ASC
        LIMIT 1
        """
    )
    suspend fun nextPending(): ScanTaskEntity?

    @Query(
        """
        UPDATE scan_tasks
        SET status = :status, leaseToken = :leaseToken,
            leaseExpiresAt = :leaseExpiresAt, attempts = attempts + 1,
            updatedAt = :updatedAt
        WHERE stationId = :stationId AND status = 'pending'
          AND attempts < maxAttempts
        """
    )
    suspend fun markClaimed(
        stationId: String,
        status: String,
        leaseToken: String,
        leaseExpiresAt: Long,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE scan_tasks
        SET status = 'succeeded', resultJson = :resultJson,
            lastError = '', updatedAt = :updatedAt
        WHERE stationId = :stationId AND leaseToken = :leaseToken
        """
    )
    suspend fun markSucceeded(stationId: String, leaseToken: String, resultJson: String, updatedAt: Long): Int

    @Query(
        """
        UPDATE scan_tasks
        SET status = 'failed', lastError = :error, updatedAt = :updatedAt
        WHERE stationId = :stationId AND leaseToken = :leaseToken
        """
    )
    suspend fun markFailed(stationId: String, leaseToken: String, error: String, updatedAt: Long): Int

    @Query("SELECT COUNT(*) FROM scan_tasks")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM scan_tasks WHERE status = 'pending' AND attempts < maxAttempts")
    suspend fun countPending(): Int

    @Query("SELECT COUNT(*) FROM scan_tasks WHERE status = 'succeeded'")
    suspend fun countSucceeded(): Int

    @Query("SELECT COUNT(*) FROM scan_tasks WHERE status = 'failed'")
    suspend fun countFailed(): Int

    @Query(
        "UPDATE scan_tasks SET status = 'pending', leaseToken = '', leaseExpiresAt = 0, " +
            "updatedAt = :now WHERE stationId = :stationId AND leaseToken = :leaseToken"
    )
    suspend fun release(stationId: String, leaseToken: String, now: Long): Int

    @Query("SELECT COUNT(*) FROM scan_tasks WHERE status = 'skipped'")
    suspend fun countSkipped(): Int

    @Query(
        "UPDATE scan_tasks SET status = 'skipped', lastError = :note, updatedAt = :now " +
            "WHERE stationId = :stationId AND leaseToken = :leaseToken"
    )
    suspend fun markSkipped(stationId: String, leaseToken: String, note: String, now: Long): Int

    @Query(
        "UPDATE scan_tasks SET status = 'pending', attempts = 0, leaseToken = '', " +
            "leaseExpiresAt = 0, lastError = '', updatedAt = :now " +
            "WHERE status IN ('running', 'failed')"
    )
    suspend fun resetUnfinished(now: Long): Int
}

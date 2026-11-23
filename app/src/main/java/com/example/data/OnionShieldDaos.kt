package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url)")
    fun observeIsBookmarked(url: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun deleteByUrl(url: String)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history_entries ORDER BY visitedAt DESC LIMIT 200")
    fun observeAll(): Flow<List<HistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: HistoryEntity)

    @Query("DELETE FROM history_entries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM history_entries")
    suspend fun clearAll()
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(download: DownloadEntity): Long

    @Query("UPDATE downloads SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("UPDATE downloads SET fileName = :fileName, url = :url, status = :status, systemDownloadId = :systemDownloadId, sizeBytes = :sizeBytes WHERE id = :id")
    suspend fun updateDetails(
        id: Long,
        fileName: String,
        url: String,
        status: String,
        systemDownloadId: Long,
        sizeBytes: Long,
    )

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM downloads")
    suspend fun clearAll()
}

@Dao
interface VaultDao {
    @Query("SELECT * FROM vault_entries ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<VaultEntryEntity>>

    @Query("SELECT COUNT(*) FROM vault_entries")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: VaultEntryEntity): Long

    @Query("DELETE FROM vault_entries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM vault_entries")
    suspend fun clearAll()
}

@Dao
interface PinnedAppDao {
    @Query("SELECT * FROM pinned_apps ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<PinnedAppEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: PinnedAppEntity): Long

    @Query("DELETE FROM pinned_apps WHERE url = :url")
    suspend fun deleteByUrl(url: String)

    @Query("DELETE FROM pinned_apps WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM pinned_apps")
    suspend fun count(): Int
}

@Dao
interface SessionRoundLogDao {
    @Query("SELECT * FROM session_round_logs ORDER BY createdAt DESC LIMIT 250")
    fun observeAll(): Flow<List<SessionRoundLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SessionRoundLogEntity): Long

    @Query("DELETE FROM session_round_logs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM session_round_logs")
    suspend fun clearAll()
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM encrypted_search_history ORDER BY lastSearchedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 25): Flow<List<EncryptedSearchHistoryEntity>>

    @Query("SELECT * FROM encrypted_search_history WHERE queryHash = :queryHash LIMIT 1")
    suspend fun findByHash(queryHash: String): EncryptedSearchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: EncryptedSearchHistoryEntity): Long

    @Query("DELETE FROM encrypted_search_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM encrypted_search_history")
    suspend fun clearAll()
}

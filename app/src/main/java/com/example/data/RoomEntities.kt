package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val isOnion: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "history_entries")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val isOnion: Boolean,
    val visitedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val url: String,
    val mimeType: String,
    val status: String, // COMPLETED, DOWNLOADING, CANCELLED, FAILED
    val systemDownloadId: Long = -1L,
    val isOnionSource: Boolean = false,
    val sizeBytes: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "vault_entries")
data class VaultEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryType: String,
    val titleCiphertext: String,
    val secretCiphertext: String,
    val urlCiphertext: String,
    val isOnion: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "pinned_apps")
data class PinnedAppEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val isOnion: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "session_round_logs")
data class SessionRoundLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val siteHost: String,
    val betAmount: Double,
    val crashMultiplier: Double,
    val cashoutMultiplier: Double? = null,
    val netProfit: Double,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "encrypted_search_history",
    indices = [Index(value = ["queryHash"], unique = true)],
)
data class EncryptedSearchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val queryHash: String,
    val queryCiphertext: String,
    val resolvedUrlCiphertext: String,
    val isOnion: Boolean = false,
    val lastSearchedAt: Long = System.currentTimeMillis(),
)

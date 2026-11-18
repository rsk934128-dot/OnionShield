package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Database(
    entities = [
        BookmarkEntity::class,
        HistoryEntity::class,
        DownloadEntity::class,
        VaultEntryEntity::class,
        PinnedAppEntity::class,
        SessionRoundLogEntity::class,
        EncryptedSearchHistoryEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class OnionShieldDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao
    abstract fun downloadDao(): DownloadDao
    abstract fun vaultDao(): VaultDao
    abstract fun pinnedAppDao(): PinnedAppDao
    abstract fun sessionRoundLogDao(): SessionRoundLogDao
    abstract fun searchHistoryDao(): SearchHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: OnionShieldDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `vault_entries` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `entryType` TEXT NOT NULL,
                        `titleCiphertext` TEXT NOT NULL,
                        `secretCiphertext` TEXT NOT NULL,
                        `urlCiphertext` TEXT NOT NULL,
                        `isOnion` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pinned_apps` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        `isOnion` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `session_round_logs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `siteHost` TEXT NOT NULL,
                        `betAmount` REAL NOT NULL,
                        `crashMultiplier` REAL NOT NULL,
                        `cashoutMultiplier` REAL,
                        `netProfit` REAL NOT NULL,
                        `note` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `encrypted_search_history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `queryHash` TEXT NOT NULL,
                        `queryCiphertext` TEXT NOT NULL,
                        `resolvedUrlCiphertext` TEXT NOT NULL,
                        `isOnion` INTEGER NOT NULL,
                        `lastSearchedAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_encrypted_search_history_queryHash`
                    ON `encrypted_search_history` (`queryHash`)
                    """.trimIndent(),
                )
            }
        }

        fun getInstance(context: Context): OnionShieldDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    OnionShieldDatabase::class.java,
                    "onionshield_local.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

class BrowserLibraryRepository(private val database: OnionShieldDatabase) {
    private val bookmarkDao = database.bookmarkDao()
    private val historyDao = database.historyDao()
    private val downloadDao = database.downloadDao()
    private val vaultDao = database.vaultDao()
    private val pinnedAppDao = database.pinnedAppDao()
    private val sessionRoundLogDao = database.sessionRoundLogDao()

    val bookmarks: Flow<List<BookmarkEntity>> = bookmarkDao.observeAll()
    val history: Flow<List<HistoryEntity>> = historyDao.observeAll()
    val downloads: Flow<List<DownloadEntity>> = downloadDao.observeAll()
    val vaultEntries: Flow<List<VaultEntryEntity>> = vaultDao.observeAll()
    val vaultCount: Flow<Int> = vaultDao.observeCount()
    val pinnedApps: Flow<List<PinnedAppEntity>> = pinnedAppDao.observeAll()
    val sessionRoundLogs: Flow<List<SessionRoundLogEntity>> = sessionRoundLogDao.observeAll()

    fun observeIsBookmarked(url: String): Flow<Boolean> = bookmarkDao.observeIsBookmarked(url)

    suspend fun toggleBookmark(title: String, url: String) {
        if (url == ABOUT_START_URL || url.isBlank()) return
        val existing = bookmarkDao
        // Use simple delete-or-insert via query check; insert replaces on conflict if same pk,
        // so we delete by url if already present via caller or check.
        bookmarkDao.deleteByUrl(url)
        bookmarkDao.insert(
            BookmarkEntity(
                title = title.ifBlank { url },
                url = url,
                isOnion = url.contains(".onion", ignoreCase = true),
            )
        )
    }

    suspend fun addBookmark(title: String, url: String) {
        if (url == ABOUT_START_URL || url.isBlank()) return
        bookmarkDao.deleteByUrl(url)
        bookmarkDao.insert(
            BookmarkEntity(
                title = title.ifBlank { url },
                url = url,
                isOnion = url.contains(".onion", ignoreCase = true),
            )
        )
    }

    suspend fun removeBookmarkByUrl(url: String) = bookmarkDao.deleteByUrl(url)

    suspend fun removeBookmarkById(id: Long) = bookmarkDao.deleteById(id)

    suspend fun recordHistory(title: String, url: String) {
        if (url == ABOUT_START_URL || url.isBlank()) return
        if (url.contains(".onion", ignoreCase = true)) return // Never persist .onion visits
        historyDao.insert(
            HistoryEntity(
                title = title.ifBlank { url },
                url = url,
                isOnion = false,
            )
        )
    }

    suspend fun deleteHistoryItem(id: Long) = historyDao.deleteById(id)

    suspend fun clearAllHistory() = historyDao.clearAll()

    suspend fun recordDownload(download: DownloadEntity): Long = downloadDao.insert(download)

    suspend fun updateDownloadStatus(id: Long, status: String) = downloadDao.updateStatus(id, status)

    suspend fun deleteDownload(id: Long) = downloadDao.deleteById(id)

    suspend fun clearDownloads() = downloadDao.clearAll()

    suspend fun insertVaultEntry(entry: VaultEntryEntity): Long = vaultDao.insert(entry)

    suspend fun deleteVaultEntry(id: Long) = vaultDao.deleteById(id)

    suspend fun clearVault() = vaultDao.clearAll()

    suspend fun ensureDefaultHomeAppsSeeded() {
        if (pinnedAppDao.count() > 0) return
        val defaults = listOf(
            PinnedAppEntity(title = "YouTube", url = "https://m.youtube.com", isOnion = false),
            PinnedAppEntity(title = "DuckDuckGo", url = "https://duckduckgo.com", isOnion = false),
            PinnedAppEntity(title = "Wikipedia", url = "https://en.m.wikipedia.org", isOnion = false),
            PinnedAppEntity(title = "GitHub", url = "https://github.com", isOnion = false),
        )
        defaults.forEach { pinnedAppDao.insert(it) }
    }

    suspend fun pinHomeApp(title: String, url: String) {
        if (url == ABOUT_START_URL || url.isBlank()) return
        pinnedAppDao.deleteByUrl(url)
        pinnedAppDao.insert(
            PinnedAppEntity(
                title = title.ifBlank { UriFallbackTitle(url) },
                url = url,
                isOnion = url.contains(".onion", ignoreCase = true),
            ),
        )
    }

    suspend fun removePinnedApp(id: Long) = pinnedAppDao.deleteById(id)

    suspend fun insertSessionRoundLog(entry: SessionRoundLogEntity): Long = sessionRoundLogDao.insert(entry)

    suspend fun deleteSessionRoundLog(id: Long) = sessionRoundLogDao.deleteById(id)

    suspend fun clearSessionRoundLogs() = sessionRoundLogDao.clearAll()

    private fun UriFallbackTitle(url: String): String {
        return runCatching {
            android.net.Uri.parse(url).host?.removePrefix("www.")?.removePrefix("m.") ?: url
        }.getOrDefault(url)
    }
}

package com.example.data.repository

import android.content.Context
import com.example.data.ABOUT_START_URL
import com.example.data.EncryptedSearchHistoryEntity
import com.example.data.OnionShieldDatabase
import com.example.data.SearchHistoryDao
import com.example.security.VaultCryptoManager
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class DecryptedSearchHistoryItem(
    val id: Long,
    val query: String,
    val resolvedUrl: String,
    val isOnion: Boolean,
    val lastSearchedAt: Long,
)

/**
 * Local-only Room repository persisting recent omnibox search queries and resolved URLs
 * encrypted with AES-256-GCM (Android Keystore) and keyed HMAC-SHA256 deduplication.
 */
class EncryptedSearchHistoryRepository(
    private val context: Context,
    private val searchHistoryDao: SearchHistoryDao,
    private val encryptionKey: SecretKey = VaultCryptoManager.getOrCreateSearchHistoryKey(context),
) {
    constructor(context: Context) : this(
        context = context.applicationContext,
        searchHistoryDao = OnionShieldDatabase.getInstance(context).searchHistoryDao(),
    )

    fun observeRecentSearches(limit: Int = 25): Flow<List<DecryptedSearchHistoryItem>> {
        return searchHistoryDao.observeRecent(limit)
            .map { entities ->
                entities.mapNotNull { entity -> decryptEntity(entity) }
            }
            .flowOn(Dispatchers.Default)
    }

    suspend fun recordSearch(
        rawQuery: String,
        resolvedUrl: String,
        isOnion: Boolean,
        timestampMs: Long = System.currentTimeMillis(),
    ): Long = withContext(Dispatchers.Default) {
        val cleanQuery = rawQuery.trim()
        val cleanUrl = resolvedUrl.trim()
        if (cleanQuery.isEmpty() || cleanUrl.isEmpty() || cleanUrl == ABOUT_START_URL) {
            return@withContext -1L
        }

        val boundedQuery = cleanQuery.take(MAX_QUERY_LENGTH)
        val boundedUrl = cleanUrl.take(MAX_URL_LENGTH)
        val hash = VaultCryptoManager.computeSearchHistoryHmac(boundedQuery, context)
        val existing = searchHistoryDao.findByHash(hash)

        val entity = EncryptedSearchHistoryEntity(
            id = existing?.id ?: 0L,
            queryHash = hash,
            queryCiphertext = VaultCryptoManager.encrypt(boundedQuery, encryptionKey),
            resolvedUrlCiphertext = VaultCryptoManager.encrypt(boundedUrl, encryptionKey),
            isOnion = isOnion,
            lastSearchedAt = timestampMs,
        )
        searchHistoryDao.upsert(entity)
    }

    suspend fun deleteSearch(id: Long) {
        searchHistoryDao.deleteById(id)
    }

    suspend fun clearAll() {
        searchHistoryDao.clearAll()
    }

    private fun decryptEntity(entity: EncryptedSearchHistoryEntity): DecryptedSearchHistoryItem? {
        return runCatching {
            val query = VaultCryptoManager.decrypt(entity.queryCiphertext, encryptionKey)
            val resolvedUrl = VaultCryptoManager.decrypt(entity.resolvedUrlCiphertext, encryptionKey)
            if (query.isBlank()) return null
            DecryptedSearchHistoryItem(
                id = entity.id,
                query = query,
                resolvedUrl = resolvedUrl,
                isOnion = entity.isOnion,
                lastSearchedAt = entity.lastSearchedAt,
            )
        }.getOrNull()
    }

    companion object {
        private const val MAX_QUERY_LENGTH = 300
        private const val MAX_URL_LENGTH = 1000

        fun filterSuggestions(
            items: List<DecryptedSearchHistoryItem>,
            omniboxInput: String,
            maxResults: Int = 6,
        ): List<DecryptedSearchHistoryItem> {
            if (items.isEmpty()) return emptyList()
            val needle = omniboxInput.trim().lowercase()
            val matched = if (needle.isEmpty()) {
                items
            } else {
                items.filter { item ->
                    item.query.lowercase().contains(needle) ||
                        item.resolvedUrl.lowercase().contains(needle)
                }
            }
            return matched.take(maxResults)
        }
    }
}

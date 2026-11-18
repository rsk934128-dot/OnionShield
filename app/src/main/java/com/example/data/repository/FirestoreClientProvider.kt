package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.R
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings

/**
 * Single entry point for obtaining the configured enterprise Firestore client
 * with explicit 100 MB persistent offline disk cache enabled before any query runs.
 */
object FirestoreClientProvider {

    private const val TAG = "FirestoreClient"
    const val OFFLINE_CACHE_SIZE_BYTES = 100L * 1024L * 1024L

    @Volatile
    private var configuredDb: FirebaseFirestore? = null

    fun buildOfflinePersistentSettings(): FirebaseFirestoreSettings {
        val persistentCache = PersistentCacheSettings.newBuilder()
            .setSizeBytes(OFFLINE_CACHE_SIZE_BYTES)
            .build()
        return FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(persistentCache)
            .build()
    }

    fun getInstance(context: Context): FirebaseFirestore {
        configuredDb?.let { return it }
        return synchronized(this) {
            configuredDb?.let { return@synchronized it }
            val appContext = context.applicationContext
            val databaseId = appContext.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(databaseId)
            try {
                db.firestoreSettings = buildOfflinePersistentSettings()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Firestore already initialized before offline settings could be applied", e)
            }
            configuredDb = db
            db
        }
    }
}

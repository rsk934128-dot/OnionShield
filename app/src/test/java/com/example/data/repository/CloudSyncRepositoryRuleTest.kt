package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSyncRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun upsertAndObserveSyncedLinks_authenticatedOwner_succeeds() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repository = CloudSyncRepository(firestore, auth)
        val uniqueUrl = "https://example.org/app/${UUID.randomUUID()}"

        val createResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.upsertSyncedLink(
                title = "Example App",
                url = uniqueUrl,
                kind = SyncedLink.KIND_HOME_APP,
            )
        }
        assertTrue(createResult.isSuccess)
        val linkId = createResult.getOrThrow()

        val observed = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeSyncedLinks(uid).first { list -> list.any { it.id == linkId } }
        }
        assertTrue(observed.any { it.id == linkId && it.title == "Example App" })

        val deleteResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.deleteSyncedLink(linkId)
        }
        assertTrue(deleteResult.isSuccess)
    }

    @Test
    fun getSyncedLink_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = CloudSyncRepository(firestore, auth)
        val linkId = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.upsertSyncedLink(
                title = "Alice Private Bookmark",
                url = "https://en.wikipedia.org/wiki/${UUID.randomUUID()}",
                kind = SyncedLink.KIND_BOOKMARK,
            ).getOrThrow()
        }

        val bobUid = signInTestUser(BOB_EMAIL)
        val bobRepo = CloudSyncRepository(firestore, auth)

        val bobDirectGetError = runCatching {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("users")
                    .document(aliceUid)
                    .collection("synced_links")
                    .document(linkId)
                    .get()
                    .await()
            }
        }.exceptionOrNull()
        assertEquals(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            bobDirectGetError?.unwrapFirestoreException()?.code,
        )

        val unauthResult = runCatching {
            withTimeout(FLOW_TIMEOUT_MS) {
                bobRepo.observeSyncedLinks("different_user_uid_$bobUid").first()
            }
        }.exceptionOrNull()
        val firestoreEx = unauthResult?.unwrapFirestoreException()
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreEx?.code)
    }

    @Test
    fun observeSyncedLinks_unauthenticatedCaller_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repository = CloudSyncRepository(firestore, auth)
        val error = runCatching {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeSyncedLinks("alice_uid").first()
            }
        }.exceptionOrNull()
        val firestoreEx = error?.unwrapFirestoreException()
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreEx?.code)
    }

    @Test
    fun upsertSyncedHistory_andPersistentOfflineSettings_configuredCorrectly() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repository = CloudSyncRepository(firestore, auth)
        val historyUrl = "https://www.torproject.org/${UUID.randomUUID()}"

        val result = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.upsertSyncedLink(
                title = "Tor Project",
                url = historyUrl,
                kind = SyncedLink.KIND_HISTORY,
            )
        }
        assertTrue(result.isSuccess)
        val linkId = result.getOrThrow()

        val snap = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeSyncedLinksWithMetadata(uid).first { s -> s.items.any { it.id == linkId } }
        }
        assertTrue(snap.items.any { it.id == linkId && it.kind == SyncedLink.KIND_HISTORY })

        val offlineSettings = FirestoreClientProvider.buildOfflinePersistentSettings()
        assertTrue(offlineSettings.cacheSettings is com.google.firebase.firestore.PersistentCacheSettings)
        val persistent = offlineSettings.cacheSettings as com.google.firebase.firestore.PersistentCacheSettings
        assertEquals(FirestoreClientProvider.OFFLINE_CACHE_SIZE_BYTES, persistent.sizeBytes)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.deleteSyncedLink(linkId)
        }
        Unit
    }

    private companion object {
        const val ALICE_EMAIL = "alice@test.com"
        const val BOB_EMAIL = "bob@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}

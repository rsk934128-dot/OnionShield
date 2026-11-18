package com.example.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.browser.TrackerAndUrlEngine
import com.example.data.BookmarkEntity
import com.example.data.HistoryEntity
import com.example.data.PinnedAppEntity
import com.example.data.repository.CloudSyncRepository
import com.example.data.repository.FirestoreClientProvider
import com.example.data.repository.RemoteSessionRound
import com.example.data.repository.SessionMonitorFirestoreRepository
import com.example.data.repository.SessionMonitorState
import com.example.data.repository.SyncedLink
import com.example.data.repository.SyncedLinksSnapshot
import com.example.ui.theme.LocalShieldColors
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

sealed interface CloudSyncFeedState {
    data object Loading : CloudSyncFeedState
    data class Success(
        val items: List<SyncedLink>,
        val isFromCache: Boolean = false,
        val hasPendingWrites: Boolean = false,
    ) : CloudSyncFeedState
    data class Error(val message: String) : CloudSyncFeedState
}

class CloudSyncViewModel(
    private val repository: CloudSyncRepository,
    private val monitorRepository: SessionMonitorFirestoreRepository,
    private val currentUserId: String,
) : ViewModel() {

    val feedState: StateFlow<CloudSyncFeedState> = repository.observeSyncedLinksWithMetadata(currentUserId)
        .map<SyncedLinksSnapshot, CloudSyncFeedState> { snap ->
            CloudSyncFeedState.Success(
                items = snap.items,
                isFromCache = snap.isFromCache,
                hasPendingWrites = snap.hasPendingWrites,
            )
        }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            Log.w(TAG, "Error observing synced links", rootError)
            emit(CloudSyncFeedState.Error(rootError.message ?: "Failed to load cloud sync"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = CloudSyncFeedState.Loading,
        )

    val remoteMonitorState: StateFlow<SessionMonitorState?> = monitorRepository
        .observeActiveMonitor(currentUserId)
        .catch { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "Error observing remote session monitor", error)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = null,
        )

    val remoteRounds: StateFlow<List<RemoteSessionRound>> = monitorRepository
        .observeRemoteRounds(currentUserId)
        .catch { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "Error observing remote rounds", error)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList(),
        )

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    fun syncLocalLibrary(
        bookmarks: List<BookmarkEntity>,
        pinnedApps: List<PinnedAppEntity>,
        history: List<HistoryEntity> = emptyList(),
    ) {
        viewModelScope.launch {
            _isSyncing.value = true
            var uploaded = 0
            var skippedOnion = 0

            bookmarks.forEach { bm ->
                if (TrackerAndUrlEngine.isOnionUrl(bm.url)) {
                    skippedOnion++
                } else {
                    val res = repository.upsertSyncedLink(bm.title, bm.url, SyncedLink.KIND_BOOKMARK)
                    if (res.isSuccess) uploaded++
                }
            }

            pinnedApps.forEach { app ->
                if (TrackerAndUrlEngine.isOnionUrl(app.url)) {
                    skippedOnion++
                } else {
                    val res = repository.upsertSyncedLink(app.title, app.url, SyncedLink.KIND_HOME_APP)
                    if (res.isSuccess) uploaded++
                }
            }

            history.take(50).forEach { hist ->
                if (TrackerAndUrlEngine.isOnionUrl(hist.url)) {
                    skippedOnion++
                } else {
                    val res = repository.upsertSyncedLink(hist.title, hist.url, SyncedLink.KIND_HISTORY)
                    if (res.isSuccess) uploaded++
                }
            }

            _isSyncing.value = false
            _statusMessage.value = buildString {
                append("Synced $uploaded clearnet item(s) to Firestore offline cache & cloud.")
                if (skippedOnion > 0) {
                    append(" ($skippedOnion .onion item(s) kept strictly on-device.)")
                }
            }
        }
    }

    fun deleteSyncedLink(linkId: String) {
        viewModelScope.launch {
            val res = repository.deleteSyncedLink(linkId)
            _statusMessage.value = if (res.isSuccess) {
                "Removed item from cloud sync."
            } else {
                res.exceptionOrNull()?.localizedMessage ?: "Delete failed."
            }
        }
    }

    private companion object {
        const val TAG = "CloudSyncVM"
    }
}

private fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { auth ->
        trySend(auth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

@SuppressLint("DiscouragedApi")
internal fun resolveDefaultWebClientId(context: Context): String? {
    val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
    if (resId == 0) return null
    return runCatching { context.getString(resId) }.getOrNull()?.takeIf { it.isNotBlank() }
}

private fun attemptAutoSignIn(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onUnauthenticated: () -> Unit,
    scope: CoroutineScope,
) {
    if (Firebase.auth.currentUser != null) {
        onAuthSuccess()
        return
    }
    val clientId = resolveDefaultWebClientId(context)
    if (clientId == null) {
        onUnauthenticated()
        return
    }

    val googleIdOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(true)
        .setServerClientId(clientId)
        .setAutoSelectEnabled(true)
        .build()

    val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()

    scope.launch {
        try {
            val result = credentialManager.getCredential(context, request)
            val credential = result.credential
            if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                Firebase.auth.signInWithCredential(authCredential).await()
                onAuthSuccess()
            } else {
                onUnauthenticated()
            }
        } catch (_: Exception) {
            onUnauthenticated()
        }
    }
}

private fun onGoogleSignInClicked(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit,
    scope: CoroutineScope,
    onAuthCancelled: () -> Unit = {},
) {
    val clientId = resolveDefaultWebClientId(context)
    if (clientId == null) {
        onAuthError("Google Sign-In configuration missing: default_web_client_id not found")
        return
    }

    val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(signInOption).build()

    scope.launch {
        try {
            val activity = context as? Activity
                ?: throw IllegalStateException("Google Sign-In requires an Activity context.")
            val result = credentialManager.getCredential(activity, request)
            val credential = result.credential
            if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                Firebase.auth.signInWithCredential(authCredential).await()
                onAuthSuccess()
            } else {
                onAuthError("Unexpected credential type returned.")
            }
        } catch (e: GetCredentialCancellationException) {
            Log.w("Auth", "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
            onAuthCancelled()
        } catch (e: Exception) {
            Log.e("Auth", "Google Sign-In failed", e)
            onAuthError(e.localizedMessage ?: "Google Sign-In failed")
        }
    }
}

private fun signOutCloudSync(
    credentialManager: CredentialManager,
    scope: CoroutineScope,
    onSignOutComplete: () -> Unit = {},
) {
    scope.launch {
        withContext(NonCancellable) {
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.w("Auth", "Failed to clear credential state: ${e.message}")
            } finally {
                Firebase.auth.signOut()
                onSignOutComplete()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSyncRoute(
    localBookmarks: List<BookmarkEntity>,
    localPinnedApps: List<PinnedAppEntity>,
    localHistory: List<HistoryEntity> = emptyList(),
    onRestoreToHomeScreen: (title: String, url: String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val auth = remember { Firebase.auth }
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    LaunchedEffect(Unit) {
        if (auth.currentUser == null) {
            attemptAutoSignIn(
                context = context,
                credentialManager = credentialManager,
                onAuthSuccess = {},
                onUnauthenticated = {},
                scope = scope,
            )
        }
    }

    val user = currentUser
    if (user == null) {
        CloudSyncSignInScreen(
            credentialManager = credentialManager,
            onNavigateBack = onNavigateBack,
        )
    } else {
        key(user.uid) {
            val viewModel: CloudSyncViewModel = viewModel(
                key = user.uid,
                factory = viewModelFactory {
                    initializer {
                        val app = checkNotNull(this[APPLICATION_KEY]) {
                            "APPLICATION_KEY missing from CreationExtras"
                        }
                        val db = FirestoreClientProvider.getInstance(app)
                        CloudSyncViewModel(
                            repository = CloudSyncRepository(db, Firebase.auth),
                            monitorRepository = SessionMonitorFirestoreRepository(db, Firebase.auth),
                            currentUserId = user.uid,
                        )
                    }
                },
            )
            CloudSyncAuthenticatedScreen(
                user = user,
                viewModel = viewModel,
                localBookmarks = localBookmarks,
                localPinnedApps = localPinnedApps,
                localHistory = localHistory,
                onRestoreToHomeScreen = onRestoreToHomeScreen,
                onOpenUrl = onOpenUrl,
                onSignOut = {
                    signOutCloudSync(
                        credentialManager = credentialManager,
                        scope = scope,
                    )
                },
                onNavigateBack = onNavigateBack,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudSyncSignInScreen(
    credentialManager: CredentialManager,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shieldColors = LocalShieldColors.current
    var isLoading by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Cloud Sync (Firebase)") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 440.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CloudSync,
                        contentDescription = "Cloud Sync",
                        tint = shieldColors.surfaceAccent,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = "Zero-Trace Bookmarks & Apps Sync",
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "Sign in with Google to back up and restore your clearnet Bookmarks & Home Apps across devices via Firestore. Onion (.onion) addresses are always excluded and remain strictly local.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    if (authError != null) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                        ) {
                            Text(
                                text = authError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                    Button(
                        onClick = {
                            authError = null
                            isLoading = true
                            onGoogleSignInClicked(
                                context = context,
                                credentialManager = credentialManager,
                                onAuthSuccess = { isLoading = false },
                                onAuthError = { msg ->
                                    isLoading = false
                                    authError = msg
                                },
                                scope = scope,
                                onAuthCancelled = { isLoading = false },
                            )
                        },
                        enabled = !isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("google_sign_in_button"),
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Sign in with Google")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudSyncAuthenticatedScreen(
    user: FirebaseUser,
    viewModel: CloudSyncViewModel,
    localBookmarks: List<BookmarkEntity>,
    localPinnedApps: List<PinnedAppEntity>,
    localHistory: List<HistoryEntity>,
    onRestoreToHomeScreen: (title: String, url: String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onSignOut: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val feedState by viewModel.feedState.collectAsStateWithLifecycle()
    val remoteMonitor by viewModel.remoteMonitorState.collectAsStateWithLifecycle()
    val remoteRounds by viewModel.remoteRounds.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val shieldColors = LocalShieldColors.current
    var selectedFilter by remember { mutableStateOf("ALL") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Cloud Sync", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = user.email ?: user.uid,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onSignOut, modifier = Modifier.testTag("cloud_sync_sign_out")) {
                        Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = "Sign out")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                LiveRemoteMonitorCard(
                    monitor = remoteMonitor,
                    recentRounds = remoteRounds,
                )
            }

            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 640.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, shieldColors.surfaceAccent.copy(alpha = 0.4f)),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CloudDone,
                                contentDescription = null,
                                tint = shieldColors.secureGreen,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Sync Bookmarks, History & Home Apps",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = "${localBookmarks.size} bookmark(s) • ${localHistory.size} history • ${localPinnedApps.size} app(s) (100 MB offline disk cache)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (statusMessage != null) {
                            Text(
                                text = statusMessage.orEmpty(),
                                style = MaterialTheme.typography.labelMedium,
                                color = shieldColors.secureGreen,
                            )
                        }
                        Button(
                            onClick = { viewModel.syncLocalLibrary(localBookmarks, localPinnedApps, localHistory) },
                            enabled = !isSyncing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("sync_now_button"),
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Syncing…")
                            } else {
                                Icon(Icons.Rounded.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Sync Now to Firestore")
                            }
                        }
                    }
                }
            }

            when (val state = feedState) {
                is CloudSyncFeedState.Loading -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
                is CloudSyncFeedState.Error -> {
                    item {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 640.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                        ) {
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(14.dp),
                            )
                        }
                    }
                }
                is CloudSyncFeedState.Success -> {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 640.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (state.isFromCache || state.hasPendingWrites) {
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = shieldColors.warningAmberContainer.copy(alpha = 0.55f),
                                    border = BorderStroke(1.dp, shieldColors.warningAmber.copy(alpha = 0.5f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("offline_cache_status_banner"),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.CloudOff,
                                            contentDescription = "Offline cache",
                                            tint = shieldColors.warningAmber,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Text(
                                            text = when {
                                                state.hasPendingWrites -> "Offline mode: changes queued locally and will upload when back online."
                                                else -> "Offline cache active: browsing locally cached synced bookmarks & history."
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilterChip(
                                    selected = selectedFilter == "ALL",
                                    onClick = { selectedFilter = "ALL" },
                                    label = { Text("All (${state.items.size})") },
                                )
                                FilterChip(
                                    selected = selectedFilter == SyncedLink.KIND_BOOKMARK,
                                    onClick = { selectedFilter = SyncedLink.KIND_BOOKMARK },
                                    label = { Text("Bookmarks") },
                                )
                                FilterChip(
                                    selected = selectedFilter == SyncedLink.KIND_HISTORY,
                                    onClick = { selectedFilter = SyncedLink.KIND_HISTORY },
                                    label = { Text("History") },
                                )
                                FilterChip(
                                    selected = selectedFilter == SyncedLink.KIND_HOME_APP,
                                    onClick = { selectedFilter = SyncedLink.KIND_HOME_APP },
                                    label = { Text("Apps") },
                                )
                            }
                        }
                    }

                    val filteredItems = if (selectedFilter == "ALL") {
                        state.items
                    } else {
                        state.items.filter { it.kind == selectedFilter }
                    }

                    if (filteredItems.isEmpty()) {
                        item {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = 640.dp),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surface,
                            ) {
                                Text(
                                    text = "No synced items in this category yet. Tap Sync Now above to cache your Bookmarks, History, and Home Apps for offline & multi-device access.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    } else {
                        items(filteredItems, key = { it.id }) { item ->
                            val isHomeApp = item.kind == SyncedLink.KIND_HOME_APP
                            val isHistory = item.kind == SyncedLink.KIND_HISTORY
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = 640.dp)
                                    .clip(MaterialTheme.shapes.medium),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = when {
                                            isHomeApp -> Icons.Rounded.Language
                                            isHistory -> Icons.Rounded.History
                                            else -> Icons.Rounded.Bookmark
                                        },
                                        contentDescription = null,
                                        tint = shieldColors.surfaceAccent,
                                    )
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(
                                                text = item.title,
                                                style = MaterialTheme.typography.titleSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = shieldColors.surfaceAccent.copy(alpha = 0.16f),
                                            ) {
                                                Text(
                                                    text = when {
                                                        isHomeApp -> "HOME APP"
                                                        isHistory -> "HISTORY"
                                                        else -> "BOOKMARK"
                                                    },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = shieldColors.surfaceAccent,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                )
                                            }
                                        }
                                        Text(
                                            text = item.url,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        OutlinedButton(
                                            onClick = {
                                                onOpenUrl(item.url)
                                                onNavigateBack()
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        ) {
                                            Text("Open in Browser", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    IconButton(
                                        onClick = { onRestoreToHomeScreen(item.title, item.url) },
                                    ) {
                                        Icon(
                                            Icons.Rounded.AddToHomeScreen,
                                            contentDescription = "Restore ${item.title} to Home Screen",
                                            tint = shieldColors.surfaceAccent,
                                        )
                                    }
                                    IconButton(
                                        onClick = { viewModel.deleteSyncedLink(item.id) },
                                    ) {
                                        Icon(
                                            Icons.Rounded.Delete,
                                            contentDescription = "Delete synced item",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveRemoteMonitorCard(
    monitor: SessionMonitorState?,
    recentRounds: List<RemoteSessionRound>,
) {
    val shieldColors = LocalShieldColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
            .testTag("live_remote_monitor_card"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (monitor?.isMonitoringActive == true) {
                shieldColors.secureGreen.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Live Remote Gaming Monitor",
                    style = MaterialTheme.typography.titleSmall,
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = when (monitor?.alertLevel) {
                        "LIMIT_REACHED" -> MaterialTheme.colorScheme.errorContainer
                        "APPROACHING_LIMIT" -> shieldColors.warningAmberContainer
                        "SAFE" -> shieldColors.secureGreenContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Text(
                        text = monitor?.alertLevel ?: "WAITING",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            if (monitor == null) {
                Text(
                    text = "Enable 'Live Remote Monitor Sync' inside Session Guard & Stats to stream ongoing session metrics and completed rounds in real time across your signed-in devices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "Host: ${monitor.siteHost} • Elapsed: ${monitor.elapsedMinutes}/${monitor.timeLimitMinutes}m • Budget Limit: ${"%.2f".format(monitor.budgetLimit)} ${monitor.currencyLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("Rounds", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${monitor.totalRounds} (${monitor.winsCount}W)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text("Net P/L", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = "${"%+.2f".format(monitor.netProfit)} ${monitor.currencyLabel}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (monitor.netProfit >= 0) shieldColors.secureGreen else MaterialTheme.colorScheme.error,
                        )
                    }
                    Column {
                        Text("Avg / Max", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = "${"%.2f".format(monitor.averageCrashMultiplier)}x / ${"%.2f".format(monitor.maxCrashMultiplier)}x",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                if (recentRounds.isNotEmpty()) {
                    Text(
                        text = "Latest synced round: Crash ${"%.2f".format(recentRounds.first().crashMultiplier)}x • Net ${"%+.2f".format(recentRounds.first().netProfit)} ${monitor.currencyLabel} (${recentRounds.size} total synced)",
                        style = MaterialTheme.typography.labelSmall,
                        color = shieldColors.surfaceAccent,
                    )
                }
            }
        }
    }
}

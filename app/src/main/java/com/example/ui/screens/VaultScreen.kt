package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EnhancedEncryption
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.data.DecryptedVaultItem
import com.example.data.VaultEntryType
import com.example.security.VaultCryptoManager
import com.example.ui.BrowserUiState
import com.example.ui.theme.LocalShieldColors
import javax.crypto.SecretKey

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    uiState: BrowserUiState,
    onUnlockWithBiometricKey: (SecretKey?) -> Unit,
    onSaveBiometricWrappedKeyAndUnlock: (SecretKey, String) -> Unit,
    onLockVault: () -> Unit,
    onAddEntry: (VaultEntryType, String, String, String) -> Unit,
    onDeleteEntry: (Long) -> Unit,
    onWipeVault: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val shieldColors = LocalShieldColors.current

    var selectedFilter by remember { mutableStateOf<VaultEntryType?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showWipeConfirm by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }

    val biometricManager = remember(context) { BiometricManager.from(context) }
    val strongBiometricStatus = remember(biometricManager) {
        biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
    }
    val weakOrStrongBiometricStatus = remember(biometricManager) {
        biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK,
        )
    }
    val isBiometricReady = weakOrStrongBiometricStatus == BiometricManager.BIOMETRIC_SUCCESS
    val isStrongCryptoReady = strongBiometricStatus == BiometricManager.BIOMETRIC_SUCCESS
    val needsEnrollment = weakOrStrongBiometricStatus == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED

    // Auto-lock and wipe decrypted in-memory entries whenever leaving Secure Vault
    DisposableEffect(Unit) {
        onDispose {
            onLockVault()
        }
    }

    fun openBiometricEnrollmentSettings() {
        val enrollIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
                putExtra(
                    Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.BIOMETRIC_WEAK,
                )
            }
        } else {
            @Suppress("DEPRECATION")
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        runCatching { context.startActivity(enrollIntent) }
            .onFailure {
                runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
            }
    }

    fun launchBiometricPromptAuth() {
        authError = null
        val activity = context.findFragmentActivity()
        if (activity == null) {
            authError = "Biometric prompt requires an active FragmentActivity window."
            return
        }

        if (!isBiometricReady) {
            authError = when (weakOrStrongBiometricStatus) {
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                    "No fingerprint or face biometric enrolled. Enroll biometrics in Android Settings to unlock."
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                    "Biometric sensor unavailable on this device. Vault stays sealed."
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                    "Biometric hardware temporarily unavailable. Try again shortly."
                else -> "Biometric authentication unavailable (status $weakOrStrongBiometricStatus)."
            }
            return
        }

        val executor = ContextCompat.getMainExecutor(context)
        val wrappedDek = uiState.wrappedVaultDek

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                authError = null
                val authenticatedCipher = result.cryptoObject?.cipher
                if (authenticatedCipher != null) {
                    runCatching {
                        if (wrappedDek != null) {
                            val dek = VaultCryptoManager.unwrapVaultDek(authenticatedCipher, wrappedDek)
                            onUnlockWithBiometricKey(dek)
                        } else {
                            val (dek, newEnvelope) = VaultCryptoManager.generateAndWrapVaultDek(authenticatedCipher)
                            onSaveBiometricWrappedKeyAndUnlock(dek, newEnvelope)
                        }
                    }.onFailure { err ->
                        authError = "Biometric key unwrap failed: ${err.localizedMessage ?: "Try wiping vault if biometrics changed."}"
                    }
                } else {
                    onUnlockWithBiometricKey(null)
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                authError = if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                ) {
                    "Biometric verification cancelled — Vault remains encrypted."
                } else {
                    errString.toString()
                }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                authError = "Fingerprint or face not recognized. Try again."
            }
        }

        val biometricPrompt = BiometricPrompt(activity, executor, callback)

        if (isStrongCryptoReady) {
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock OnionShield Vault")
                .setSubtitle("Verify fingerprint or face to decrypt AES-256 storage")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("Cancel")
                .setConfirmationRequired(true)
                .build()

            try {
                val cipher = if (wrappedDek != null) {
                    VaultCryptoManager.createBiometricDecryptCipher(wrappedDek)
                } else {
                    VaultCryptoManager.createBiometricEncryptCipher()
                }
                biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
            } catch (_: KeyPermanentlyInvalidatedException) {
                authError = "Biometric enrollment changed and invalidated the previous Keystore key. Erase Vault or reset key to re-enroll."
            } catch (e: Exception) {
                authError = "Could not initialize hardware biometric cipher: ${e.localizedMessage ?: e.javaClass.simpleName}"
            }
        } else {
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock OnionShield Vault")
                .setSubtitle("Verify fingerprint or face to decrypt local storage")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.BIOMETRIC_WEAK,
                )
                .setNegativeButtonText("Cancel")
                .setConfirmationRequired(false)
                .build()
            biometricPrompt.authenticate(promptInfo)
        }
    }

    BackHandler {
        onLockVault()
        onNavigateBack()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Zero-Trace AES-256 Vault") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            onLockVault()
                            onNavigateBack()
                        },
                        modifier = Modifier.testTag("vault_back_button"),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.isVaultUnlocked) {
                        IconButton(
                            onClick = onLockVault,
                            modifier = Modifier.testTag("vault_lock_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = "Lock vault",
                                tint = shieldColors.torAccent,
                            )
                        }
                        if (uiState.decryptedVaultItems.isNotEmpty()) {
                            IconButton(
                                onClick = { showWipeConfirm = true },
                                modifier = Modifier.testTag("vault_wipe_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteSweep,
                                    contentDescription = "Wipe all vault entries",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            if (uiState.isVaultUnlocked) {
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = shieldColors.torAccent,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier.testTag("vault_add_entry_fab"),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add encrypted vault entry")
                }
            }
        },
    ) { innerPadding ->
        if (!uiState.isVaultUnlocked) {
            // Locked Gate UI — strictly gated behind BiometricPrompt
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .navigationBarsPadding()
                    .padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 460.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, shieldColors.torAccent.copy(alpha = 0.45f)),
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = shieldColors.torAccent.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, shieldColors.torAccent.copy(alpha = 0.45f)),
                            modifier = Modifier.size(64.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.EnhancedEncryption,
                                    contentDescription = "Encrypted vault locked",
                                    tint = shieldColors.torAccent,
                                    modifier = Modifier.size(32.dp),
                                )
                            }
                        }

                        Text(
                            text = "Vault Sealed (AES-256-GCM)",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )

                        Text(
                            text = "${uiState.vaultLockedCount} encrypted record(s) locked on disk. Decryption is strictly gated by BiometricPrompt (Fingerprint / Face).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isBiometricReady) {
                                shieldColors.secureGreen.copy(alpha = 0.12f)
                            } else {
                                shieldColors.warningAmberContainer.copy(alpha = 0.55f)
                            },
                            border = BorderStroke(
                                1.dp,
                                if (isBiometricReady) shieldColors.secureGreen.copy(alpha = 0.45f)
                                else shieldColors.warningAmber.copy(alpha = 0.45f),
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = if (isBiometricReady) Icons.Rounded.VerifiedUser else Icons.Rounded.WarningAmber,
                                    contentDescription = "Biometric status",
                                    tint = if (isBiometricReady) shieldColors.secureGreen else shieldColors.warningAmber,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = when {
                                        isStrongCryptoReady ->
                                            "Class 3 Strong Biometrics Ready (Hardware Keystore CryptoObject)"
                                        isBiometricReady ->
                                            "Biometric Sensor Ready (Fingerprint / Face Unlock)"
                                        needsEnrollment ->
                                            "No fingerprint or face enrolled on this device"
                                        else ->
                                            "Biometric sensor unavailable — vault data remains encrypted"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }

                        Button(
                            onClick = { launchBiometricPromptAuth() },
                            colors = ButtonDefaults.buttonColors(containerColor = shieldColors.torAccent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("vault_biometric_unlock_button"),
                        ) {
                            Icon(Icons.Rounded.Fingerprint, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Authenticate with Fingerprint / Face")
                        }

                        if (needsEnrollment) {
                            OutlinedButton(
                                onClick = { openBiometricEnrollmentSettings() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("vault_enroll_biometric_button"),
                            ) {
                                Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Enroll Fingerprint / Face in Settings")
                            }
                        }

                        authError?.let { err ->
                            Text(
                                text = err,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        } else {
            // Unlocked Vault List UI
            val filtered = remember(uiState.decryptedVaultItems, selectedFilter) {
                if (selectedFilter == null) {
                    uiState.decryptedVaultItems
                } else {
                    uiState.decryptedVaultItems.filter { it.entryType == selectedFilter }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .navigationBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedFilter == null,
                        onClick = { selectedFilter = null },
                        label = { Text("All (${uiState.decryptedVaultItems.size})") },
                    )
                    VaultEntryType.entries.forEach { type ->
                        val count = uiState.decryptedVaultItems.count { it.entryType == type }
                        FilterChip(
                            selected = selectedFilter == type,
                            onClick = { selectedFilter = type },
                            label = { Text("${type.label} ($count)") },
                        )
                    }
                }

                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.EnhancedEncryption,
                                contentDescription = null,
                                tint = shieldColors.torAccent,
                                modifier = Modifier.size(40.dp),
                            )
                            Text("No Sealed Vault Entries Yet", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Tap + to encrypt private notes, .onion credentials, or secret bookmarks with AES-256-GCM.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        items(filtered, key = { it.id }) { item ->
                            VaultEntryCard(
                                item = item,
                                onOpenUrl = {
                                    onOpenUrl(item.url)
                                    onNavigateBack()
                                },
                                onCopySecret = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Vault Secret", item.secret))
                                },
                                onDelete = { onDeleteEntry(item.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddVaultEntryDialog(
            suggestedUrl = if (uiState.activeTab.isStartPage) "" else uiState.activeTab.url,
            suggestedTitle = if (uiState.activeTab.isStartPage) "" else uiState.activeTab.title,
            onSave = { type, title, secret, url ->
                onAddEntry(type, title, secret, url)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    if (showWipeConfirm) {
        AlertDialog(
            onDismissRequest = { showWipeConfirm = false },
            title = { Text("Erase Entire Vault?") },
            text = { Text("This permanently deletes all AES-256 encrypted bookmarks, passwords, and private notes from disk.") },
            confirmButton = {
                Button(
                    onClick = {
                        onWipeVault()
                        showWipeConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Erase Vault")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWipeConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun VaultEntryCard(
    item: DecryptedVaultItem,
    onOpenUrl: () -> Unit,
    onCopySecret: () -> Unit,
    onDelete: () -> Unit,
) {
    val shieldColors = LocalShieldColors.current
    var revealSecret by remember { mutableStateOf(item.entryType == VaultEntryType.NOTE) }

    val accent = when (item.entryType) {
        VaultEntryType.NOTE -> shieldColors.surfaceAccent
        VaultEntryType.CREDENTIAL -> shieldColors.torAccent
        VaultEntryType.BOOKMARK -> shieldColors.secureGreen
    }
    val icon = when (item.entryType) {
        VaultEntryType.NOTE -> Icons.Rounded.Description
        VaultEntryType.CREDENTIAL -> Icons.Rounded.Key
        VaultEntryType.BOOKMARK -> Icons.Rounded.Bookmark
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
            .testTag("vault_item_${item.id}"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(icon, contentDescription = item.entryType.label, tint = accent, modifier = Modifier.size(20.dp))
                    Column {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = item.entryType.label + if (item.isOnion) " • .onion" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                        )
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Delete vault entry", tint = MaterialTheme.colorScheme.error)
                }
            }

            if (item.url.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenUrl() },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = item.url,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.Rounded.OpenInBrowser,
                        contentDescription = "Open URL in browser",
                        tint = shieldColors.surfaceAccent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            if (item.secret.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = if (revealSecret) item.secret else "••••••••••••••••",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        Row {
                            IconButton(onClick = { revealSecret = !revealSecret }, modifier = Modifier.size(40.dp)) {
                                Icon(
                                    imageVector = if (revealSecret) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = if (revealSecret) "Hide secret" else "Reveal secret",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            IconButton(onClick = onCopySecret, modifier = Modifier.size(40.dp)) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = "Copy secret",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddVaultEntryDialog(
    suggestedUrl: String,
    suggestedTitle: String,
    onSave: (VaultEntryType, String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedType by remember { mutableStateOf(VaultEntryType.NOTE) }
    var title by remember { mutableStateOf(suggestedTitle) }
    var url by remember { mutableStateOf(suggestedUrl) }
    var secret by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New AES-256 Vault Entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    VaultEntryType.entries.forEach { type ->
                        FilterChip(
                            selected = selectedType == type,
                            onClick = { selectedType = type },
                            label = { Text(type.label) },
                        )
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title / Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (selectedType != VaultEntryType.NOTE) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Site / .onion URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = {
                        Text(
                            when (selectedType) {
                                VaultEntryType.NOTE -> "Encrypted Note Content"
                                VaultEntryType.CREDENTIAL -> "Username / Password"
                                VaultEntryType.BOOKMARK -> "Optional Private Annotation"
                            },
                        )
                    },
                    minLines = if (selectedType == VaultEntryType.NOTE) 3 else 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(selectedType, title, secret, url) },
                enabled = title.isNotBlank() || secret.isNotBlank() || url.isNotBlank(),
                modifier = Modifier.testTag("vault_save_entry_button"),
            ) {
                Text("Encrypt & Seal")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

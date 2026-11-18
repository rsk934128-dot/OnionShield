package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.ui.theme.LocalShieldColors

object AppLockPolicy {
    fun resolveConfigUpdate(
        currentLocked: Boolean,
        isConfigLoaded: Boolean,
        biometricAppLockEnabled: Boolean,
    ): Boolean = when {
        !biometricAppLockEnabled -> false
        !isConfigLoaded -> true
        else -> currentLocked
    }

    fun shouldLockOnBackground(biometricAppLockEnabled: Boolean): Boolean = biometricAppLockEnabled

    fun resolveAfterToggle(currentLocked: Boolean, enabled: Boolean): Boolean =
        if (!enabled) false else currentLocked
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BiometricAppLockScreen(
    isTorMode: Boolean,
    isConfigLoaded: Boolean,
    onUnlocked: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val shieldColors = LocalShieldColors.current
    val accentColor = if (isTorMode) shieldColors.torAccent else shieldColors.surfaceAccent

    val biometricAuthenticators =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK

    var biometricStatus by remember {
        mutableIntStateOf(
            runCatching {
                BiometricManager.from(context).canAuthenticate(biometricAuthenticators)
            }.getOrDefault(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE),
        )
    }
    var authError by remember { mutableStateOf<String?>(null) }
    var isAuthenticating by remember { mutableStateOf(false) }
    var hasAutoPrompted by remember { mutableStateOf(false) }

    val isBiometricReady = biometricStatus == BiometricManager.BIOMETRIC_SUCCESS

    // Pressing system Back while locked exits the activity rather than revealing protected data
    BackHandler {
        context.findAppLockActivity()?.finish()
    }

    // Re-check biometric enrollment when returning from system settings
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                biometricStatus = runCatching {
                    BiometricManager.from(context).canAuthenticate(biometricAuthenticators)
                }.getOrDefault(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun openBiometricSettings() {
        val enrollIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
                putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, biometricAuthenticators)
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

    fun launchBiometricPrompt() {
        val activity = context.findAppLockActivity()
        if (activity == null) {
            authError = "Biometric prompt requires an active FragmentActivity window."
            return
        }
        if (!isBiometricReady) {
            authError = biometricStatusMessage(biometricStatus)
            return
        }

        authError = null
        isAuthenticating = true

        val executor = ContextCompat.getMainExecutor(context)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                isAuthenticating = false
                authError = null
                onUnlocked()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                isAuthenticating = false
                authError = if (
                    errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                ) {
                    "Authentication cancelled — browsing history and bookmarks remain locked."
                } else {
                    errString.toString()
                }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                authError = "Biometric not recognized. Please try again."
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(R.string.biometric_lock_prompt_title))
            .setSubtitle(context.getString(R.string.biometric_lock_prompt_subtitle))
            .setAllowedAuthenticators(biometricAuthenticators)
            .setNegativeButtonText("Cancel")
            .setConfirmationRequired(false)
            .build()

        runCatching {
            prompt.authenticate(promptInfo)
        }.onFailure { err ->
            isAuthenticating = false
            authError = err.localizedMessage ?: "Unable to start biometric prompt."
        }
    }

    LaunchedEffect(isConfigLoaded, isBiometricReady) {
        if (isConfigLoaded && isBiometricReady && !hasAutoPrompted) {
            hasAutoPrompted = true
            launchBiometricPrompt()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.16f),
                        Color.Transparent,
                    ),
                ),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp)
            .testTag("app_lock_screen"),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = accentColor.copy(alpha = 0.16f),
                    border = BorderStroke(1.5.dp, accentColor.copy(alpha = 0.6f)),
                    modifier = Modifier.size(80.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Fingerprint,
                            contentDescription = "Biometric app lock",
                            tint = accentColor,
                            modifier = Modifier.size(44.dp),
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.biometric_lock_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.biometric_lock_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProtectedAssetBadge(
                        icon = Icons.Rounded.History,
                        label = "History Locked",
                        accent = shieldColors.secureGreen,
                    )
                    ProtectedAssetBadge(
                        icon = Icons.Rounded.Bookmark,
                        label = "Bookmarks Locked",
                        accent = accentColor,
                    )
                    ProtectedAssetBadge(
                        icon = Icons.Rounded.Shield,
                        label = if (isTorMode) "Tor Session" else "Surface Session",
                        accent = if (isTorMode) shieldColors.torAccent else shieldColors.surfaceAccent,
                    )
                }

                if (!isConfigLoaded) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = accentColor,
                        )
                        Text(
                            text = "Verifying security policy…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = if (isBiometricReady) {
                            shieldColors.secureGreenContainer.copy(alpha = 0.35f)
                        } else {
                            shieldColors.warningAmberContainer.copy(alpha = 0.4f)
                        },
                        border = BorderStroke(
                            1.dp,
                            if (isBiometricReady) {
                                shieldColors.secureGreen.copy(alpha = 0.5f)
                            } else {
                                shieldColors.warningAmber.copy(alpha = 0.55f)
                            },
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = if (isBiometricReady) Icons.Rounded.VerifiedUser else Icons.Rounded.WarningAmber,
                                contentDescription = null,
                                tint = if (isBiometricReady) shieldColors.secureGreen else shieldColors.warningAmber,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = if (isBiometricReady) {
                                    "Biometric sensor ready — device unlock alone will not reveal app data."
                                } else {
                                    biometricStatusMessage(biometricStatus)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    AnimatedVisibility(visible = authError != null) {
                        authError?.let { msg ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("app_lock_error_banner"),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Lock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = msg,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (isBiometricReady) {
                            Button(
                                onClick = { launchBiometricPrompt() },
                                enabled = !isAuthenticating,
                                colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("app_lock_unlock_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Fingerprint,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.biometric_lock_unlock_button))
                            }
                        } else {
                            Button(
                                onClick = { openBiometricSettings() },
                                colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("app_lock_enroll_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Fingerprint,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.biometric_lock_enroll_button))
                            }

                            OutlinedButton(
                                onClick = onUnlocked,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("app_lock_fallback_continue_button"),
                            ) {
                                Text(stringResource(R.string.biometric_lock_continue_unconfigured))
                            }
                        }

                        TextButton(
                            onClick = { context.findAppLockActivity()?.finish() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("app_lock_exit_button"),
                        ) {
                            Text("Exit OnionShield")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtectedAssetBadge(
    icon: ImageVector,
    label: String,
    accent: Color,
) {
    Surface(
        shape = CircleShape,
        color = accent.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private fun biometricStatusMessage(status: Int): String = when (status) {
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
        "No fingerprint or face biometric enrolled on this device. Enroll one in Android Settings to enforce lock."
    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
        "Biometric sensor unavailable on this hardware."
    BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
        "Biometric hardware temporarily unavailable."
    else ->
        "Biometric authentication unavailable on this device."
}

private tailrec fun Context.findAppLockActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findAppLockActivity()
    else -> null
}

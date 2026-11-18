package com.example.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.browser.QrCodeUrlParser
import com.example.browser.QrScanOutcome
import com.example.ui.theme.LocalShieldColors
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode

@Composable
fun QrScannerModal(
    isTorMode: Boolean,
    onOpenScannedInput: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val shieldColors = LocalShieldColors.current
    val accentColor = if (isTorMode) shieldColors.torAccent else shieldColors.surfaceAccent

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var hasRequestedPermission by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }
    var scannerPaused by remember { mutableStateOf(false) }
    var lastErrorNotice by remember { mutableStateOf<String?>(null) }
    var nonUrlScanText by remember { mutableStateOf<String?>(null) }
    var cameraBindError by remember { mutableStateOf<String?>(null) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        hasRequestedPermission = true
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission && !hasRequestedPermission) {
            hasRequestedPermission = true
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        BackHandler(onBack = onDismiss)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag("qr_scanner_modal"),
        ) {
            if (hasCameraPermission && cameraBindError == null) {
                val barcodeScanner = remember {
                    val options = BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .build()
                    BarcodeScanning.getClient(options)
                }

                val cameraController = remember(context) {
                    LifecycleCameraController(context).apply {
                        setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
                        cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    }
                }

                LaunchedEffect(cameraController, barcodeScanner, lifecycleOwner) {
                    val mainExecutor = ContextCompat.getMainExecutor(context)
                    runCatching {
                        cameraController.bindToLifecycle(lifecycleOwner)
                        cameraController.setImageAnalysisAnalyzer(
                            mainExecutor,
                            MlKitAnalyzer(
                                listOf(barcodeScanner),
                                CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED,
                                mainExecutor,
                            ) { result ->
                                if (scannerPaused) return@MlKitAnalyzer
                                val barcodes = result?.getValue(barcodeScanner).orEmpty()
                                val candidate = barcodes.firstNotNullOfOrNull { code ->
                                    code.url?.url ?: code.rawValue
                                } ?: return@MlKitAnalyzer

                                when (val outcome = QrCodeUrlParser.parseQrPayload(candidate)) {
                                    is QrScanOutcome.NavigableUrl -> {
                                        scannerPaused = true
                                        onOpenScannedInput(outcome.targetInput)
                                    }
                                    is QrScanOutcome.UnsafeScheme -> {
                                        scannerPaused = true
                                        nonUrlScanText = null
                                        lastErrorNotice = "Blocked unsafe QR scheme (${outcome.scheme}:)"
                                    }
                                    is QrScanOutcome.NonUrlText -> {
                                        scannerPaused = true
                                        lastErrorNotice = null
                                        nonUrlScanText = outcome.text
                                    }
                                    QrScanOutcome.Empty -> Unit
                                }
                            },
                        )
                    }.onFailure { err ->
                        cameraBindError = err.localizedMessage ?: "Camera unavailable on this device"
                    }
                }

                LaunchedEffect(torchEnabled, cameraController) {
                    runCatching {
                        cameraController.enableTorch(torchEnabled)
                    }
                }

                DisposableEffect(cameraController, barcodeScanner) {
                    onDispose {
                        runCatching { cameraController.clearImageAnalysisAnalyzer() }
                        runCatching { cameraController.unbind() }
                        runCatching { barcodeScanner.close() }
                    }
                }

                AndroidView(
                    factory = { viewCtx ->
                        PreviewView(viewCtx).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            controller = cameraController
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                QrReticleOverlay(
                    accentColor = accentColor,
                    isPaused = scannerPaused,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                CameraPermissionOrErrorContent(
                    cameraBindError = cameraBindError,
                    accentColor = accentColor,
                    onRequestPermission = {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    onOpenSettings = {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                        context.startActivity(intent)
                    },
                    onDismiss = onDismiss,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )
            }

            // Top Controls Overlay
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.5f)),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.QrCodeScanner,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier
                                .padding(10.dp)
                                .size(20.dp),
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.qr_scanner_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.qr_scanner_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.78f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasCameraPermission && cameraBindError == null) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.6f),
                            border = BorderStroke(
                                1.dp,
                                if (torchEnabled) accentColor else Color.White.copy(alpha = 0.25f),
                            ),
                        ) {
                            IconButton(
                                onClick = { torchEnabled = !torchEnabled },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("qr_torch_toggle_button"),
                            ) {
                                Icon(
                                    imageVector = if (torchEnabled) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff,
                                    contentDescription = stringResource(R.string.cd_qr_torch),
                                    tint = if (torchEnabled) accentColor else Color.White,
                                )
                            }
                        }
                    }

                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                    ) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("qr_scanner_close_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.cd_qr_close),
                                tint = Color.White,
                            )
                        }
                    }
                }
            }

            // Bottom Hint / Result Card
            if (hasCameraPermission && cameraBindError == null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                        .align(Alignment.BottomCenter),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    lastErrorNotice?.let { err ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 460.dp)
                                .testTag("qr_unsafe_banner"),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.errorContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.7f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.WarningAmber,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = {
                                        lastErrorNotice = null
                                        scannerPaused = false
                                    },
                                ) {
                                    Text("Retry")
                                }
                            }
                        }
                    }

                    nonUrlScanText?.let { decodedText ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 460.dp)
                                .testTag("qr_text_result_card"),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.6f)),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = "QR Code Text Detected",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = accentColor,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = decodedText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            nonUrlScanText = null
                                            scannerPaused = false
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Refresh,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.size(6.dp))
                                        Text("Scan Again")
                                    }
                                    Button(
                                        onClick = { onOpenScannedInput(decodedText) },
                                        colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Search,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.size(6.dp))
                                        Text("Search Web")
                                    }
                                }
                            }
                        }
                    }

                    if (lastErrorNotice == null && nonUrlScanText == null) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.68f),
                            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Security,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = if (isTorMode) {
                                        "Align a QR code to open link in Tor mode"
                                    } else {
                                        "Align a QR code to open URL in browser"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White,
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
private fun QrReticleOverlay(
    accentColor: Color,
    isPaused: Boolean,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "qrScanLine")
    val scanLineProgress by infiniteTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scanLineY",
    )

    Canvas(modifier = modifier) {
        val boxSide = minOf(size.width, size.height) * 0.66f
        val left = (size.width - boxSide) / 2f
        val top = (size.height - boxSide) / 2f
        val cornerRadius = 24.dp.toPx()

        val cutoutPath = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(left, top, left + boxSide, top + boxSide),
                    cornerRadius = CornerRadius(cornerRadius, cornerRadius),
                ),
            )
        }

        clipPath(cutoutPath, clipOp = ClipOp.Difference) {
            drawRect(color = Color.Black.copy(alpha = 0.62f))
        }

        val bracketLen = 32.dp.toPx()
        val strokeWidth = 4.dp.toPx()
        val right = left + boxSide
        val bottom = top + boxSide

        // Top-left bracket
        drawLine(accentColor, Offset(left, top + bracketLen), Offset(left, top), strokeWidth, StrokeCap.Round)
        drawLine(accentColor, Offset(left, top), Offset(left + bracketLen, top), strokeWidth, StrokeCap.Round)
        // Top-right bracket
        drawLine(accentColor, Offset(right - bracketLen, top), Offset(right, top), strokeWidth, StrokeCap.Round)
        drawLine(accentColor, Offset(right, top), Offset(right, top + bracketLen), strokeWidth, StrokeCap.Round)
        // Bottom-left bracket
        drawLine(accentColor, Offset(left, bottom - bracketLen), Offset(left, bottom), strokeWidth, StrokeCap.Round)
        drawLine(accentColor, Offset(left, bottom), Offset(left + bracketLen, bottom), strokeWidth, StrokeCap.Round)
        // Bottom-right bracket
        drawLine(accentColor, Offset(right - bracketLen, bottom), Offset(right, bottom), strokeWidth, StrokeCap.Round)
        drawLine(accentColor, Offset(right, bottom - bracketLen), Offset(right, bottom), strokeWidth, StrokeCap.Round)

        if (!isPaused) {
            val lineY = top + (boxSide * scanLineProgress)
            drawLine(
                color = accentColor.copy(alpha = 0.85f),
                start = Offset(left + 16.dp.toPx(), lineY),
                end = Offset(right - 16.dp.toPx(), lineY),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun CameraPermissionOrErrorContent(
    cameraBindError: String?,
    accentColor: Color,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 420.dp)
            .testTag("qr_permission_card"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = accentColor.copy(alpha = 0.16f),
                modifier = Modifier.size(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (cameraBindError != null) Icons.Rounded.WarningAmber else Icons.Rounded.QrCodeScanner,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Text(
                text = if (cameraBindError != null) "Camera Unavailable" else stringResource(R.string.qr_permission_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )

            Text(
                text = cameraBindError ?: stringResource(R.string.qr_permission_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(4.dp))

            if (cameraBindError == null) {
                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("qr_grant_camera_button"),
                ) {
                    Text(stringResource(R.string.qr_permission_allow))
                }
                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.qr_permission_settings))
                }
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Cancel")
            }
        }
    }
}

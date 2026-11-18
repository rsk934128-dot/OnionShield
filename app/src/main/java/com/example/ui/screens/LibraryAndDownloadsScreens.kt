package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.browser.TrackerAndUrlEngine
import com.example.data.BookmarkEntity
import com.example.data.DownloadEntity
import com.example.data.HistoryEntity
import com.example.data.PendingDownloadRequest
import com.example.ui.components.DownloadSandboxDialog
import com.example.ui.theme.LocalShieldColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    bookmarks: List<BookmarkEntity>,
    history: List<HistoryEntity>,
    onOpenUrl: (String) -> Unit,
    onInstallToHomeScreen: (title: String, url: String) -> Unit = { _, _ -> },
    onDeleteBookmark: (Long) -> Unit,
    onDeleteHistory: (Long) -> Unit,
    onClearAllHistory: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val shieldColors = LocalShieldColors.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Bookmarks & History") },
                    navigationIcon = {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testTag("library_back_button"),
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (selectedTab == 1 && history.isNotEmpty()) {
                            TextButton(onClick = onClearAllHistory) {
                                Text("Clear All")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Bookmarks (${bookmarks.size})") },
                        icon = { Icon(Icons.Rounded.Bookmark, contentDescription = null) },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("History (${history.size})") },
                        icon = { Icon(Icons.Rounded.History, contentDescription = null) },
                    )
                }
            }
        },
    ) { innerPadding ->
        if (selectedTab == 0) {
            if (bookmarks.isEmpty()) {
                EmptyLibraryState(
                    icon = Icons.Rounded.Bookmark,
                    title = "No Local Bookmarks Yet",
                    subtitle = "Tap the bookmark icon in the address bar to save pages locally on-device.",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .navigationBarsPadding(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(bookmarks, key = { it.id }) { item ->
                        val accent = if (item.isOnion) shieldColors.torAccent else shieldColors.surfaceAccent
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 640.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .clickable {
                                    onOpenUrl(item.url)
                                    onNavigateBack()
                                },
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
                                    imageVector = if (item.isOnion) Icons.Rounded.Security else Icons.Rounded.Language,
                                    contentDescription = null,
                                    tint = accent,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = item.url,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(
                                    onClick = { onInstallToHomeScreen(item.title, item.url) },
                                    modifier = Modifier.testTag("bookmark_install_home_${item.id}"),
                                ) {
                                    Icon(
                                        Icons.Rounded.AddToHomeScreen,
                                        contentDescription = "Install ${item.title} to Home Screen",
                                        tint = accent,
                                    )
                                }
                                IconButton(onClick = { onDeleteBookmark(item.id) }) {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = "Remove bookmark",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            if (history.isEmpty()) {
                EmptyLibraryState(
                    icon = Icons.Rounded.History,
                    title = "History Is Empty",
                    subtitle = "Local Surface Web history is disabled by default for maximum privacy, and .onion visits are never recorded.",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .navigationBarsPadding(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(history, key = { it.id }) { item ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 640.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .clickable {
                                    onOpenUrl(item.url)
                                    onNavigateBack()
                                },
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
                                    imageVector = Icons.Rounded.Language,
                                    contentDescription = null,
                                    tint = shieldColors.surfaceAccent,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = item.url,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(
                                    onClick = { onInstallToHomeScreen(item.title, item.url) },
                                    modifier = Modifier.testTag("history_install_home_${item.id}"),
                                ) {
                                    Icon(
                                        Icons.Rounded.AddToHomeScreen,
                                        contentDescription = "Install ${item.title} to Home Screen",
                                        tint = shieldColors.surfaceAccent,
                                    )
                                }
                                IconButton(onClick = { onDeleteHistory(item.id) }) {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = "Delete history entry",
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    downloads: List<DownloadEntity>,
    pendingDownload: PendingDownloadRequest? = null,
    onConfirmPendingDownload: () -> Unit = {},
    onCancelPendingDownload: () -> Unit = {},
    onRefreshDownloads: () -> Unit = {},
    onOpenOrInstallDownload: (DownloadEntity) -> Unit = {},
    onOpenSystemDownloads: () -> Unit = {},
    onRequestDirectDownload: (String) -> Unit = {},
    onDeleteDownload: (Long) -> Unit,
    onClearDownloads: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val shieldColors = LocalShieldColors.current
    val clipboard = LocalClipboardManager.current
    val dateFormatter = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    var showApkUrlDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Downloads & APK Installer") },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("downloads_back_button"),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = onRefreshDownloads,
                        modifier = Modifier.testTag("refresh_downloads_button"),
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh download status")
                    }
                    IconButton(
                        onClick = onOpenSystemDownloads,
                        modifier = Modifier.testTag("open_system_downloads_button"),
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = "Open device Downloads folder")
                    }
                    if (downloads.isNotEmpty()) {
                        IconButton(onClick = onClearDownloads) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear download log")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showApkUrlDialog = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = "Download APK or file link") },
                text = { Text("Download APK / Link") },
                modifier = Modifier.testTag("download_apk_url_fab"),
            )
        },
    ) { innerPadding ->
        if (downloads.isEmpty()) {
            EmptyLibraryState(
                icon = Icons.Rounded.InstallMobile,
                title = "No Downloads Recorded",
                subtitle = "Download any .apk package or file from the browser, or tap Download APK / Link below to download & open it via Android's installer.",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(downloads, key = { it.id }) { dl ->
                    val isApk = TrackerAndUrlEngine.isApkAsset(dl.url, dl.fileName, dl.mimeType)
                    val statusColor = when (dl.status) {
                        "DOWNLOADING", "COMPLETED" -> shieldColors.secureGreen
                        "CANCELLED" -> shieldColors.warningAmber
                        else -> MaterialTheme.colorScheme.error
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 640.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    imageVector = if (isApk) Icons.Rounded.InstallMobile else Icons.Rounded.Download,
                                    contentDescription = null,
                                    tint = statusColor,
                                )
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        text = dl.fileName,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = statusColor.copy(alpha = 0.16f),
                                        ) {
                                            Text(
                                                text = dl.status,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = statusColor,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                            )
                                        }
                                        if (isApk) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = shieldColors.surfaceAccent.copy(alpha = 0.18f),
                                            ) {
                                                Text(
                                                    text = "APK",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = shieldColors.surfaceAccent,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                )
                                            }
                                        }
                                        if (dl.isOnionSource) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = shieldColors.torAccent.copy(alpha = 0.18f),
                                            ) {
                                                Text(
                                                    text = "TOR ORIGIN",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = shieldColors.torAccent,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                )
                                            }
                                        }
                                        Text(
                                            text = dateFormatter.format(Date(dl.createdAt)),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                IconButton(onClick = { onDeleteDownload(dl.id) }) {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = "Delete download entry",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            if (dl.systemDownloadId >= 0L && dl.status != "CANCELLED" && dl.status != "FAILED") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    FilledTonalButton(
                                        onClick = { onOpenOrInstallDownload(dl) },
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                        modifier = Modifier.testTag("install_or_open_download_${dl.id}"),
                                    ) {
                                        Icon(
                                            imageVector = if (isApk) Icons.Rounded.InstallMobile else Icons.AutoMirrored.Rounded.OpenInNew,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Text(
                                            text = if (isApk) "  Install APK" else "  Open File",
                                            style = MaterialTheme.typography.labelLarge,
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

    if (showApkUrlDialog) {
        var urlInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showApkUrlDialog = false },
            title = { Text("Download APK or File Link") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Enter a direct .apk or file download link. OnionShield will verify it in the Sandbox Guard before saving to Downloads.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("APK / File URL") },
                        placeholder = { Text("https://.../app-release.apk") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("direct_apk_url_input"),
                    )
                    TextButton(
                        onClick = {
                            val clip = clipboard.getText()?.text.orEmpty()
                            val extracted = TrackerAndUrlEngine.extractFirstHttpUrl(clip) ?: clip.trim()
                            if (extracted.isNotBlank()) urlInput = extracted
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("Paste Link from Clipboard")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showApkUrlDialog = false
                        onRequestDirectDownload(urlInput)
                    },
                    enabled = urlInput.trim().isNotEmpty(),
                    modifier = Modifier.testTag("direct_apk_download_confirm"),
                ) {
                    Text("Check & Download")
                }
            },
            dismissButton = {
                TextButton(onClick = { showApkUrlDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    pendingDownload?.let { req ->
        DownloadSandboxDialog(
            request = req,
            onConfirm = onConfirmPendingDownload,
            onCancel = onCancelPendingDownload,
        )
    }
}

@Composable
private fun EmptyLibraryState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.widthIn(max = 360.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

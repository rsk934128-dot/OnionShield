package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.browser.WebsiteLogoCache

@Composable
fun WebsiteLogo(
    url: String,
    title: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    cornerRadius: Dp = 10.dp,
    isOnion: Boolean = WebsiteLogoCache.isOnionHostOrUrl(url),
    allowNetworkFetch: Boolean = !isOnion,
) {
    val context = LocalContext.current
    val cacheVersion by WebsiteLogoCache.cacheVersion.collectAsStateWithLifecycle()
    val hostKey = remember(url) { WebsiteLogoCache.buildCacheKey(url) ?: url }

    val logoBitmap by produceState<Bitmap?>(
        initialValue = WebsiteLogoCache.getCachedMemory(url),
        key1 = hostKey,
        key2 = allowNetworkFetch,
        key3 = cacheVersion,
    ) {
        val loaded = WebsiteLogoCache.getOrFetchBitmap(
            context = context,
            url = url,
            allowNetworkFetch = allowNetworkFetch && !isOnion,
        )
        if (loaded != null) {
            value = loaded
        }
    }

    val shape = RoundedCornerShape(cornerRadius)
    val badgeLetters = remember(title, url) {
        deriveBadgeLetters(title, url)
    }

    val activeBitmap = logoBitmap?.takeIf { !it.isRecycled }
    if (activeBitmap != null) {
        val imageBitmap = remember(activeBitmap) { activeBitmap.asImageBitmap() }
        Surface(
            modifier = modifier.size(size),
            shape = shape,
            color = Color(0xFFF8FAFC),
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
        ) {
            Image(
                bitmap = imageBitmap,
                contentDescription = title.ifBlank { hostKey },
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding((size.value * 0.12f).coerceAtLeast(3f).dp)
                    .clip(RoundedCornerShape((cornerRadius.value - 2f).coerceAtLeast(4f).dp)),
            )
        }
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(accentColor.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            if (isOnion && badgeLetters == "OS") {
                Icon(
                    imageVector = Icons.Rounded.Security,
                    contentDescription = title.ifBlank { ".onion site" },
                    tint = accentColor,
                    modifier = Modifier.size(size * 0.5f),
                )
            } else {
                Text(
                    text = badgeLetters,
                    style = if (size >= 34.dp) {
                        MaterialTheme.typography.labelLarge
                    } else {
                        MaterialTheme.typography.labelSmall
                    },
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun deriveBadgeLetters(title: String, url: String): String {
    val cleanTitle = title.trim()
    val candidateSource = if (cleanTitle.isNotEmpty() &&
        !cleanTitle.startsWith("http://", ignoreCase = true) &&
        !cleanTitle.startsWith("https://", ignoreCase = true)
    ) {
        cleanTitle
    } else {
        WebsiteLogoCache.extractNormalizedHost(url)
            ?.substringBefore('.')
            .orEmpty()
    }
    val words = candidateSource
        .split(' ', '-', '_', '.')
        .filter { it.isNotBlank() && it.first().isLetterOrDigit() }
    return when {
        words.size >= 2 -> "${words[0].first().uppercaseChar()}${words[1].first().uppercaseChar()}"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> "OS"
    }
}

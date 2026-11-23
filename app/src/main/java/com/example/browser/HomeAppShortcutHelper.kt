package com.example.browser

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.MainActivity
import com.example.data.ABOUT_START_URL
import java.util.Locale

object HomeAppShortcutHelper {
    const val EXTRA_PINNED_APP_URL = "extra_pinned_app_url"

    fun pinWebsiteToHomeScreen(
        context: Context,
        title: String,
        url: String,
        isOnion: Boolean,
        fetchedLogoBitmap: Bitmap? = null,
    ): Pair<Boolean, String> {
        if (url.isBlank() || url == ABOUT_START_URL) {
            return false to "Open a website first to install it as a Home App."
        }

        val cleanTitle = deriveDisplayTitle(title, url)
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            return false to "Added \"$cleanTitle\" to Start Page Apps (launcher pin prompt unsupported)."
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(url)
            putExtra(EXTRA_PINNED_APP_URL, url)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val validLogo = fetchedLogoBitmap?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
        val iconBitmap = if (validLogo != null) {
            createCompositeLogoBitmap(validLogo, isOnion)
        } else {
            createMonogramIconBitmap(cleanTitle, isOnion)
        }
        val shortcutId = "onionshield_pwa_${url.hashCode()}"
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(cleanTitle.take(18))
            .setLongLabel(cleanTitle.take(36))
            .setIcon(IconCompat.createWithBitmap(iconBitmap))
            .setIntent(launchIntent)
            .build()

        val requested = runCatching {
            ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
        }.getOrDefault(false)

        return if (requested) {
            true to "Install prompt sent for \"$cleanTitle\" (also pinned to Start Page)."
        } else {
            false to "Pinned \"$cleanTitle\" to Start Page Apps."
        }
    }

    fun deriveDisplayTitle(rawTitle: String, url: String): String {
        val trimmed = rawTitle.trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return trimmed.substringBefore(" - ").substringBefore(" | ").trim().ifBlank { trimmed }
        }
        val host = runCatching { Uri.parse(url).host }.getOrNull().orEmpty()
            .removePrefix("www.")
            .removePrefix("m.")
        val primary = host.substringBefore('.').replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
        }
        return primary.ifBlank { "Web App" }
    }

    private fun createCompositeLogoBitmap(logo: Bitmap, isOnion: Boolean): Bitmap {
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F8FAFC")
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(if (isOnion) "#B66CFF" else "#00E5D1")
            style = Paint.Style.STROKE
            strokeWidth = 10f
        }
        val rect = RectF(10f, 10f, size - 10f, size - 10f)
        canvas.drawRoundRect(rect, 44f, 44f, bgPaint)

        val inset = 34f
        val destRect = RectF(inset, inset, size - inset, size - inset)
        val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(logo, null, destRect, drawPaint)
        canvas.drawRoundRect(rect, 44f, 44f, borderPaint)
        return bitmap
    }

    private fun createMonogramIconBitmap(title: String, isOnion: Boolean): Bitmap {
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0B0F19")
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(if (isOnion) "#B66CFF" else "#00E5D1")
            style = Paint.Style.STROKE
            strokeWidth = 10f
        }
        val rect = RectF(10f, 10f, size - 10f, size - 10f)
        canvas.drawRoundRect(rect, 44f, 44f, bgPaint)
        canvas.drawRoundRect(rect, 44f, 44f, borderPaint)

        val letters = title.trim()
            .split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }
            .ifBlank { "OS" }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(if (isOnion) "#D8B4FE" else "#00E5D1")
            textSize = if (letters.length > 1) 74f else 88f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val yPos = (canvas.height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(letters, canvas.width / 2f, yPos, textPaint)
        return bitmap
    }
}

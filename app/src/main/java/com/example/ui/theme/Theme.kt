package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class ShieldExtendedColors(
    val surfaceAccent: Color,
    val torAccent: Color,
    val secureGreen: Color,
    val secureGreenContainer: Color,
    val warningAmber: Color,
    val warningAmberContainer: Color,
    val elevatedSurface: Color,
)

val LocalShieldColors = staticCompositionLocalOf {
    ShieldExtendedColors(
        surfaceAccent = SurfaceCyan,
        torAccent = TorAmethyst,
        secureGreen = ShieldGreen,
        secureGreenContainer = ShieldGreenContainer,
        warningAmber = ShieldAmber,
        warningAmberContainer = ShieldAmberContainer,
        elevatedSurface = ObsidianSurfaceElevated,
    )
}

private val SurfaceDarkScheme = darkColorScheme(
    primary = SurfaceCyan,
    onPrimary = OnSurfaceCyan,
    primaryContainer = SurfaceCyanContainer,
    onPrimaryContainer = OnSurfaceCyanContainer,
    secondary = TorAmethyst,
    onSecondary = OnTorAmethyst,
    secondaryContainer = TorAmethystContainer,
    onSecondaryContainer = OnTorAmethystContainer,
    tertiary = ShieldGreen,
    onTertiary = ObsidianBackground,
    background = ObsidianBackground,
    onBackground = TextPrimaryDark,
    surface = ObsidianSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = TextSecondaryDark,
    outline = ObsidianOutline,
    error = ShieldCrimson,
    errorContainer = ShieldCrimsonContainer,
    onError = TextPrimaryDark,
)

private val TorDarkScheme = darkColorScheme(
    primary = TorAmethyst,
    onPrimary = OnTorAmethyst,
    primaryContainer = TorAmethystContainer,
    onPrimaryContainer = OnTorAmethystContainer,
    secondary = SurfaceCyan,
    onSecondary = OnSurfaceCyan,
    secondaryContainer = SurfaceCyanContainer,
    onSecondaryContainer = OnSurfaceCyanContainer,
    tertiary = ShieldGreen,
    onTertiary = ObsidianBackground,
    background = ObsidianBackground,
    onBackground = TextPrimaryDark,
    surface = ObsidianSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = TextSecondaryDark,
    outline = ObsidianOutline,
    error = ShieldCrimson,
    errorContainer = ShieldCrimsonContainer,
    onError = TextPrimaryDark,
)

private val SurfaceLightScheme = lightColorScheme(
    primary = SurfaceCyanDarkAccent,
    onPrimary = Color.White,
    primaryContainer = SurfaceCyanLightContainer,
    onPrimaryContainer = OnSurfaceCyan,
    secondary = TorAmethystDarkAccent,
    onSecondary = Color.White,
    secondaryContainer = TorAmethystLightContainer,
    onSecondaryContainer = OnTorAmethyst,
    tertiary = ShieldGreen,
    background = LightBackground,
    onBackground = TextPrimaryLight,
    surface = LightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = TextSecondaryLight,
    outline = LightOutline,
    error = ShieldCrimson,
)

private val TorLightScheme = lightColorScheme(
    primary = TorAmethystDarkAccent,
    onPrimary = Color.White,
    primaryContainer = TorAmethystLightContainer,
    onPrimaryContainer = OnTorAmethyst,
    secondary = SurfaceCyanDarkAccent,
    onSecondary = Color.White,
    secondaryContainer = SurfaceCyanLightContainer,
    onSecondaryContainer = OnSurfaceCyan,
    tertiary = ShieldGreen,
    background = LightBackground,
    onBackground = TextPrimaryLight,
    surface = LightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = TextSecondaryLight,
    outline = LightOutline,
    error = ShieldCrimson,
)

val OnionShieldShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun OnionShieldTheme(
    isTorMode: Boolean = false,
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        darkTheme && isTorMode -> TorDarkScheme
        darkTheme -> SurfaceDarkScheme
        isTorMode -> TorLightScheme
        else -> SurfaceLightScheme
    }

    val extendedColors = if (darkTheme) {
        ShieldExtendedColors(
            surfaceAccent = SurfaceCyan,
            torAccent = TorAmethyst,
            secureGreen = ShieldGreen,
            secureGreenContainer = ShieldGreenContainer,
            warningAmber = ShieldAmber,
            warningAmberContainer = ShieldAmberContainer,
            elevatedSurface = ObsidianSurfaceElevated,
        )
    } else {
        ShieldExtendedColors(
            surfaceAccent = SurfaceCyanDarkAccent,
            torAccent = TorAmethystDarkAccent,
            secureGreen = ShieldGreen,
            secureGreenContainer = Color(0xFFDCFCE7),
            warningAmber = ShieldAmber,
            warningAmberContainer = Color(0xFFFEF3C7),
            elevatedSurface = LightSurfaceVariant,
        )
    }

    CompositionLocalProvider(LocalShieldColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = OnionShieldTypography,
            shapes = OnionShieldShapes,
            content = content,
        )
    }
}

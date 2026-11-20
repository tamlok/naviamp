package app.naviamp.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import app.naviamp.domain.settings.NaviampBuiltinTheme
import app.naviamp.domain.settings.NaviampThemePack
import app.naviamp.domain.settings.NaviampThemePalette
import app.naviamp.domain.settings.NaviampThemeSettings
import app.naviamp.domain.settings.resolveNaviampTheme

data class NaviampUiTheme(
    val colors: NaviampColors,
    val isDark: Boolean,
    val pack: NaviampThemePack,
    val colorScheme: ColorScheme,
)

data class NaviampThemeChrome(val darkWindow: Boolean, val lightSystemBarIcons: Boolean)

val LocalNaviampTheme = staticCompositionLocalOf {
    NaviampUiTheme(NaviampColors.Dark, true, NaviampBuiltinTheme, NaviampColors.Dark.toMaterialColorScheme())
}
val LocalNaviampThemeChromeChanged = staticCompositionLocalOf<((NaviampThemeChrome) -> Unit)?> { null }

@Composable
fun NaviampTheme(
    settings: NaviampThemeSettings,
    systemDark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val theme = remember(settings, systemDark) {
        val resolved = resolveNaviampTheme(settings, systemDark)
        val colors = resolved.palette.toNaviampColors()
        NaviampUiTheme(colors, resolved.isDark, resolved.pack, colors.toMaterialColorScheme())
    }
    NaviampTheme(theme, content)
}

@Composable
internal fun NaviampTheme(theme: NaviampUiTheme, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalNaviampTheme provides theme) {
        MaterialTheme(colorScheme = theme.colorScheme, typography = rememberNaviampTypography(), content = content)
    }
}

@Composable
internal fun NaviampThemeChromeEffect() {
    val isDark = LocalNaviampTheme.current.isDark
    val onChanged = LocalNaviampThemeChromeChanged.current
    LaunchedEffect(isDark, onChanged) {
        onChanged?.invoke(NaviampThemeChrome(darkWindow = isDark, lightSystemBarIcons = isDark))
    }
}

@Composable
internal fun rememberNaviampArtworkOverlayColors(): NaviampColors {
    val pack = LocalNaviampTheme.current.pack
    return remember(pack) { naviampArtworkOverlayColors(pack) }
}

internal fun naviampArtworkOverlayColors(pack: NaviampThemePack): NaviampColors = pack.dark.toNaviampColors()

internal fun NaviampColors.withPlayerTextColors(): NaviampColors =
    if (secondaryText == playerSecondaryText && mutedText == playerMutedText) this
    else copy(secondaryText = playerSecondaryText, mutedText = playerMutedText)

internal fun NaviampThemePalette.toNaviampColors(): NaviampColors = NaviampColors(
    background = background.toThemeColor(),
    backgroundWarm = backgroundWarm.toThemeColor(),
    backgroundOlive = backgroundOlive.toThemeColor(),
    primaryText = primaryText.toThemeColor(),
    secondaryText = secondaryText.toThemeColor(),
    mutedText = mutedText.toThemeColor(),
    border = border.toThemeColor(),
    accent = accent.toThemeColor(),
    onAccent = onAccent.toThemeColor(),
    controlSurface = controlSurface.toThemeColor(),
    playerSurface = playerSurface?.toThemeColor()?.copy(alpha = playerSurfaceOpacityPercent / 100f) ?: Color.Transparent,
    playerSecondaryText = playerSecondaryText.toThemeColor(),
    playerMutedText = playerMutedText.toThemeColor(),
    albumArtPlaceholder = albumArtPlaceholder.toThemeColor(),
    favorite = favorite.toThemeColor(),
    menuSurface = menuSurface.toThemeColor(),
    menuText = menuText.toThemeColor(),
    error = error.toThemeColor(),
    onError = onError.toThemeColor(),
    errorContainer = errorContainer.toThemeColor(),
    onErrorContainer = onErrorContainer.toThemeColor(),
    success = success.toThemeColor(),
    onSuccess = onSuccess.toThemeColor(),
)

private fun String.toThemeColor(): Color = Color(0xFF000000L or substring(1).toLong(16))

internal fun NaviampColors.toMaterialColorScheme(): ColorScheme = ColorScheme(
    primary = accent,
    onPrimary = onAccent,
    primaryContainer = accent,
    onPrimaryContainer = onAccent,
    inversePrimary = accent,
    secondary = accent,
    onSecondary = onAccent,
    secondaryContainer = controlSurface,
    onSecondaryContainer = primaryText,
    tertiary = success,
    onTertiary = onSuccess,
    tertiaryContainer = controlSurface,
    onTertiaryContainer = primaryText,
    background = background,
    onBackground = primaryText,
    surface = controlSurface,
    onSurface = primaryText,
    surfaceVariant = controlSurface,
    onSurfaceVariant = secondaryText,
    surfaceTint = accent,
    inverseSurface = primaryText,
    inverseOnSurface = background,
    error = error,
    onError = onError,
    errorContainer = errorContainer,
    onErrorContainer = onErrorContainer,
    outline = border,
    outlineVariant = border,
    scrim = Color.Black,
    surfaceBright = controlSurface,
    surfaceDim = background,
    surfaceContainer = background,
    surfaceContainerHigh = controlSurface,
    surfaceContainerHighest = menuSurface,
    surfaceContainerLow = background,
    surfaceContainerLowest = background,
    primaryFixed = accent,
    primaryFixedDim = accent,
    onPrimaryFixed = onAccent,
    onPrimaryFixedVariant = onAccent,
    secondaryFixed = accent,
    secondaryFixedDim = accent,
    onSecondaryFixed = onAccent,
    onSecondaryFixedVariant = onAccent,
    tertiaryFixed = success,
    tertiaryFixedDim = success,
    onTertiaryFixed = onSuccess,
    onTertiaryFixedVariant = onSuccess,
)

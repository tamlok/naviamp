package app.naviamp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.naviamp.domain.settings.AppBackgroundStyle
import app.naviamp.domain.settings.DefaultSingleColorHex
import app.naviamp.domain.settings.InterfaceSettings
import app.naviamp.domain.settings.MaxAlbumBlurRadiusDp
import app.naviamp.domain.settings.MinAlbumBlurRadiusDp

internal data class NaviampAppBackgroundUi(
    val style: AppBackgroundStyle,
    val coverArtUrl: String?,
    val blurRadiusDp: Int,
    val singleColor: Color,
    val targetPlayerColors: NaviampPlayerColors,
    val auroraColorSteps: Int = 3,
    val auroraAngleDegrees: Int = 45,
)

internal fun naviampAppBackgroundUi(
    interfaceSettings: InterfaceSettings,
    coverArtUrl: String?,
    albumPlayerColors: NaviampPlayerColors,
    colors: NaviampColors,
    isDark: Boolean,
): NaviampAppBackgroundUi {
    val singleColor = naviampColorFromHex(interfaceSettings.singleColorHex)
        ?: naviampColorFromHex(DefaultSingleColorHex)!!
    val playerColors = when (interfaceSettings.appBackgroundStyle) {
        AppBackgroundStyle.SingleColor -> NaviampPlayerColors.fromSingleColor(singleColor, colors)
        AppBackgroundStyle.Aurora -> albumPlayerColors.withAuroraTheme(isDark)
        AppBackgroundStyle.AlbumBlur -> albumPlayerColors
    }
    return NaviampAppBackgroundUi(
        style = interfaceSettings.appBackgroundStyle,
        coverArtUrl = coverArtUrl,
        blurRadiusDp = interfaceSettings.albumBlurRadiusDp,
        singleColor = singleColor,
        targetPlayerColors = playerColors,
        auroraColorSteps = interfaceSettings.auroraColorSteps,
        auroraAngleDegrees = interfaceSettings.auroraAngleDegrees,
    )
}

@Composable
internal fun rememberNaviampPlayerBackground(
    interfaceSettings: InterfaceSettings,
    coverArtUrl: String?,
    colors: NaviampColors,
): NaviampAppBackgroundUi {
    val isDark = LocalNaviampTheme.current.isDark
    val albumColors = if (interfaceSettings.appBackgroundStyle == AppBackgroundStyle.SingleColor) {
        remember(colors) { NaviampPlayerColors.solid(colors.background) }
    } else {
        rememberNaviampCoverArtPlayerColors(coverArtUrl, colors)
    }
    return remember(interfaceSettings, coverArtUrl, albumColors, colors, isDark) {
        naviampAppBackgroundUi(interfaceSettings, coverArtUrl, albumColors, colors, isDark)
    }
}

/** Keep finite palette transitions in the background layer, outside player controls/navigation. */
@Composable
internal fun NaviampPlayerBackground(
    background: NaviampAppBackgroundUi,
    colors: NaviampColors,
    modifier: Modifier = Modifier,
) {
    val playerColors = if (background.style == AppBackgroundStyle.SingleColor) {
        background.targetPlayerColors
    } else {
        animatedNaviampPlayerColors(background.targetPlayerColors)
    }
    NaviampAppBackground(background, colors, playerColors, modifier)
}

@Composable
internal fun NaviampAppBackground(
    background: NaviampAppBackgroundUi,
    colors: NaviampColors,
    playerColors: NaviampPlayerColors,
    modifier: Modifier = Modifier,
) {
    when (background.style) {
        AppBackgroundStyle.Aurora -> NaviampAuroraBackground(
            playerColors, background.auroraColorSteps, background.auroraAngleDegrees, modifier,
        )
        AppBackgroundStyle.AlbumBlur -> NaviampAlbumBlurBackground(
            url = background.coverArtUrl,
            colors = colors,
            playerColors = playerColors,
            blurRadiusDp = background.blurRadiusDp,
            modifier = modifier,
        )
        AppBackgroundStyle.SingleColor -> Box(
            modifier
                .fillMaxSize()
                .background(background.singleColor),
        )
    }
}

@Composable
fun NaviampAlbumBlurBackground(
    url: String?,
    colors: NaviampColors,
    playerColors: NaviampPlayerColors,
    blurRadiusDp: Int,
    modifier: Modifier = Modifier,
) {
    val scrim = if (LocalNaviampTheme.current.isDark) Color.Black else Color.White
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val blurFraction =
            (blurRadiusDp.coerceIn(MinAlbumBlurRadiusDp, MaxAlbumBlurRadiusDp) - MinAlbumBlurRadiusDp).toFloat() /
                (MaxAlbumBlurRadiusDp - MinAlbumBlurRadiusDp).toFloat()
        val coverScale = 1.04f + blurFraction * 0.10f
        val imageSize = maxOf(maxWidth, maxHeight) * (1.08f + blurFraction * 0.10f)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = coverScale, scaleY = coverScale)
                .blur(blurRadiusDp.coerceIn(MinAlbumBlurRadiusDp, MaxAlbumBlurRadiusDp).dp),
        ) {
            NaviampCoverArt(
                url = url,
                colors = colors,
                size = imageSize,
                cornerRadius = 0.dp,
                mediaKind = SharedMediaItemKind.Album,
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        playerColors.gradientColors.map { it.copy(alpha = 0.34f) },
                    ),
                )
                .background(scrim.copy(alpha = 0.38f)),
        )
    }
}

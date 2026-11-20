package app.naviamp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp

/** The same covers drive the playlist collage and its header, never the playing track. */
internal fun SharedPlaylistDetailUi.headerArtworkUrls(): List<String> =
    playlist.coverArtUrl?.let(::listOf) ?: tracks.asSequence()
        .mapNotNull { it.coverArtUrl }
        .distinct()
        .take(4)
        .toList()
        .ifEmpty { playlist.coverArtUrls.distinct().take(4) }

internal suspend fun loadNaviampDetailHeaderPalette(
    artworkUrls: List<String>,
    fallbackArtworkUrl: String? = null,
): NaviampAlbumPalette? {
    val samples = artworkUrls.mapNotNull { naviampCoverArtSamples(it)?.takeIf(List<NaviampRgbSample>::isNotEmpty) }
    if (samples.isNotEmpty()) return naviampAlbumPalette(samples.asSequence().flatten().asIterable())
    return fallbackArtworkUrl?.let { naviampCoverArtSamples(it) }?.let(::naviampAlbumPalette)
}

internal fun naviampDetailHeaderColors(palette: NaviampAlbumPalette?, colors: NaviampColors): NaviampPlayerColors {
    if (palette == null) return NaviampPlayerColors.solid(colors.background)
    val artwork = NaviampPlayerColors.from(palette, colors)
    // A restrained tint keeps normal theme text readable across arbitrary artwork.
    return artwork.copy(
        backgroundStart = artwork.backgroundStart.mix(colors.background, 0.84f),
        backgroundMid = artwork.backgroundMid.mix(colors.background, 0.84f),
        backgroundEnd = artwork.backgroundEnd.mix(colors.background, 0.84f),
    )
}

internal fun naviampDetailHeaderContentColors(colors: NaviampColors): NaviampColors = colors.copy(
    mutedText = colors.secondaryText,
    favorite = colors.favorite.mix(colors.primaryText, 0.4f),
)

@Composable
internal fun rememberNaviampDetailHeaderColors(
    mediaId: String?,
    artworkUrls: List<String>,
    fallbackArtworkUrl: String?,
    colors: NaviampColors,
): NaviampPlayerColors {
    // A thumbnail-to-hero upgrade or newly discovered fallback is still the same media.
    var palette by remember(mediaId) { mutableStateOf<NaviampAlbumPalette?>(null) }
    LaunchedEffect(mediaId, artworkUrls, fallbackArtworkUrl) {
        palette = loadNaviampDetailHeaderPalette(artworkUrls, fallbackArtworkUrl)
    }
    return remember(palette, colors) { naviampDetailHeaderColors(palette, colors) }
}

/** Static artwork Aurora, deliberately independent of every Player Background preference. */
@Composable
internal fun NaviampDetailHeader(
    colors: NaviampColors,
    background: NaviampPlayerColors,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(0.dp),
    content: @Composable ColumnScope.(NaviampColors) -> Unit,
) {
    val contentColors = remember(colors) { naviampDetailHeaderContentColors(colors) }
    Box(Modifier.fillMaxWidth()) {
        NaviampAuroraBackground(
            colors = background,
            steps = 3,
            angleDegrees = 45,
            modifier = Modifier.matchParentSize().clipToBounds(),
        )
        Column(Modifier.fillMaxWidth(), verticalArrangement = verticalArrangement) { content(contentColors) }
    }
}

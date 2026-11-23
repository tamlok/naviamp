package app.naviamp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import app.naviamp.ui.generated.resources.Res
import app.naviamp.ui.generated.resources.tv_track_count
import org.jetbrains.compose.resources.pluralStringResource

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
    val start = artwork.backgroundStart
    val middle = artwork.backgroundMid
    val end = artwork.backgroundEnd
    // Component bounds protect every gradient pixel, not just its three stops.
    val minimum = Color(minOf(start.red, middle.red, end.red), minOf(start.green, middle.green, end.green),
        minOf(start.blue, middle.blue, end.blue))
    val maximum = Color(maxOf(start.red, middle.red, end.red), maxOf(start.green, middle.green, end.green),
        maxOf(start.blue, middle.blue, end.blue))
    val primaryLuminance = colors.primaryText.luminance()
    val secondaryLuminance = colors.secondaryText.luminance()
    val favoriteLuminance = naviampDetailHeaderFavoriteColor(colors).luminance()
    // Black/white adjusts luminance without the gray wash of a theme-surface blend.
    var target = if (primaryLuminance > colors.background.luminance()) Color.Black else Color.White
    repeat(2) { pass ->
        val firstStep = if (pass == 0) 0 else 1
        for (step in firstStep..8) {
            val amount = step / 8f
            val low = minimum.mix(target, amount).luminance()
            val high = maximum.mix(target, amount).luminance()
            if (minimumHeaderContrast(primaryLuminance, low, high) >= 4.5f &&
                minimumHeaderContrast(secondaryLuminance, low, high) >= 4.5f &&
                minimumHeaderContrast(favoriteLuminance, low, high) >= 3f
            ) {
                return artwork.copy(
                    backgroundStart = start.mix(target, amount),
                    backgroundMid = middle.mix(target, amount),
                    backgroundEnd = end.mix(target, amount),
                )
            }
        }
        // Unusual custom foreground combinations may need their original theme surface.
        target = colors.background
    }
    return NaviampPlayerColors.solid(colors.background)
}

private fun minimumHeaderContrast(foreground: Float, minimum: Float, maximum: Float): Float = when {
    foreground < minimum -> (minimum + 0.05f) / (foreground + 0.05f)
    foreground > maximum -> (foreground + 0.05f) / (maximum + 0.05f)
    else -> 1f
}

private fun naviampDetailHeaderFavoriteColor(colors: NaviampColors): Color =
    colors.favorite.mix(colors.primaryText, 0.4f)

internal fun naviampDetailHeaderContentColors(colors: NaviampColors): NaviampColors = colors.copy(
    mutedText = colors.secondaryText,
    favorite = naviampDetailHeaderFavoriteColor(colors),
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

@Composable
internal fun NaviampDetailArtworkPlayButton(
    contentDescription: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Black,
            contentColor = Color.White,
            disabledContainerColor = Color.Black,
            disabledContentColor = Color.White,
        ),
        modifier = modifier.size(48.dp).alpha(0.3f),
    ) {
        Icon(
            NaviampTransportIcons.Play,
            contentDescription = contentDescription,
            modifier = Modifier.size(32.dp),
        )
    }
}

@Composable
internal fun trackCountDurationLabel(count: Int, durationLabel: String): String {
    val countLabel = pluralStringResource(Res.plurals.tv_track_count, count, count)
    return if (durationLabel.isBlank()) countLabel else "$countLabel • $durationLabel"
}

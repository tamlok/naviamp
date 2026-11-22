package app.naviamp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.naviamp.domain.playback.PlaybackProgress
import app.naviamp.domain.settings.normalizedCompactLyricsFontSize
import app.naviamp.ui.generated.resources.Res
import app.naviamp.ui.generated.resources.compact_lyrics_font_size
import app.naviamp.ui.generated.resources.compact_lyrics_loading
import app.naviamp.ui.generated.resources.compact_lyrics_refresh
import app.naviamp.ui.generated.resources.now_playing_lyrics_unavailable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource
import kotlin.time.TimeSource

/** A separate card renderer; the full lyrics renderer and its controls remain unchanged. */
@Composable
internal fun NaviampCompactLyricsCard(
    nowPlaying: NowPlayingUi,
    playbackProgress: StateFlow<PlaybackProgress>?,
    colors: NaviampColors,
    onCycleFontSize: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .testTag("now-playing-lyrics-card")
            .clip(RoundedCornerShape(8.dp))
            .background(colors.playerSurface)
            .padding(16.dp),
    ) {
        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds(), contentAlignment = Alignment.Center) {
            when {
                nowPlaying.lyricsLoading -> CompactLyricsStatus(stringResource(Res.string.compact_lyrics_loading), colors)
                nowPlaying.lyricsLines.isNotEmpty() -> CompactLyricsText(nowPlaying, playbackProgress, colors)
                else -> CompactLyricsStatus(
                    nowPlaying.lyricsStatus ?: stringResource(Res.string.now_playing_lyrics_unavailable),
                    colors,
                )
            }
        }
        Row(
            modifier = Modifier.align(AbsoluteAlignment.Right),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val fontSize = normalizedCompactLyricsFontSize(nowPlaying.compactLyricsFontSizeSp)
            val fontLabel = stringResource(Res.string.compact_lyrics_font_size, fontSize)
            NaviampTooltip(fontLabel, colors) {
                Box(
                    modifier = Modifier.size(48.dp)
                        .testTag("compact-lyrics-font-size")
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = nowPlaying.lyricsAvailable, role = Role.Button, onClick = onCycleFontSize)
                        .semantics { contentDescription = fontLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(fontSize.toString(), color = if (nowPlaying.lyricsAvailable) colors.primaryText else colors.mutedText,
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            NaviampTransportIconButton(
                enabled = nowPlaying.canRefreshLyrics && !nowPlaying.lyricsLoading,
                icon = NaviampIcons.Refresh,
                contentDescription = stringResource(Res.string.compact_lyrics_refresh),
                colors = colors,
                buttonSize = 48.dp,
                iconSize = 20.dp,
                modifier = Modifier.testTag("compact-lyrics-refresh"),
                onClick = onRefresh,
            )
        }
    }
}

@Composable
private fun CompactLyricsStatus(text: String, colors: NaviampColors) {
    Text(text, color = colors.mutedText, fontSize = 13.sp, textAlign = TextAlign.Center)
}

@Composable
private fun CompactLyricsText(
    nowPlaying: NowPlayingUi,
    playbackProgress: StateFlow<PlaybackProgress>?,
    colors: NaviampColors,
) {
    val lines = nowPlaying.lyricsLines
    val synced = remember(lines) { lines.any { it.startMillis != null } }
    if (!synced) {
        NaviampPlainLyricsText(nowPlaying, playbackProgress, colors)
        return
    }
    val progress = playbackProgress?.collectAsState()?.value
    val positionSeconds = progress?.positionSeconds ?: nowPlaying.positionSeconds
    val positionMillis = positionSeconds?.times(1000)?.toLong()
    val offsetMillis = nowPlaying.lyricsOffsetMillis
    val listState = remember(nowPlaying.id, nowPlaying.lyricsSourceId, lines) { LazyListState() }
    var activeLine by remember(nowPlaying.id, lines) {
        mutableIntStateOf(compactActiveLyricLine(lines, positionMillis, offsetMillis))
    }
    LaunchedEffect(nowPlaying.id, lines, positionMillis, progress?.seekGeneration, nowPlaying.isPlaying, offsetMillis) {
        val anchor = TimeSource.Monotonic.markNow()
        followCompactLyricLines(lines, positionMillis, offsetMillis, nowPlaying.isPlaying,
            elapsedMillis = { anchor.elapsedNow().inWholeMilliseconds },
            onActiveLine = { activeLine = it })
    }
    val fontSize = normalizedCompactLyricsFontSize(nowPlaying.compactLyricsFontSizeSp)
    BoxWithConstraints(Modifier.fillMaxSize().testTag("compact-lyrics-viewport")) {
        LaunchedEffect(listState, activeLine, fontSize, maxWidth, maxHeight) {
            if (activeLine >= 0) {
                // Font/viewport changes must be measured before centering the wrapped line.
                withFrameNanos { }
                listState.animateToActiveLyricLine(activeLine)
            }
        }
        LazyColumn(
            state = listState,
            contentPadding = if (synced) PaddingValues(vertical = maxHeight / 2) else PaddingValues(0.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().testTag("compact-lyrics-list"),
        ) {
            items(lines.size) { index ->
                val active = index == activeLine
                Text(
                    text = lines[index].text,
                    color = if (!synced || active) colors.primaryText else colors.secondaryText,
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * 1.5f).sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth().testTag("compact-lyrics-line-$index")
                        .semantics { selected = active },
                )
            }
        }
    }
}

internal fun compactActiveLyricLine(lines: List<NaviampLyricLineUi>, positionMillis: Long?, offsetMillis: Int): Int =
    if (positionMillis == null) -1 else lines.indexOfLast { line ->
        line.startMillis?.let { it + offsetMillis <= positionMillis } == true
    }

/** Wake only at lyric boundaries, not on a repeating animation timer. Playback samples restart this. */
internal suspend fun followCompactLyricLines(
    lines: List<NaviampLyricLineUi>,
    positionMillis: Long?,
    offsetMillis: Int,
    playing: Boolean,
    elapsedMillis: () -> Long,
    onActiveLine: (Int) -> Unit,
) {
    if (positionMillis == null) {
        onActiveLine(-1)
        return
    }
    while (true) {
        val position = positionMillis + if (playing) elapsedMillis().coerceAtLeast(0) else 0
        onActiveLine(compactActiveLyricLine(lines, position, offsetMillis))
        if (!playing) return
        var nextBoundary: Long? = null
        for (line in lines) {
            val boundary = line.startMillis?.plus(offsetMillis) ?: continue
            if (boundary > position && (nextBoundary == null || boundary < nextBoundary)) nextBoundary = boundary
        }
        delay((nextBoundary ?: return) - position)
    }
}

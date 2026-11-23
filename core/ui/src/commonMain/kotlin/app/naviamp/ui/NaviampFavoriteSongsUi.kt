package app.naviamp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.naviamp.ui.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NaviampFavoriteSongsPage(
    colors: NaviampColors,
    page: SharedFavoriteSongsPageUi,
    actions: NaviampHomeActions,
    television: Boolean = false,
) {
    val ready = page.status == FavoriteSongsStatus.Ready
    val backFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val artworkFocus = remember { FocusRequester() }
    val shuffleFocus = remember { FocusRequester() }
    val trackFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun focusTrack(index: Int) {
        val id = page.tracks.getOrNull(index)?.id ?: return
        scope.launch {
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
            withFrameNanos { }
            trackFocusRequesters[id]?.requestFocus()
        }
    }
    fun playbackNavigation(event: androidx.compose.ui.input.key.KeyEvent, shuffle: Boolean): Boolean {
        if (!television) return false
        if (event.key == Key.DirectionDown && page.tracks.isNotEmpty()) {
            if (event.type == KeyEventType.KeyDown) focusTrack(0)
            return true
        }
        val target = when (event.key) {
            Key.DirectionUp -> backFocus
            Key.DirectionLeft -> if (shuffle) playFocus else artworkFocus
            Key.DirectionRight -> shuffleFocus.takeIf { !shuffle && page.tracks.size > 1 }
            else -> null
        } ?: return false
        if (event.type == KeyEventType.KeyDown) target.requestFocus()
        return true
    }
    val covers = remember(page.tracks) {
        page.tracks.asSequence().mapNotNull { it.coverArtUrl }.distinct().take(4).toList()
    }
    val headerBackground = rememberNaviampDetailHeaderColors("favorite-songs", covers, null, colors)
    val backLabel = stringResource(Res.string.common_back)
    val refreshLabel = stringResource(
        if (page.status == FavoriteSongsStatus.Failed) Res.string.library_sources_retry else Res.string.library_refresh,
    )
    Column(
        Modifier.fillMaxSize().onKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && (event.key == Key.Escape || event.key == Key.Back)) {
                actions.onFavoriteSongsBack()
                true
            } else {
                false
            }
        },
    ) {
        NaviampDetailHeader(colors = colors, background = headerBackground) { headerColors ->
            Column(
                Modifier.fillMaxWidth().padding(if (television) 16.dp else 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                    if (!television || event.key != Key.DirectionDown || !ready || page.tracks.isEmpty()) false else {
                        if (event.type == KeyEventType.KeyDown) playFocus.requestFocus()
                        true
                    }
                }, verticalAlignment = Alignment.CenterVertically) {
                    if (television) {
                        TelevisionIconButton(
                            enabled = true,
                            icon = NaviampIcons.Back,
                            description = backLabel,
                            colors = headerColors,
                            initiallyFocused = true,
                            focusRequester = backFocus,
                            onClick = actions.onFavoriteSongsBack,
                        )
                    } else {
                        IconButton(onClick = actions.onFavoriteSongsBack, modifier = Modifier.size(32.dp)) {
                            Icon(NaviampIcons.Back, contentDescription = backLabel, tint = headerColors.primaryText, modifier = Modifier.size(18.dp))
                        }
                    }
                    Text(
                        stringResource(Res.string.favorite_songs_title),
                        color = headerColors.primaryText,
                        fontSize = if (television) 30.sp else 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (television) {
                        TelevisionIconButton(
                            enabled = true,
                            icon = NaviampIcons.Refresh,
                            description = refreshLabel,
                            colors = headerColors,
                            onClick = actions.onFavoriteSongsRefresh,
                        )
                    } else {
                        IconButton(onClick = actions.onFavoriteSongsRefresh) {
                            Icon(NaviampIcons.Refresh, contentDescription = refreshLabel, tint = headerColors.primaryText)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val artworkSize = if (television) 132.dp else 96.dp
                    Box(contentAlignment = Alignment.Center) {
                        if (covers.isNotEmpty()) {
                            MultiCoverArt(
                                colors = headerColors,
                                covers = covers,
                                size = artworkSize,
                                mediaKind = SharedMediaItemKind.Playlist,
                            )
                        } else {
                            Box(
                                Modifier.size(artworkSize).background(headerColors.controlSurface, RoundedCornerShape(4.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    NaviampTransportIcons.HeartFilled,
                                    contentDescription = null,
                                    tint = headerColors.favorite,
                                    modifier = Modifier.size(artworkSize / 2),
                                )
                            }
                        }
                        if (television) {
                            Box(Modifier.testTag("favorite-songs-artwork-play").onPreviewKeyEvent { event ->
                                val target = when (event.key) {
                                    Key.DirectionUp -> backFocus
                                    Key.DirectionRight -> playFocus
                                    else -> null
                                }
                                if (event.key == Key.DirectionDown && page.tracks.isNotEmpty()) {
                                    if (event.type == KeyEventType.KeyDown) focusTrack(0)
                                    true
                                } else if (target != null) {
                                    if (event.type == KeyEventType.KeyDown) target.requestFocus()
                                    true
                                } else false
                            }) {
                                TelevisionIconButton(
                                    enabled = ready && page.tracks.isNotEmpty(),
                                    icon = NaviampTransportIcons.Play,
                                    description = stringResource(Res.string.transport_play),
                                    colors = headerColors,
                                    focusRequester = artworkFocus,
                                    onClick = { actions.onFavoriteSongsPlay(false) },
                                )
                            }
                        } else {
                            NaviampDetailArtworkPlayButton(
                                contentDescription = stringResource(Res.string.transport_play),
                                enabled = ready && page.tracks.isNotEmpty(),
                                modifier = Modifier.testTag("favorite-songs-artwork-play"),
                                onClick = { actions.onFavoriteSongsPlay(false) },
                            )
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(Res.string.favorite_songs_subtitle),
                            color = headerColors.secondaryText,
                            fontSize = if (television) 18.sp else 12.sp,
                        )
                        if (ready || page.status == FavoriteSongsStatus.Empty) {
                            val countLabel = pluralStringResource(Res.plurals.tv_track_count, page.tracks.size, page.tracks.size)
                            Text(
                                if (page.totalDurationLabel.isBlank()) countLabel else "$countLabel • ${page.totalDurationLabel}",
                                color = headerColors.secondaryText,
                                fontSize = if (television) 16.sp else 12.sp,
                            )
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FavoriteSongsPlaybackButton(
                                label = stringResource(Res.string.transport_play),
                                icon = NaviampTransportIcons.Play,
                                colors = headerColors,
                                enabled = ready && page.tracks.isNotEmpty(),
                                television = television,
                                focusRequester = playFocus,
                                modifier = if (television) Modifier.onPreviewKeyEvent { playbackNavigation(it, false) } else Modifier,
                                onClick = { actions.onFavoriteSongsPlay(false) },
                            )
                            FavoriteSongsPlaybackButton(
                                label = stringResource(Res.string.transport_shuffle),
                                icon = NaviampTransportIcons.Shuffle,
                                colors = headerColors,
                                enabled = ready && page.tracks.size > 1,
                                television = television,
                                focusRequester = shuffleFocus,
                                modifier = if (television) Modifier.onPreviewKeyEvent { playbackNavigation(it, true) } else Modifier,
                                onClick = { actions.onFavoriteSongsPlay(true) },
                            )
                        }
                    }
                }
                val statusLabel = when (page.status) {
                    FavoriteSongsStatus.Loading -> Res.string.library_loading_songs
                    FavoriteSongsStatus.Empty -> Res.string.library_favorites_empty
                    FavoriteSongsStatus.Failed -> Res.string.favorite_songs_failed
                    FavoriteSongsStatus.Unsupported -> Res.string.library_favorites_unsupported
                    FavoriteSongsStatus.Idle, FavoriteSongsStatus.Ready -> null
                }
                statusLabel?.let {
                    Text(stringResource(it), color = headerColors.secondaryText, fontSize = if (television) 18.sp else 13.sp)
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("favorite-songs-tracks"),
            contentPadding = PaddingValues(horizontal = if (television) 16.dp else 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(if (ready) page.tracks else emptyList(), key = { _, track -> track.id }) { index, track ->
                if (television) {
                    val rowFocus = remember(track.id) { FocusRequester() }
                    DisposableEffect(track.id, rowFocus) {
                        trackFocusRequesters[track.id] = rowFocus
                        onDispose { if (trackFocusRequesters[track.id] === rowFocus) trackFocusRequesters.remove(track.id) }
                    }
                    TelevisionTrackRow(
                        track = track,
                        leadingText = (index + 1).toString(),
                        showArtwork = true,
                        colors = colors,
                        onAction = { action -> actions.onFavoriteSongAction(SharedTrackRowActionRequest(track, action)) },
                        modifier = Modifier.focusRequester(rowFocus).onPreviewKeyEvent { event ->
                            if (event.key != Key.DirectionUp && event.key != Key.DirectionDown) false else {
                                if (event.type == KeyEventType.KeyDown) {
                                    if (event.key == Key.DirectionUp && index == 0) playFocus.requestFocus()
                                    else focusTrack(index + if (event.key == Key.DirectionDown) 1 else -1)
                                }
                                true
                            }
                        },
                        artistContext = track.artistCredits.joinToString(", ") { it.name },
                        albumContext = track.albumTitle,
                    )
                } else {
                    TrackRow(
                        track = track,
                        colors = colors,
                        onTrackAction = actions.onFavoriteSongAction,
                        canSelect = true,
                        canStartRadio = page.canStartRadio,
                        canAddToQueue = true,
                        canDownload = page.canDownload,
                        canAddToPlaylist = true,
                        showMenu = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteSongsPlaybackButton(
    label: String,
    icon: ImageVector,
    colors: NaviampColors,
    enabled: Boolean,
    television: Boolean,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(modifier) {
        if (television) {
            TelevisionIconButton(
                enabled = enabled,
                icon = icon,
                description = label,
                colors = colors,
                focusRequester = focusRequester,
                onClick = onClick,
            )
        } else {
            MiniPlayerIconButton(colors, enabled, icon, label, onClick)
        }
    }
}

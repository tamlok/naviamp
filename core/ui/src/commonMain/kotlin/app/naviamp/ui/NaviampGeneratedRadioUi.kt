package app.naviamp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.naviamp.domain.radio.*
import app.naviamp.ui.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Preview state is separate from the active playback recipe. Mutations are owned by shared Core. */
data class NaviampGeneratedRadioUi(
    val station: GeneratedRadioStation? = null,
    val batch: GeneratedRadioBatch? = null,
    val stations: List<GeneratedRadioStation> = emptyList(),
    val tracks: List<SharedTrackRowUi> = emptyList(),
    val open: Boolean = false,
    val loading: Boolean = false,
    val status: GeneratedRadioStatus = GeneratedRadioStatus.Inactive,
    val activeStationId: String? = null,
    val preferences: GeneratedRadioPreferences = GeneratedRadioPreferences(),
    val artworkUrl: String? = null,
    val sourceAvailable: Boolean = true,
    val offline: Boolean = false,
)

enum class GeneratedRadioAction {
    Open, Close, Start, PlayBatch, Refresh, Apply, PlayNext, AddToQueue, SavePlaylist,
    Save, Rename, TogglePin, Delete, OpenSeed, Retry, StartFromMix,
}

data class GeneratedRadioActionRequest(
    val action: GeneratedRadioAction,
    val stationId: String? = null,
    val value: String? = null,
)

data class NaviampGeneratedRadioActions(
    val onAction: (GeneratedRadioActionRequest) -> Unit,
    val onPreferencesChanged: (GeneratedRadioPreferences) -> Unit,
)

internal fun GeneratedRadioStation.toSharedGeneratedRadioItem(
    coverArtUrl: (String?) -> String?,
    artistArtworkUrl: (String) -> String? = { null },
): SharedHomeCollectionItemUi {
    val artist = kind == GeneratedRadioKind.Artist || kind == GeneratedRadioKind.Artists
    val image = if (artist) artists.firstOrNull()?.id?.let(artistArtworkUrl)
        else coverArtUrl(albums.firstOrNull()?.coverArtId ?: seedTracks.firstOrNull()?.coverArtId)
    return SharedHomeCollectionItemUi(
        mediaItem = SharedMediaItemUi(id = id, title = name,
            subtitle = (artists.map { it.name } + albums.map { it.title } + seedTracks.map { it.title } + genres)
                .joinToString(" · "), coverArtUrl = image),
        mediaKind = if (artist) SharedMediaItemKind.Artist else SharedMediaItemKind.Album,
        action = SharedHomeCollectionItemAction.StartGeneratedStation,
    )
}

internal fun SharedHomeCollectionItemUi.generatedRadioPreviewRequest(): GeneratedRadioActionRequest? = when (action) {
    SharedHomeCollectionItemAction.StartGeneratedStation -> GeneratedRadioActionRequest(GeneratedRadioAction.Open, mediaItem.id)
    SharedHomeCollectionItemAction.SelectRecentRadio -> GeneratedRadioActionRequest(GeneratedRadioAction.Open, mediaItem.id, "RecentRadio")
    else -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NaviampGeneratedRadioContent(
    screen: NaviampGeneratedRadioUi,
    actions: NaviampGeneratedRadioActions,
    colors: NaviampColors,
    mediaActions: NaviampMediaActions,
    modifier: Modifier = Modifier,
    canDownload: Boolean = false,
    canSavePlaylist: Boolean = true,
    television: Boolean = false,
) {
    val station = screen.station
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(station?.id, television) {
        if (television) {
            withFrameNanos { }
            backFocus.requestFocus()
        }
    }
    var nameAction by remember(station?.id) { mutableStateOf<GeneratedRadioAction?>(null) }
    var deleteOpen by remember(station?.id) { mutableStateOf(false) }
    val emit: (GeneratedRadioAction, String?) -> Unit = { action, value ->
        actions.onAction(GeneratedRadioActionRequest(action, station?.id, value))
    }
    val batch = screen.batch?.takeIf { it.stationId == station?.id }
    val hasBatch = batch?.tracks?.isNotEmpty() == true
    val canGenerate = station != null && station.regenerable && screen.sourceAvailable && !screen.offline && !screen.loading &&
        screen.status != GeneratedRadioStatus.Unsupported && screen.status != GeneratedRadioStatus.InvalidRecipe
    LazyColumn(modifier.fillMaxSize().testTag("generated-radio-detail"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "back") {
            TextButton(onClick = { emit(GeneratedRadioAction.Close, null) }, modifier = Modifier.focusRequester(backFocus)) {
                Text(stringResource(Res.string.common_back))
            }
        }
        if (station != null) {
            item(key = "recipe") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NaviampCoverArt(screen.artworkUrl, colors, 112.dp, 8.dp,
                        mediaKind = if (station.kind == GeneratedRadioKind.Artist || station.kind == GeneratedRadioKind.Artists)
                            SharedMediaItemKind.Artist else SharedMediaItemKind.Album)
                    Text(station.name, style = MaterialTheme.typography.headlineSmall, color = colors.primaryText)
                    Text(stringResource(Res.string.generated_radio_revision, station.revision), color = colors.secondaryText)
                    Text(stringResource(if (station.sonicSimilarity) Res.string.settings_related_sonic_similarity else Res.string.radio_instant_mix),
                        color = colors.secondaryText)
                    GeneratedRadioTuningSummary(station, colors)
                    Text(stringResource(Res.string.generated_radio_seeds), color = colors.primaryText)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        station.artists.forEach { seed ->
                            TextButton(onClick = { emit(GeneratedRadioAction.OpenSeed, "artist:${seed.id}") }, enabled = screen.sourceAvailable) {
                                Text(seed.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        station.albums.forEach { seed ->
                            TextButton(onClick = { emit(GeneratedRadioAction.OpenSeed, "album:${seed.id}") }, enabled = screen.sourceAvailable) {
                                Text(seed.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        station.seedTracks.forEach { seed ->
                            Column {
                                Text(seed.title, color = colors.primaryText)
                                seed.artistId?.let { id ->
                                    TextButton(onClick = { emit(GeneratedRadioAction.OpenSeed, "artist:$id") }, enabled = screen.sourceAvailable) {
                                        Text(seed.artistName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                seed.albumId?.let { id ->
                                    TextButton(onClick = { emit(GeneratedRadioAction.OpenSeed, "album:$id") }, enabled = screen.sourceAvailable) {
                                        Text(seed.albumTitle.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                    if (station.genres.isNotEmpty()) Text(station.genres.joinToString(" · "), color = colors.secondaryText)
                    if (station.fromYear != null || station.toYear != null) Text(
                        listOfNotNull(station.fromYear, station.toYear).distinct().joinToString("–"), color = colors.secondaryText)
                }
            }
        }
        item(key = "status") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(generatedRadioStatusResource(if (screen.loading) GeneratedRadioStatus.Fetching else screen.status)),
                    color = colors.secondaryText, modifier = Modifier.testTag("generated-radio-status"))
                if (!screen.sourceAvailable) Text(stringResource(Res.string.generated_radio_source_unavailable), color = colors.secondaryText)
                Text(stringResource(Res.string.generated_radio_cached_notice), color = colors.secondaryText)
                if (screen.offline) Text(stringResource(Res.string.generated_radio_offline), color = colors.secondaryText)
                batch?.let {
                    Text(stringResource(Res.string.generated_radio_batch_time,
                        formatNaviampDateTime(it.generatedAtEpochMillis, includeTime = true, timeZoneId = null), it.stationRevision),
                        color = colors.secondaryText)
                }
            }
        }
        if (station != null) {
            item(key = "actions") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    GeneratedRadioButton(GeneratedRadioAction.Start, Res.string.generated_radio_start, canGenerate, emit)
                    GeneratedRadioButton(GeneratedRadioAction.PlayBatch, Res.string.generated_radio_play_batch, hasBatch && screen.sourceAvailable, emit)
                    GeneratedRadioButton(GeneratedRadioAction.Refresh, Res.string.generated_radio_refresh, canGenerate, emit)
                    GeneratedRadioButton(GeneratedRadioAction.Apply, Res.string.generated_radio_apply,
                        hasBatch && screen.sourceAvailable && !screen.loading && screen.activeStationId == station.id && batch?.stationRevision == station.revision, emit)
                    GeneratedRadioButton(GeneratedRadioAction.PlayNext, Res.string.tv_play_next, hasBatch && screen.sourceAvailable, emit)
                    GeneratedRadioButton(GeneratedRadioAction.AddToQueue, Res.string.generated_radio_add_queue, hasBatch && screen.sourceAvailable, emit)
                    GeneratedRadioButton(GeneratedRadioAction.SavePlaylist, Res.string.generated_radio_save_playlist,
                        hasBatch && screen.sourceAvailable && !screen.offline && canSavePlaylist) { action, _ -> nameAction = action }
                    if (!station.saved) GeneratedRadioButton(GeneratedRadioAction.Save, Res.string.generated_radio_save, true, emit)
                    GeneratedRadioButton(GeneratedRadioAction.Rename, Res.string.generated_radio_rename, true) { action, _ -> nameAction = action }
                    GeneratedRadioButton(GeneratedRadioAction.TogglePin,
                        if (station.pinned) Res.string.generated_radio_unpin else Res.string.generated_radio_pin, true, emit)
                    if (station.saved || station.pinned) GeneratedRadioButton(GeneratedRadioAction.Delete, Res.string.common_delete, true) { _, _ -> deleteOpen = true }
                    if (screen.status == GeneratedRadioStatus.Failed || screen.status == GeneratedRadioStatus.Exhausted)
                        GeneratedRadioButton(GeneratedRadioAction.Retry, Res.string.generated_radio_retry, canGenerate, emit)
                }
            }
        }
        itemsIndexed(screen.tracks, key = { index, track -> "$index:${track.id}" }) { index, track ->
            TrackRow(track, colors, mediaActions.onTrackAction,
                canSelect = screen.sourceAvailable, canStartRadio = canGenerate,
                canAddToQueue = screen.sourceAvailable, canDownload = canDownload && !screen.offline && screen.sourceAvailable,
                canAddToPlaylist = canSavePlaylist && !screen.offline && screen.sourceAvailable,
                showMenu = true, trackNumber = index + 1, background = true, horizontalPadding = 8.dp, verticalPadding = 8.dp)
        }
    }
    nameAction?.let { action ->
        var name by remember(station?.id, action) { mutableStateOf(station?.name.orEmpty()) }
        val televisionEditor = LocalNaviampTelevisionTextEntry.current
        if (television && televisionEditor != null) {
            TelevisionSearchEditor(NaviampTelevisionTextEntryRequest(name,
                stringResource(Res.string.generated_radio_name), stringResource(Res.string.common_save), stringResource(Res.string.common_cancel)),
                colors, televisionEditor) { value ->
                if (!value.isNullOrBlank()) emit(action, value.trim())
                nameAction = null
            }
        } else {
        AlertDialog(onDismissRequest = { nameAction = null },
            title = { Text(stringResource(if (action == GeneratedRadioAction.Rename) Res.string.generated_radio_rename else Res.string.generated_radio_save_playlist)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(Res.string.generated_radio_name)) }) },
            confirmButton = { TextButton(onClick = { emit(action, name.trim()); nameAction = null }, enabled = name.isNotBlank()) { Text(stringResource(Res.string.common_save)) } },
            dismissButton = { TextButton(onClick = { nameAction = null }) { Text(stringResource(Res.string.common_cancel)) } })
        }
    }
    if (deleteOpen) AlertDialog(onDismissRequest = { deleteOpen = false },
        title = { Text(stringResource(Res.string.common_delete)) },
        text = { Text(stringResource(Res.string.generated_radio_delete_notice)) },
        confirmButton = { TextButton(onClick = { emit(GeneratedRadioAction.Delete, null); deleteOpen = false }) { Text(stringResource(Res.string.common_delete)) } },
        dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text(stringResource(Res.string.common_cancel)) } })
}

@Composable
private fun GeneratedRadioButton(action: GeneratedRadioAction, label: StringResource, enabled: Boolean,
    emit: (GeneratedRadioAction, String?) -> Unit) {
    OutlinedButton(onClick = { emit(action, null) }, enabled = enabled,
        modifier = Modifier.testTag("generated-radio-${action.name}")) { Text(stringResource(label)) }
}

internal fun generatedRadioStatusResource(status: GeneratedRadioStatus): StringResource = when (status) {
    GeneratedRadioStatus.Inactive -> Res.string.generated_radio_preview
    GeneratedRadioStatus.Active -> Res.string.generated_radio_active
    GeneratedRadioStatus.Fetching -> Res.string.generated_radio_fetching
    GeneratedRadioStatus.Exhausted -> Res.string.generated_radio_exhausted
    GeneratedRadioStatus.Failed -> Res.string.generated_radio_failed
    GeneratedRadioStatus.Unsupported -> Res.string.generated_radio_unsupported
    GeneratedRadioStatus.InvalidRecipe -> Res.string.generated_radio_invalid
}

@Composable
internal fun NaviampGeneratedRadioStatusButton(
    station: GeneratedRadioStation?,
    status: GeneratedRadioStatus,
    colors: NaviampColors,
    onAction: (GeneratedRadioActionRequest) -> Unit,
) {
    val active = station != null && status != GeneratedRadioStatus.Inactive
    val label = if (active) stringResource(Res.string.generated_radio_context, station.name)
        else stringResource(Res.string.generated_radio_preview)
    val stateLabel = stringResource(generatedRadioStatusResource(if (active) status else GeneratedRadioStatus.Inactive))
    NaviampTransportIconButton(
        enabled = active,
        icon = NaviampIcons.Flame,
        contentDescription = label,
        colors = colors,
        selected = active,
        buttonSize = 40.dp,
        iconSize = 24.dp,
        modifier = Modifier.testTag("now-playing-station-button").semantics {
            selected = active
            stateDescription = stateLabel
        },
        onClick = { station?.takeIf { active }?.let { onAction(GeneratedRadioActionRequest(GeneratedRadioAction.Open, it.id)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NaviampGeneratedRadioContext(station: GeneratedRadioStation, status: GeneratedRadioStatus,
    colors: NaviampColors, onAction: (GeneratedRadioActionRequest) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("now-playing-station")) {
        TextButton(onClick = { onAction(GeneratedRadioActionRequest(GeneratedRadioAction.Open, station.id)) }) {
            Text(stringResource(Res.string.generated_radio_context, station.name), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(stringResource(generatedRadioStatusResource(status)), color = colors.secondaryText)
        if (status == GeneratedRadioStatus.Failed || status == GeneratedRadioStatus.Exhausted)
            TextButton(onClick = { onAction(GeneratedRadioActionRequest(GeneratedRadioAction.Retry, station.id)) }) {
                Text(stringResource(Res.string.generated_radio_retry))
            }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NaviampGeneratedRadioBuilderActions(origin: String, enabled: Boolean,
    actions: NaviampGeneratedRadioActions, sonic: Boolean = false) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { actions.onAction(GeneratedRadioActionRequest(GeneratedRadioAction.Open, value = origin)) }, enabled = enabled) {
            Text(stringResource(Res.string.generated_radio_preview))
        }
        if (sonic) OutlinedButton(onClick = { actions.onAction(GeneratedRadioActionRequest(GeneratedRadioAction.StartFromMix, value = origin)) }, enabled = enabled) {
            Text(stringResource(Res.string.generated_radio_start_mix))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NaviampGeneratedRadioPreferences(preferences: GeneratedRadioPreferences, colors: NaviampColors,
    onChanged: (GeneratedRadioPreferences) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        SettingsCheckboxRow(colors = colors, checked = preferences.endlessEnabled,
            label = stringResource(Res.string.generated_radio_endless), subtitle = stringResource(Res.string.generated_radio_endless_hint),
            onCheckedChange = { onChanged(preferences.copy(endlessEnabled = it)) })
        Text(stringResource(Res.string.generated_radio_recent_window), color = colors.primaryText)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf(0, 24, 72, 168) + preferences.normalized().recentTrackExclusionHours).distinct().sorted().forEach { hours ->
                FilterChip(selected = preferences.recentTrackExclusionHours == hours,
                    onClick = { onChanged(preferences.copy(recentTrackExclusionHours = hours)) },
                    label = { Text(if (hours == 0) stringResource(Res.string.common_off) else stringResource(Res.string.generated_radio_hours, hours)) })
            }
        }
        Text(stringResource(Res.string.generated_radio_defaults_hint), color = colors.secondaryText)
    }
}

@Composable
private fun GeneratedRadioTuningSummary(station: GeneratedRadioStation, colors: NaviampColors) {
    val tuning = station.tuning
    val familiarity = when (tuning.familiarity) {
        RadioFamiliarity.Balanced -> Res.string.mix_bias_balanced
        RadioFamiliarity.Favorites -> Res.string.mix_bias_favorites
        RadioFamiliarity.DeepCuts -> Res.string.generated_radio_deep_cuts
    }
    val spread = when (tuning.artistSpread) {
        RadioArtistSpread.Balanced -> Res.string.mix_bias_balanced
        RadioArtistSpread.Narrow -> Res.string.generated_radio_narrow
        RadioArtistSpread.Broad -> Res.string.generated_radio_broad
    }
    val runs = when (tuning.artistRunMode) {
        RadioArtistRunMode.Mixed -> Res.string.generated_radio_mixed
        RadioArtistRunMode.SingleArtist -> Res.string.generated_radio_single_artist
        RadioArtistRunMode.ArtistBlocks -> Res.string.generated_radio_artist_blocks
    }
    Text(stringResource(Res.string.generated_radio_tuning, stringResource(familiarity), stringResource(spread), stringResource(runs), station.batchSize), color = colors.secondaryText)
    if (tuning.sameDecadeOnly) Text(stringResource(Res.string.settings_radio_stay_in_decade), color = colors.secondaryText)
    if (tuning.artistRunMode == RadioArtistRunMode.ArtistBlocks) Text(
        stringResource(Res.string.generated_radio_runs, tuning.sameArtistRunLength, tuning.otherArtistRunLength), color = colors.secondaryText)
}

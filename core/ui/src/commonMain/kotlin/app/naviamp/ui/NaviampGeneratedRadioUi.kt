package app.naviamp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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
    Save, Rename, Delete, OpenSeed, Retry, StartFromMix,
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
    var detailsOpen by remember(station?.id) { mutableStateOf(false) }
    val covers = remember(screen.artworkUrl) { listOfNotNull(screen.artworkUrl) }
    val headerBackground = rememberNaviampDetailHeaderColors(station?.id, covers, null, colors)
    val status = if (screen.loading) GeneratedRadioStatus.Fetching else screen.status
    val ordinaryStatus = when (status) {
        GeneratedRadioStatus.Inactive, GeneratedRadioStatus.Active -> true
        GeneratedRadioStatus.Fetching, GeneratedRadioStatus.Exhausted, GeneratedRadioStatus.Failed,
        GeneratedRadioStatus.Unsupported, GeneratedRadioStatus.InvalidRecipe -> false
    }
    val detailActions = if (station == null) emptyList() else buildList {
        add(NaviampDetailAction(stringResource(Res.string.generated_radio_start), NaviampIcons.Flame,
            { emit(GeneratedRadioAction.Start, null) }, canGenerate))
        add(NaviampDetailAction(stringResource(Res.string.generated_radio_play_batch), NaviampTransportIcons.Play,
            { emit(GeneratedRadioAction.PlayBatch, null) }, hasBatch && screen.sourceAvailable))
        add(NaviampDetailAction(stringResource(Res.string.generated_radio_refresh), NaviampIcons.Refresh,
            { emit(GeneratedRadioAction.Refresh, null) }, canGenerate))
        add(NaviampDetailAction(stringResource(Res.string.generated_radio_apply), NaviampIcons.Check,
            { emit(GeneratedRadioAction.Apply, null) },
            hasBatch && screen.sourceAvailable && !screen.loading && screen.activeStationId == station.id && batch.stationRevision == station.revision))
        add(NaviampDetailAction(stringResource(Res.string.generated_radio_details), NaviampIcons.Info, { detailsOpen = true }))
        add(NaviampDetailAction(stringResource(Res.string.tv_play_next), NaviampTransportIcons.Next,
            { emit(GeneratedRadioAction.PlayNext, null) }, hasBatch && screen.sourceAvailable))
        add(NaviampDetailAction(stringResource(Res.string.generated_radio_add_queue), NaviampIcons.Queue,
            { emit(GeneratedRadioAction.AddToQueue, null) }, hasBatch && screen.sourceAvailable))
        add(NaviampDetailAction(stringResource(Res.string.generated_radio_save_playlist), NaviampIcons.Playlist,
            { nameAction = GeneratedRadioAction.SavePlaylist }, hasBatch && screen.sourceAvailable && !screen.offline && canSavePlaylist))
        if (!station.saved) add(NaviampDetailAction(stringResource(Res.string.generated_radio_save), NaviampIcons.Plus,
            { emit(GeneratedRadioAction.Save, null) }))
        add(NaviampDetailAction(stringResource(Res.string.generated_radio_rename), NaviampIcons.Edit,
            { nameAction = GeneratedRadioAction.Rename }))
        if (station.saved) add(NaviampDetailAction(stringResource(Res.string.common_delete), NaviampIcons.Trash,
            { deleteOpen = true }))
    }
    Column(modifier.fillMaxSize().background(colors.background).testTag("generated-radio-detail")) {
        NaviampDetailHeader(colors, headerBackground) { headerColors ->
            Column(Modifier.fillMaxWidth().padding(if (television) 16.dp else 8.dp).testTag("generated-radio-header"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (television) {
                        TelevisionIconButton(true, NaviampIcons.Back, stringResource(Res.string.common_back), headerColors,
                            initiallyFocused = true, focusRequester = backFocus) { emit(GeneratedRadioAction.Close, null) }
                    } else {
                        IconButton(onClick = { emit(GeneratedRadioAction.Close, null) }, modifier = Modifier.size(32.dp)) {
                            Icon(NaviampIcons.Back, stringResource(Res.string.common_back), tint = headerColors.primaryText,
                                modifier = Modifier.size(18.dp))
                        }
                    }
                    Text(station?.name ?: stringResource(Res.string.generated_radio_preview),
                        color = headerColors.primaryText, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                if (station != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        NaviampCoverArt(screen.artworkUrl, headerColors, if (television) 132.dp else 96.dp, 4.dp,
                            mediaKind = if (station.kind == GeneratedRadioKind.Artist || station.kind == GeneratedRadioKind.Artists)
                                SharedMediaItemKind.Artist else SharedMediaItemKind.Album)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(if (station.sonicSimilarity) Res.string.settings_related_sonic_similarity else Res.string.radio_instant_mix),
                                color = headerColors.secondaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(trackCountDurationLabel(screen.tracks.size, ""), color = headerColors.secondaryText, fontSize = 12.sp)
                            if (ordinaryStatus) Text(stringResource(generatedRadioStatusResource(status)),
                                color = headerColors.secondaryText, fontSize = 11.sp)
                            if (!television) NaviampResponsiveActionRow(headerColors, detailActions, Modifier.testTag("generated-radio-toolbar"))
                        }
                    }
                    if (television) GeneratedRadioTelevisionActions(detailActions, headerColors)
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("generated-radio-tracks"),
            contentPadding = PaddingValues(horizontal = if (television) 16.dp else 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!ordinaryStatus || !screen.sourceAvailable || screen.offline) item(key = "status") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!ordinaryStatus) Text(stringResource(generatedRadioStatusResource(status)),
                        color = colors.secondaryText, fontSize = 13.sp, modifier = Modifier.testTag("generated-radio-status"))
                    if (!screen.sourceAvailable) Text(stringResource(Res.string.generated_radio_source_unavailable), color = colors.secondaryText)
                    if (screen.offline) Text(stringResource(Res.string.generated_radio_offline), color = colors.secondaryText)
                    if (station != null && (screen.status == GeneratedRadioStatus.Failed || screen.status == GeneratedRadioStatus.Exhausted))
                        GeneratedRadioButton(GeneratedRadioAction.Retry, Res.string.generated_radio_retry, canGenerate, emit)
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
    }
    if (detailsOpen && station != null) {
        NaviampPopupPresence()
        NaviampAlertDialog(onDismissRequest = { detailsOpen = false },
            containerColor = colors.menuSurface, titleContentColor = colors.primaryText, textContentColor = colors.secondaryText,
            title = { Text(stringResource(Res.string.generated_radio_details)) },
            text = {
                Column(Modifier.fillMaxWidth().testTag("generated-radio-details-body").verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(station.name, color = colors.primaryText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(stringResource(Res.string.generated_radio_revision, station.revision), fontSize = 12.sp)
                    GeneratedRadioTuningDetails(station, colors)
                    Text(stringResource(Res.string.generated_radio_seeds), color = colors.primaryText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    GeneratedRadioSeeds(station, screen.sourceAvailable, colors) { value ->
                        detailsOpen = false
                        emit(GeneratedRadioAction.OpenSeed, value)
                    }
                    batch?.let {
                        Text(stringResource(Res.string.generated_radio_batch_time,
                            formatNaviampDateTime(it.generatedAtEpochMillis, includeTime = true, timeZoneId = null), it.stationRevision), fontSize = 12.sp)
                    }
                    Text(stringResource(Res.string.generated_radio_cached_notice), fontSize = 12.sp)
                }
            },
            confirmButton = { NaviampTextButton(onClick = { detailsOpen = false },
                colors = ButtonDefaults.textButtonColors(contentColor = colors.actionText)) { Text(stringResource(Res.string.common_close)) } })
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
            NaviampPopupPresence()
            NaviampAlertDialog(onDismissRequest = { nameAction = null },
                title = { Text(stringResource(if (action == GeneratedRadioAction.Rename) Res.string.generated_radio_rename else Res.string.generated_radio_save_playlist)) },
                text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(Res.string.generated_radio_name)) }) },
                confirmButton = { TextButton(onClick = { emit(action, name.trim()); nameAction = null }, enabled = name.isNotBlank()) { Text(stringResource(Res.string.common_save)) } },
                dismissButton = { TextButton(onClick = { nameAction = null }) { Text(stringResource(Res.string.common_cancel)) } })
        }
    }
    if (deleteOpen) {
        NaviampPopupPresence()
        NaviampAlertDialog(onDismissRequest = { deleteOpen = false },
            title = { Text(stringResource(Res.string.common_delete)) },
            text = { Text(stringResource(Res.string.generated_radio_delete_notice)) },
            confirmButton = { TextButton(onClick = { emit(GeneratedRadioAction.Delete, null); deleteOpen = false }) { Text(stringResource(Res.string.common_delete)) } },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text(stringResource(Res.string.common_cancel)) } })
    }
}

@Composable
private fun GeneratedRadioTelevisionActions(actions: List<NaviampDetailAction>, colors: NaviampColors) {
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("generated-radio-toolbar")) {
        val visibleCount = responsiveVisibleActionCount(maxWidth.value, actions.size, actionSlotWidthDp = 54f)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (index in 0 until visibleCount) {
                val action = actions[index]
                TelevisionIconButton(action.enabled, action.icon, action.label, colors,
                    selected = action.selected, onClick = action.onClick)
            }
            if (visibleCount < actions.size) {
                NaviampRowOverflowMenu(colors, actions.drop(visibleCount).map {
                    NaviampRowMenuItem(it.label, it.icon, it.onClick, it.enabled)
                }, button = { onClick ->
                    TelevisionIconButton(true, NaviampTransportIcons.MoreVertical,
                        stringResource(Res.string.playlist_item_more_actions), colors, onClick = onClick)
                })
            }
        }
    }
}

@Composable
private fun GeneratedRadioSeeds(station: GeneratedRadioStation, sourceAvailable: Boolean, colors: NaviampColors,
    openSeed: (String) -> Unit) {
    if (station.artists.isNotEmpty()) Column(Modifier.fillMaxWidth().testTag("generated-radio-seed-artists")) {
        Text(stringResource(Res.string.library_view_artists), color = colors.secondaryText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        station.artists.forEach { seed ->
            GeneratedRadioSeedLink(seed.name, sourceAvailable, colors) { openSeed("artist:${seed.id}") }
        }
    }
    if (station.albums.isNotEmpty()) Column(Modifier.fillMaxWidth().testTag("generated-radio-seed-albums")) {
        Text(stringResource(Res.string.library_view_albums), color = colors.secondaryText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        station.albums.forEach { seed ->
            GeneratedRadioSeedLink(seed.title, sourceAvailable, colors) { openSeed("album:${seed.id}") }
        }
    }
    if (station.seedTracks.isNotEmpty()) Column(Modifier.fillMaxWidth().testTag("generated-radio-seed-tracks"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.library_view_songs), color = colors.secondaryText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        station.seedTracks.forEachIndexed { index, seed ->
            Column(Modifier.fillMaxWidth().testTag("generated-radio-seed-track-$index")) {
                Text(seed.title, color = colors.primaryText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (seed.artistId != null && seed.artistName.isNotBlank()) {
                    GeneratedRadioSeedLink(seed.artistName, sourceAvailable, colors) { openSeed("artist:${seed.artistId}") }
                } else if (seed.artistName.isNotBlank()) {
                    Text(seed.artistName, color = colors.secondaryText, fontSize = 12.sp)
                }
                val albumTitle = seed.albumTitle
                if (seed.albumId != null && !albumTitle.isNullOrBlank()) {
                    GeneratedRadioSeedLink(albumTitle, sourceAvailable, colors) { openSeed("album:${seed.albumId}") }
                } else if (!albumTitle.isNullOrBlank()) {
                    Text(albumTitle, color = colors.secondaryText, fontSize = 12.sp)
                }
            }
        }
    }
    if (station.genres.isNotEmpty()) Text(station.genres.joinToString(" · "), color = colors.secondaryText, fontSize = 12.sp)
    if (station.fromYear != null || station.toYear != null) Text(
        listOfNotNull(station.fromYear, station.toYear).distinct().joinToString("–"), color = colors.secondaryText, fontSize = 12.sp)
}

@Composable
private fun GeneratedRadioSeedLink(label: String, enabled: Boolean, colors: NaviampColors, onClick: () -> Unit) {
    NaviampTextButton(onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = colors.actionText, disabledContentColor = colors.mutedText)) {
        Text(label, modifier = Modifier.fillMaxWidth(), fontSize = 12.sp, fontWeight = FontWeight.Normal, textAlign = TextAlign.Start)
    }
}

@Composable
private fun GeneratedRadioMetadataRow(label: String, value: String, colors: NaviampColors) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Text(label, color = colors.secondaryText, fontSize = 12.sp, modifier = Modifier.width(96.dp))
        Text(value, color = colors.primaryText, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
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
private fun GeneratedRadioTuningDetails(station: GeneratedRadioStation, colors: NaviampColors) {
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        GeneratedRadioMetadataRow(stringResource(Res.string.settings_radio_familiarity), stringResource(familiarity), colors)
        GeneratedRadioMetadataRow(stringResource(Res.string.settings_radio_artist_spread), stringResource(spread), colors)
        GeneratedRadioMetadataRow(stringResource(Res.string.settings_radio_artist_runs), stringResource(runs), colors)
        GeneratedRadioMetadataRow(stringResource(Res.string.mix_target_tracks), station.batchSize.toString(), colors)
        if (tuning.sameDecadeOnly) Text(stringResource(Res.string.settings_radio_stay_in_decade), color = colors.secondaryText, fontSize = 12.sp)
        if (tuning.artistRunMode == RadioArtistRunMode.ArtistBlocks) Text(
            stringResource(Res.string.generated_radio_runs, tuning.sameArtistRunLength, tuning.otherArtistRunLength),
            color = colors.secondaryText, fontSize = 12.sp)
    }
}

package app.naviamp.storage

import app.naviamp.domain.TrackId
import app.naviamp.domain.cache.PlaybackSessionRepository
import app.naviamp.domain.cache.PlaybackSessionRepositoryPerformance
import app.naviamp.domain.playback.PlaylistPlaybackBookmark
import app.naviamp.domain.playback.PlaylistPlaybackBookmarkRepository
import app.naviamp.domain.settings.PlaybackSessionSettings
import app.naviamp.domain.settings.SavedInternetRadioStation
import app.naviamp.domain.settings.SavedTrack
import app.naviamp.domain.queue.PlaybackQueueGroup
import app.naviamp.domain.radio.GeneratedRadioCheckpoint
import app.naviamp.domain.radio.RadioQueueOccurrence
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

/** Shared SQLDelight persistence for Core-owned playback-session policy. */
class StoragePlaybackSessionStore(
    private val queries: NaviampStorageQueries,
    private val nowEpochMillis: () -> Long,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : PlaybackSessionRepository {
    private var performance = PlaybackSessionRepositoryPerformance()
    private val queueTrackIds = mutableMapOf<String, List<String>>()
    private val queueOccurrencePayloads = mutableMapOf<String, Pair<List<RadioQueueOccurrence>, JsonElement>>()
    private val queuePayloadCharacters = mutableMapOf<String, Int>()

    override val playlistBookmarks: PlaylistPlaybackBookmarkRepository = object : PlaylistPlaybackBookmarkRepository {
        override fun loadBookmark(sourceId: String, playlistId: String): PlaylistPlaybackBookmark? =
            queries.selectPlaylistPlaybackBookmark(sourceId, playlistId).executeAsOneOrNull()?.let { row ->
                PlaylistPlaybackBookmark(
                    trackId = TrackId(row.remote_track_id),
                    occurrence = row.track_occurrence.toInt(),
                    positionSeconds = row.position_seconds,
                )
            }

        override fun saveBookmark(sourceId: String, playlistId: String, bookmark: PlaylistPlaybackBookmark?) {
            if (bookmark == null) {
                queries.deletePlaylistPlaybackBookmark(sourceId, playlistId)
            } else {
                queries.upsertPlaylistPlaybackBookmark(
                    source_id = sourceId,
                    playlist_id = playlistId,
                    remote_track_id = bookmark.trackId.value,
                    track_occurrence = bookmark.occurrence.toLong(),
                    position_seconds = bookmark.positionSeconds,
                )
            }
        }
    }

    override fun loadPlaybackSession(sourceId: String?): PlaybackSessionSettings? {
        val id = sourceId?.takeIf(String::isNotBlank) ?: return null
        val readMark = TimeSource.Monotonic.markNow()
        val state = queries.selectPlaybackSessionState(id).executeAsOneOrNull()
        val queueRows = state?.let { queries.selectPlaybackSessionQueue(id).executeAsList() }
        val legacyPayload = if (state == null) queries.selectPlaybackSession(id).executeAsOneOrNull() else null
        val readMillis = readMark.elapsedNow().inWholeMicroseconds / 1_000.0
        if (state == null && legacyPayload == null) {
            performance = performance.copy(readMillis = readMillis, decodeMillis = null, payloadCharacters = null)
            return null
        }
        val decodeMark = TimeSource.Monotonic.markNow()
        val decoded = if (state != null && queueRows != null) {
            runCatching {
                val tracks = queueRows.map { row -> json.decodeFromString<SavedTrack>(row.payload) }
                val radio = state.internet_radio_payload?.let {
                    json.decodeFromString<SavedInternetRadioStation>(it)
                }
                queueTrackIds[id] = queueRows.map { it.remote_track_id }
                queuePayloadCharacters[id] = queueRows.sumOf { it.payload.length }
                PlaybackSessionSettings(
                    tracks = tracks,
                    currentIndex = state.current_index.toInt(),
                    playNextCount = state.play_next_count.toInt(),
                    queueGroups = state.queue_groups_payload
                        ?.let { json.decodeFromString<List<PlaybackQueueGroup>>(it) }
                        .orEmpty(),
                    positionSeconds = state.position_seconds,
                    internetRadioStation = radio,
                    nowPlayingOpen = state.now_playing_open != 0L,
                    generatedRadio = state.generated_radio_checkpoint_payload?.let { payload ->
                        // A corrupt new checkpoint must never make the legacy queue unreadable.
                        runCatching { json.decodeFromString<GeneratedRadioCheckpoint>(payload) }.getOrNull()
                    },
                )
            }.getOrNull()
        } else {
            runCatching { json.decodeFromString<PlaybackSessionSettings>(requireNotNull(legacyPayload)) }.getOrNull()
        }
        val payloadCharacters = legacyPayload?.length
            ?: queuePayloadCharacters[id]
            ?: queueRows?.sumOf { it.payload.length }
        performance = performance.copy(
            readMillis = readMillis,
            decodeMillis = decodeMark.elapsedNow().inWholeMicroseconds / 1_000.0,
            payloadCharacters = payloadCharacters,
        )
        return decoded
    }

    override fun savePlaybackSession(session: PlaybackSessionSettings?, sourceId: String?) {
        val id = sourceId?.takeIf(String::isNotBlank) ?: return
        if (session == null) {
            val writeMark = TimeSource.Monotonic.markNow()
            queries.transaction {
                queries.deletePlaybackSessionQueue(id)
                queries.deletePlaybackSessionState(id)
                queries.deletePlaybackSession(id)
            }
            queueTrackIds.remove(id)
            queuePayloadCharacters.remove(id)
            queueOccurrencePayloads.remove(id)
            performance = performance.copy(
                encodeMillis = null,
                writeMillis = writeMark.elapsedNow().inWholeMicroseconds / 1_000.0,
                payloadCharacters = null,
                queueRewritten = true,
            )
        } else {
            val nextTrackIds = session.tracks.map { it.id }
            val storedTrackIds = queueTrackIds[id]
                ?: queries.selectPlaybackSessionQueueTrackIds(id).executeAsList().also {
                    queueTrackIds[id] = it
                }
            val queueChanged = storedTrackIds != nextTrackIds
            val encodeMark = TimeSource.Monotonic.markNow()
            val queuePayloads = if (queueChanged) {
                session.tracks.map { json.encodeToString(it) }
            } else {
                emptyList()
            }
            val radioPayload = session.internetRadioStation?.let { json.encodeToString(it) }
            val queueGroupsPayload = session.queueGroups
                .takeIf { it.isNotEmpty() }
                ?.let { json.encodeToString(it) }
            val generatedRadioPayload = session.generatedRadio?.let { checkpoint ->
                val cached = queueOccurrencePayloads[id]
                val occurrencePayload = if (cached != null && cached.first == checkpoint.occurrences) {
                    cached.second
                } else {
                    json.encodeToJsonElement(checkpoint.occurrences).also {
                        queueOccurrencePayloads[id] = checkpoint.occurrences to it
                    }
                }
                // Only the large ordered occurrence list is cached. Pause/policy/exclusions are live.
                val liveCheckpoint = json.encodeToJsonElement(checkpoint.copy(occurrences = emptyList())).jsonObject
                json.encodeToString(JsonObject(liveCheckpoint + ("occurrences" to occurrencePayload)))
            }
            val encodeMillis = encodeMark.elapsedNow().inWholeMicroseconds / 1_000.0
            val writeMark = TimeSource.Monotonic.markNow()
            queries.transaction {
                if (queueChanged) {
                    queries.deletePlaybackSessionQueue(id)
                    session.tracks.forEachIndexed { index, track ->
                        queries.insertPlaybackSessionQueueTrack(
                            source_id = id,
                            queue_index = index.toLong(),
                            remote_track_id = track.id,
                            payload = queuePayloads[index],
                        )
                    }
                }
                queries.upsertPlaybackSessionState(
                    source_id = id,
                    current_index = session.currentIndex.toLong(),
                    play_next_count = session.playNextCount.toLong(),
                    queue_groups_payload = queueGroupsPayload,
                    position_seconds = session.positionSeconds,
                    internet_radio_payload = radioPayload,
                    now_playing_open = if (session.nowPlayingOpen) 1L else 0L,
                    updated_at_epoch_millis = nowEpochMillis(),
                    generated_radio_checkpoint_payload = generatedRadioPayload,
                )
                queries.deletePlaybackSession(id)
            }
            if (queueChanged) {
                queueTrackIds[id] = nextTrackIds
                queuePayloadCharacters[id] = queuePayloads.sumOf { it.length }
            }
            performance = performance.copy(
                encodeMillis = encodeMillis,
                writeMillis = writeMark.elapsedNow().inWholeMicroseconds / 1_000.0,
                payloadCharacters = queuePayloadCharacters[id] ?: 0,
                queueRewritten = queueChanged,
            )
        }
    }

    override fun performanceSnapshot(): PlaybackSessionRepositoryPerformance = performance
}

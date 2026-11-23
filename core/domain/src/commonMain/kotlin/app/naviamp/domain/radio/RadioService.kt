package app.naviamp.domain.radio

import app.naviamp.domain.Album
import app.naviamp.domain.AlbumId
import app.naviamp.domain.Artist
import app.naviamp.domain.ArtistId
import app.naviamp.domain.Track
import app.naviamp.domain.TrackId
import app.naviamp.domain.cache.ProviderResponseService
import app.naviamp.domain.provider.MediaProvider
import app.naviamp.domain.sonicmix.SonicMixBias
import app.naviamp.domain.sonicmix.sonicMixCandidateScore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

fun generatedRadioQueue(seedTrack: Track, fetchedTracks: List<Track>): List<Track> =
    (listOf(seedTrack) + fetchedTracks).distinctBy { it.id }

class RadioService(
    private val provider: MediaProvider,
    private val count: Int = DefaultRadioCount,
    private val tuning: RadioTuningSettings = RadioTuningSettings(),
    private val providerResponseService: ProviderResponseService? = null,
    private val similarityMethod: RadioSimilarityMethod = RadioSimilarityMethod.InstantMix,
    private val onSimilarityResult: (SimilarityResult) -> Unit = {},
) {
    private val similarity = SimilarityService(provider)

    /** Recommendations only: the owner adds an explicit seed prefix once, never on refills. */
    suspend fun stationTracks(
        station: GeneratedRadioStation,
        excludedTrackIds: Set<TrackId> = emptySet(),
        previousTrack: Track? = null,
        nowEpochMillis: Long = 0L,
    ): List<Track> {
        validateStation(station)
        val seedIds = station.seedTracks.map { TrackId(it.id) }.distinct()
        val excluded = excludedTrackIds + seedIds
        val target = station.batchSize.coerceIn(1, MaxRadioMinimumTracks)
        val requested = (target * 3 + excluded.size).coerceAtMost(300)
        val sonic = station.sonicSimilarity || station.kind == GeneratedRadioKind.SonicMix
        val candidates = if (sonic || station.kind == GeneratedRadioKind.Track) {
            if (sonic && !provider.capabilities.supportsSonicSimilarity) {
                throw UnsupportedOperationException("Sonic similarity is unavailable for this source. Choose another method explicitly.")
            }
            val result = similarity.load(SimilarityRequest(seedIds, excluded, requested, preferSonic = sonic))
            onSimilarityResult(result)
            val bias = SonicMixBias.entries.firstOrNull { it.name == station.sonicBias } ?: SonicMixBias.Balanced
            result.getOrThrow().flatMapIndexed { index, seed ->
                seed.matches.map { match ->
                    RadioCandidate(match.track, index, if (station.kind == GeneratedRadioKind.SonicMix) {
                        sonicMixCandidateScore(match, bias, nowEpochMillis)
                    } else match.similarity ?: 0.0)
                }
            }
        } else {
            val collected = mutableListOf<RadioCandidate>()
            var firstFailure: Exception? = null
            suspend fun collect(index: Int, load: suspend () -> List<Track>) {
                try {
                    load().take(requested).forEach { collected += RadioCandidate(it, index) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (firstFailure == null) firstFailure = failure
                }
            }
            val completed = withTimeoutOrNull(SimilarityRequestTimeoutMillis) {
                when (station.kind) {
                    GeneratedRadioKind.Artist, GeneratedRadioKind.Artists -> station.artists.forEachIndexed { index, artist ->
                        collect(index) { provider.artistRadio(ArtistId(artist.id), requested) }
                    }
                    GeneratedRadioKind.Album, GeneratedRadioKind.Albums, GeneratedRadioKind.RandomAlbum -> station.albums.forEachIndexed { index, album ->
                        collect(index) { provider.albumRadio(AlbumId(album.id), requested) }
                    }
                    GeneratedRadioKind.Genres -> station.genres.forEachIndexed { index, genre ->
                        collect(index) { provider.genreRadio(genre, requested) }
                    }
                    GeneratedRadioKind.Decade -> collect(0) {
                        provider.randomSongs(requested, fromYear = station.fromYear, toYear = station.toYear)
                    }
                    GeneratedRadioKind.Library -> collect(0) { provider.randomSongs(requested) }
                    GeneratedRadioKind.Track, GeneratedRadioKind.SonicMix -> error("Similarity recipes are collected above.")
                }
                true
            } == true
            if (collected.isEmpty()) {
                if (!completed) throw SimilarityRequestException(timedOut = true)
                firstFailure?.let { throw it }
            }
            collected
        }
        return selectRadioCandidates(
            candidates = candidates,
            targetCount = target,
            tuning = station.tuning,
            excludedTrackIds = excluded,
            previousTrack = previousTrack,
            anchorArtistId = station.resolvedArtistId?.let(::ArtistId),
            decadeStart = station.resolvedDecadeStart,
            fromYear = station.fromYear,
            toYear = station.toYear,
            genres = station.genres,
            excludedAlbumIds = station.excludedAlbumIds.toSet(),
        )
    }

    fun validateStation(station: GeneratedRadioStation) {
        fun requireRecipe(valid: Boolean, message: String) {
            if (!valid) throw RadioRecipeValidationException(message)
        }
        when (station.kind) {
            GeneratedRadioKind.Track -> requireRecipe(station.seedTracks.isNotEmpty(), "Select at least one seed track.")
            GeneratedRadioKind.SonicMix -> {
                requireRecipe(station.seedTracks.distinctBy { it.id }.size in 2..8, "Select between two and eight Sonic Mix seeds.")
                requireRecipe(SonicMixBias.entries.any { it.name == station.sonicBias }, "Choose a supported Sonic Mix bias.")
            }
            GeneratedRadioKind.Artist, GeneratedRadioKind.Artists -> requireRecipe(station.artists.isNotEmpty(), "Select an artist.")
            GeneratedRadioKind.Album, GeneratedRadioKind.Albums, GeneratedRadioKind.RandomAlbum -> requireRecipe(station.albums.isNotEmpty(), "Select an album.")
            GeneratedRadioKind.Genres -> requireRecipe(station.genres.any { it.isNotBlank() }, "Select a genre.")
            GeneratedRadioKind.Decade -> requireRecipe(station.fromYear != null && station.toYear != null, "Select a release-year range.")
            GeneratedRadioKind.Library -> Unit
        }
        requireRecipe(station.fromYear == null || station.toYear == null || station.fromYear <= station.toYear,
            "The release-year range must end after it starts.")
        if (station.tuning.sameDecadeOnly) requireRecipe(station.resolvedDecadeStart != null,
            "Select a seed with a known release year or disable Same decade.")
        if (station.tuning.artistRunMode != RadioArtistRunMode.Mixed) {
            requireRecipe(!station.resolvedArtistId.isNullOrBlank(), "Select a catalog artist or use Mixed artists.")
        }
        if (station.tuning.artistRunMode == RadioArtistRunMode.SingleArtist) {
            requireRecipe(station.artists.all { it.id == station.resolvedArtistId } &&
                station.seedTracks.all { it.artistId == station.resolvedArtistId },
                "Multiple or unknown seed artists conflict with Single artist. Select one artist or remove the constraint.")
        }
        if (station.sonicSimilarity) {
            requireRecipe(station.seedTracks.isNotEmpty(), "Resolve the original Sonic seed tracks before starting this station.")
            when (station.kind) {
                GeneratedRadioKind.Artist, GeneratedRadioKind.Artists -> requireRecipe(
                    station.artists.all { artist -> station.seedTracks.any { it.artistId == artist.id } },
                    "Select an original Sonic seed track for every artist.")
                GeneratedRadioKind.Album, GeneratedRadioKind.Albums, GeneratedRadioKind.RandomAlbum -> requireRecipe(
                    station.albums.all { album -> station.seedTracks.any { it.albumId == album.id } },
                    "Select an original Sonic seed track for every album.")
                GeneratedRadioKind.Genres, GeneratedRadioKind.Decade, GeneratedRadioKind.Library ->
                    throw UnsupportedOperationException("Sonic similarity requires track, artist, album or Sonic Mix seeds.")
                GeneratedRadioKind.Track, GeneratedRadioKind.SonicMix -> Unit
            }
        }
    }

    suspend fun albumSeed(album: Album, loadedTracks: List<Track> = emptyList()): Track? =
        loadedTracks.randomOrNull()
            ?: albumTracks(album.id).randomOrNull()

    suspend fun artistSeed(artist: Artist, loadedAlbums: List<Album> = emptyList()): Track? {
        val albums = loadedAlbums.ifEmpty {
            providerResponseService?.artist(provider, artist.id)?.albums
                ?: provider.artist(artist.id).albums
        }
        return albums.shuffled().firstNotNullOfOrNull { album ->
            albumTracks(album.id)
                .filter { it.matchesArtist(artist.id, artist.name) }
                .randomOrNull()
        }
    }

    suspend fun albumRadio(albumId: AlbumId, fallbackTracks: List<Track> = emptyList()): List<Track> {
        requireSimilarityMethod(similarityMethod == RadioSimilarityMethod.SonicSimilarity)
        val albumTracks = fallbackTracks.ifEmpty { albumTracks(albumId) }
        val seedTrack = albumTracks.randomOrNull()
        if (similarityMethod == RadioSimilarityMethod.SonicSimilarity) {
            return seedTrack?.let { trackRadio(it, true) }.orEmpty()
        }
        val providerTracks = provider.albumRadio(albumId, count = fetchCount)
        if (seedTrack == null) {
            val fallback = providerTracks.ifEmpty { albumTracks.shuffled() }
            return tunedRadioTracks(
                seedTrack = fallback.firstOrNull(),
                tracks = fallback,
                tuning = tuning,
                targetCount = count,
            )
        }
        val candidates = providerTracks
            .ifEmpty { albumTracks.shuffled() }
            .filterNot { track -> track.id == seedTrack.id }
        val tuned = tunedRadioTracks(
            seedTrack = seedTrack,
            tracks = candidates,
            tuning = tuning,
            targetCount = (count - 1).coerceAtLeast(0),
        )
        return generatedRadioQueue(seedTrack, tuned).take(count)
    }

    suspend fun artistRadio(artistId: ArtistId): List<Track> {
        requireSimilarityMethod(similarityMethod == RadioSimilarityMethod.SonicSimilarity)
        if (similarityMethod == RadioSimilarityMethod.SonicSimilarity) {
            val details = providerResponseService?.artist(provider, artistId) ?: provider.artist(artistId)
            return artistSeed(details.artist, details.albums)?.let { trackRadio(it, true) }.orEmpty()
        }
        return provider.artistRadio(artistId, count = fetchCount)
            .let { tracks -> tunedRadioTracks(seedTrack = tracks.firstOrNull(), tracks = tracks, tuning = tuning, targetCount = count) }
    }

    suspend fun trackRadio(trackId: TrackId): List<Track> {
        requireSimilarityMethod(similarityMethod == RadioSimilarityMethod.SonicSimilarity)
        val seed = if (tuning.sameDecadeOnly || tuning.artistRunMode != RadioArtistRunMode.Mixed ||
            tuning.artistSpread == RadioArtistSpread.Narrow) provider.track(trackId) else null
        val result = similarity.load(SimilarityRequest(listOf(trackId), countPerSeed = fetchCount,
            preferSonic = similarityMethod == RadioSimilarityMethod.SonicSimilarity))
        onSimilarityResult(result)
        return result.getOrThrow().flatMap { matches -> matches.matches.map { it.track } }
            .let { tracks -> tunedRadioTracks(seedTrack = seed, tracks = tracks, tuning = tuning, targetCount = count) }
    }

    suspend fun trackRadio(
        seedTrack: Track,
        preferSonicSimilarity: Boolean,
    ): List<Track> {
        requireSimilarityMethod(preferSonicSimilarity)
        val result = similarity.load(SimilarityRequest(
            listOf(seedTrack.id), countPerSeed = fetchCount, preferSonic = preferSonicSimilarity,
        ))
        onSimilarityResult(result)
        val primary = result.getOrThrow().flatMap { seed -> seed.matches.map { it.track } }
        return tunedTrackRadio(seedTrack, primary, allowArtistExpansion = result.source != SimilarityEndpoint.Sonic)
    }

    private fun requireSimilarityMethod(sonic: Boolean) {
        if (!sonic || provider.capabilities.supportsSonicSimilarity) return
        onSimilarityResult(SimilarityResult(SimilarityEndpoint.Sonic, emptyList(),
            failure = SimilarityEndpointResult(SimilarityResultKind.Unsupported)))
        throw UnsupportedOperationException("Sonic similarity is unavailable for this source. Choose another method explicitly.")
    }

    suspend fun libraryRadio(): List<Track> =
        provider.randomSongs(limit = LibraryRadioCount)
            .let { tracks ->
                tunedRadioTracks(
                    seedTrack = null,
                    tracks = tracks,
                    tuning = tuning,
                    targetCount = LibraryRadioCount,
                )
            }

    suspend fun genreRadio(genre: String): List<Track> =
        provider.genreRadio(genre, count = fetchCount)
            .let { tracks -> tunedRadioTracks(seedTrack = null, tracks = tracks, tuning = tuning, targetCount = count) }

    suspend fun decadeRadio(fromYear: Int, toYear: Int): List<Track> =
        provider.randomSongs(limit = fetchCount, fromYear = fromYear, toYear = toYear)
            .let { tracks -> tunedRadioTracks(seedTrack = null, tracks = tracks, tuning = tuning, targetCount = count) }

    fun queue(seedTrack: Track, fetchedTracks: List<Track>): List<Track> =
        generatedRadioQueue(seedTrack, fetchedTracks)

    private suspend fun albumTracks(albumId: AlbumId): List<Track> =
        providerResponseService?.album(provider, albumId)?.tracks
            ?: provider.album(albumId).tracks

    private suspend fun tunedTrackRadio(seedTrack: Track, tracks: List<Track>, allowArtistExpansion: Boolean): List<Track> {
        val candidateTracks = if (!allowArtistExpansion || tuning.artistRunMode == RadioArtistRunMode.Mixed) {
            tracks
        } else {
            sameArtistTracks(seedTrack) + tracks
        }
        return tunedRadioTracks(
            seedTrack = seedTrack,
            tracks = candidateTracks.filterNot { it.id == seedTrack.id },
            tuning = tuning,
            targetCount = count,
        )
    }

    private suspend fun sameArtistTracks(seedTrack: Track): List<Track> {
        val artistId = seedTrack.artistId ?: return emptyList()
        val albums = try {
            providerResponseService?.artist(provider, artistId)?.albums
                ?: provider.artist(artistId).albums
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return emptyList()
        }
        return albums
            .flatMap { album ->
                try {
                    albumTracks(album.id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
            }
            .filter { track -> track.matchesArtist(artistId, seedTrack.artistName) && track.id != seedTrack.id }
            .distinctBy { it.id }
    }

    private val fetchCount: Int
        get() = when (tuning) {
            RadioTuningSettings() -> count
            else -> (count * 3).coerceAtLeast(count)
        }

    private fun Track.matchesArtist(artistId: ArtistId, artistName: String): Boolean =
        if (this.artistId != null) {
            this.artistId == artistId
        } else {
            this.artistName.equals(artistName, ignoreCase = true)
        }

    private companion object {
        const val DefaultRadioCount = 50
        const val LibraryRadioCount = 500
    }
}

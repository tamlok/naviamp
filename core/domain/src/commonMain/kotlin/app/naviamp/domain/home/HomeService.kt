package app.naviamp.domain.home

import app.naviamp.domain.Album
import app.naviamp.domain.Artist
import app.naviamp.domain.Genre
import app.naviamp.domain.InternetRadioStation
import app.naviamp.domain.Playlist
import app.naviamp.domain.Track
import app.naviamp.domain.cache.FavoriteDownloadCatalog
import app.naviamp.domain.cache.enumerateFavoriteDownloadCatalog
import app.naviamp.domain.cache.ProviderResponseService
import app.naviamp.domain.navibeat.NavibeatMix
import app.naviamp.domain.navibeat.partitionNavibeatMixes
import app.naviamp.domain.navibeat.prioritizedForHour
import app.naviamp.domain.provider.AlbumListType
import app.naviamp.domain.provider.MediaProvider
import app.naviamp.domain.settings.RecentRadioStream
import app.naviamp.domain.radio.recentRadioStreamsForSource
import kotlinx.coroutines.CancellationException

enum class HomeSection {
    RecentlyAdded, RandomAlbums, RecentAlbums, FrequentAlbums, Artists, FavoriteArtists,
    FavoriteAlbums, Playlists, RecentlyPlayed, RadioStations, Genres, GenreSpotlight, Decade,
}

data class HomeContent(
    val date: HomeDate = HomeDate(year = 1970, dayOfYear = 1),
    val recentlyAddedAlbums: List<Album> = emptyList(),
    val mixAlbums: List<Album> = emptyList(),
    val recentAlbums: List<Album> = emptyList(),
    val frequentAlbums: List<Album> = emptyList(),
    val randomAlbums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val favoriteArtists: List<Artist> = emptyList(),
    val favoriteArtistsStatus: FavoriteArtistsStatus = FavoriteArtistsStatus.Unsupported,
    val favoriteArtistLastPlayed: Map<app.naviamp.domain.ArtistId, String> = emptyMap(),
    val supportsFavoriteSongs: Boolean = false,
    val favoriteAlbums: List<Album> = emptyList(),
    val favoriteAlbumsFailed: Boolean = false,
    val playlists: List<Playlist> = emptyList(),
    val navibeatMixes: List<NavibeatMix> = emptyList(),
    val recentRadioStreams: List<RecentRadioStream> = emptyList(),
    val recentlyPlayedTracks: List<Track> = emptyList(),
    val radioStations: List<InternetRadioStation> = emptyList(),
    val recentInternetRadioStations: List<InternetRadioStation> = emptyList(),
    val genres: List<Genre> = emptyList(),
    val genreSpotlight: Genre? = null,
    val genreSpotlightAlbums: List<Album> = emptyList(),
    val decadeLabel: String = "Decade",
    val decadeFromYear: Int = 0,
    val decadeToYear: Int = 0,
    val decadeAlbums: List<Album> = emptyList(),
    val failedSections: Set<HomeSection> = emptySet(),
) {
    val isEmpty: Boolean
        get() = recentlyAddedAlbums.isEmpty() &&
            mixAlbums.isEmpty() &&
            recentAlbums.isEmpty() &&
            frequentAlbums.isEmpty() &&
            randomAlbums.isEmpty() &&
            artists.isEmpty() &&
            favoriteArtists.isEmpty() &&
            favoriteArtistsStatus == FavoriteArtistsStatus.Unsupported &&
            !supportsFavoriteSongs &&
            favoriteAlbums.isEmpty() &&
            !favoriteAlbumsFailed &&
            playlists.isEmpty() &&
            navibeatMixes.isEmpty() &&
            recentRadioStreams.isEmpty() &&
            recentlyPlayedTracks.isEmpty() &&
            radioStations.isEmpty() &&
            recentInternetRadioStations.isEmpty() &&
            genres.isEmpty() &&
            genreSpotlightAlbums.isEmpty() &&
            decadeAlbums.isEmpty()
}

fun HomeContent.mixBuilderAlbumCandidates(): List<Album> =
    (recentlyPlayedTracks.mapNotNull(Track::toMixBuilderAlbum) +
        randomAlbums + mixAlbums + recentAlbums + frequentAlbums).distinctBy { it.id }

fun HomeContent.mixBuilderArtistCandidates(): List<Artist> =
    (recentlyPlayedTracks.mapNotNull(Track::toMixBuilderArtist) + artists).distinctBy { it.id }

private fun Track.toMixBuilderArtist(): Artist? {
    val resolvedId = artistId ?: artistCredits.firstNotNullOfOrNull { credit -> credit.id } ?: return null
    val resolvedName = artistCredits.firstOrNull { credit -> credit.id == resolvedId }?.name ?: artistName
    return Artist(resolvedId, resolvedName)
}

private fun Track.toMixBuilderAlbum(): Album? {
    val resolvedId = albumId ?: return null
    val resolvedTitle = albumTitle ?: return null
    return Album(
        id = resolvedId,
        title = resolvedTitle,
        artistName = artistName,
        coverArtId = coverArtId,
        recentlyAddedAtIso8601 = null,
        releaseYear = albumReleaseYear,
        originalReleaseYear = originalReleaseYear,
        artistCredits = artistCredits,
    )
}

data class HomeDate(
    val year: Int,
    val dayOfYear: Int,
    val hourOfDay: Int = 12,
)

interface FavoriteArtistActivityRepository {
    fun reconcileFavoriteArtists(
        sourceId: String,
        artists: List<Artist>,
        observedAtIso8601: String,
        complete: Boolean = true,
    ): List<Artist> = artists

    fun locallyKnownFavoriteArtists(sourceId: String, limit: Long = 500): List<Artist> = emptyList()

    fun favoriteArtistRadioLastPlayed(sourceId: String): Map<app.naviamp.domain.ArtistId, String> = emptyMap()

    fun setArtistFavoriteActivity(
        sourceId: String,
        artist: Artist,
        favorite: Boolean,
        changedAtIso8601: String,
    ) = Unit

    fun recordArtistRadioPlayed(sourceId: String, artist: Artist, playedAtIso8601: String) = Unit

    fun recordTrackArtistRadioPlayedIfFavorite(
        sourceId: String,
        artistId: app.naviamp.domain.ArtistId,
        artistName: String,
        playedAtIso8601: String,
    ): Boolean = false
}

interface HomeLibraryRepository : FavoriteArtistActivityRepository {
    fun albumYears(sourceId: String): List<HomeAlbumYear>

    fun recentlyPlayedTracks(sourceId: String, limit: Long = 12): List<Track> = emptyList()

    fun recordPlayedTrack(sourceId: String, track: Track, playedAtEpochMillis: Long) = Unit
}

data class HomeAlbumYear(
    val year: Int,
    val albumCount: Long,
)

class HomeService(
    private val provider: MediaProvider,
    private val providerResponseService: ProviderResponseService? = null,
    private val libraryRepository: HomeLibraryRepository? = null,
    private val sourceId: String? = null,
    private val date: HomeDate,
    private val observedAtIso8601: () -> String = { "" },
) {
    suspend fun load(
        recentRadioStreams: List<RecentRadioStream> = emptyList(),
        recentInternetRadioStations: List<InternetRadioStation> = emptyList(),
        artistLimit: Int = HomeDefaultArtistLimit,
        forceRefreshRecentlyAdded: Boolean = false,
    ): HomeContent {
        val failures = mutableSetOf<HomeSection>()
        val genres = attempt(HomeSection.Genres, failures, emptyList()) { provider.genres(limit = 12) }
            .rotatedBy(date.dayOfYear)
        val genreSpotlight = genres.firstOrNull()
        val decadePick = pickHomeDecade(failures)
        val playlistPartition = attempt(HomeSection.Playlists, failures, emptyList()) { playlists(limit = 50) }
            .partitionNavibeatMixes()
        val favoriteArtists = loadFavoriteArtists()
        if (favoriteStatus == FavoriteArtistsStatus.Failed || favoriteStatus == FavoriteArtistsStatus.Cached) {
            failures += HomeSection.FavoriteArtists
        }
        val favoriteAlbums = attempt(HomeSection.FavoriteAlbums, failures, emptyList()) { loadFavoriteAlbums() }
        val randomPool = attempt(HomeSection.RandomAlbums, failures, emptyList()) { albumList(AlbumListType.Random, limit = 14) }
        if (HomeSection.Genres in failures) failures += HomeSection.GenreSpotlight
        return HomeContent(
            date = date,
            recentlyAddedAlbums = attempt(HomeSection.RecentlyAdded, failures, emptyList()) {
                albumList(
                    AlbumListType.Newest,
                    limit = 8,
                    maxAgeMillis = if (forceRefreshRecentlyAdded) 0L else HomeRecentlyAddedMaximumAgeMillis,
                )
            },
            mixAlbums = randomPool.take(8),
            recentAlbums = attempt(HomeSection.RecentAlbums, failures, emptyList()) { albumList(AlbumListType.Recent, limit = 6) },
            frequentAlbums = attempt(HomeSection.FrequentAlbums, failures, emptyList()) { albumList(AlbumListType.Frequent, limit = 6) },
            randomAlbums = randomPool.drop(8).ifEmpty { randomPool }.take(6),
            artists = attempt(HomeSection.Artists, failures, emptyList()) { artists(limit = artistLimit) },
            favoriteArtists = favoriteArtists,
            favoriteArtistsStatus = favoriteStatus,
            favoriteArtistLastPlayed = sourceId?.let { id ->
                homeAttempt { libraryRepository?.favoriteArtistRadioLastPlayed(id) }.getOrNull()
            }.orEmpty(),
            supportsFavoriteSongs = provider.capabilities.supportsTrackFavorites,
            favoriteAlbums = favoriteAlbums,
            favoriteAlbumsFailed = HomeSection.FavoriteAlbums in failures,
            playlists = playlistPartition.ordinary,
            navibeatMixes = playlistPartition.mixes.prioritizedForHour(date.hourOfDay),
            recentRadioStreams = recentRadioStreamsForSource(recentRadioStreams, sourceId),
            recentlyPlayedTracks = sourceId
                ?.let { id -> attempt(HomeSection.RecentlyPlayed, failures, emptyList()) { libraryRepository?.recentlyPlayedTracks(id, 12).orEmpty() } }
                .orEmpty(),
            radioStations = attempt(HomeSection.RadioStations, failures, emptyList()) { internetRadioStations() },
            recentInternetRadioStations = recentInternetRadioStations,
            genres = genres,
            genreSpotlight = genreSpotlight,
            genreSpotlightAlbums = genreSpotlight?.let { genre ->
                attempt(HomeSection.GenreSpotlight, failures, emptyList()) { albumsByGenre(genre.name, limit = 6) }
            }.orEmpty(),
            decadeLabel = decadePick?.label ?: "Decade",
            decadeFromYear = decadePick?.fromYear ?: 0,
            decadeToYear = decadePick?.toYear ?: 0,
            decadeAlbums = decadePick?.albums.orEmpty(),
            failedSections = failures.toSet(),
        )
    }

    private var favoriteStatus = FavoriteArtistsStatus.Unsupported

    private suspend fun loadFavoriteArtists(): List<Artist> {
        if (!provider.capabilities.supportsArtistFavorites) return emptyList()
        val result = try { Result.success(provider.favoriteArtists(limit = 500)) }
        catch (cause: kotlinx.coroutines.CancellationException) { throw cause }
        catch (cause: Exception) { Result.failure(cause) }
        favoriteStatus = if (result.isSuccess) FavoriteArtistsStatus.Ready else FavoriteArtistsStatus.Failed
        val id = sourceId
        val repository = libraryRepository
        if (id == null || repository == null) return result.getOrDefault(emptyList())
        return result.fold(
            onSuccess = { artists ->
                homeAttempt {
                    repository.reconcileFavoriteArtists(id, artists, observedAtIso8601(), complete = artists.size < 500)
                }.getOrDefault(artists)
            },
            onFailure = {
                homeAttempt { repository.locallyKnownFavoriteArtists(id, 500) }
                    .getOrDefault(emptyList()).also {
                        if (it.isNotEmpty()) favoriteStatus = FavoriteArtistsStatus.Cached
                    }
            },
        )
    }

    private suspend fun loadFavoriteAlbums(): List<Album> {
        if (!provider.capabilities.supportsAlbumFavorites) return emptyList()
        val albums = when (val catalog = enumerateFavoriteDownloadCatalog(Int.MAX_VALUE, provider::favoriteAlbumsPage)) {
            is FavoriteDownloadCatalog.Complete -> catalog.items
            is FavoriteDownloadCatalog.TooLarge -> error("Favorite album catalog exceeds the supported size.")
            FavoriteDownloadCatalog.Unsupported -> error("Favorite album enumeration is unavailable.")
        }
        return albums.distinctBy { it.id }
            .map { album ->
                if (album.favoritedAtIso8601.isNullOrBlank()) album.copy(favoritedAtIso8601 = "favorite") else album
            }
            .sortedWith(compareBy<Album> { it.title.lowercase() }.thenBy { it.title }.thenBy { it.id.value })
    }

    private suspend fun pickHomeDecade(failures: MutableSet<HomeSection>): HomeDecadePick? {
        val candidates = sourceId
            ?.let { attempt(HomeSection.Decade, failures, emptyList()) { libraryRepository?.albumYears(it).orEmpty() } }
            .orEmpty()
            .toHomeDecadeCandidates(date)
            .ifEmpty { fallbackHomeDecadeCandidates(date) }

        return candidates.firstNotNullOfOrNull { candidate ->
            val albums = attempt(HomeSection.Decade, failures, emptyList()) {
                albumsByYear(candidate.fromYear, candidate.toYear, limit = 6)
            }
            albums.takeIf { it.isNotEmpty() }?.let {
                HomeDecadePick(
                    label = homeDecadeLabel(candidate.fromYear),
                    fromYear = candidate.fromYear,
                    toYear = candidate.toYear,
                    albums = it,
                )
            }
        }
    }

    private suspend fun albumList(
        type: AlbumListType,
        limit: Int,
        maxAgeMillis: Long? = null,
    ): List<Album> =
        providerResponseService?.albumList(provider, type, limit, maxAgeMillis)
            ?: provider.albumList(type, limit)

    private suspend fun albumsByGenre(genre: String, limit: Int): List<Album> =
        providerResponseService?.albumsByGenre(provider, genre, limit)
            ?: provider.albumsByGenre(genre, limit)

    private suspend fun albumsByYear(fromYear: Int, toYear: Int, limit: Int): List<Album> =
        providerResponseService?.albumsByYear(provider, fromYear, toYear, limit)
            ?: provider.albumsByYear(fromYear, toYear, limit)

    private suspend fun artists(limit: Int): List<Artist> =
        providerResponseService?.artists(provider, limit)
            ?: provider.artists(limit)

    private suspend fun playlists(limit: Int): List<Playlist> =
        providerResponseService?.playlists(provider, limit)
            ?: provider.playlists(limit)

    private suspend fun internetRadioStations(): List<InternetRadioStation> =
        providerResponseService?.internetRadioStations(provider)
            ?: provider.internetRadioStations()
}

private suspend inline fun <T> homeAttempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (cause: Exception) {
    Result.failure(cause)
}

private suspend inline fun <T> attempt(
    section: HomeSection,
    failures: MutableSet<HomeSection>,
    fallback: T,
    block: () -> T,
): T = homeAttempt(block).getOrElse { failures += section; fallback }

/** Only a newly failed, empty section may retain its same-source cached payload. */
fun HomeContent.retainFailedSections(previous: HomeContent): HomeContent {
    if (failedSections.isEmpty()) return this
    fun <T> retain(section: HomeSection, incoming: List<T>, cached: List<T>): List<T> =
        if (section in failedSections && incoming.isEmpty()) cached else incoming
    val retainPlaylists = HomeSection.Playlists in failedSections && playlists.isEmpty() && navibeatMixes.isEmpty()
    val retainSpotlight = HomeSection.GenreSpotlight in failedSections && genreSpotlightAlbums.isEmpty() && previous.genreSpotlightAlbums.isNotEmpty()
    val retainDecade = HomeSection.Decade in failedSections && decadeAlbums.isEmpty()
    val retainedArtists = retain(HomeSection.FavoriteArtists, favoriteArtists, previous.favoriteArtists)
    return copy(
        recentlyAddedAlbums = retain(HomeSection.RecentlyAdded, recentlyAddedAlbums, previous.recentlyAddedAlbums),
        mixAlbums = retain(HomeSection.RandomAlbums, mixAlbums, previous.mixAlbums),
        randomAlbums = retain(HomeSection.RandomAlbums, randomAlbums, previous.randomAlbums),
        recentAlbums = retain(HomeSection.RecentAlbums, recentAlbums, previous.recentAlbums),
        frequentAlbums = retain(HomeSection.FrequentAlbums, frequentAlbums, previous.frequentAlbums),
        artists = retain(HomeSection.Artists, artists, previous.artists),
        favoriteArtists = retainedArtists,
        favoriteArtistsStatus = if (HomeSection.FavoriteArtists in failedSections && retainedArtists.isNotEmpty()) FavoriteArtistsStatus.Cached else favoriteArtistsStatus,
        favoriteAlbums = retain(HomeSection.FavoriteAlbums, favoriteAlbums, previous.favoriteAlbums),
        playlists = if (retainPlaylists) previous.playlists else playlists,
        navibeatMixes = if (retainPlaylists) previous.navibeatMixes else navibeatMixes,
        recentlyPlayedTracks = retain(HomeSection.RecentlyPlayed, recentlyPlayedTracks, previous.recentlyPlayedTracks),
        radioStations = retain(HomeSection.RadioStations, radioStations, previous.radioStations),
        genres = if (retainSpotlight) previous.genres else retain(HomeSection.Genres, genres, previous.genres),
        genreSpotlight = if (retainSpotlight) previous.genreSpotlight else genreSpotlight,
        genreSpotlightAlbums = if (retainSpotlight) previous.genreSpotlightAlbums else genreSpotlightAlbums,
        decadeAlbums = if (retainDecade) previous.decadeAlbums else decadeAlbums,
        decadeLabel = if (retainDecade) previous.decadeLabel else decadeLabel,
        decadeFromYear = if (retainDecade) previous.decadeFromYear else decadeFromYear,
        decadeToYear = if (retainDecade) previous.decadeToYear else decadeToYear,
    )
}

private const val HomeRecentlyAddedMaximumAgeMillis = 5 * 60 * 1000L

data class HomeStation(
    val id: String,
    val title: String,
    val subtitle: String,
)

fun homeStations(homeContent: HomeContent): List<HomeStation> =
    buildList {
        add(HomeStation(HomeStationLibrary, "Library Radio", "Random tracks from your full library"))
        add(HomeStation(HomeStationRandomAlbum, "Random Album Radio", "Start from a random album"))
        homeContent.genres.take(3).forEach { genre ->
            add(HomeStation(homeGenreStationId(genre.name), "${genre.name} Radio", "A random ${genre.name} station"))
        }
        if (homeContent.decadeAlbums.isNotEmpty()) {
            add(
                HomeStation(
                    homeDecadeStationId(homeContent.decadeFromYear, homeContent.decadeToYear),
                    "${homeContent.decadeLabel} Radio",
                    "Random songs from ${homeContent.decadeLabel}",
                ),
            )
        }
    }

fun homeGenreStationId(genre: String): String =
    "$HomeStationGenrePrefix$genre"

fun homeDecadeStationId(fromYear: Int, toYear: Int): String =
    "$HomeStationDecadePrefix$fromYear:$toYear"

fun parseHomeGenreStationId(id: String): String? =
    id.takeIf { it.startsWith(HomeStationGenrePrefix) }?.removePrefix(HomeStationGenrePrefix)

fun parseHomeDecadeStationId(id: String): HomeDecadeRange? {
    val value = id.takeIf { it.startsWith(HomeStationDecadePrefix) }
        ?.removePrefix(HomeStationDecadePrefix)
        ?: return null
    val years = value.split(":")
    val fromYear = years.getOrNull(0)?.toIntOrNull() ?: return null
    val toYear = years.getOrNull(1)?.toIntOrNull() ?: return null
    return HomeDecadeRange(fromYear, toYear)
}

data class HomeDecadeRange(
    val fromYear: Int,
    val toYear: Int,
)

private data class HomeDecadePick(
    val label: String,
    val fromYear: Int,
    val toYear: Int,
    val albums: List<Album>,
)

private data class HomeDecadeCandidate(
    val fromYear: Int,
    val toYear: Int,
)

private fun List<HomeAlbumYear>.toHomeDecadeCandidates(date: HomeDate): List<HomeDecadeCandidate> =
    groupBy { it.year.floorToDecade() }
        .map { (decade, years) ->
            decade to years.sumOf { it.albumCount }
        }
        .sortedWith(compareByDescending<Pair<Int, Long>> { it.second }.thenByDescending { it.first })
        .take(HomeDecadeCandidateLimit)
        .rotatedBy(date.dayOfYear)
        .map { (decade, _) -> HomeDecadeCandidate(decade, decade + 9) }

private fun fallbackHomeDecadeCandidates(date: HomeDate): List<HomeDecadeCandidate> {
    val currentDecade = date.year.floorToDecade()
    return generateSequence(currentDecade) { previous ->
        (previous - 10).takeIf { it >= HomeEarliestFallbackDecade }
    }
        .take(HomeDecadeCandidateLimit)
        .toList()
        .rotatedBy(date.dayOfYear)
        .map { decade -> HomeDecadeCandidate(decade, minOf(decade + 9, date.year)) }
}

private fun homeDecadeLabel(fromYear: Int): String =
    "The ${fromYear}s"

fun Int.floorToDecade(): Int =
    (this / 10) * 10

fun <T> List<T>.rotatedBy(offset: Int): List<T> {
    if (isEmpty()) return this
    val normalizedOffset = offset.floorMod(size)
    return drop(normalizedOffset) + take(normalizedOffset)
}

private fun Int.floorMod(divisor: Int): Int =
    ((this % divisor) + divisor) % divisor

const val HomeStationLibrary = "library"
const val HomeStationRandomAlbum = "random-album"
const val HomeDefaultArtistLimit = 50

private const val HomeStationGenrePrefix = "genre:"
private const val HomeStationDecadePrefix = "decade:"
private const val HomeDecadeCandidateLimit = 8
private const val HomeEarliestFallbackDecade = 1950

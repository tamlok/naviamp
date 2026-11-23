package app.naviamp.presentation

import app.naviamp.app.NaviampNavigationPage
import app.naviamp.app.NaviampRoutePage
import app.naviamp.domain.app.NaviampRoute
import app.naviamp.domain.Album
import app.naviamp.domain.Artist
import app.naviamp.domain.Genre
import app.naviamp.domain.Track
import app.naviamp.domain.albummix.AlbumMixBuilderService
import app.naviamp.domain.artistmix.ArtistMixBuilderService
import app.naviamp.domain.genremix.GenreMixBuilderService
import app.naviamp.domain.library.LibraryGenreOntologyProjection
import app.naviamp.domain.mixbuilder.albumMixGeneratedQueue
import app.naviamp.domain.mixbuilder.artistMixGeneratedQueue
import app.naviamp.domain.mixbuilder.genreMixGeneratedQueue
import app.naviamp.ui.SharedAlbumMixBuilderUi
import app.naviamp.ui.SharedArtistMixBuilderUi
import app.naviamp.ui.SharedGenreMixBuilderUi
import app.naviamp.ui.SharedGenreMixItemUi
import app.naviamp.ui.SharedGenreMixTreeRowUi
import app.naviamp.ui.genreDisplayTitle
import app.naviamp.ui.toSharedGenreMixItemUi
import app.naviamp.ui.toSharedMediaItemUi
import app.naviamp.ui.toSharedTrackRowUi
import app.naviamp.domain.genremix.GenreTrackBrowser
import kotlinx.coroutines.CancellationException

interface NaviampCoreStandardMixPlaybackPort {
    suspend fun playArtistMix(artists: List<Artist>, seedTracks: List<Track>)
    suspend fun playAlbumMix(albums: List<Album>, seedTracks: List<Track>)
    suspend fun playGenreMix(genres: List<Genre>)
    suspend fun playGenreSongs(tracks: List<Track>, startIndex: Int)
}

/** Owns Artist, Album, and Genre builder state, selection, discovery, and playback intent. */
class NaviampCoreStandardMixController(
    private val stateStore: NaviampCoreStateStore,
    private val providerSource: NaviampCoreMediaProviderSource,
    private val artistService: () -> ArtistMixBuilderService,
    private val albumService: () -> AlbumMixBuilderService,
    private val genreService: () -> GenreMixBuilderService,
    private val playback: NaviampCoreStandardMixPlaybackPort,
) : NaviampCoreCommandController {
    private var selectedArtists = emptyList<Artist>()
    private var artistSuggestions = emptyList<Artist>()
    private var artistTracks = emptyMap<String, List<Track>>()
    private var selectedAlbums = emptyList<Album>()
    private var albumSuggestions = emptyList<Album>()
    private var albumTracks = emptyMap<String, List<Track>>()
    private var selectedGenres = emptyList<Genre>()
    private var genreSuggestions = emptyList<Genre>()
    private var genreProjection = LibraryGenreOntologyProjection()
    private var expandedGenreOntologyIds = emptySet<String>()
    private var sourceGeneration = 0L
    private var artistGeneration = 0L
    private var albumGeneration = 0L
    private var genreGeneration = 0L
    private var genreBrowser: GenreTrackBrowser? = null
    private var genreBrowserKey: Pair<String?, List<String>>? = null

    internal fun stationRecipe(kind: String): app.naviamp.domain.radio.GeneratedRadioStation? {
        val settings = stateStore.state.value.shell.playback.settings
        val recipe = when (kind) {
            "ArtistMix" -> app.naviamp.domain.radio.GeneratedRadioStation("", app.naviamp.domain.radio.artistMixRadioLabel(selectedArtists), app.naviamp.domain.radio.GeneratedRadioKind.Artists,
                artists = selectedArtists.map(app.naviamp.domain.settings.SavedArtist::fromArtist),
                seedTracks = selectedArtists.mapNotNull { artistTracks[it.id.value]?.firstOrNull() }.map(app.naviamp.domain.settings.SavedTrack::fromTrack))
            "AlbumMix" -> app.naviamp.domain.radio.GeneratedRadioStation("", app.naviamp.domain.radio.albumMixRadioLabel(selectedAlbums), app.naviamp.domain.radio.GeneratedRadioKind.Albums,
                albums = selectedAlbums.map(app.naviamp.domain.settings.SavedAlbum::fromAlbum),
                seedTracks = selectedAlbums.mapNotNull { albumTracks[it.id.value]?.firstOrNull() }.map(app.naviamp.domain.settings.SavedTrack::fromTrack))
            "GenreMix" -> app.naviamp.domain.radio.GeneratedRadioStation("", app.naviamp.domain.radio.genreMixRadioLabel(selectedGenres), app.naviamp.domain.radio.GeneratedRadioKind.Genres,
                genres = selectedGenres.map { it.name })
            else -> return null
        }
        return recipe.copy(tuning = settings.radioTuning, batchSize = settings.radioMinimumTracks, sonicSimilarity = settings.sonicSimilarityEnabled)
    }

    private enum class CandidateKind { Artist, Album, Genre }
    private enum class CandidatePhase { Loading, Failed, Scheduled, Complete }
    private class CandidateLoad(
        val kind: CandidateKind,
        val query: String,
        val source: String?,
        val initial: Boolean,
        val retry: Boolean,
        var phase: CandidatePhase = CandidatePhase.Loading,
    )
    private val candidateLoads = mutableMapOf<CandidateKind, CandidateLoad>()
    // Retain queued command identities until consumed, including across query/source resets.
    private val queuedRecovery = mutableListOf<Pair<NaviampCoreCommand, CandidateLoad>>()

    override fun recoverEmptyPage(page: NaviampNavigationPage): List<NaviampCoreCommand> {
        if (page !is NaviampRoutePage) return emptyList()
        val kind = when (page.route) {
            NaviampRoute.ArtistMix -> CandidateKind.Artist
            NaviampRoute.AlbumMix -> CandidateKind.Album
            NaviampRoute.GenreMix -> CandidateKind.Genre
            else -> return emptyList()
        }
        val load = candidateLoads[kind] ?: return emptyList()
        if (load.phase != CandidatePhase.Failed || !isCurrent(load) || !candidatesEmpty(kind)) return emptyList()
        val command = when (kind) {
            CandidateKind.Artist -> NaviampCoreCommand.MixBuilder.Artist(NaviampCoreCommand.ArtistAction.Search)
            CandidateKind.Album -> NaviampCoreCommand.MixBuilder.Album(NaviampCoreCommand.AlbumAction.Search)
            CandidateKind.Genre -> NaviampCoreCommand.MixBuilder.Genre(NaviampCoreCommand.GenreAction.Search)
        }
        load.phase = CandidatePhase.Scheduled
        queuedRecovery += command to load
        return listOf(command)
    }

    private fun candidateQuery(kind: CandidateKind): String = when (kind) {
        CandidateKind.Artist -> currentArtistUi().query
        CandidateKind.Album -> currentAlbumUi().query
        CandidateKind.Genre -> currentGenreUi().query
    }

    private fun candidatesEmpty(kind: CandidateKind): Boolean = when (kind) {
        CandidateKind.Artist -> artistSuggestions.isEmpty() && selectedArtists.isEmpty() && artistTracks.isEmpty()
        CandidateKind.Album -> albumSuggestions.isEmpty() && selectedAlbums.isEmpty() && albumTracks.isEmpty()
        CandidateKind.Genre -> genreSuggestions.isEmpty() && selectedGenres.isEmpty() &&
            currentGenreUi().treeRows.isEmpty() && currentGenreUi().unmatchedGenres.isEmpty() && currentGenreUi().songs.isEmpty()
    }

    private fun isCurrent(load: CandidateLoad): Boolean = candidateLoads[load.kind] === load &&
        load.source == providerSource.current()?.cacheNamespace && load.query == candidateQuery(load.kind)

    private fun beginCandidates(kind: CandidateKind, initial: Boolean = false): CandidateLoad? {
        val previous = candidateLoads[kind]
        if (previous != null && isCurrent(previous) && previous.phase == CandidatePhase.Loading) return null
        return CandidateLoad(kind, candidateQuery(kind), providerSource.current()?.cacheNamespace, initial,
            previous != null && isCurrent(previous) &&
                (previous.phase == CandidatePhase.Failed || previous.phase == CandidatePhase.Scheduled))
            .also { candidateLoads[kind] = it }
    }

    private fun changeCandidateQuery(kind: CandidateKind, query: String) {
        if (candidateQuery(kind) == query) return
        candidateLoads.remove(kind)
        when (kind) {
            CandidateKind.Artist -> { artistGeneration++; updateArtistUi { it.copy(query = query, loading = false) } }
            CandidateKind.Album -> { albumGeneration++; updateAlbumUi { it.copy(query = query, loading = false) } }
            CandidateKind.Genre -> { genreGeneration++; updateGenreUi { it.copy(query = query, loading = false) } }
        }
    }

    private fun candidateFailure(load: CandidateLoad, cause: Throwable, publish: (String?) -> Unit) {
        if (isCurrent(load)) {
            load.phase = if (cause is CancellationException && !load.retry) CandidatePhase.Complete else CandidatePhase.Failed
            publish(if (cause is CancellationException) null else cause.message)
        }
        if (cause is CancellationException) throw cause
    }

    fun resetForSourceChange() {
        sourceGeneration++
        artistGeneration++
        albumGeneration++
        genreGeneration++
        candidateLoads.clear()
        selectedArtists = emptyList()
        artistSuggestions = emptyList()
        artistTracks = emptyMap()
        selectedAlbums = emptyList()
        albumSuggestions = emptyList()
        albumTracks = emptyMap()
        selectedGenres = emptyList()
        genreSuggestions = emptyList()
        genreProjection = LibraryGenreOntologyProjection()
        expandedGenreOntologyIds = emptySet()
        genreBrowser = null
        genreBrowserKey = null
        updateArtistUi { SharedArtistMixBuilderUi() }
        updateAlbumUi { SharedAlbumMixBuilderUi() }
        updateGenreUi { SharedGenreMixBuilderUi() }
    }

    override fun dispatch(command: NaviampCoreCommand): NaviampCoreImmediateCommandResult = when (command) {
        is NaviampCoreCommand.MixBuilder.Artist -> when (val action = command.action) {
            is NaviampCoreCommand.ArtistAction.ChangeQuery -> {
                changeCandidateQuery(CandidateKind.Artist, action.query)
                NaviampCoreImmediateCommandResult.Handled()
            }
            else -> NaviampCoreImmediateCommandResult.Deferred
        }
        is NaviampCoreCommand.MixBuilder.Album -> when (val action = command.action) {
            is NaviampCoreCommand.AlbumAction.ChangeQuery -> {
                changeCandidateQuery(CandidateKind.Album, action.query)
                NaviampCoreImmediateCommandResult.Handled()
            }
            else -> NaviampCoreImmediateCommandResult.Deferred
        }
        is NaviampCoreCommand.MixBuilder.Genre -> when (val action = command.action) {
            is NaviampCoreCommand.GenreAction.ChangeQuery -> {
                changeCandidateQuery(CandidateKind.Genre, action.query)
                NaviampCoreImmediateCommandResult.Handled()
            }
            else -> NaviampCoreImmediateCommandResult.Deferred
        }
        else -> NaviampCoreImmediateCommandResult.Unhandled
    }

    override suspend fun execute(command: NaviampCoreCommand): NaviampCoreCommandResult? {
        val recoveryIndex = queuedRecovery.indexOfFirst { it.first === command }
        if (recoveryIndex >= 0) {
            val load = queuedRecovery.removeAt(recoveryIndex).second
            if (isCurrent(load) && load.phase == CandidatePhase.Scheduled) {
                if (candidatesEmpty(load.kind)) {
                    when (load.kind) {
                        CandidateKind.Artist -> loadArtistSuggestions(load.initial)
                        CandidateKind.Album -> loadAlbumSuggestions(load.initial)
                        CandidateKind.Genre -> loadGenreSuggestions()
                    }
                } else load.phase = CandidatePhase.Failed
            }
            return NaviampCoreCommandResult.Completed
        }
        when (command) {
            is NaviampCoreCommand.MixBuilder.Artist -> executeArtist(command.action)
            is NaviampCoreCommand.MixBuilder.Album -> executeAlbum(command.action)
            is NaviampCoreCommand.MixBuilder.Genre -> executeGenre(command.action)
            else -> return null
        }
        return NaviampCoreCommandResult.Completed
    }

    suspend fun initializeArtist() = loadArtistSuggestions(initial = true)
    suspend fun initializeAlbum() = loadAlbumSuggestions(initial = true)
    suspend fun initializeGenre() = loadGenreSuggestions()

    private suspend fun executeArtist(action: NaviampCoreCommand.ArtistAction) {
        when (action) {
            is NaviampCoreCommand.ArtistAction.ChangeQuery ->
                changeCandidateQuery(CandidateKind.Artist, action.query)
            NaviampCoreCommand.ArtistAction.Search -> loadArtistSuggestions(initial = false)
            is NaviampCoreCommand.ArtistAction.Select -> selectArtist(action.artist.id)
            is NaviampCoreCommand.ArtistAction.Remove -> removeArtist(action.artist.id)
            NaviampCoreCommand.ArtistAction.Reset -> resetArtist()
            NaviampCoreCommand.ArtistAction.Play -> playArtistMix()
        }
    }

    private suspend fun executeAlbum(action: NaviampCoreCommand.AlbumAction) {
        when (action) {
            is NaviampCoreCommand.AlbumAction.ChangeQuery -> changeCandidateQuery(CandidateKind.Album, action.query)
            NaviampCoreCommand.AlbumAction.Search -> loadAlbumSuggestions(initial = false)
            is NaviampCoreCommand.AlbumAction.Select -> selectAlbum(action.album.id)
            is NaviampCoreCommand.AlbumAction.Remove -> removeAlbum(action.album.id)
            NaviampCoreCommand.AlbumAction.Reset -> resetAlbum()
            NaviampCoreCommand.AlbumAction.Play -> playAlbumMix()
        }
    }

    private suspend fun executeGenre(action: NaviampCoreCommand.GenreAction) {
        when (action) {
            NaviampCoreCommand.GenreAction.BrowseSongs -> browseGenreSongs()
            is NaviampCoreCommand.GenreAction.PlaySong -> {
                val tracks = genreBrowser?.tracks.orEmpty()
                val index = tracks.indexOfFirst { it.id.value == action.id }
                if (index >= 0) playback.playGenreSongs(tracks, index)
            }
            is NaviampCoreCommand.GenreAction.ChangeQuery -> changeCandidateQuery(CandidateKind.Genre, action.query)
            NaviampCoreCommand.GenreAction.Search -> loadGenreSuggestions()
            is NaviampCoreCommand.GenreAction.Select -> selectGenre(action.genre.id)
            is NaviampCoreCommand.GenreAction.Remove -> removeGenre(action.genre.id)
            is NaviampCoreCommand.GenreAction.ToggleBranch -> toggleGenreBranch(action.ontologyId)
            is NaviampCoreCommand.GenreAction.SelectBranch -> selectGenreBranch(action.ontologyId)
            NaviampCoreCommand.GenreAction.Reset -> resetGenre()
            NaviampCoreCommand.GenreAction.Play -> playGenreMix()
        }
    }

    private suspend fun loadArtistSuggestions(initial: Boolean) {
        val load = beginCandidates(CandidateKind.Artist, initial) ?: return
        val generation = ++artistGeneration
        updateArtistUi { it.copy(loading = true, status = null) }
        runCatching {
            if (initial) artistService().initialSuggestions(selectedArtists)
            else artistService().searchSuggestions(load.query, selectedArtists)
        }.onSuccess { suggestions ->
            if (generation != artistGeneration || !isCurrent(load)) return@onSuccess
            load.phase = CandidatePhase.Complete
            artistSuggestions = suggestions
            publishArtist(
                loading = false,
                status = if (suggestions.isEmpty()) {
                    if (initial) "No artist suggestions yet" else "No artists matched"
                } else null,
            )
        }.onFailure { cause ->
            candidateFailure(load, cause) { message -> publishArtist(false, message ?: if (cause is CancellationException) null else "Could not load artists.") }
        }
    }

    private suspend fun selectArtist(id: String) {
        val artist = artistSuggestions.firstOrNull { it.id.value == id }
        if (artist == null) {
            publishArtist(false, "Artist is no longer available.")
            return
        }
        val generation = sourceGeneration
        selectedArtists = (selectedArtists + artist).distinctBy { it.id }
        publishArtist(true, "Loading ${artist.name} songs...")
        runCatching { artistService().popularTracks(artist) }
            .onSuccess { tracks ->
                if (generation != sourceGeneration) return@onSuccess
                artistTracks = artistTracks + (artist.id.value to tracks)
                publishArtist(false, if (tracks.isEmpty()) "${artist.name} popular songs were not matched." else null)
            }
            .onFailure { cause -> if (generation == sourceGeneration) publishArtist(false, cause.message ?: "Could not load ${artist.name} songs.") }
        if (generation == sourceGeneration) loadArtistRelatedSuggestions(artist)
    }

    private suspend fun removeArtist(id: String) {
        selectedArtists = selectedArtists.filterNot { it.id.value == id }
        artistTracks = artistTracks - id
        loadArtistSuggestions(initial = true)
    }

    private suspend fun loadArtistRelatedSuggestions(seed: Artist) {
        val generation = ++artistGeneration
        candidateLoads.remove(CandidateKind.Artist)
        updateArtistUi { it.copy(loading = true) }
        runCatching { artistService().relatedSuggestions(selectedArtists, seed) }
            .onSuccess { suggestions ->
                if (generation != artistGeneration) return@onSuccess
                artistSuggestions = suggestions
                publishArtist(false, if (suggestions.isEmpty()) "No artist suggestions yet" else null)
            }
            .onFailure { cause ->
                if (generation == artistGeneration) {
                    publishArtist(false, cause.message ?: "Could not load similar artists.")
                }
            }
    }

    private suspend fun resetArtist() {
        candidateLoads.remove(CandidateKind.Artist)
        selectedArtists = emptyList()
        artistSuggestions = emptyList()
        artistTracks = emptyMap()
        updateArtistUi { SharedArtistMixBuilderUi() }
        loadArtistSuggestions(initial = true)
    }

    private suspend fun playArtistMix() {
        val queue = artistMixGeneratedQueue(selectedArtists, artistTracks)
        if (queue.artists.isEmpty() || queue.popularTracks.isEmpty()) {
            publishArtist(false, "Select artists with matched songs first.")
            return
        }
        runCatching { playback.playArtistMix(queue.artists, queue.popularTracks) }
            .onSuccess { publishArtist(false, null) }
            .onFailure { publishArtist(false, it.message ?: "Could not play artist mix.") }
    }

    private suspend fun loadAlbumSuggestions(initial: Boolean) {
        val load = beginCandidates(CandidateKind.Album, initial) ?: return
        val generation = ++albumGeneration
        updateAlbumUi { it.copy(loading = true, status = null) }
        runCatching {
            if (initial) albumService().initialSuggestions(selectedAlbums)
            else albumService().searchSuggestions(load.query, selectedAlbums)
        }.onSuccess { suggestions ->
            if (generation != albumGeneration || !isCurrent(load)) return@onSuccess
            load.phase = CandidatePhase.Complete
            albumSuggestions = suggestions
            publishAlbum(false, if (suggestions.isEmpty()) {
                if (initial) "No album suggestions yet" else "No albums matched"
            } else null)
        }.onFailure { cause ->
            candidateFailure(load, cause) { message -> publishAlbum(false, message ?: if (cause is CancellationException) null else "Could not load albums.") }
        }
    }

    private suspend fun selectAlbum(id: String) {
        val album = albumSuggestions.firstOrNull { it.id.value == id }
        if (album == null) {
            publishAlbum(false, "Album is no longer available.")
            return
        }
        val generation = sourceGeneration
        selectedAlbums = (selectedAlbums + album).distinctBy { it.id }
        publishAlbum(true, "Loading ${album.title} songs...")
        runCatching { albumService().selectedTracks(album) }
            .onSuccess { tracks ->
                if (generation != sourceGeneration) return@onSuccess
                albumTracks = albumTracks + (album.id.value to tracks)
                publishAlbum(false, if (tracks.isEmpty()) "${album.title} did not return tracks." else null)
            }
            .onFailure { cause -> if (generation == sourceGeneration) publishAlbum(false, cause.message ?: "Could not load ${album.title} songs.") }
        if (generation == sourceGeneration) loadAlbumRelatedSuggestions(album)
    }

    private suspend fun removeAlbum(id: String) {
        selectedAlbums = selectedAlbums.filterNot { it.id.value == id }
        albumTracks = albumTracks - id
        loadAlbumSuggestions(initial = true)
    }

    private suspend fun loadAlbumRelatedSuggestions(seed: Album) {
        val generation = ++albumGeneration
        candidateLoads.remove(CandidateKind.Album)
        updateAlbumUi { it.copy(loading = true) }
        runCatching { albumService().relatedSuggestions(selectedAlbums, seed) }
            .onSuccess { suggestions ->
                if (generation != albumGeneration) return@onSuccess
                albumSuggestions = suggestions
                publishAlbum(false, if (suggestions.isEmpty()) "No album suggestions yet" else null)
            }
            .onFailure { cause ->
                if (generation == albumGeneration) {
                    publishAlbum(false, cause.message ?: "Could not load related albums.")
                }
            }
    }

    private suspend fun resetAlbum() {
        candidateLoads.remove(CandidateKind.Album)
        selectedAlbums = emptyList()
        albumSuggestions = emptyList()
        albumTracks = emptyMap()
        updateAlbumUi { SharedAlbumMixBuilderUi() }
        loadAlbumSuggestions(initial = true)
    }

    private suspend fun playAlbumMix() {
        val queue = albumMixGeneratedQueue(selectedAlbums, albumTracks)
        if (queue.albums.isEmpty() || queue.selectedTracks.isEmpty()) {
            publishAlbum(false, "Select albums with matched songs first.")
            return
        }
        runCatching { playback.playAlbumMix(queue.albums, queue.selectedTracks) }
            .onSuccess { publishAlbum(false, null) }
            .onFailure { publishAlbum(false, it.message ?: "Could not play album mix.") }
    }

    private suspend fun loadGenreSuggestions() {
        val load = beginCandidates(CandidateKind.Genre) ?: return
        val generation = ++genreGeneration
        publishGenre(loading = true, status = null)
        runCatching {
            val service = genreService()
            service.browseProjection() to service.searchSuggestions(load.query, selectedGenres)
        }.onSuccess { (projection, suggestions) ->
            if (generation != genreGeneration || !isCurrent(load)) return@onSuccess
            load.phase = CandidatePhase.Complete
            genreProjection = projection
            genreSuggestions = suggestions
                publishGenre(false, if (suggestions.isEmpty()) "No genres matched" else null)
            }
            .onFailure { cause ->
                candidateFailure(load, cause) { message -> publishGenre(false, message ?: if (cause is CancellationException) null else "Could not load genres.") }
            }
    }

    private suspend fun selectGenre(id: String) {
        val genre = (genreSuggestions + genreProjection.selectableGenres).firstOrNull { it.name == id }
        if (genre == null) {
            publishGenre(false, "Genre is no longer available.")
            return
        }
        selectedGenres = (selectedGenres + genre).distinctBy { it.name.lowercase() }
        loadGenreSuggestions()
    }

    private suspend fun removeGenre(id: String) {
        selectedGenres = selectedGenres.filterNot { it.name == id }
        loadGenreSuggestions()
    }

    private suspend fun selectGenreBranch(ontologyId: String) {
        val genres = genreProjection.selectableGenresForSubtree(ontologyId)
        if (genres.isEmpty()) {
            publishGenre(false, "Genre group is no longer available.")
            return
        }
        selectedGenres = (selectedGenres + genres).distinctBy { it.name.lowercase() }
        loadGenreSuggestions()
    }

    private fun toggleGenreBranch(ontologyId: String) {
        val node = genreProjection.nodes.firstOrNull { it.id == ontologyId } ?: return
        if (node.childIds.isEmpty()) return
        expandedGenreOntologyIds = if (ontologyId in expandedGenreOntologyIds) {
            expandedGenreOntologyIds - ontologyId
        } else {
            expandedGenreOntologyIds + ontologyId
        }
        publishGenre(loading = false, status = currentGenreUi().status)
    }

    private suspend fun resetGenre() {
        candidateLoads.remove(CandidateKind.Genre)
        selectedGenres = emptyList()
        genreSuggestions = emptyList()
        genreProjection = LibraryGenreOntologyProjection()
        expandedGenreOntologyIds = emptySet()
        updateGenreUi { SharedGenreMixBuilderUi(initialized = true) }
        loadGenreSuggestions()
    }

    private suspend fun playGenreMix() {
        val queue = genreMixGeneratedQueue(selectedGenres)
        if (queue.genres.isEmpty()) {
            publishGenre(false, "Select at least one genre first.")
            return
        }
        runCatching { playback.playGenreMix(queue.genres) }
            .onSuccess { publishGenre(false, null) }
            .onFailure { publishGenre(false, it.message ?: "Could not play genre mix.") }
    }

    private fun publishArtist(loading: Boolean, status: String?) {
        val art = coverArtUrl()
        updateArtistUi {
            it.copy(
                selectedArtists = selectedArtists.map { artist -> artist.toSharedMediaItemUi(art) },
                suggestedArtists = artistSuggestions.map { artist -> artist.toSharedMediaItemUi(art) },
                loading = loading,
                status = status,
            )
        }
    }

    private fun publishAlbum(loading: Boolean, status: String?) {
        val art = coverArtUrl()
        updateAlbumUi {
            it.copy(
                selectedAlbums = selectedAlbums.map { album -> album.toSharedMediaItemUi(art) },
                suggestedAlbums = albumSuggestions.map { album -> album.toSharedMediaItemUi(art) },
                loading = loading,
                status = status,
            )
        }
    }

    private fun publishGenre(loading: Boolean, status: String?) {
        val provider = providerSource.current()
        val key = provider?.cacheNamespace to selectedGenres.map { it.name }
        if (genreBrowserKey != key) {
            genreBrowserKey = key
            genreBrowser = null
            updateGenreUi { it.copy(songs = emptyList(), songsOpened = false, songsLoading = false,
                songsFailed = false, songsHaveMore = false) }
        }
        val browseOntology = genreProjection.audit.usefulForBrowsing
        updateGenreUi {
            it.copy(
                selectedGenres = selectedGenres.map(Genre::toSharedGenreMixItemUi),
                canBrowseSongs = provider?.capabilities?.supportsGenreTrackBrowsing == true && selectedGenres.isNotEmpty(),
                suggestedGenres = genreSuggestions.map(Genre::toSharedGenreMixItemUi),
                treeRows = if (browseOntology) {
                    genreTreeRows(genreProjection, expandedGenreOntologyIds, selectedGenres)
                } else emptyList(),
                unmatchedGenres = if (browseOntology) genreProjection.unmatchedGenreNames
                    .map(::Genre)
                    .map(Genre::toSharedGenreMixItemUi) else emptyList(),
                loading = loading,
                status = status,
                initialized = true,
            )
        }
    }

    private suspend fun browseGenreSongs() {
        val provider = providerSource.current() ?: return
        if (!provider.capabilities.supportsGenreTrackBrowsing || selectedGenres.isEmpty() || currentGenreUi().songsLoading) return
        val key = provider.cacheNamespace to selectedGenres.map { it.name }
        val browser = genreBrowser ?: GenreTrackBrowser(selectedGenres, provider::genreTracksPage).also { genreBrowser = it }
        updateGenreUi { it.copy(songsOpened = true, songsLoading = true, songsFailed = false) }
        try {
            browser.loadNext()
            if (key != (providerSource.current()?.cacheNamespace to selectedGenres.map { it.name })) return
            updateGenreUi { it.copy(songs = browser.tracks.map { track -> track.toSharedTrackRowUi(coverArtUrl()) },
                songsLoading = false, songsHaveMore = browser.hasMore) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (key == (providerSource.current()?.cacheNamespace to selectedGenres.map { it.name })) {
                updateGenreUi { it.copy(songsLoading = false, songsFailed = true, songsHaveMore = true) }
            }
        }
    }

    private fun coverArtUrl(): (String?) -> String? {
        val provider = providerSource.current()
        return { id -> id?.let { provider?.coverArtUrl(it) } }
    }

    private fun currentArtistUi() = stateStore.state.value.shell.artistMixBuilder
    private fun currentAlbumUi() = stateStore.state.value.shell.albumMixBuilder
    private fun currentGenreUi() = stateStore.state.value.shell.genreMixBuilder

    private fun updateArtistUi(transform: (SharedArtistMixBuilderUi) -> SharedArtistMixBuilderUi) {
        stateStore.updateShell { shell -> shell.copy(artistMixBuilder = transform(shell.artistMixBuilder)) }
    }

    private fun updateAlbumUi(transform: (SharedAlbumMixBuilderUi) -> SharedAlbumMixBuilderUi) {
        stateStore.updateShell { shell -> shell.copy(albumMixBuilder = transform(shell.albumMixBuilder)) }
    }

    private fun updateGenreUi(transform: (SharedGenreMixBuilderUi) -> SharedGenreMixBuilderUi) {
        stateStore.updateShell { shell -> shell.copy(genreMixBuilder = transform(shell.genreMixBuilder)) }
    }
}

internal fun genreTreeRows(
    projection: LibraryGenreOntologyProjection,
    expandedIds: Set<String>,
    selectedGenres: List<Genre>,
): List<SharedGenreMixTreeRowUi> {
    val nodesById = projection.nodes.associateBy { it.id }
    val selectedNames = selectedGenres.map { it.name.lowercase() }.toSet()
    val rows = mutableListOf<SharedGenreMixTreeRowUi>()
    fun appendNode(id: String, depth: Int, path: Set<String>) {
        if (id in path) return
        val node = nodesById[id] ?: return
        val providerGenres = projection.selectableGenresForSubtree(node.id)
        val selectedCount = providerGenres.count { genre -> genre.name.lowercase() in selectedNames }
        rows += SharedGenreMixTreeRowUi(
            ontologyId = node.id,
            title = genreDisplayTitle(node.canonicalName),
            subtitle = genreGroupSubtitle(
                selectableGenreCount = providerGenres.size,
                directProviderNames = node.libraryGenreNames,
                trackCount = node.trackCount,
                albumCount = node.albumCount,
            ),
            depth = depth,
            expandable = node.childIds.isNotEmpty(),
            expanded = node.id in expandedIds,
            selectableGenreCount = providerGenres.size,
            selectedGenreCount = selectedCount,
            selected = providerGenres.isNotEmpty() && selectedCount == providerGenres.size,
        )
        if (node.id in expandedIds) {
            node.childIds
                .mapNotNull(nodesById::get)
                .sortedBy { it.canonicalName.lowercase() }
                .forEach { child -> appendNode(child.id, depth + 1, path + id) }
        }
    }
    projection.rootIds
        .mapNotNull(nodesById::get)
        .sortedBy { it.canonicalName.lowercase() }
        .forEach { appendNode(it.id, depth = 0, path = emptySet()) }
    return rows
}

private fun genreGroupSubtitle(
    selectableGenreCount: Int,
    directProviderNames: List<String>,
    trackCount: Int?,
    albumCount: Int?,
): String = listOfNotNull(
        when {
            selectableGenreCount > 1 -> "$selectableGenreCount library genres"
            directProviderNames.size > 1 -> directProviderNames.joinToString(" · ")
            else -> null
        },
        trackCount?.let { "$it ${if (it == 1) "track" else "tracks"}" },
        albumCount?.let { "$it ${if (it == 1) "album" else "albums"}" },
    ).joinToString(" · ")

package app.naviamp.presentation

import app.naviamp.app.NaviampKeepDownloadedReconciliationApplication
import app.naviamp.app.NaviampKeepDownloadedToggleResult
import app.naviamp.app.NaviampNavigationController
import app.naviamp.app.NaviampNavigationPage
import app.naviamp.app.NaviampRoutePage
import app.naviamp.domain.Album
import app.naviamp.domain.AlbumId
import app.naviamp.domain.Artist
import app.naviamp.domain.ArtistId
import app.naviamp.domain.InternetRadioStation
import app.naviamp.domain.Track
import app.naviamp.domain.app.NaviampRoute
import app.naviamp.domain.cache.KeepDownloadedCollectionPolicy
import app.naviamp.domain.cache.PersistedDownloadJob
import app.naviamp.domain.cache.LibrarySnapshot
import app.naviamp.domain.cache.LocalLibraryIndexRepository
import app.naviamp.domain.popular.ArtistPopularTrackCandidate
import app.naviamp.domain.library.AlbumCatalogRepository
import app.naviamp.domain.library.AlbumCatalogScope
import app.naviamp.domain.library.AlbumCatalogSnapshot
import app.naviamp.domain.library.AlbumLibraryIndex
import app.naviamp.domain.provider.MediaPage
import app.naviamp.domain.provider.MediaPageRequest
import app.naviamp.domain.provider.MediaProvider
import app.naviamp.domain.provider.MediaSearchResults
import app.naviamp.ui.NaviampLibraryView
import app.naviamp.ui.toSharedRoute
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NaviampPageEntryRecoveryTest {
    @Test
    fun actualFailedEmptyRootsRecoverOnBackReselectionReplacementAndReturningFromAnotherRoot() = runTest {
        for (mode in recoveryModes) for (entry in RecoveryEntry.entries) {
            val fixture = RecoveryFixture(this)
            fixture.prepare(mode)
            fixture.fail(mode)
            assertEquals(1, fixture.probe.requests.size, "$mode initial failure")
            fixture.probe.outcome = RecoveryOutcome.Content

            fixture.enter(mode, entry)
            assertEquals(1, fixture.probe.requests.size, "$mode claims before execution")
            advanceUntilIdle()

            assertEquals(1 + mode.successfulRequestCount, fixture.probe.requests.size, "$mode $entry")
            fixture.assertContent(mode)
            fixture.enter(mode, RecoveryEntry.Reselect)
            advanceUntilIdle()
            assertEquals(1 + mode.successfulRequestCount, fixture.probe.requests.size, "$mode loaded root")
        }
    }

    @Test
    fun successfulEmptyAndLoadedRootsNeverScheduleRecovery() = runTest {
        for (mode in recoveryModes) for (outcome in listOf(RecoveryOutcome.Empty, RecoveryOutcome.Content)) {
            val fixture = RecoveryFixture(this)
            fixture.prepare(mode)
            fixture.probe.outcome = outcome
            fixture.router.execute(mode.loadCommand)
            val calls = fixture.probe.requests.size
            for (entry in RecoveryEntry.entries) fixture.enter(mode, entry)
            advanceUntilIdle()
            assertEquals(calls, fixture.probe.requests.size, "$mode $outcome")
        }
    }

    @Test
    fun repeatedEntriesClaimOnlyOneScheduledAndInFlightRecoveryPerVisibleMode() = runTest {
        for (mode in recoveryModes) {
            val fixture = RecoveryFixture(this)
            fixture.prepare(mode)
            fixture.fail(mode)
            fixture.probe.outcome = RecoveryOutcome.Content
            val gate = CompletableDeferred<Unit>()
            fixture.probe.gate = gate

            repeat(8) { fixture.enter(mode, RecoveryEntry.Reselect) }
            assertEquals(1, fixture.probe.requests.size, "$mode scheduled")
            runCurrent()
            assertEquals(2, fixture.probe.requests.size, "$mode in flight")
            repeat(8) { fixture.enter(mode, RecoveryEntry.AwayAndReselect) }
            runCurrent()
            assertEquals(2, fixture.probe.requests.size, "$mode single flight")

            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(1 + mode.successfulRequestCount, fixture.probe.requests.size, "$mode completed")
            fixture.assertContent(mode)
        }
    }

    @Test
    fun cancelledRecoveriesKeepFailureEligibilityButInitialCancellationDoesNotCreateFailure() = runTest {
        for (mode in recoveryModes) for (failedBeforeCancellation in listOf(false, true)) {
            val fixture = RecoveryFixture(this)
            fixture.prepare(mode)
            if (failedBeforeCancellation) fixture.fail(mode)
            fixture.probe.outcome = RecoveryOutcome.Content
            fixture.probe.gate = CompletableDeferred()
            val command = if (failedBeforeCancellation) {
                fixture.owner(mode).recoverEmptyPage(NaviampRoutePage(mode.route)).single()
            } else mode.loadCommand
            val job = launch { fixture.router.execute(command) }
            runCurrent()
            val calls = fixture.probe.requests.size
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            fixture.assertNotLoading(mode)

            fixture.probe.gate = null
            fixture.enter(mode, RecoveryEntry.Reselect)
            advanceUntilIdle()
            assertEquals(calls + if (failedBeforeCancellation) mode.successfulRequestCount else 0,
                fixture.probe.requests.size, "$mode prior failure=$failedBeforeCancellation")
            if (failedBeforeCancellation) fixture.assertContent(mode)
        }
    }

    @Test
    fun queuedAndInFlightRecoveriesCannotPublishOrLoadAcrossSourceReset() = runTest {
        for (mode in recoveryModes) for (started in listOf(false, true)) {
            val fixture = RecoveryFixture(this)
            fixture.prepare(mode)
            fixture.fail(mode)
            fixture.probe.outcome = RecoveryOutcome.Content
            val gate = CompletableDeferred<Unit>()
            fixture.probe.gate = gate
            fixture.enter(mode, RecoveryEntry.Reselect)
            if (started) runCurrent()
            val calls = fixture.probe.requests.size

            fixture.changeSource()
            fixture.enter(mode, RecoveryEntry.Reselect)
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(calls, fixture.probe.requests.size, "$mode started=$started")
            fixture.assertEmptyResetState()
            assertTrue(fixture.owner(mode).recoverEmptyPage(NaviampRoutePage(mode.route)).isEmpty())
        }
    }

    @Test
    fun newQueryRejectsQueuedAndLateRecoveryResultsForEverySearchMode() = runTest {
        for (mode in recoveryModes.filter { it.query.isNotBlank() }) for (started in listOf(false, true)) {
            val fixture = RecoveryFixture(this)
            fixture.prepare(mode)
            fixture.fail(mode)
            fixture.probe.outcome = RecoveryOutcome.Content
            val gate = CompletableDeferred<Unit>()
            fixture.probe.gate = gate
            fixture.enter(mode, RecoveryEntry.Reselect)
            if (started) runCurrent()
            fixture.probe.gate = null
            fixture.router.execute(if (mode.view == null) NaviampCoreCommand.Search.ChangeQuery("replacement")
                else NaviampCoreCommand.Library.ChangeQuery("replacement"))
            val calls = fixture.probe.requests.size
            gate.complete(Unit)
            advanceUntilIdle()

            val shell = fixture.store.state.value.shell
            if (mode.view == null) {
                assertEquals("replacement", shell.search.query)
                assertEquals("replacement", shell.search.results.tracks.single().title)
            } else {
                val catalog = shell.library.catalog(mode.view)
                assertEquals("replacement", catalog.query)
                assertEquals("replacement", catalog.resultsQuery)
                assertEquals("replacement", if (mode.view == NaviampLibraryView.Songs)
                    catalog.tracks.single().title else catalog.items.single().title)
            }
            fixture.enter(mode, RecoveryEntry.Reselect)
            advanceUntilIdle()
            assertEquals(calls, fixture.probe.requests.size, "$mode started=$started")
        }
    }

    @Test
    fun queryAndFavoritesRecoveryUsesVisibleEmptinessNotTheRetainedOrdinaryCatalog() = runTest {
        for (view in NaviampLibraryView.entries) for (favorites in listOf(false, true)) {
            val fixture = RecoveryFixture(this)
            val browse = RecoveryMode(NaviampRoute.Library, view)
            fixture.prepare(browse)
            fixture.probe.outcome = RecoveryOutcome.Content
            fixture.router.execute(browse.loadCommand)
            fixture.assertContent(browse)
            fixture.probe.outcome = RecoveryOutcome.Failed
            fixture.router.execute(if (favorites) NaviampCoreCommand.Library.ChangeFavoritesOnly(true)
                else NaviampCoreCommand.Library.ChangeQuery("unmatched"))
            val mode = browse.copy(query = if (favorites) "" else "unmatched", favorites = favorites)
            val calls = fixture.probe.requests.size

            fixture.probe.outcome = RecoveryOutcome.Content
            fixture.enter(mode, RecoveryEntry.Reselect)
            advanceUntilIdle()

            assertEquals(calls + 1, fixture.probe.requests.size, "$mode")
            fixture.assertContent(mode)
        }
    }

    @Test
    fun failedLaterLibraryPagesKeepPartialContentWithoutRetryingOnEntry() = runTest {
        for (view in NaviampLibraryView.entries) for (query in listOf("", "needle")) {
            val fixture = RecoveryFixture(this)
            val mode = RecoveryMode(NaviampRoute.Library, view, query)
            fixture.prepare(mode)
            fixture.probe.outcome = RecoveryOutcome.Content
            fixture.provider.continuingPage = true
            fixture.router.execute(mode.loadCommand)
            fixture.probe.outcome = RecoveryOutcome.Failed
            fixture.router.execute(NaviampCoreCommand.Library.LoadMore)
            val calls = fixture.probe.requests.size
            fixture.enter(mode, RecoveryEntry.Reselect)
            advanceUntilIdle()
            assertEquals(calls, fixture.probe.requests.size, "$mode partial")
            fixture.assertContent(mode)
        }
    }

    @Test
    fun typedRootCheckRejectsOtherPageTypesEvenWhenTheyReportTheSameRoute() = runTest {
        for (mode in recoveryModes) {
            val fixture = RecoveryFixture(this)
            fixture.prepare(mode)
            fixture.fail(mode)
            val otherPage = object : NaviampNavigationPage { override val route = mode.route }
            fixture.router.onPageEntered(otherPage)
            advanceUntilIdle()
            assertEquals(1, fixture.probe.requests.size, "$mode")
            assertEquals(1, fixture.owner(mode).recoverEmptyPage(NaviampRoutePage(mode.route)).size)
        }
    }

    @Test
    fun failedAlbumIndexRefreshRetriesEvenWithAFreshSuccessfulEmptyCachedSnapshot() = runTest {
        val repository = RecoveryAlbumCatalog()
        val fixture = RecoveryFixture(this, repository)
        val mode = RecoveryMode(NaviampRoute.Library, NaviampLibraryView.Albums)
        fixture.prepare(mode)
        assertTrue(repository.snapshots.values.single().albums.isEmpty())
        fixture.fail(mode)
        assertTrue(fixture.store.state.value.shell.library.albums.syncStatus.albumIndexFailed)
        fixture.probe.outcome = RecoveryOutcome.Content
        fixture.enter(mode, RecoveryEntry.Back)
        advanceUntilIdle()
        assertEquals(2, fixture.probe.requests.size)
        fixture.assertContent(mode)
    }

    @Test
    fun failedAlbumIndexSnapshotReadIsRecoverableWithoutMisclassifyingSuccessfulEmpty() = runTest {
        val repository = RecoveryAlbumCatalog()
        val fixture = RecoveryFixture(this, repository)
        val mode = RecoveryMode(NaviampRoute.Library, NaviampLibraryView.Albums)
        fixture.prepare(mode)
        repository.failRead = true
        fixture.router.execute(mode.loadCommand)
        assertTrue(fixture.store.state.value.shell.library.albums.syncStatus.albumIndexFailed)
        repository.failRead = false
        fixture.probe.outcome = RecoveryOutcome.Content
        fixture.enter(mode, RecoveryEntry.Reselect)
        advanceUntilIdle()
        assertEquals(1, fixture.probe.requests.size)
        fixture.assertContent(mode)
    }

    @Test
    fun downloadsRecoveryNeverRetriesIndividualFailedJobs() = runTest {
        val fixture = RecoveryFixture(this)
        val mode = RecoveryMode(NaviampRoute.Downloads)
        fixture.prepare(mode)
        assertTrue(fixture.downloads.downloadTracks("Failed job", listOf(fixture.provider.sample.track)))
        advanceUntilIdle()
        assertEquals(1, fixture.transferCalls)
        assertEquals(1, fixture.store.state.value.shell.downloads.jobs.size)
        fixture.enter(mode, RecoveryEntry.Reselect)
        advanceUntilIdle()
        assertTrue(fixture.probe.requests.isEmpty())
        assertEquals(1, fixture.transferCalls)

        fixture.fail(mode)
        fixture.probe.outcome = RecoveryOutcome.Content
        fixture.enter(mode, RecoveryEntry.Reselect)
        advanceUntilIdle()
        assertEquals(1, fixture.probe.requests.size)
        assertEquals(1, fixture.transferCalls)
        assertEquals(0, fixture.keep.reconcileCalls)
    }

    @Test
    fun downloadsPruneFailuresRecoverButPopulatedOfflineDashboardDoesNotRetry() = runTest {
        val fixture = RecoveryFixture(this)
        val mode = RecoveryMode(NaviampRoute.Downloads)
        fixture.prepare(mode)
        fixture.storage.failPrune = true
        fixture.router.execute(mode.loadCommand)
        fixture.storage.failPrune = false
        fixture.enter(mode, RecoveryEntry.Reselect)
        advanceUntilIdle()
        assertEquals(1, fixture.probe.requests.size)

        fixture.storage.audioCacheCount = 2
        fixture.router.execute(mode.loadCommand)
        fixture.probe.outcome = RecoveryOutcome.Failed
        fixture.router.execute(mode.loadCommand)
        val calls = fixture.probe.requests.size
        fixture.enter(mode, RecoveryEntry.Reselect)
        advanceUntilIdle()
        assertEquals(calls, fixture.probe.requests.size)
        assertEquals(2L, fixture.store.state.value.shell.downloads.offlineDashboard.audioCacheCount)
    }

    @Test
    fun manualPlaylistAndDownloadsRefreshSupersedesAnOlderRead() = runTest {
        for (route in listOf(NaviampRoute.Playlists, NaviampRoute.Downloads)) {
            for (olderOutcome in listOf(RecoveryOutcome.Empty, RecoveryOutcome.Failed)) {
                val fixture = RecoveryFixture(this)
                val mode = RecoveryMode(route)
                fixture.prepare(mode)
                val gate = CompletableDeferred<Unit>()
                fixture.probe.outcome = olderOutcome
                fixture.probe.gate = gate
                val older = launch { fixture.router.execute(mode.loadCommand) }
                runCurrent()
                fixture.probe.gate = null
                fixture.probe.outcome = RecoveryOutcome.Content
                fixture.router.execute(mode.loadCommand)
                fixture.assertContent(mode)
                gate.complete(Unit)
                older.join()
                fixture.assertContent(mode)
                assertEquals(2, fixture.probe.requests.size)
                fixture.enter(mode, RecoveryEntry.Reselect)
                advanceUntilIdle()
                assertEquals(2, fixture.probe.requests.size)
            }
        }
    }

    @Test
    fun downloadsRecoveryRestoresSavedJobsAndReconcilesSubscriptions() = runTest {
        val fixture = RecoveryFixture(this)
        val mode = RecoveryMode(NaviampRoute.Downloads)
        fixture.prepare(mode)
        fixture.fail(mode)
        fixture.keep.activePolicies = listOf(KeepDownloadedCollectionPolicy(
            "source", app.naviamp.domain.cache.KeepDownloadedCollectionKind.Favorites, "favorites", "Favorites",
        ))
        val pendingJob = PersistedDownloadJob(
            sourceId = "source",
            job = app.naviamp.domain.cache.createDownloadJob("download-000000000009", "Saved failed download",
                listOf(fixture.provider.sample.track)).copy(status = app.naviamp.domain.cache.DownloadJobStatus.Failed),
            qualityKey = "transcoded:mp3:192",
            replaceExisting = false,
            manualRetention = true,
            includeCompletedCount = true,
        )
        fixture.keep.saveJob(pendingJob)
        fixture.probe.outcome = RecoveryOutcome.Content
        fixture.enter(mode, RecoveryEntry.Reselect)
        advanceUntilIdle()
        assertEquals(2, fixture.probe.requests.size)
        assertTrue(fixture.store.state.value.shell.downloads.keepFavoritesDownloaded)
        assertEquals(1, fixture.keep.reconcileCalls)
        assertEquals(0, fixture.transferCalls, "A saved failed job is displayed, not automatically restarted")
        assertEquals(listOf(pendingJob.job.id), fixture.store.state.value.shell.downloads.jobs.map { it.id })
        assertEquals(listOf(pendingJob), fixture.keep.savedJobs("source"))
    }

    @Test
    fun failedRefreshesWithRetainedVisibleContentDoNotRetry() = runTest {
        for (mode in recoveryModes.filter { it.route != NaviampRoute.Search && !it.favorites }) {
            val fixture = RecoveryFixture(this)
            fixture.prepare(mode)
            fixture.probe.outcome = RecoveryOutcome.Content
            fixture.router.execute(mode.loadCommand)
            fixture.fail(mode)
            val calls = fixture.probe.requests.size
            fixture.enter(mode, RecoveryEntry.Back)
            advanceUntilIdle()
            assertEquals(calls, fixture.probe.requests.size, "$mode retained")
            fixture.assertContent(mode)
        }
    }

    @Test
    fun albumIndexCancellationPreservesRecoveryAndPartialIndexFailureDoesNotRetry() = runTest {
        val fixture = RecoveryFixture(this, RecoveryAlbumCatalog())
        val mode = RecoveryMode(NaviampRoute.Library, NaviampLibraryView.Albums)
        fixture.prepare(mode)
        fixture.fail(mode)
        fixture.probe.outcome = RecoveryOutcome.Content
        fixture.probe.gate = CompletableDeferred()
        val recovery = fixture.catalog.recoverEmptyPage(NaviampRoutePage(mode.route)).single()
        val job = launch { fixture.router.execute(recovery) }
        runCurrent()
        job.cancelAndJoin()
        fixture.assertNotLoading(mode)
        fixture.probe.gate = null
        fixture.enter(mode, RecoveryEntry.Reselect)
        advanceUntilIdle()
        assertEquals(3, fixture.probe.requests.size)
        fixture.assertContent(mode)

        // Repeated continuing pages fail the real index's nonadvancing-page guard.
        fixture.provider.continuingPage = true
        fixture.router.execute(mode.loadCommand)
        assertTrue(fixture.store.state.value.shell.library.albums.syncStatus.albumIndexFailed)
        val calls = fixture.probe.requests.size
        fixture.enter(mode, RecoveryEntry.Reselect)
        advanceUntilIdle()
        assertEquals(calls, fixture.probe.requests.size)
        fixture.assertContent(mode)
    }

    @Test
    fun artistIndexFailureRetriesAfterCancellationAndRejectsOldSourceCompletion() = runTest {
        for (changeSource in listOf(false, true)) {
            val repository = RecoveryArtistIndex()
            val fixture = RecoveryFixture(this, artistIndex = repository)
            val mode = RecoveryMode(NaviampRoute.Library, NaviampLibraryView.Artists)
            fixture.prepare(mode)
            fixture.fail(mode)
            fixture.probe.outcome = RecoveryOutcome.Content
            val gate = CompletableDeferred<Unit>()
            fixture.probe.gate = gate
            val recovery = fixture.catalog.recoverEmptyPage(NaviampRoutePage(mode.route)).single()
            val job = launch { fixture.router.execute(recovery) }
            runCurrent()
            if (changeSource) {
                fixture.changeSource()
                gate.complete(Unit)
                job.join()
                fixture.assertEmptyResetState()
                assertTrue(repository.artists.values.all { it.isEmpty() })
            } else {
                job.cancelAndJoin()
                fixture.assertNotLoading(mode)
                fixture.probe.gate = null
                fixture.enter(mode, RecoveryEntry.Back)
                advanceUntilIdle()
                assertEquals(3, fixture.probe.requests.size)
                fixture.assertContent(mode)
                assertEquals(1, repository.artists.getValue("source").size)
            }
        }
    }

    @Test
    fun successfulEmptyArtistIndexDoesNotRetryForAnOptionalScanFailure() = runTest {
        val fixture = RecoveryFixture(this, artistIndex = RecoveryArtistIndex())
        val mode = RecoveryMode(NaviampRoute.Library, NaviampLibraryView.Artists)
        fixture.prepare(mode)
        fixture.provider.failScan = true
        fixture.router.execute(mode.loadCommand)
        assertEquals(1, fixture.probe.requests.size)
        fixture.enter(mode, RecoveryEntry.Reselect)
        advanceUntilIdle()
        assertEquals(1, fixture.probe.requests.size)
    }
}

private enum class RecoveryEntry { Back, Reselect, AwayAndReselect, Replace }
private enum class RecoveryOutcome { Failed, Empty, Content }

private data class RecoveryMode(
    val route: NaviampRoute,
    val view: NaviampLibraryView? = null,
    val query: String = "",
    val favorites: Boolean = false,
) {
    val successfulRequestCount: Int get() = if (favorites && query.isNotBlank()) 2 else 1
    val loadCommand: NaviampCoreCommand get() = when (route) {
        NaviampRoute.Search -> NaviampCoreCommand.Search.Submit
        NaviampRoute.Library -> if (query.isNotBlank() && !favorites) NaviampCoreCommand.Library.ChangeQuery(query)
            else NaviampCoreCommand.Library.Refresh
        NaviampRoute.Playlists -> NaviampCoreCommand.Playlists.Refresh
        NaviampRoute.Radio -> NaviampCoreCommand.Radio.Refresh
        NaviampRoute.Downloads -> NaviampCoreCommand.Downloads.Refresh
        else -> error("Not a recovery root")
    }
}

private val recoveryModes = listOf(
    RecoveryMode(NaviampRoute.Search, query = "needle"),
    RecoveryMode(NaviampRoute.Playlists),
    RecoveryMode(NaviampRoute.Radio),
    RecoveryMode(NaviampRoute.Downloads),
) + NaviampLibraryView.entries.flatMap { view ->
    listOf(false, true).flatMap { favorites ->
        listOf("", "needle").map { query -> RecoveryMode(NaviampRoute.Library, view, query, favorites) }
    }
}

private class RecoveryProbe {
    var outcome = RecoveryOutcome.Empty
    var gate: CompletableDeferred<Unit>? = null
    val requests = mutableListOf<String>()

    suspend fun fetch(key: String): RecoveryOutcome {
        requests += key
        val result = outcome
        gate?.await()
        // Deliberately looks like a successful empty status: recovery must use typed outcomes.
        if (result == RecoveryOutcome.Failed) error("No matches found.")
        return result
    }
}

private class RecoveryProvider(
    private val probe: RecoveryProbe,
    override val cacheNamespace: String = "source",
    val sample: FakeCoreMediaProvider = FakeCoreMediaProvider(),
) : MediaProvider by sample {
    override val capabilities = sample.capabilities.copy(
        supportsArtistFavorites = true, supportsAlbumFavorites = true, supportsTrackFavorites = true,
    )
    var continuingPage = false
    var failScan = false
    override suspend fun libraryScanStatus(): app.naviamp.domain.provider.LibraryScanStatus? {
        if (failScan) error("Scan status failed")
        return null
    }

    override suspend fun search(query: String, limit: Int): MediaSearchResults =
        if (probe.fetch("search:$query") == RecoveryOutcome.Empty) MediaSearchResults()
        else MediaSearchResults(tracks = listOf(sample.track.copy(title = query)))

    override suspend fun artistsPage(request: MediaPageRequest) = page("artists", request, sample.artist)
    override suspend fun albumsPage(request: MediaPageRequest) = page("albums", request, sample.album)
    override suspend fun tracksPage(request: MediaPageRequest) = page("songs", request, sample.track)
    override suspend fun searchArtistsPage(query: String, request: MediaPageRequest): MediaPage<Artist> =
        page("artists:$query", request, sample.artist.copy(name = query))
    override suspend fun searchAlbumsPage(query: String, request: MediaPageRequest): MediaPage<Album> =
        page("albums:$query", request, sample.album.copy(title = query))
    override suspend fun searchTracksPage(query: String, request: MediaPageRequest): MediaPage<Track> =
        page("songs:$query", request, sample.track.copy(title = query))
    override suspend fun favoriteArtistsPage(request: MediaPageRequest) = page("favorite-artists", request, sample.artist)
    override suspend fun favoriteAlbumsPage(request: MediaPageRequest) = page("favorite-albums", request, sample.album)
    override suspend fun favoriteTracksPage(request: MediaPageRequest) = page("favorite-songs", request, sample.track)
    override suspend fun playlists(limit: Int) = if (probe.fetch("playlists") == RecoveryOutcome.Empty) emptyList()
        else listOf(sample.playlist)
    override suspend fun internetRadioStations() = if (probe.fetch("radio") == RecoveryOutcome.Empty) emptyList()
        else listOf(InternetRadioStation("radio", "Station", "https://example.test/radio"))

    private suspend fun <T> page(key: String, request: MediaPageRequest, item: T): MediaPage<T> {
        val items = if (probe.fetch("$key:${request.offset}") == RecoveryOutcome.Empty) emptyList() else listOf(item)
        return MediaPage(items, request.offset, request.limit, continuingPage && items.isNotEmpty())
    }
}

private class RecoveryStorage(private val probe: RecoveryProbe) : NaviampCoreDownloadStoragePort {
    var failPrune = false
    var audioCacheCount = 0L
    override suspend fun snapshot(sourceId: String): NaviampCoreDownloadStorageSnapshot {
        val downloads = if (probe.fetch("downloads:$sourceId") == RecoveryOutcome.Empty) emptyList()
            else listOf(NaviampCoreDownloadedTrack("file", FakeCoreMediaProvider().track, 100))
        return NaviampCoreDownloadStorageSnapshot(downloads, audioCacheCount = audioCacheCount)
    }
    override suspend fun pruneMissing(sourceId: String): Int {
        if (failPrune) error("Prune failed")
        return 0
    }
    override suspend fun remove(sourceId: String, track: Track): Unit = error("Unexpected removal")
    override suspend fun deleteAll(sourceId: String): Int = error("Unexpected deletion")
}

private class RecoveryKeepDownloaded : NaviampCoreKeepDownloadedPort {
    private val jobs = mutableMapOf<String, PersistedDownloadJob>()
    var reconcileCalls = 0
    var activePolicies = emptyList<KeepDownloadedCollectionPolicy>()
    override fun savedJobs(sourceId: String) = jobs.values.filter { it.sourceId == sourceId }
    override fun saveJob(job: PersistedDownloadJob) { jobs[job.job.id] = job }
    override fun deleteJob(sourceId: String, jobId: String) { jobs.remove(jobId) }
    override fun policies(sourceId: String) = activePolicies.filter { it.sourceId == sourceId }
    override fun toggle(policy: KeepDownloadedCollectionPolicy): NaviampKeepDownloadedToggleResult = error("Unexpected toggle")
    override fun reconcile(policy: KeepDownloadedCollectionPolicy, tracks: List<Track>): NaviampKeepDownloadedReconciliationApplication {
        reconcileCalls++
        return NaviampKeepDownloadedReconciliationApplication(emptyList(), null, null, false)
    }
}

private class RecoveryAlbumCatalog : AlbumCatalogRepository {
    val snapshots = mutableMapOf<AlbumCatalogScope, AlbumCatalogSnapshot>()
    var failRead = false
    override fun readAlbumCatalog(scope: AlbumCatalogScope): AlbumCatalogSnapshot? {
        if (failRead) error("Snapshot read failed")
        return snapshots[scope]
    }
    override fun replaceAlbumCatalog(scope: AlbumCatalogScope, snapshot: AlbumCatalogSnapshot) { snapshots[scope] = snapshot }
}

private class RecoveryArtistIndex : LocalLibraryIndexRepository {
    val artists = mutableMapOf<String, List<Artist>>()
    override fun mediaSource(sourceId: String) = null
    override fun replaceLibraryArtists(sourceId: String, artists: List<Artist>) { this.artists[sourceId] = artists }
    override fun upsertLibraryArtists(sourceId: String, artists: List<Artist>) {
        this.artists[sourceId] = (this.artists[sourceId].orEmpty() + artists).distinctBy { it.id }
    }
    override fun librarySnapshot(sourceId: String, limit: Long, offset: Long) =
        LibrarySnapshot(artists = artists[sourceId].orEmpty().drop(offset.toInt()).take(limit.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
    override fun markLibraryScanChecked(sourceId: String, signature: String): Unit = error("Unexpected scan write")
    override fun markLibrarySyncStarted(sourceId: String): Unit = error("Unexpected full sync")
    override fun markLibrarySyncCompleted(sourceId: String): Unit = error("Unexpected full sync")
    override fun upsertLibraryAlbums(sourceId: String, albums: List<Album>): Unit = error("Unexpected album write")
    override fun upsertLibraryTracks(sourceId: String, tracks: List<Track>): Unit = error("Unexpected track write")
    override fun searchLibrary(sourceId: String, query: String, limit: Long, offset: Long): LibrarySnapshot = error("Unexpected local search")
    override fun randomLibraryTrackForAlbum(sourceId: String, albumId: AlbumId): Track? = error("Unexpected track lookup")
    override fun libraryTracksForAlbum(sourceId: String, albumId: AlbumId, limit: Long): List<Track> = error("Unexpected track lookup")
    override fun randomLibraryTrackForArtist(sourceId: String, artistId: ArtistId): Track? = error("Unexpected track lookup")
    override fun libraryTracksForArtist(sourceId: String, artistId: ArtistId, limit: Long): List<Track> = error("Unexpected track lookup")
    override fun libraryTracksForArtistName(sourceId: String, artistName: String, limit: Long): List<Track> = error("Unexpected track lookup")
    override fun relatedLibraryTracks(sourceId: String, track: Track, limit: Long): List<Track> = error("Unexpected track lookup")
    override fun libraryIndexStats(sourceId: String): app.naviamp.domain.cache.LibraryIndexStats = error("Unexpected stats lookup")
    override fun libraryAlbumYears(sourceId: String): List<app.naviamp.domain.cache.LibraryAlbumYear> = error("Unexpected year lookup")
    override fun clearLibraryData(sourceId: String?) { if (sourceId == null) artists.clear() else artists.remove(sourceId) }
    override fun artistPopularTracks(sourceId: String, artistId: ArtistId, source: String):
        List<app.naviamp.domain.popular.ArtistPopularTrackMatch> = error("Unexpected popular tracks")
    override fun replaceArtistPopularTracks(sourceId: String, artistId: ArtistId, source: String,
        candidates: List<ArtistPopularTrackCandidate>, matchedTracksBySourceTrackId: Map<String, Track>,
        fetchedAtEpochMillis: Long): Unit = error("Unexpected popular track write")
}

private class RecoveryFixture(
    scope: CoroutineScope,
    albumCatalog: RecoveryAlbumCatalog? = null,
    artistIndex: RecoveryArtistIndex? = null,
) {
    val probe = RecoveryProbe()
    var provider = RecoveryProvider(probe)
    val store = NaviampCoreStateStore().apply {
        updateShell { it.copy(connectionSettings = it.connectionSettings.copy(currentSourceId = "source")) }
    }
    // Real provider sources may recreate decorators on every lookup.
    private val providerSource = NaviampCoreMediaProviderSource { object : MediaProvider by provider {} }
    lateinit var router: NaviampCoreCommandRouter
        private set
    val navigation = NaviampCoreNavigationController(NaviampNavigationController(), store,
        restoreCommand = { router.dispatch(it) }, onPageEntered = { router.onPageEntered(it) })
    val catalog = NaviampCoreCatalogController(store, providerSource, libraryIndex = artistIndex, albumIndex = albumCatalog?.let {
        AlbumLibraryIndex(it, { store.state.value.shell.connectionSettings.currentSourceId }, { 1L })
    })
    val playlists = NaviampCorePlaylistBrowseController(store, providerSource, navigation)
    val radio = NaviampCoreInternetRadioController(store, providerSource,
        playback = { error("Unexpected radio playback") },
        recents = object : NaviampCoreInternetRadioRecentsPort {
            override fun current() = emptyList<InternetRadioStation>()
            override suspend fun record(station: InternetRadioStation): List<InternetRadioStation> = error("Unexpected radio history")
        })
    val storage = RecoveryStorage(probe)
    val keep = RecoveryKeepDownloaded()
    var transferCalls = 0
    val downloads = NaviampCoreDownloadsController(scope, store, providerSource, storage,
        transfer = { _, _, _ -> transferCalls++; error("Transfer failed") }, keepDownloaded = keep,
        playback = { _, _ -> error("Unexpected downloaded playback") })

    init { router = NaviampCoreCommandRouter(scope, listOf(navigation, catalog, playlists, radio, downloads)) }

    suspend fun prepare(mode: RecoveryMode) {
        router.execute(NaviampCoreCommand.Navigation.SelectRoute(mode.route.toSharedRoute()))
        mode.view?.let { router.execute(NaviampCoreCommand.Library.ChangeView(it)) }
        if (mode.favorites) router.execute(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
        if (mode.query.isNotBlank()) router.execute(if (mode.view == null) NaviampCoreCommand.Search.ChangeQuery(mode.query)
            else NaviampCoreCommand.Library.ChangeQuery(mode.query))
        probe.requests.clear()
    }

    suspend fun fail(mode: RecoveryMode) {
        probe.outcome = RecoveryOutcome.Failed
        router.execute(mode.loadCommand)
    }

    fun enter(mode: RecoveryMode, entry: RecoveryEntry) {
        when (entry) {
            RecoveryEntry.Back -> {
                navigation.openPage(NaviampRoutePage(NaviampRoute.Settings))
                router.dispatch(NaviampCoreCommand.Navigation.Back)
            }
            RecoveryEntry.Reselect -> router.dispatch(NaviampCoreCommand.Navigation.SelectRoute(mode.route.toSharedRoute()))
            RecoveryEntry.AwayAndReselect -> {
                router.dispatch(NaviampCoreCommand.Navigation.SelectRoute(NaviampRoute.Home.toSharedRoute()))
                router.dispatch(NaviampCoreCommand.Navigation.SelectRoute(mode.route.toSharedRoute()))
            }
            RecoveryEntry.Replace -> {
                navigation.openPage(NaviampRoutePage(NaviampRoute.Settings))
                router.dispatch(NaviampCoreCommand.Navigation.ReplaceRoute(mode.route.toSharedRoute()))
            }
        }
    }

    fun owner(mode: RecoveryMode): NaviampCoreCommandController = when (mode.route) {
        NaviampRoute.Search, NaviampRoute.Library -> catalog
        NaviampRoute.Playlists -> playlists
        NaviampRoute.Radio -> radio
        NaviampRoute.Downloads -> downloads
        else -> error("Not a recovery root")
    }

    fun changeSource() {
        provider = RecoveryProvider(probe, "replacement")
        store.updateShell { it.copy(connectionSettings = it.connectionSettings.copy(currentSourceId = "replacement")) }
        catalog.resetForSourceChange()
        playlists.resetForSourceChange()
        radio.resetForSourceChange()
        downloads.resetForSourceChange()
        navigation.resetForSourceChange()
    }

    fun assertContent(mode: RecoveryMode) {
        val shell = store.state.value.shell
        when (mode.route) {
            NaviampRoute.Search -> assertFalse(shell.search.results.isEmpty, "$mode")
            NaviampRoute.Library -> {
                val view = assertNotNull(mode.view)
                val catalog = shell.library.catalog(view)
                assertEquals(mode.view, shell.library.selectedView)
                assertEquals(mode.query, catalog.query)
                assertEquals(mode.favorites, shell.library.favoritesOnly)
                assertFalse(catalog.favoritesFailed)
                if (view == NaviampLibraryView.Songs) {
                    assertTrue(catalog.tracks.isNotEmpty(), "$mode")
                    if (mode.favorites) assertTrue(catalog.tracks.any { it.favoriteActive }, "$mode")
                } else {
                    assertTrue(catalog.items.isNotEmpty(), "$mode")
                    if (mode.favorites) assertTrue(catalog.items.any { it.favoriteActive }, "$mode")
                }
            }
            NaviampRoute.Playlists -> assertTrue(shell.playlists.playlists.isNotEmpty())
            NaviampRoute.Radio -> assertTrue(shell.radio.stations.isNotEmpty())
            NaviampRoute.Downloads -> assertTrue(shell.downloads.downloads.isNotEmpty())
            else -> error("Not a recovery root")
        }
        assertNotLoading(mode)
    }

    fun assertNotLoading(mode: RecoveryMode) {
        val shell = store.state.value.shell
        when (mode.route) {
            NaviampRoute.Search -> assertFalse(shell.search.searching)
            NaviampRoute.Library -> {
                val catalog = shell.library.catalog(assertNotNull(mode.view))
                assertFalse(catalog.searching)
                assertFalse(catalog.favoritesLoading)
                assertFalse(catalog.syncStatus.isSyncing)
            }
            NaviampRoute.Playlists -> assertFalse(shell.playlists.refreshing)
            NaviampRoute.Radio -> assertFalse(shell.radio.refreshing)
            else -> Unit
        }
    }

    fun assertEmptyResetState() {
        val shell = store.state.value.shell
        assertTrue(shell.search.results.isEmpty)
        assertNull(shell.search.status)
        assertTrue(shell.playlists.playlists.isEmpty())
        assertNull(shell.playlists.status)
        assertTrue(shell.radio.stations.isEmpty())
        assertNull(shell.radio.status)
        assertTrue(shell.downloads.downloads.isEmpty())
        assertNull(shell.downloads.status)
        NaviampLibraryView.entries.forEach { view ->
            val catalog = shell.library.catalog(view)
            assertTrue(catalog.items.isEmpty())
            assertTrue(catalog.tracks.isEmpty())
            assertFalse(catalog.favoritesFailed)
            assertNull(catalog.syncStatus.message)
        }
    }
}

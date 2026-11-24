package app.naviamp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import app.naviamp.domain.radio.GeneratedRadioBatch
import app.naviamp.domain.radio.GeneratedRadioKind
import app.naviamp.domain.radio.GeneratedRadioPreferences
import app.naviamp.domain.radio.GeneratedRadioStation
import app.naviamp.domain.radio.GeneratedRadioStatus
import app.naviamp.domain.settings.SavedArtist
import app.naviamp.domain.settings.SavedTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NaviampGeneratedRadioUiTest {
    @Test
    fun displayedBatchActionsRemainDistinctAndNeverOptimisticallyChangePreview() = runDesktopComposeUiTest(420, 760) {
        val requests = mutableListOf<GeneratedRadioActionRequest>()
        val screen = preview()
        setContent {
            NaviampGeneratedRadioContent(screen, NaviampGeneratedRadioActions(requests::add, {}),
                NaviampColors.Dark, mediaActions())
        }
        listOf("Generate fresh batch", "Start station", "Play this batch").forEach {
            onNodeWithContentDescription(it).performClick()
        }
        listOf("Apply fresh batch to active station", "Play next", "Add to queue", "Save station").forEach {
            stationAction(it).performClick()
        }
        val actions = listOf(GeneratedRadioAction.Refresh, GeneratedRadioAction.Start, GeneratedRadioAction.PlayBatch,
            GeneratedRadioAction.Apply, GeneratedRadioAction.PlayNext, GeneratedRadioAction.AddToQueue,
            GeneratedRadioAction.Save)
        runOnIdle {
            assertEquals(actions.map { GeneratedRadioActionRequest(it, station.id) }, requests)
            assertEquals(listOf("second", "first", "second"), screen.batch!!.tracks.map { it.id })
            assertFalse(screen.loading)
            assertFalse(screen.station!!.saved)
        }
        stationAction("Save station").assertExists()
    }

    @Test
    fun offlinePreviewPermitsExactBatchButNotGenerationAndSourceMismatchBlocksPlayback() = runDesktopComposeUiTest(400, 720) {
        val state = mutableStateOf(preview().copy(offline = true))
        val requests = mutableListOf<GeneratedRadioActionRequest>()
        setContent {
            NaviampGeneratedRadioContent(state.value, NaviampGeneratedRadioActions(requests::add, {}), NaviampColors.Dark, mediaActions())
        }
        onNodeWithContentDescription("Start station").assertIsNotEnabled()
        onNodeWithContentDescription("Generate fresh batch").assertIsNotEnabled()
        onNodeWithContentDescription("Play this batch").assertIsEnabled().performClick()
        assertEquals(listOf(GeneratedRadioActionRequest(GeneratedRadioAction.PlayBatch, station.id)), requests)
        runOnIdle { state.value = state.value.copy(sourceAvailable = false) }
        onNodeWithContentDescription("Play this batch").assertIsNotEnabled()
        stationAction("Add to queue").assertIsNotEnabled()
        stationAction("Station details").performClick()
        onNodeWithText("Seed artist").assertIsNotEnabled()
        onNodeWithText("Saving a station or batch does not download audio.", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun staleRevisionAndUnrelatedActiveStationCannotApplyPreview() = runDesktopComposeUiTest(480, 760) {
        val state = mutableStateOf(preview().copy(batch = batch.copy(stationRevision = 1)))
        setContent {
            NaviampGeneratedRadioContent(state.value, NaviampGeneratedRadioActions({}, {}), NaviampColors.Dark, mediaActions())
        }
        onNodeWithContentDescription("Play this batch").assertIsEnabled()
        stationAction("Apply fresh batch to active station").assertIsNotEnabled()
        runOnIdle { state.value = preview().copy(activeStationId = "different-station") }
        stationAction("Apply fresh batch to active station").assertIsNotEnabled()
        runOnIdle { state.value = preview().copy(loading = true) }
        stationAction("Apply fresh batch to active station").assertIsNotEnabled()
        onNodeWithContentDescription("Generate fresh batch").assertIsNotEnabled()
        runOnIdle { state.value = preview() }
        stationAction("Apply fresh batch to active station").assertIsEnabled()
    }

    @Test
    fun renamePlaylistAndDeletionRequireExplicitUserSubmission() = runDesktopComposeUiTest(460, 780) {
        val requests = mutableListOf<GeneratedRadioActionRequest>()
        val screen = preview().copy(station = station.copy(saved = true))
        setContent {
            NaviampGeneratedRadioContent(screen, NaviampGeneratedRadioActions(requests::add, {}), NaviampColors.Dark, mediaActions())
        }
        stationAction("Rename station").performClick()
        onNode(hasSetTextAction()).performTextReplacement("  Evening station  ")
        onNodeWithText("Save", useUnmergedTree = true).performClick()
        assertEquals(GeneratedRadioActionRequest(GeneratedRadioAction.Rename, station.id, "Evening station"), requests.single())
        stationAction("Save as playlist").performClick()
        onNode(hasSetTextAction()).performTextReplacement("Exact saved batch")
        onNodeWithText("Save", useUnmergedTree = true).performClick()
        assertEquals(GeneratedRadioActionRequest(GeneratedRadioAction.SavePlaylist, station.id, "Exact saved batch"), requests.last())
        stationAction("Delete").performClick()
        onNodeWithText("Cancel").performClick()
        assertEquals(2, requests.size)
        stationAction("Delete").performClick()
        onNode(hasText("Delete") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        assertEquals(GeneratedRadioActionRequest(GeneratedRadioAction.Delete, station.id), requests.last())
        assertTrue(screen.station!!.saved)
    }

    @Test
    fun preferenceEditsPreserveIndependentOptionsAndWaitForOwnerState() = runDesktopComposeUiTest(340, 640) {
        val state = mutableStateOf(GeneratedRadioPreferences())
        val requests = mutableListOf<GeneratedRadioPreferences>()
        setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                NaviampGeneratedRadioPreferences(state.value, NaviampColors.Dark, requests::add)
            }
        }
        onNodeWithText("Endless generated radio").performScrollTo().performClick()
        assertEquals(GeneratedRadioPreferences(endlessEnabled = false, recentTrackExclusionHours = 24), requests.single())
        assertTrue(state.value.endlessEnabled)
        runOnIdle { state.value = requests.last() }
        onNodeWithText("Off").performScrollTo().performClick()
        assertEquals(GeneratedRadioPreferences(endlessEnabled = false, recentTrackExclusionHours = 0), requests.last())
        assertEquals(24, state.value.recentTrackExclusionHours)
    }

    @Test
    fun sonicPreviewAndContinuousStartEmitDifferentRequestsWithKeyboardSupport() = runDesktopComposeUiTest(320, 500) {
        val requests = mutableListOf<GeneratedRadioActionRequest>()
        setContent {
            NaviampGeneratedRadioBuilderActions("SonicMix", true, NaviampGeneratedRadioActions(requests::add, {}), sonic = true)
        }
        onNodeWithText("Preview station").performClick()
        onNodeWithText("Start radio from this mix").performSemanticsAction(SemanticsActions.RequestFocus)
        onRoot().performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf(
            GeneratedRadioActionRequest(GeneratedRadioAction.Open, value = "SonicMix"),
            GeneratedRadioActionRequest(GeneratedRadioAction.StartFromMix, value = "SonicMix")), requests)
    }

    @Test
    fun savedHomeStationsOfferQuickStartPreviewAndViewAllOnTelevision() = runDesktopComposeUiTest(1000, 850) {
        val requests = mutableListOf<GeneratedRadioActionRequest>()
        val collections = mutableListOf<String>()
        val item = station.copy(saved = true).toSharedGeneratedRadioItem({ null })
        val section = SharedHomeCollectionSectionUi(SharedHomeCollectionSectionIds.SavedGeneratedStations, "", listOf(item),
            titleResource = SharedHomeCollectionTitleResource.SavedGeneratedStations)
        setContent {
            TelevisionHome(NaviampHomeScreenUi(content = SharedHomeUi(collectionSections = listOf(section))),
                NaviampColors.Dark, homeActions(requests::add, collections::add), mediaActions(), DiscoverySamplePlaybackUi(),
                entryFocusGeneration = 1)
        }
        onRoot().performKeyInput { pressKey(Key.Enter) }
        assertEquals(GeneratedRadioActionRequest(GeneratedRadioAction.Start, station.id), requests.single())
        onRoot().performKeyInput { pressKey(Key.Menu) }
        assertEquals(GeneratedRadioActionRequest(GeneratedRadioAction.Open, station.id), requests.last())
        onNodeWithText("Preview station").performClick()
        assertEquals(GeneratedRadioActionRequest(GeneratedRadioAction.Open, station.id), requests.last())
        onNodeWithTag("$TelevisionHomeViewAllTestTagPrefix${section.id}").performClick()
        assertEquals(listOf(section.id), collections)
        assertEquals(SharedMediaItemKind.Artist, item.mediaKind)
    }

    @Test
    fun nowPlayingRetryTargetsStationRatherThanChangingTrackIdentity() = runDesktopComposeUiTest(320, 420) {
        val requests = mutableListOf<GeneratedRadioActionRequest>()
        setContent { NaviampGeneratedRadioContext(station, GeneratedRadioStatus.Exhausted, NaviampColors.Dark, requests::add) }
        onNodeWithText("Station: Original artist station").performClick()
        onNodeWithText("Retry").performClick()
        assertEquals(listOf(GeneratedRadioActionRequest(GeneratedRadioAction.Open, station.id),
            GeneratedRadioActionRequest(GeneratedRadioAction.Retry, station.id)), requests)
    }

    @Test
    fun narrowTelevisionDetailFocusesBackAndPreservesOpaqueSeedIds() = runDesktopComposeUiTest(320, 480) {
        mainClock.autoAdvance = false
        val requests = mutableListOf<GeneratedRadioActionRequest>()
        setContent {
            NaviampGeneratedRadioContent(preview(), NaviampGeneratedRadioActions(requests::add, {}),
                NaviampColors.Dark, mediaActions(), television = true)
        }
        mainClock.advanceTimeBy(200)
        onNodeWithContentDescription("Back").assertIsFocused()
        mainClock.autoAdvance = true
        onRoot().performKeyInput { pressKey(Key.Enter) }
        assertEquals(GeneratedRadioActionRequest(GeneratedRadioAction.Close, station.id), requests.single())
        stationAction("Station details").performClick()
        onNodeWithText("Seed artist").performScrollTo().performClick()
        assertEquals(GeneratedRadioActionRequest(GeneratedRadioAction.OpenSeed, station.id, "artist:artist:opaque:id"), requests.last())
        onNode(isDialog()).assertDoesNotExist()
        onNodeWithContentDescription("Play this batch").assertIsDisplayed()
        val bounds = onNodeWithContentDescription("Play this batch").fetchSemanticsNode().boundsInRoot
        val root = onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= root.left && bounds.right <= root.right)
    }

    @Test
    fun compactPlaylistHeaderKeepsTracksVisibleAndBackPinnedAcrossSizesAndThemes() {
        for ((width, height, television) in listOf(Triple(280, 360, false), Triple(320, 480, false),
            Triple(1000, 650, false), Triple(320, 480, true))) {
            for ((theme, colors) in listOf("dark" to NaviampColors.Dark, "light" to NaviampColors.Light)) {
                runDesktopComposeUiTest(width, height) {
                    val tracks = List(80) { SavedTrack("track-$it", "Track $it", artistName = "Artist") }
                    val requests = mutableListOf<GeneratedRadioActionRequest>()
                    val screen = preview().copy(
                        station = station.copy(name = "A long station name that must not displace navigation or playback actions"),
                        batch = batch.copy(tracks = tracks),
                        tracks = tracks.map { SharedTrackRowUi(it.id, it.title, it.artistName) },
                    )
                    setContent {
                        NaviampGeneratedRadioContent(screen, NaviampGeneratedRadioActions(requests::add, {}),
                            colors, mediaActions(), television = television)
                    }
                    onNodeWithText("Track 0").assertIsDisplayed()
                    onNodeWithText("Track 79").assertDoesNotExist()
                    onNodeWithText("Original seeds").assertDoesNotExist()
                    val root = onRoot().getUnclippedBoundsInRoot()
                    val back = onNodeWithContentDescription("Back")
                    val backBounds = back.getUnclippedBoundsInRoot()
                    val start = onNodeWithContentDescription("Start station").assertIsDisplayed().getUnclippedBoundsInRoot()
                    val play = onNodeWithContentDescription("Play this batch").assertIsDisplayed().getUnclippedBoundsInRoot()
                    assertTrue(start.right <= play.left)
                    assertTrue(start.left >= root.left && play.right <= root.right)
                    System.getenv("NAVIAMP_ARTWORK_CAPTURE")?.let { directory ->
                        val target = java.io.File(directory, "station-detail-$width-$height-$television-$theme.png")
                        target.parentFile.mkdirs()
                        javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", target)
                    }
                    onNodeWithTag("generated-radio-tracks").performScrollToIndex(tracks.lastIndex)
                    onNodeWithText("Track 79").assertIsDisplayed()
                    back.assertIsDisplayed()
                    assertEquals(backBounds, back.getUnclippedBoundsInRoot())
                    back.performSemanticsAction(SemanticsActions.RequestFocus)
                    onRoot().performKeyInput { pressKey(Key.Enter) }
                    assertEquals(listOf(GeneratedRadioActionRequest(GeneratedRadioAction.Close, station.id)), requests)
                }
            }
        }
    }

    @Test
    fun exceptionalStatusesExplainDisabledGenerationAndKeepRetryAvailable() = runDesktopComposeUiTest(500, 600) {
        val state = mutableStateOf(preview())
        val requests = mutableListOf<GeneratedRadioActionRequest>()
        setContent {
            NaviampGeneratedRadioContent(state.value, NaviampGeneratedRadioActions(requests::add, {}), NaviampColors.Dark, mediaActions())
        }
        for (status in listOf(GeneratedRadioStatus.Fetching, GeneratedRadioStatus.Exhausted, GeneratedRadioStatus.Failed,
            GeneratedRadioStatus.Unsupported, GeneratedRadioStatus.InvalidRecipe)) {
            runOnIdle { state.value = preview().copy(status = status, loading = status == GeneratedRadioStatus.Fetching) }
            val label = kotlinx.coroutines.runBlocking { org.jetbrains.compose.resources.getString(generatedRadioStatusResource(status)) }
            onNodeWithText(label).assertIsDisplayed()
            if (status == GeneratedRadioStatus.Failed || status == GeneratedRadioStatus.Exhausted) {
                onNodeWithText("Retry").assertIsEnabled().performClick()
                onNodeWithContentDescription("Start station").assertIsEnabled()
            } else {
                onNodeWithText("Retry").assertDoesNotExist()
                onNodeWithContentDescription("Start station").assertIsNotEnabled()
                onNodeWithContentDescription("Generate fresh batch").assertIsNotEnabled()
            }
        }
        assertEquals(List(2) { GeneratedRadioActionRequest(GeneratedRadioAction.Retry, station.id) }, requests)
    }

    @Test
    fun stationDialogsProtectFullscreenUntilDismissedWithoutSubmittingActions() = runDesktopComposeUiTest(500, 650) {
        val popups = NaviampPopupRegistry()
        val dispatcher = NaviampSystemBackDispatcher()
        val owned = mutableStateOf(false)
        val controller = NaviampWindowController({ true }, NaviampWindowSnapshot())
        val requests = mutableListOf<GeneratedRadioActionRequest>()
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalNaviampPopupRegistry provides popups,
                LocalNaviampSystemBackDispatcher provides dispatcher, LocalNaviampOwnedPopupWindows provides owned.value) {
                NaviampWindowEnvironment(controller, app.naviamp.domain.settings.DesktopShortcutPlatform.Windows) {
                    NaviampGeneratedRadioContent(preview().copy(station = station.copy(saved = true)),
                        NaviampGeneratedRadioActions(requests::add, {}), NaviampColors.Dark, mediaActions())
                }
            }
        }
        for (ownedWindows in listOf(false, true)) {
            runOnIdle { owned.value = ownedWindows }
            for (label in listOf("Station details", "Rename station", "Save as playlist", "Delete")) {
                runOnIdle { controller.toggle() }
                        stationAction(label).performClick()
                onNode(isDialog()).assertExists()
                runOnIdle {
                    controller.escape(popups.blocksWindowEscape)
                    assertEquals(NaviampWindowPlacement.Fullscreen, controller.state.placement)
                }
                if (ownedWindows) {
                    onNodeWithText(if (label == "Station details") "Close" else "Cancel")
                        .performSemanticsAction(SemanticsActions.RequestFocus).performKeyInput { pressKey(Key.Escape) }
                } else {
                    // Scene tests dispatch host Back directly for Material dialogs.
                    runOnIdle { checkNotNull(dispatcher.currentHandler).invoke() }
                }
                onNode(isDialog()).assertDoesNotExist()
                runOnIdle {
                    assertEquals(NaviampWindowPlacement.Fullscreen, controller.state.placement)
                    controller.escape(popups.blocksWindowEscape)
                    assertEquals(NaviampWindowPlacement.Floating, controller.state.placement)
                }
            }
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun wideStationToolbarUsesPlaylistControlsAndExposesEveryActionDirectly() = runDesktopComposeUiTest(1000, 650) {
        setContent {
            Column {
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                    NaviampGeneratedRadioContent(preview().copy(station = station.copy(saved = true)),
                        NaviampGeneratedRadioActions({}, {}), NaviampColors.Dark, mediaActions())
                }
                NaviampResponsiveActionRow(NaviampColors.Dark, listOf(
                    NaviampDetailAction("Canonical play", NaviampTransportIcons.Play, {}),
                ))
            }
        }
        for (label in listOf("Start station", "Play this batch", "Generate fresh batch", "Apply fresh batch to active station",
            "Station details", "Play Next", "Add to queue", "Save as playlist", "Rename station", "Delete")) {
            onNode(hasContentDescription(label) and hasAnyAncestor(hasTestTag("generated-radio-toolbar"))).assertIsDisplayed()
        }
        val toolbarButtons = onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("generated-radio-toolbar")))
            .fetchSemanticsNodes().sortedBy { it.boundsInRoot.left }
        val refreshIndex = toolbarButtons.indexOfFirst {
            it.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].contains("Generate fresh batch")
        }
        assertEquals(listOf("Apply fresh batch to active station"),
            toolbarButtons[refreshIndex + 1].config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription])
        onNode(hasContentDescription("More actions") and hasAnyAncestor(hasTestTag("generated-radio-header"))).assertDoesNotExist()
        val canonical = onNodeWithContentDescription("Canonical play").getUnclippedBoundsInRoot()
        val play = onNodeWithContentDescription("Play this batch").getUnclippedBoundsInRoot()
        assertEquals(canonical.right - canonical.left, play.right - play.left)
        assertEquals(canonical.bottom - canonical.top, play.bottom - play.top)
    }

    @Test
    fun mixedSeedDetailsStayAlignedAndKeepEachNavigationTarget() {
        val mixed = station.copy(name = "华纳群星难忘您许冠杰",
            artists = listOf(SavedArtist("artist:explicit:group", "群星")),
            albums = listOf(app.naviamp.domain.settings.SavedAlbum("album:explicit:tribute", "华纳群星难忘您许冠杰", "群星")),
            seedTracks = listOf(
                SavedTrack("track:student", "学生哥", "artist:lam", "林子祥", "album:track:tribute", "华纳群星难忘您许冠杰"),
                SavedTrack("track:yang", "阳关", "artist:track:group", "群星", "album:dunhuang", "大敦煌 民乐交响曲"),
                SavedTrack("track:long", "A long original seed title that must wrap without shifting the artist and album links",
                    artistName = "Unknown artist", albumId = "album:blank", albumTitle = ""),
            ))
        for ((width, height) in listOf(690 to 704, 320 to 480)) {
            for ((theme, colors) in listOf("light" to NaviampColors.Light, "dark" to NaviampColors.Dark)) {
                runDesktopComposeUiTest(width, height) {
                    val requests = mutableListOf<GeneratedRadioActionRequest>()
                    setContent {
                        androidx.compose.runtime.CompositionLocalProvider(LocalNaviampOwnedPopupWindows provides true) {
                            NaviampGeneratedRadioContent(preview().copy(station = mixed, batch = batch.copy(stationRevision = 2)),
                                NaviampGeneratedRadioActions(requests::add, {}), colors, mediaActions())
                        }
                    }
                    stationAction("Station details").performClick()
                    onNodeWithText("Recipe revision 3").assertIsDisplayed()
                    val label = onNodeWithText("Familiarity").assertIsDisplayed().getUnclippedBoundsInRoot()
                    val value = onAllNodesWithText("Balanced")[0].getUnclippedBoundsInRoot()
                    assertTrue(value.left >= label.right)
                    assertEquals(label.left, onNodeWithText("Artist spread").getUnclippedBoundsInRoot().left)
                    fun capture(part: String) {
                        System.getenv("NAVIAMP_ARTWORK_CAPTURE")?.let { directory ->
                            val target = java.io.File(directory, "station-dialog-$width-$theme-$part.png")
                            target.parentFile.mkdirs()
                            javax.imageio.ImageIO.write(onNode(isDialog()).captureToImage().toAwtImage(), "png", target)
                        }
                    }
                    capture("metadata")
                    val artists = onNodeWithTag("generated-radio-seed-artists").getUnclippedBoundsInRoot()
                    val albums = onNodeWithTag("generated-radio-seed-albums").getUnclippedBoundsInRoot()
                    val songs = onNodeWithTag("generated-radio-seed-tracks").getUnclippedBoundsInRoot()
                    assertEquals(artists.left, albums.left)
                    assertEquals(artists.left, songs.left)
                    onNodeWithText("阳关").performScrollTo().assertIsDisplayed()
                    capture("seeds")
                    onNodeWithText(mixed.seedTracks.last().title).performScrollTo().assertIsDisplayed()
                    onNode(hasClickAction() and hasAnyAncestor(hasTestTag("generated-radio-seed-track-2"))).assertDoesNotExist()
                    onNodeWithText("recipe revision 2", substring = true).performScrollTo().assertIsDisplayed()
                    onNodeWithText("Close").performClick()
                    val links = listOf(
                        Triple("generated-radio-seed-artists", "群星", "artist:artist:explicit:group"),
                        Triple("generated-radio-seed-albums", "华纳群星难忘您许冠杰", "album:album:explicit:tribute"),
                        Triple("generated-radio-seed-track-0", "林子祥", "artist:artist:lam"),
                        Triple("generated-radio-seed-track-0", "华纳群星难忘您许冠杰", "album:album:track:tribute"),
                        Triple("generated-radio-seed-track-1", "群星", "artist:artist:track:group"),
                        Triple("generated-radio-seed-track-1", "大敦煌 民乐交响曲", "album:album:dunhuang"),
                    )
                    for ((section, text, target) in links) {
                        stationAction("Station details").performClick()
                        onNode(hasText(text) and hasAnyAncestor(hasTestTag(section))).performScrollTo().performClick()
                        onNode(isDialog()).assertDoesNotExist()
                        assertEquals(GeneratedRadioActionRequest(GeneratedRadioAction.OpenSeed, station.id, target), requests.last())
                    }
                    assertEquals(links.size, requests.size)
                }
            }
        }
    }

    private fun ComposeUiTest.stationAction(label: String): SemanticsNodeInteraction {
        val direct = onAllNodes(hasContentDescription(label, ignoreCase = true) and hasAnyAncestor(hasTestTag("generated-radio-toolbar")))
        if (direct.fetchSemanticsNodes().isNotEmpty()) {
            if (onAllNodes(isPopup()).fetchSemanticsNodes().isNotEmpty()) {
                onAllNodes(hasClickAction() and isEnabled() and hasAnyAncestor(isPopup())).onFirst()
                    .performSemanticsAction(SemanticsActions.RequestFocus).performKeyInput { pressKey(Key.Escape) }
                onAllNodes(isPopup()).assertCountEquals(0)
            }
            return direct[0]
        }
        if (onAllNodes(isPopup()).fetchSemanticsNodes().isEmpty()) {
            onNode(hasContentDescription("More actions") and hasAnyAncestor(hasTestTag("generated-radio-toolbar"))).performClick()
        }
        return onNode(hasText(label, ignoreCase = true) and hasClickAction()).performScrollTo()
    }

    private val station = GeneratedRadioStation("station-id", "Original artist station", GeneratedRadioKind.Artist,
        revision = 3, artists = listOf(SavedArtist("artist:opaque:id", "Seed artist")))
    private val batch = GeneratedRadioBatch("batch-id", station.id, station.revision, 1_700_000_000_000,
        listOf(SavedTrack("second", "Second", artistName = "Artist"), SavedTrack("first", "First", artistName = "Artist"),
            SavedTrack("second", "Second", artistName = "Artist")))
    private fun preview() = NaviampGeneratedRadioUi(station = station, batch = batch, open = true,
        activeStationId = station.id, tracks = batch.tracks.map { SharedTrackRowUi(it.id, it.title, it.artistName) })
    private fun mediaActions() = NaviampMediaActions({}, {})
    private fun homeActions(onGenerated: (GeneratedRadioActionRequest) -> Unit, onCollection: (String) -> Unit) = NaviampHomeActions(
        onRefresh = {}, onRecentRadioSelected = {}, onInternetRadioStationSelected = {}, onMixBuilderSelected = {},
        onStationSelected = {}, onSonicDiscoveryTrackAction = {}, onRecentlyPlayedTrackAction = {},
        onCollectionSelected = onCollection, onCollectionBack = {}, onCollectionPageLayoutChanged = { _, _ -> },
        onGeneratedRadioAction = onGenerated)
}

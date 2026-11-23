package app.naviamp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
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
        val actions = listOf(GeneratedRadioAction.Refresh, GeneratedRadioAction.Start, GeneratedRadioAction.PlayBatch,
            GeneratedRadioAction.Apply, GeneratedRadioAction.PlayNext, GeneratedRadioAction.AddToQueue,
            GeneratedRadioAction.Save, GeneratedRadioAction.TogglePin)
        actions.forEach { action ->
            onNodeWithTag("generated-radio-${action.name}").performScrollTo().performClick()
        }
        runOnIdle {
            assertEquals(actions.map { GeneratedRadioActionRequest(it, station.id) }, requests)
            assertEquals(listOf("second", "first", "second"), screen.batch!!.tracks.map { it.id })
            assertFalse(screen.loading)
            assertFalse(screen.station!!.saved)
            assertFalse(screen.station.pinned)
        }
        onNodeWithTag("generated-radio-Save").assertExists()
    }

    @Test
    fun offlinePreviewPermitsExactBatchButNotGenerationAndSourceMismatchBlocksPlayback() = runDesktopComposeUiTest(400, 720) {
        val state = mutableStateOf(preview().copy(offline = true))
        val requests = mutableListOf<GeneratedRadioActionRequest>()
        setContent {
            NaviampGeneratedRadioContent(state.value, NaviampGeneratedRadioActions(requests::add, {}), NaviampColors.Dark, mediaActions())
        }
        onNodeWithTag("generated-radio-Start").performScrollTo().assertIsNotEnabled()
        onNodeWithTag("generated-radio-Refresh").assertIsNotEnabled()
        onNodeWithTag("generated-radio-PlayBatch").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(GeneratedRadioActionRequest(GeneratedRadioAction.PlayBatch, station.id)), requests)
        runOnIdle { state.value = state.value.copy(sourceAvailable = false) }
        onNodeWithTag("generated-radio-PlayBatch").assertIsNotEnabled()
        onNodeWithTag("generated-radio-AddToQueue").performScrollTo().assertIsNotEnabled()
        onNodeWithText("Saving a station or batch does not download audio.", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun staleRevisionAndUnrelatedActiveStationCannotApplyPreview() = runDesktopComposeUiTest(480, 760) {
        val state = mutableStateOf(preview().copy(batch = batch.copy(stationRevision = 1)))
        setContent {
            NaviampGeneratedRadioContent(state.value, NaviampGeneratedRadioActions({}, {}), NaviampColors.Dark, mediaActions())
        }
        onNodeWithTag("generated-radio-Apply").performScrollTo().assertIsNotEnabled()
        onNodeWithTag("generated-radio-PlayBatch").assertIsEnabled()
        runOnIdle { state.value = preview().copy(activeStationId = "different-station") }
        onNodeWithTag("generated-radio-Apply").assertIsNotEnabled()
        runOnIdle { state.value = preview().copy(loading = true) }
        onNodeWithTag("generated-radio-Apply").assertIsNotEnabled()
        onNodeWithTag("generated-radio-Refresh").assertIsNotEnabled()
        runOnIdle { state.value = preview() }
        onNodeWithTag("generated-radio-Apply").assertIsEnabled()
    }

    @Test
    fun renamePlaylistAndDeletionRequireExplicitUserSubmission() = runDesktopComposeUiTest(460, 780) {
        val requests = mutableListOf<GeneratedRadioActionRequest>()
        val screen = preview().copy(station = station.copy(saved = true))
        setContent {
            NaviampGeneratedRadioContent(screen, NaviampGeneratedRadioActions(requests::add, {}), NaviampColors.Dark, mediaActions())
        }
        onNodeWithTag("generated-radio-Rename").performScrollTo().performClick()
        onNode(hasSetTextAction()).performTextReplacement("  Evening station  ")
        onNodeWithText("Save", useUnmergedTree = true).performClick()
        assertEquals(GeneratedRadioActionRequest(GeneratedRadioAction.Rename, station.id, "Evening station"), requests.single())
        onNodeWithTag("generated-radio-SavePlaylist").performScrollTo().performClick()
        onNode(hasSetTextAction()).performTextReplacement("Exact saved batch")
        onNodeWithText("Save", useUnmergedTree = true).performClick()
        assertEquals(GeneratedRadioActionRequest(GeneratedRadioAction.SavePlaylist, station.id, "Exact saved batch"), requests.last())
        onNodeWithTag("generated-radio-Delete").performScrollTo().performClick()
        onNodeWithText("Cancel").performClick()
        assertEquals(2, requests.size)
        onNodeWithTag("generated-radio-Delete").performClick()
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
        val item = station.copy(saved = true, pinned = true).toSharedGeneratedRadioItem({ null })
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
        onNodeWithText("Back").assertIsFocused()
        mainClock.autoAdvance = true
        onRoot().performKeyInput { pressKey(Key.Enter) }
        assertEquals(GeneratedRadioActionRequest(GeneratedRadioAction.Close, station.id), requests.single())
        onNodeWithText("Seed artist").performScrollTo().performClick()
        assertEquals(GeneratedRadioActionRequest(GeneratedRadioAction.OpenSeed, station.id, "artist:artist:opaque:id"), requests.last())
        onNodeWithTag("generated-radio-detail").performScrollToNode(hasTestTag("generated-radio-PlayBatch"))
        onNodeWithTag("generated-radio-PlayBatch").assertIsDisplayed()
        val bounds = onNodeWithTag("generated-radio-PlayBatch").fetchSemanticsNode().boundsInRoot
        val root = onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= root.left && bounds.right <= root.right)
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

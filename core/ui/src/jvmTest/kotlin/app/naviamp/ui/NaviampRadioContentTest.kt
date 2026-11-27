package app.naviamp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import app.naviamp.domain.AlbumId
import app.naviamp.domain.Genre
import app.naviamp.domain.home.HomeContent
import app.naviamp.domain.home.HomeStationLibrary
import app.naviamp.domain.radio.GeneratedRadioKind
import app.naviamp.domain.radio.GeneratedRadioStation
import app.naviamp.domain.settings.SavedArtist
import app.naviamp.ui.generated.resources.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NaviampRadioContentTest {
    @Test
    fun sectionsMoveOutOfHomeAndServerActionsKeepTheirOriginalIntents() = runDesktopComposeUiTest(690, 900) {
        val artistMixLabel = runBlocking { getString(Res.string.mix_artist_builder_title) }
        val libraryRadioLabel = runBlocking { getString(Res.string.home_station_library_title) }
        var showHome by mutableStateOf(true)
        var home by mutableStateOf(home())
        val builders = mutableListOf<String>()
        val stations = mutableListOf<String>()
        val generated = mutableListOf<GeneratedRadioActionRequest>()
        val collections = mutableListOf<String>()
        val actions = homeActions(builder = { builders += it.id }, station = { stations += it.id }, generated = generated::add,
            collection = { id ->
                collections += id
                home = home.copy(collectionPage = SharedHomeCollectionPageUi(home.content.collectionSection(id)!!))
            }, back = { home = home.copy(collectionPage = null) })
        setContent {
            Box(Modifier.fillMaxSize().background(NaviampColors.Light.background)) {
                if (showHome) SharedHome(NaviampColors.Light, home.content, actions, mediaActions())
                else NaviampRadioContent(NaviampColors.Light, home, internet(), actions, internetActions(), mediaActions())
            }
        }
        onNodeWithText("Home album").assertIsDisplayed()
        listOf("Mix Builders", "Stations", "Saved stations").forEach { onNodeWithText(it, ignoreCase = true).assertDoesNotExist() }
        runOnIdle { showHome = false }
        onNodeWithText("Server Radio").assertIsSelected()
        onNodeWithText("Internet Radio").assertIsNotSelected()
        onNodeWithText("Home album").assertDoesNotExist()
        assertTrue(builders.isEmpty() && stations.isEmpty() && generated.isEmpty())
        onNodeWithText(artistMixLabel).performScrollTo().performClick()
        onNodeWithText(libraryRadioLabel).performScrollTo().performClick()
        onNodeWithText("Evening station").performScrollTo().performClick()
        assertEquals(listOf("artist"), builders)
        assertEquals(listOf(HomeStationLibrary), stations)
        assertEquals(listOf(GeneratedRadioActionRequest(GeneratedRadioAction.Start, "saved")), generated)
        onNodeWithText("Saved stations", ignoreCase = true).performScrollTo().performClick()
        assertEquals(listOf(SharedHomeCollectionSectionIds.SavedGeneratedStations), collections)
        onNodeWithText("Server Radio").assertDoesNotExist()
        onNodeWithText("Evening station").assertIsDisplayed()
        onNode((hasContentDescription("Back") or hasText("Back")) and hasClickAction()).performClick()
        onNodeWithText("Server Radio").assertIsSelected()
        onNodeWithText("Internet Radio").performClick()
        onNodeWithText("Ambient One").assertIsDisplayed()
        listOf(artistMixLabel, libraryRadioLabel, "Saved stations").forEach { onNodeWithText(it, ignoreCase = true).assertDoesNotExist() }
        assertEquals(1, generated.size)
    }

    @Test
    fun internetTabPreservesPlaybackRefreshAndExplicitStationEditing() = runDesktopComposeUiTest(690, 850) {
        var serverRefreshes = 0
        var internetRefreshes = 0
        var server by mutableStateOf(home())
        var internet by mutableStateOf(internet())
        val stationActions = mutableListOf<StationRowActionRequest>()
        val saves = mutableListOf<NaviampInternetRadioStationEditUi>()
        setContent {
            NaviampRadioContent(NaviampColors.Dark, server, internet, homeActions(refresh = { serverRefreshes++ }),
                NaviampInternetRadioActions({ internetRefreshes++ }, stationActions::add, saves::add), mediaActions())
        }
        assertHeaderActions("Refresh")
        onNodeWithContentDescription("New station").assertDoesNotExist()
        onNodeWithContentDescription("Refresh").performClick()
        assertEquals(1, serverRefreshes)
        runOnIdle { server = server.copy(refreshing = true) }
        headerAction("Refresh").assertIsNotEnabled()
        onNodeWithText("Internet Radio").performClick()
        assertTrue(stationActions.isEmpty() && saves.isEmpty())
        assertHeaderActions("New station", "Refresh")
        headerAction("Refresh").assertIsEnabled().performClick()
        assertEquals(1, internetRefreshes)
        assertEquals(1, serverRefreshes)
        runOnIdle { internet = internet.copy(refreshing = true) }
        headerAction("Refresh").assertIsNotEnabled()
        headerAction("New station").assertIsEnabled()
        onNodeWithText("Server Radio").performClick()
        assertHeaderActions("Refresh")
        onNodeWithContentDescription("New station").assertDoesNotExist()
        headerAction("Refresh").assertIsNotEnabled()
        runOnIdle { server = server.copy(refreshing = false) }
        headerAction("Refresh").assertIsEnabled()
        onNodeWithText("Internet Radio").performClick()
        headerAction("Refresh").assertIsNotEnabled()
        runOnIdle { internet = internet.copy(refreshing = false) }
        headerAction("Refresh").assertIsEnabled()
        onNodeWithText("Ambient One").performClick()
        assertEquals(StationRowAction.Select, stationActions.single().action)
        onNodeWithContentDescription("New station").performClick()
        onAllNodes(hasSetTextAction())[0].performTextReplacement("New stream")
        onAllNodes(hasSetTextAction())[1].performTextReplacement("https://example.test/live")
        onNode(hasText("Save") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        assertEquals(NaviampInternetRadioStationEditUi(name = "New stream", streamUrl = "https://example.test/live"), saves.single())
        onNodeWithText("Ambient One").performMouseInput { click(button = MouseButton.Secondary) }
        onNode(hasText("Edit", substring = true, ignoreCase = true) and hasAnyAncestor(isPopup())).performClick()
        onAllNodes(hasSetTextAction())[0].performTextReplacement("Renamed stream")
        onNode(hasText("Save") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        assertEquals("stream", saves.last().id)
        assertEquals("Renamed stream", saves.last().name)
        onNodeWithText("Ambient One").performMouseInput { click(button = MouseButton.Secondary) }
        onNode(hasText("Delete", substring = true, ignoreCase = true) and hasAnyAncestor(isPopup())).performClick()
        onNode(hasText("Delete", substring = true) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        assertEquals(listOf(StationRowAction.Select, StationRowAction.Delete), stationActions.map { it.action })
        assertEquals("stream", stationActions.last().station.id)
    }

    @Test
    fun selectedTabSurvivesNavigationWithoutLeakingToAnotherSource() = runDesktopComposeUiTest(690, 700) {
        var source by mutableStateOf("first")
        var visible by mutableStateOf(true)
        setContent {
            val holder = rememberSaveableStateHolder()
            if (visible) holder.SaveableStateProvider("radio:$source") {
                NaviampRadioContent(NaviampColors.Dark, home(), internet(), homeActions(), internetActions(), mediaActions(), sourceId = source)
            }
        }
        onNodeWithText("Internet Radio").performClick()
        runOnIdle { visible = false }
        runOnIdle { visible = true }
        onNodeWithText("Internet Radio").assertIsSelected()
        assertHeaderActions("New station", "Refresh")
        runOnIdle { source = "second" }
        onNodeWithText("Server Radio").assertIsSelected()
        assertHeaderActions("Refresh")
        runOnIdle { source = "first" }
        onNodeWithText("Internet Radio").assertIsSelected()
        assertHeaderActions("New station", "Refresh")
    }

    @Test
    fun televisionTabsAndServerBuildersRemainKeyboardReachable() = runDesktopComposeUiTest(1000, 850) {
        var entry by mutableStateOf<Int?>(1)
        val builders = mutableListOf<String>()
        val stations = mutableListOf<StationRowActionRequest>()
        setContent {
            NaviampRadioContent(NaviampColors.Dark, home(), internet(), homeActions(builder = { builders += it.id }),
                NaviampInternetRadioActions({}, stations::add, {}), mediaActions(), television = true,
                entryFocusGeneration = entry, onEntryFocusHandled = { entry = null })
        }
        onRoot().performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("artist"), builders)
        onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        onNodeWithText("Server Radio").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.Enter) }
        onNodeWithText("Internet Radio").assertIsSelected().assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        headerAction("New station").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        headerAction("Refresh").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithText("Internet Radio").assertIsFocused()
        assertTrue(stations.isEmpty())
        runOnIdle { entry = 2 }
        onRoot().performKeyInput { pressKey(Key.Enter) }
        assertEquals(StationRowAction.Select, stations.single().action)
        onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        onNodeWithText("Internet Radio").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionLeft); pressKey(Key.Enter) }
        onNodeWithText("Server Radio").assertIsSelected().assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        headerAction("Refresh").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithText("Server Radio").assertIsFocused()
    }

    @Test
    fun radioTabsRenderAtCompactAndWideSizesAndKeepEmptyServerRefreshAvailable() {
        for ((width, height, television) in listOf(Triple(320, 640, false), Triple(690, 704, false),
            Triple(1000, 750, false), Triple(320, 480, true))) {
            for ((theme, colors) in listOf("light" to NaviampColors.Light, "dark" to NaviampColors.Dark)) {
                runDesktopComposeUiTest(width, height) {
                    var empty by mutableStateOf(false)
                    var refreshes = 0
                    setContent {
                        Box(Modifier.fillMaxSize().background(colors.background)) {
                            NaviampRadioContent(colors, if (empty) NaviampHomeScreenUi() else home(), internet(),
                                homeActions(refresh = { refreshes++ }), internetActions(), mediaActions(), television = television)
                        }
                    }
                    onNodeWithText("Server Radio").assertIsDisplayed().assertIsSelected()
                    onNodeWithText("Internet Radio").assertIsDisplayed()
                    assertHeaderActions("Refresh")
                    capture("server-$width-$height-$television-$theme")
                    onNodeWithText("Internet Radio").performClick()
                    onNodeWithText("Ambient One").assertIsDisplayed()
                    assertHeaderActions("New station", "Refresh", inline = !television || width >= 690)
                    capture("internet-$width-$height-$television-$theme")
                    onNodeWithText("Server Radio").performClick()
                    runOnIdle { empty = true }
                    onNodeWithText("No server radio is available").assertIsDisplayed()
                    onNodeWithContentDescription("Refresh").performClick()
                    assertEquals(1, refreshes)
                }
            }
        }
    }

    private fun ComposeUiTest.headerAction(label: String) = onNode(
        (hasContentDescription(label) or hasText(label)) and hasClickAction() and hasAnyAncestor(hasTestTag("radio-header")),
    )

    private fun ComposeUiTest.assertHeaderActions(vararg labels: String, inline: Boolean = true) {
        onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("radio-header"))).assertCountEquals(labels.size)
        onNode(hasContentDescription("More actions") and hasAnyAncestor(hasTestTag("radio-header"))).assertDoesNotExist()
        val title = onNodeWithText("Radio").fetchSemanticsNode().boundsInRoot
        val tabs = onNodeWithTag("radio-tabs").fetchSemanticsNode().boundsInRoot
        val header = onNodeWithTag("radio-header").fetchSemanticsNode().boundsInRoot
        assertTrue(header.bottom <= tabs.top, "Radio title and controls must stay above the tabs")
        for (label in labels) {
            val bounds = headerAction(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= header.left && bounds.right <= header.right && bounds.bottom <= tabs.top,
                "$label must fit in the Radio header")
            if (inline) {
                assertTrue(bounds.left >= title.right, "$label must be beside the title")
                assertTrue(kotlin.math.abs(bounds.center.y - title.center.y) <= 1f, "$label must align with the title")
            }
        }
    }

    private fun ComposeUiTest.capture(name: String) {
        System.getenv("NAVIAMP_ARTWORK_CAPTURE")?.let { directory ->
            val target = java.io.File(directory, "radio-tabs-$name.png")
            target.parentFile.mkdirs()
            javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", target)
        }
    }

    private fun home(): NaviampHomeScreenUi {
        val station = GeneratedRadioStation("saved", "Evening station", GeneratedRadioKind.Artist,
            saved = true, artists = listOf(SavedArtist("artist", "Evening artist")))
        val mapped = HomeContent(genres = listOf(Genre("Jazz"))).toSharedHomeUi(coverArtUrl = { null }, savedGeneratedStations = listOf(station))
        val album = SharedHomeCollectionItemUi(SharedMediaItemUi(AlbumId("home-album").value, "Home album", "Artist"),
            SharedMediaItemKind.Album, action = SharedHomeCollectionItemAction.OpenAlbum)
        return NaviampHomeScreenUi(content = mapped.copy(collectionSections = listOf(SharedHomeCollectionSectionUi("home-albums", "Albums", listOf(album)))))
    }

    private fun internet() = NaviampInternetRadioScreenUi(listOf(
        NaviampInternetRadioStationUi(SharedMediaItemUi("stream", "Ambient One", ""), "https://example.test/ambient")))
    private fun internetActions() = NaviampInternetRadioActions({}, {}, {})
    private fun mediaActions() = NaviampMediaActions({}, {})
    private fun homeActions(
        refresh: () -> Unit = {},
        builder: (SharedMixBuilderUi) -> Unit = {},
        station: (SharedHomeStationUi) -> Unit = {},
        collection: (String) -> Unit = {},
        back: () -> Unit = {},
        generated: (GeneratedRadioActionRequest) -> Unit = {},
    ) = NaviampHomeActions(refresh, {}, {}, builder, station, {}, {}, collection, back, { _, _ -> }, onGeneratedRadioAction = generated)
}

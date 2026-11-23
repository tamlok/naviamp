package app.naviamp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import app.naviamp.domain.settings.AlbumCollectionLayout
import app.naviamp.domain.settings.AlbumSortOrder
import app.naviamp.domain.settings.QuickAccessKind
import app.naviamp.domain.settings.QuickAccessPin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
class NaviampQuickAccessUiTest {
    @Test fun albumToolbarTogglesPinning() = verifyToggle(QuickAccessKind.Album)
    @Test fun artistToolbarTogglesPinning() = verifyToggle(QuickAccessKind.Artist)
    @Test fun playlistToolbarTogglesPinning() = verifyToggle(QuickAccessKind.Playlist)

    @Test fun televisionAlbumToolbarTogglesWithDpad() = verifyToggle(QuickAccessKind.Album, television = true)
    @Test fun televisionArtistToolbarTogglesWithDpad() = verifyToggle(QuickAccessKind.Artist, television = true)
    @Test fun televisionPlaylistToolbarTogglesWithDpad() = verifyToggle(QuickAccessKind.Playlist, television = true)

    @Test fun pinAppearsAndDisappearsWithTheLoadedToolbar() {
        for (television in listOf(false, true)) for (kind in QuickAccessKind.entries) for (pinned in listOf(false, true)) {
            runDesktopComposeUiTest(if (television) 1000 else 420, 700) {
                val fixture = ToggleFixture(kind, initiallyPinned = pinned)
                val loaded = mutableStateOf(false)
                val status = mutableStateOf<String?>(null)
                setContent {
                    CompositionLocalProvider(LocalNaviampQuickAccess provides fixture.quickAccess()) {
                        fixture.Detail(television, loaded.value, status.value, populated = true)
                    }
                }
                val play = if (television) onNodeWithText("Play") else onNode(hasContentDescription(when (kind) {
                    QuickAccessKind.Album -> "Play album"
                    QuickAccessKind.Artist -> "Play artist catalog"
                    QuickAccessKind.Playlist -> "Play playlist"
                }) and hasTestTag("playlist-artwork-play").not())
                fun assertNoToolbar() {
                    play.assertDoesNotExist()
                    onNodeWithContentDescription("Pin to Quick Access").assertDoesNotExist()
                    onNodeWithContentDescription("Remove from Quick Access").assertDoesNotExist()
                }
                assertNoToolbar()
                runOnIdle { status.value = "Load failed" }
                onNodeWithText("Load failed").assertIsDisplayed()
                assertNoToolbar()
                runOnIdle { status.value = null; loaded.value = true }
                play.assertIsDisplayed()
                val toggle = onNodeWithContentDescription(if (pinned) "Remove from Quick Access" else "Pin to Quick Access")
                toggle.assertIsDisplayed()
                if (pinned) toggle.assertIsOn() else toggle.assertIsOff()
                runOnIdle { loaded.value = false }
                assertNoToolbar()
                runOnIdle { assertTrue(fixture.events.isEmpty()) }
            }
        }
    }

    @Test fun pinHasTheSameVisualSizeAsOrdinaryToolbarButtonsInBothStates() {
        for (kind in QuickAccessKind.entries) runDesktopComposeUiTest(1200, 700) {
            val fixture = ToggleFixture(kind, initiallyPinned = false)
            setContent {
                // Measure visual containers, not the framework's expanded accessibility hit targets.
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp,
                    LocalNaviampQuickAccess provides fixture.quickAccess()) {
                    fixture.Detail(television = false, loaded = true, status = null)
                }
            }
            val neighbor = onNodeWithContentDescription(when (kind) {
                QuickAccessKind.Album -> "Play album"
                QuickAccessKind.Artist -> "Play artist catalog"
                QuickAccessKind.Playlist -> "Delete playlist"
            })
            neighbor.assertWidthIsEqualTo(38.dp).assertHeightIsEqualTo(38.dp)
            val toggle = onNodeWithContentDescription("Pin to Quick Access")
            toggle.assertWidthIsEqualTo(38.dp).assertHeightIsEqualTo(38.dp).assertIsOff().performClick()
            onNodeWithContentDescription("Remove from Quick Access")
                .assertWidthIsEqualTo(38.dp).assertHeightIsEqualTo(38.dp).assertIsOn()
            neighbor.assertWidthIsEqualTo(38.dp).assertHeightIsEqualTo(38.dp)
        }
    }

    @Test fun wideToolbarsKeepTheToggleAfterAllOrdinaryTools() {
        QuickAccessKind.entries.forEach { verifyToggle(it, width = 1200) }
    }

    @Test fun narrowToolbarsKeepTheToggleVisibleOutsideOverflow() {
        QuickAccessKind.entries.forEach { verifyToggle(it, width = 240) }
    }

    @Test fun televisionRightTraversalReachesOffscreenToggleAndDownEntersContent() {
        for (width in listOf(1200, 640)) for (kind in QuickAccessKind.entries) {
            runDesktopComposeUiTest(width, 800) {
                val fixture = ToggleFixture(kind, initiallyPinned = false)
                setContent {
                    CompositionLocalProvider(LocalNaviampQuickAccess provides fixture.quickAccess()) {
                        fixture.Detail(television = true, loaded = true, status = null, populated = true)
                    }
                }
                onNodeWithText("Play").assertIsFocused()
                repeat(3) { onRoot().performKeyInput { pressKey(Key.DirectionRight) } }
                onNodeWithContentDescription("Pin to Quick Access").assertIsDisplayed().assertIsFocused().assertIsOff()
                    .performKeyInput { pressKey(Key.DirectionCenter) }
                onNodeWithContentDescription("Remove from Quick Access").assertIsOn().assertIsFocused()
                    .performKeyInput { pressKey(Key.DirectionDown) }
                if (kind == QuickAccessKind.Artist) {
                    onNode(isFocused() and hasAnyDescendant(hasText("First album"))).assertExists()
                } else onNodeWithText("First track").assertIsFocused()
                runOnIdle { assertEquals(listOf("pin" to fixture.pin), fixture.events) }
            }
        }
    }

    @Test fun standaloneDetailWithoutQuickAccessContextHasNoToggle() = runDesktopComposeUiTest(420, 700) {
        val fixture = ToggleFixture(QuickAccessKind.Album, initiallyPinned = false)
        setContent { fixture.Detail(television = false, loaded = true, status = null) }
        onNodeWithContentDescription("Pin to Quick Access").assertDoesNotExist()
        onNodeWithContentDescription("Remove from Quick Access").assertDoesNotExist()
        assertTrue(fixture.events.isEmpty())
    }

    private fun verifyToggle(
        kind: QuickAccessKind,
        television: Boolean = false,
        width: Int = if (television) 1000 else 420,
    ) = runDesktopComposeUiTest(width, 700) {
        val fixture = ToggleFixture(kind, initiallyPinned = false)
        setContent {
            CompositionLocalProvider(LocalNaviampQuickAccess provides fixture.quickAccess()) {
                Box(Modifier.fillMaxSize().testTag("detail-page")) { fixture.Detail(television, loaded = true, status = null) }
            }
        }
        val toggle = onNodeWithContentDescription("Pin to Quick Access")
        toggle.assertIsDisplayed().assertIsOff()
        val bounds = toggle.fetchSemanticsNode().boundsInRoot
        val title = onAllNodesWithText(fixture.item.title)[0].fetchSemanticsNode().boundsInRoot
        val page = onNodeWithTag("detail-page").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.top >= title.bottom, "Pinning belongs in the actions, not the title row")
        assertTrue(bounds.right <= page.right && bounds.left >= page.left, "Toggle must remain inside the viewport")
        assertTrue(bounds.left >= if (television) 176f else 96f, "Toggle must be alongside artwork")
        onAllNodes(hasClickAction()).fetchSemanticsNodes().filter {
            it.id != toggle.fetchSemanticsNode().id && it.boundsInRoot.center.y in bounds.top..bounds.bottom
        }.forEach { assertTrue(it.boundsInRoot.right <= bounds.left, "Toggle must follow all other toolbar controls") }
        toggle.activate(television)
        onNodeWithContentDescription("Remove from Quick Access").assertIsOn()
        runOnIdle { assertEquals(listOf(fixture.pin), fixture.pins.value) }
        onNodeWithContentDescription("Remove from Quick Access").activate(television)
        onNodeWithContentDescription("Pin to Quick Access").assertIsOff()
        runOnIdle {
            assertTrue(fixture.pins.value.isEmpty())
            assertEquals(listOf("pin" to fixture.pin, "remove" to fixture.pin), fixture.events)
            assertTrue(fixture.otherActions.isEmpty(), "Pinning must not play, navigate, or mutate media")
        }
        if (!television && width <= 420) {
            onAllNodesWithContentDescription("More actions")[0].performClick()
            onNodeWithText("Pin to Quick Access").assertDoesNotExist()
            onNodeWithText("Remove from Quick Access").assertDoesNotExist()
        }
    }

    private fun SemanticsNodeInteraction.activate(keyboard: Boolean) {
        if (keyboard) {
            performSemanticsAction(SemanticsActions.RequestFocus)
            performKeyInput { pressKey(Key.DirectionCenter) }
        } else performClick()
    }
}

private class ToggleFixture(private val kind: QuickAccessKind, initiallyPinned: Boolean) {
    val item = SharedMediaItemUi("media-id", "A very long ${kind.name} title that must not push the toolbar off screen ".repeat(4), "")
    val pin = QuickAccessPin("active-source", kind, item.id, item.title)
    val pins = mutableStateOf(if (initiallyPinned) listOf(pin) else emptyList())
    val events = mutableListOf<Pair<String, QuickAccessPin>>()
    val otherActions = mutableListOf<String>()
    private val quickAccessActions = NaviampQuickAccessActions(
        onPin = { events += "pin" to it; pins.value = pins.value + it },
        onRemove = { events += "remove" to it; pins.value = pins.value - it },
        onOpen = { otherActions += "open" },
    )
    private val albumActions = NaviampAlbumDetailActions(
        onBack = { otherActions += "back" },
        onAlbumAction = { otherActions += "album" },
        onTrackAction = { otherActions += "track" },
    )
    private val artistActions = NaviampArtistDetailActions(
        onBack = { otherActions += "back" },
        onArtistAction = { otherActions += "artist" },
        onAlbumAction = { otherActions += "album" },
        onPopularTrackAction = { otherActions += "popular-track" },
    )
    private val playlistActions = NaviampPlaylistDetailActions(
        onBack = { otherActions += "back" },
        onPlaylistAction = { otherActions += "playlist" },
        onUpdateStandardPlaylist = { _, _, _ -> otherActions += "edit-playlist" },
        onTrackAction = { otherActions += "track" },
    )
    private val playlistsActions = NaviampPlaylistsActions(
        onRefresh = { otherActions += "refresh" },
        onSortModeChanged = { otherActions += "sort" },
        smartPlaylist = NaviampSmartPlaylistActions(
            onSave = { error("Unexpected smart playlist save") },
            onUpdate = { _, _ -> error("Unexpected smart playlist update") },
            onSaveWithPassword = { _, _ -> error("Unexpected smart playlist save") },
            onUpdateWithPassword = { _, _, _ -> error("Unexpected smart playlist update") },
            onLoad = { error("Unexpected smart playlist load") },
            onLoadWithPassword = { _, _ -> error("Unexpected smart playlist load") },
        ),
    )

    fun quickAccess() = NaviampQuickAccessUi("active-source", pins.value, null, quickAccessActions)

    @Composable
    fun Detail(television: Boolean, loaded: Boolean, status: String?, populated: Boolean = false) {
        val colors = NaviampColors.Dark
        val topFocus = remember { FocusRequester() }
        val tracks = if (populated) listOf(SharedTrackRowUi("track-1", "First track", "Artist"),
            SharedTrackRowUi("track-2", "Second track", "Artist")) else emptyList()
        val albums = if (populated) listOf(SharedMediaItemUi("album-1", "First album", "Artist")) else emptyList()
        when (kind) {
            QuickAccessKind.Album -> {
                val screen = NaviampAlbumDetailScreenUi(
                    selectedAlbum = item,
                    detail = if (loaded) SharedAlbumDetailUi(item, tracks) else null,
                    status = status,
                )
                if (television) TelevisionAlbumDetail(screen, colors, albumActions, topFocus)
                else NaviampAlbumDetailContent(colors, screen, actions = albumActions)
            }
            QuickAccessKind.Artist -> {
                val screen = NaviampArtistDetailScreenUi(
                    selectedArtist = item,
                    detail = if (loaded) SharedArtistDetailUi(item, albums,
                        albumSections = if (populated) listOf(SharedAlbumSectionUi(
                            app.naviamp.domain.media.AlbumReleaseSection.Albums, albums,
                        )) else emptyList()) else null,
                    status = status,
                )
                if (television) TelevisionArtistDetail(screen, colors, artistActions, topNavigationFocusRequester = topFocus)
                else NaviampArtistDetailContent(
                    colors, screen,
                    albumCollectionLayout = AlbumCollectionLayout.List,
                    albumSortOrder = AlbumSortOrder.ReleaseYearAscending,
                    groupAlbumsByReleaseType = false,
                    actions = artistActions,
                )
            }
            QuickAccessKind.Playlist -> {
                val screen = NaviampPlaylistDetailScreenUi(
                    selectedPlaylist = item,
                    detail = if (loaded) SharedPlaylistDetailUi(item, tracks) else null,
                    status = status,
                )
                if (television) TelevisionPlaylistDetail(screen, colors, playlistActions, topFocus)
                else NaviampPlaylistDetailContent(colors, screen, playlistActions, playlistsActions, emptyList())
            }
        }
    }
}

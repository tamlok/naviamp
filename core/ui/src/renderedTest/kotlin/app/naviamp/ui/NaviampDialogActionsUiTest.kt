package app.naviamp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import app.naviamp.domain.settings.NaviampBuiltinTheme
import app.naviamp.domain.settings.NaviampForestTheme
import app.naviamp.domain.settings.NaviampThemeMode
import app.naviamp.domain.settings.PlaybackSettings
import app.naviamp.domain.settings.NaviampThemeSettings
import app.naviamp.domain.smartplaylist.SmartPlaylistDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NaviampDialogActionsUiTest {
    private val playlist = SharedMediaItemUi("source", "Source playlist", "2 tracks")
    private val destinations = List(8) { NaviampPlaylistChoiceUi("target-$it", "Destination ${it + 1}") }
    private val createLabel = "Create playlist and add these tracks"
    private val inlineSurface = object : NaviampOwnedDialogSurface {
        @Composable
        override fun Render(size: DpSize, properties: PopupProperties, onCloseRequest: () -> Unit,
            content: @Composable () -> Unit) = content()
    }

    @Test
    fun bulkActionsHaveReadableRenderedTextAcrossThemesAndDialogHosts() {
        for (pack in listOf(NaviampBuiltinTheme, NaviampForestTheme)) {
            for (mode in listOf(NaviampThemeMode.Light, NaviampThemeMode.Dark)) {
                for (owned in listOf(false, true)) runComposeUiTest {
                    setContent {
                        Host(NaviampThemeSettings(mode, pack.id), owned) { BulkFixture {} }
                    }
                    openBulkTools()
                    for (label in listOf("Copy playlist", "Copy deduplicated playlist", destinations.last().name, createLabel)) {
                        onNodeWithText(label).performScrollTo().assertIsDisplayed().assertIsEnabled()
                        assertReadableAction(label)
                    }
                    assertReadableAction("Close")
                    onNodeWithText("Close").performClick()
                    onNodeWithText("Playlist bulk tools").assertDoesNotExist()
                }
            }
        }
    }

    @Test
    fun shortDialogKeepsFooterVisibleAndScrollsToEveryDestinationAndCreateAction() = runComposeUiTest {
        val requests = mutableListOf<NaviampPlaylistDetailActionRequest>()
        setContent { Host(NaviampThemeSettings(), owned = true) { BulkFixture { requests += it } } }
        openBulkTools()
        onNodeWithText("Close").assertIsDisplayed()
        onNodeWithText(destinations.last().name).performScrollTo().assertIsDisplayed().performClick()
        runOnIdle {
            assertEquals(NaviampPlaylistDetailCommand.AddToPlaylist(destinations.last()), requests.single().command)
        }
        openBulkTools()
        onNodeWithText(createLabel).performScrollTo().assertIsDisplayed()
        onNodeWithText("Close").assertIsDisplayed()
        onNodeWithText(createLabel).performClick()
        runOnIdle {
            assertEquals(NaviampPlaylistDetailCommand.CreatePlaylistAndAdd("Source playlist Copy"), requests.last().command)
        }
    }

    @Test
    fun blankCopyNameDisablesNameActionsButLeavesExistingDestinationsUsable() = runComposeUiTest {
        val requests = mutableListOf<NaviampPlaylistDetailActionRequest>()
        setContent { Host(NaviampThemeSettings(), owned = true) { BulkFixture { requests += it } } }
        openBulkTools()
        onNode(hasSetTextAction()).performScrollTo().performTextReplacement("   ")
        for (label in listOf("Copy playlist", "Copy deduplicated playlist", createLabel)) {
            onNodeWithText(label).performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        }
        runOnIdle { assertTrue(requests.isEmpty()) }
        onNodeWithText(destinations.last().name).performScrollTo().assertIsEnabled().performClick()
        runOnIdle {
            assertEquals(NaviampPlaylistDetailCommand.AddToPlaylist(destinations.last()), requests.single().command)
        }
    }

    @Test
    fun renameAndDeleteDialogsUseReadableActionsAndPreserveCallbacks() {
        for (pack in listOf(NaviampBuiltinTheme, NaviampForestTheme)) {
            for (mode in listOf(NaviampThemeMode.Light, NaviampThemeMode.Dark)) runComposeUiTest {
                val renaming = mutableStateOf(true)
                var renamed = ""
                var deleted = false
                setContent {
                    Host(NaviampThemeSettings(mode, pack.id), owned = true) {
                        val colors = LocalNaviampTheme.current.colors
                        if (renaming.value) RenamePlaylistDialog(playlist, colors, {}, {
                            renamed = it
                            renaming.value = false
                        }) else DeletePlaylistDialog(playlist, colors, {}, { deleted = true })
                    }
                }
                assertReadableAction("Save")
                assertReadableAction("Cancel")
                onNodeWithText("Save").performClick()
                assertReadableAction("Delete")
                assertReadableAction("Cancel")
                onNodeWithText("Delete").performClick()
                runOnIdle {
                    assertEquals(playlist.title, renamed)
                    assertTrue(deleted)
                }
            }
        }
    }

    @Test
    fun radioCreationDialogInheritsActionTextAndCancelsWithoutSaving() = runComposeUiTest {
        val open = mutableStateOf(true)
        var saves = 0
        setContent {
            Host(NaviampThemeSettings(), owned = true) {
                if (open.value) NaviampRadioDjCreationDialog(
                    LocalNaviampTheme.current.colors, PlaybackSettings(), { saves++ }, { open.value = false },
                )
            }
        }
        val cancel = onNodeWithText("Cancel").performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        cancel.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(NaviampColors.Dark.actionText, layouts.single().layoutInput.style.color)
        cancel.performClick()
        runOnIdle {
            assertEquals(false, open.value)
            assertEquals(0, saves)
        }
    }

    private fun ComposeUiTest.openBulkTools() {
        onNodeWithContentDescription("Playlist bulk tools").assertIsDisplayed().performClick()
        onNodeWithText("Playlist bulk tools").assertIsDisplayed()
    }

    private fun ComposeUiTest.assertReadableAction(label: String) {
        val pixels = onNodeWithText(label).assertIsDisplayed().captureToImage().toPixelMap()
        val background = pixels[0, 0].luminance()
        var readablePixels = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val luminance = pixels[x, y].luminance()
            val contrast = (maxOf(luminance, background) + 0.05f) / (minOf(luminance, background) + 0.05f)
            if (contrast >= 4.5f) readablePixels++
        }
        assertTrue(readablePixels >= 4, "$label must render readable glyph pixels against its actual surface")
    }

    @Composable
    private fun Host(settings: NaviampThemeSettings, owned: Boolean, content: @Composable () -> Unit) {
        NaviampTheme(settings, systemDark = false) {
            CompositionLocalProvider(
                LocalNaviampOwnedPopupWindows provides owned,
                LocalNaviampOwnedDialogSurface provides inlineSurface,
                LocalNaviampWindowGroupActive provides true,
            ) {
                Box(Modifier.size(620.dp, 420.dp).background(LocalNaviampTheme.current.colors.background)) { content() }
            }
        }
    }

    @Composable
    private fun BulkFixture(onAction: (NaviampPlaylistDetailActionRequest) -> Unit) {
        NaviampPlaylistDetailContent(
            colors = LocalNaviampTheme.current.colors,
            screen = NaviampPlaylistDetailScreenUi(
                selectedPlaylist = playlist,
                detail = SharedPlaylistDetailUi(playlist, listOf(
                    SharedTrackRowUi("song", "Song", "Artist"),
                    SharedTrackRowUi("song", "Song", "Artist"),
                )),
            ),
            actions = NaviampPlaylistDetailActions({}, onAction, { _, _, _ -> }, {}),
            playlistsActions = NaviampPlaylistsActions(
                onRefresh = {}, onSortModeChanged = {},
                smartPlaylist = NaviampSmartPlaylistActions(
                    onSave = {}, onUpdate = { _, _ -> }, onSaveWithPassword = { _, _ -> },
                    onUpdateWithPassword = { _, _, _ -> },
                    onLoad = { SmartPlaylistDefinition(name = "Unused", rules = emptyList()) },
                    onLoadWithPassword = { _, _ -> SmartPlaylistDefinition(name = "Unused", rules = emptyList()) },
                ),
            ),
            playlistChoices = destinations,
        )
    }
}

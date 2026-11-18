package app.naviamp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import app.naviamp.domain.settings.AppBackgroundStyle
import app.naviamp.domain.settings.AuroraTone
import app.naviamp.domain.settings.InterfaceSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NaviampTelevisionBackgroundSettingsTest {
    @Test
    fun appearanceKeepsAllStylesInlineAndPreservesUnrelatedSettings() =
        runDesktopComposeUiTest(1920, 1080) {
            val initial = InterfaceSettings(
                appBackgroundStyle = AppBackgroundStyle.Aurora,
                auroraTone = AuroraTone.Light,
                auroraColorSteps = 3,
                auroraAngleDegrees = 45,
                albumBlurRadiusDp = 20,
                singleColorHex = "#406080",
                keepScreenAwake = true,
                squareAlbumArtwork = false,
            )
            val settings = mutableStateOf(initial)
            setContent { Appearance(settings.value) { settings.value = it } }
            fun control(tag: String) = onNode(
                hasTestTag("player-background-$tag") and
                    hasAnyAncestor(hasTestTag("player-background-settings")),
            )
            fun stylesRemainInline() {
                AppBackgroundStyle.entries.forEach { style ->
                    control("style:${style.name}").performScrollTo().assertIsDisplayed()
                }
                onNodeWithTag("player-background-settings").assertExists()
            }

            stylesRemainInline()
            control("style:AlbumBlur").performClick().assertIsSelected()
            control("tone:Light").assertDoesNotExist()
            control("hue").assertDoesNotExist()
            control("blur").performScrollTo().assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(48f) }
            runOnIdle {
                assertEquals(initial.copy(appBackgroundStyle = AppBackgroundStyle.AlbumBlur, albumBlurRadiusDp = 48), settings.value)
            }

            control("style:SingleColor").performScrollTo().performClick().assertIsSelected()
            stylesRemainInline()
            control("blur").assertDoesNotExist()
            control("color-preview").assertDoesNotExist()
            control("hue").assertDoesNotExist()
            control("saturation").assertDoesNotExist()
            control("brightness").assertDoesNotExist()
            onAllNodes(isSelected() and hasAnyAncestor(hasTestTag("player-background-color-palette")))
                .assertCountEquals(0)
            runOnIdle { assertEquals(initial.singleColorHex, settings.value.singleColorHex) }
            control("color:#315442").performScrollTo().performClick().assertIsSelected()
            control("color:#32253F").assertIsNotSelected()
            val selectedHex = "#315442"
            runOnIdle {
                assertEquals(initial.copy(
                    appBackgroundStyle = AppBackgroundStyle.SingleColor,
                    albumBlurRadiusDp = 48,
                    singleColorHex = selectedHex,
                ), settings.value)
            }

            control("style:Aurora").performScrollTo().performClick().assertIsSelected()
            stylesRemainInline()
            control("color-palette").assertDoesNotExist()
            control("color-preview").assertDoesNotExist()
            control("tone:DeepDark").performScrollTo().performClick().assertIsSelected()
            control("color-steps").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(5f) }
            control("angle").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(180f) }
            runOnIdle {
                assertEquals(initial.copy(
                    auroraTone = AuroraTone.DeepDark,
                    auroraColorSteps = 5,
                    auroraAngleDegrees = 180,
                    albumBlurRadiusDp = 48,
                    singleColorHex = selectedHex,
                ), settings.value)
            }
        }

    @Test
    fun inlineAppearanceSupportsRemoteSelectionSteppingClampingAndVerticalFocus() =
        runDesktopComposeUiTest(1920, 1080) {
            val settings = mutableStateOf(InterfaceSettings(
                appBackgroundStyle = AppBackgroundStyle.Aurora,
                auroraColorSteps = 4,
                auroraAngleDegrees = 170,
                albumBlurRadiusDp = 47,
            ))
            setContent { Appearance(settings.value) { settings.value = it } }
            fun control(tag: String) = onNode(
                hasTestTag("player-background-$tag") and
                    hasAnyAncestor(hasTestTag("player-background-settings")),
            )

            val count = control("color-steps").performScrollTo()
            count.performSemanticsAction(SemanticsActions.RequestFocus)
            count.performKeyInput { pressKey(Key.DirectionRight) }
            count.performKeyInput { pressKey(Key.DirectionRight) }
            runOnIdle { assertEquals(5, settings.value.auroraColorSteps) }
            count.performKeyInput { pressKey(Key.DirectionLeft) }
            runOnIdle { assertEquals(4, settings.value.auroraColorSteps) }
            count.performKeyInput { pressKey(Key.DirectionDown) }
            count.assertIsNotFocused()
            control("angle").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
            runOnIdle {
                assertEquals(4, settings.value.auroraColorSteps)
                assertEquals(180, settings.value.auroraAngleDegrees)
            }
            control("angle").performKeyInput { pressKey(Key.DirectionLeft) }
            runOnIdle { assertEquals(165, settings.value.auroraAngleDegrees) }
            control("angle").performKeyInput { pressKey(Key.DirectionUp) }
            count.assertIsFocused()
            runOnIdle { assertEquals(165, settings.value.auroraAngleDegrees) }

            val blurStyle = control("style:AlbumBlur").performScrollTo()
            blurStyle.performSemanticsAction(SemanticsActions.RequestFocus)
            blurStyle.performKeyInput { pressKey(Key.Enter) }
            blurStyle.assertIsSelected()
            val blur = control("blur").performScrollTo()
            blur.performSemanticsAction(SemanticsActions.RequestFocus)
            blur.performKeyInput { pressKey(Key.DirectionRight) }
            runOnIdle { assertEquals(48, settings.value.albumBlurRadiusDp) }
            blur.performKeyInput { pressKey(Key.DirectionLeft) }
            runOnIdle { assertEquals(46, settings.value.albumBlurRadiusDp) }
            blur.performSemanticsAction(SemanticsActions.SetProgress) { it(8f) }
            blur.performKeyInput { pressKey(Key.DirectionLeft) }
            runOnIdle { assertEquals(8, settings.value.albumBlurRadiusDp) }
        }

    @Test
    fun paletteFitsCompactAppearanceAndSeparatesFocusFromSelection() = runDesktopComposeUiTest(360, 740) {
        val settings = mutableStateOf(InterfaceSettings(
            appBackgroundStyle = AppBackgroundStyle.SingleColor,
            singleColorHex = "32253f",
        ))
        val themeColors = mutableStateOf(NaviampColors.Dark)
        setContent { Appearance(settings.value, themeColors.value) { settings.value = it } }
        val paletteTag = hasTestTag("player-background-color-palette")
        fun swatch(hex: String) = onNodeWithTag("player-background-color:$hex")
        for (colors in listOf(NaviampColors.Dark, NaviampColors.Light)) {
            runOnIdle {
                themeColors.value = colors
                settings.value = settings.value.copy(singleColorHex = "32253f")
            }
            onNode(paletteTag).performScrollTo().assertIsDisplayed()
            val bounds = onNode(paletteTag).fetchSemanticsNode().boundsInRoot
            val root = onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= root.left && bounds.right <= root.right)
            val cells = onAllNodes(hasAnyAncestor(paletteTag) and hasClickAction()).fetchSemanticsNodes()
            assertEquals(36, cells.size)
            assertEquals(listOf(6, 6, 6, 6, 6, 6), cells.groupBy { it.boundsInRoot.top }.values.map { it.size })
            cells.forEach { cell ->
                assertTrue(cell.boundsInRoot.left >= bounds.left && cell.boundsInRoot.right <= bounds.right)
            }
            swatch("#32253F").assertIsSelected()
            swatch("#141414").performSemanticsAction(SemanticsActions.RequestFocus)
            swatch("#141414").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
            swatch("#301A20").assertIsFocused().assertIsNotSelected()
            runOnIdle { assertEquals("32253f", settings.value.singleColorHex) }
            swatch("#301A20").performKeyInput { pressKey(Key.DirectionDown) }
            swatch("#482630").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
            swatch("#482630").assertIsSelected()
            swatch("#32253F").assertIsNotSelected()
            onAllNodes(isSelected() and hasAnyAncestor(paletteTag)).assertCountEquals(1)
            runOnIdle { assertEquals("#482630", settings.value.singleColorHex) }
            swatch("#946CB2").performClick().assertIsSelected()
            swatch("#482630").assertIsNotSelected()
            runOnIdle { assertEquals("#946CB2", settings.value.singleColorHex) }
        }
    }

    @Composable
    private fun Appearance(
        settings: InterfaceSettings,
        colors: NaviampColors = televisionReadingColors(NaviampColors.Dark),
        onChanged: (InterfaceSettings) -> Unit,
    ) {
        val firstFocusRequester = remember { FocusRequester() }
        Column(Modifier.fillMaxSize().focusRequester(firstFocusRequester).verticalScroll(rememberScrollState())) {
            NaviampThemesSettings(
                colors = colors,
                interfaceSettings = settings,
                onInterfaceSettingsChanged = onChanged,
            )
        }
    }
}

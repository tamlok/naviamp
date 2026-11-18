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
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
                onNodeWithText("Theme packs").assertExists()
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
            control("color-preview").performScrollTo().assertIsDisplayed()
            control("hue").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(180f) }
            control("saturation").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(50f) }
            control("brightness").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(60f) }
            var selectedHex = initial.singleColorHex
            runOnIdle {
                selectedHex = settings.value.singleColorHex
                assertNotEquals(initial.singleColorHex, selectedHex)
                val hsv = naviampColorToHsv(naviampColorFromHex(selectedHex)!!)
                assertTrue(abs(hsv[0] - 0.5f) < 0.01f)
                assertTrue(abs(hsv[1] - 0.5f) < 0.01f)
                assertTrue(abs(hsv[2] - 0.6f) < 0.01f)
                assertEquals(initial.copy(
                    appBackgroundStyle = AppBackgroundStyle.SingleColor,
                    albumBlurRadiusDp = 48,
                    singleColorHex = selectedHex,
                ), settings.value)
            }

            control("style:Aurora").performScrollTo().performClick().assertIsSelected()
            stylesRemainInline()
            control("hue").assertDoesNotExist()
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

    @Composable
    private fun Appearance(settings: InterfaceSettings, onChanged: (InterfaceSettings) -> Unit) {
        val firstFocusRequester = remember { FocusRequester() }
        Column(Modifier.fillMaxSize().focusRequester(firstFocusRequester).verticalScroll(rememberScrollState())) {
            NaviampThemesSettings(
                colors = televisionReadingColors(NaviampColors.Dark),
                interfaceSettings = settings,
                onInterfaceSettingsChanged = onChanged,
            )
        }
    }
}

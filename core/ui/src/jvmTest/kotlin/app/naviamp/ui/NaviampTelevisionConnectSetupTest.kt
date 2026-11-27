package app.naviamp.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.*
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class NaviampTelevisionConnectSetupTest {
    @Test fun firstRunShowsCodeWithoutAButtonAndStopsOnLeave() = runComposeUiTest {
        val calls = mutableListOf<String>()
        val shown = mutableStateOf(true)
        setContent {
            if (shown.value) TelevisionFirstRunConnectSetup(target().copy(pairingCode = "123456", codeVisible = true),
                actions(calls), NaviampColors.Dark)
        }
        onNodeWithText("123 456").assertIsDisplayed()
        assertEquals(listOf("start"), calls)
        runOnIdle { shown.value = false }
        waitForIdle()
        assertEquals(listOf("start", "stop"), calls)
    }

    @Test fun televisionDevicesShowsTheCodeOnRequestAndStopsOnLeave() = runComposeUiTest {
        val calls = mutableListOf<String>()
        val shown = mutableStateOf(true)
        setContent {
            if (shown.value) TelevisionControllersSettings(target().copy(pairingCode = "654321"), actions(calls),
                NaviampColors.Dark, FocusRequester())
        }
        assertEquals(emptyList(), calls)
        onNodeWithText("Let another device connect").performClick()
        waitForIdle()
        assertEquals(listOf("start"), calls)
        onNodeWithText("654 321").assertIsDisplayed()
        runOnIdle { shown.value = false }
        waitForIdle()
        assertEquals(listOf("start", "stop"), calls, "Leaving the page ends the offer")
    }

    @Test fun setupIntroPutsTheDirectOptionBelowTheCode() = runComposeUiTest {
        setContent {
            androidx.compose.foundation.layout.Column {
                TelevisionSetupIntro(addingAccount = false, connect = target().copy(pairingCode = "123456"),
                    actions = actions(mutableListOf()), colors = NaviampColors.Dark)
            }
        }
        val phoneTop = onNodeWithText("Set up from a phone or computer").fetchSemanticsNode().boundsInRoot.top
        val directTop = onNodeWithText("Or connect to your server", substring = true).fetchSemanticsNode().boundsInRoot.top
        kotlin.test.assertTrue(directTop > phoneTop, "The 'Or …' option follows the code box")
    }

    @Test fun theCodeBoxTakesInitialFocusSoTheTvShowsTheCode() = runComposeUiTest {
        setContent {
            androidx.compose.foundation.layout.Column {
                TelevisionSetupIntro(addingAccount = false, connect = target().copy(pairingCode = "123456"),
                    actions = actions(mutableListOf()), colors = NaviampColors.Dark)
                androidx.compose.material3.Button(onClick = {}) { androidx.compose.material3.Text("Navidrome") }
            }
        }
        waitForIdle()
        onNodeWithTag("tv-connect-code-box").assertIsFocused()
    }

    @Test fun addingAnAccountHasNoOrLine() = runComposeUiTest {
        setContent {
            TelevisionSetupIntro(addingAccount = true, connect = target(), actions = actions(mutableListOf()),
                colors = NaviampColors.Dark)
        }
        onNodeWithText("Or connect to your server", substring = true).assertDoesNotExist()
        onNodeWithText("Set up from a phone or computer").assertDoesNotExist()
    }

    @Test fun televisionCodeEntryOwnsDiscoveryUntilLeavingThePage() = runComposeUiTest {
        val calls = mutableListOf<String>()
        val shown = mutableStateOf(true)
        setContent {
            if (shown.value) TelevisionControllersSettings(target().copy(role = NaviampConnectUiRole.Controller),
                actions(calls), NaviampColors.Dark, FocusRequester())
        }
        onNodeWithText("Connect a device").performClick()
        onNodeWithTag("connect-code-entry").performScrollTo().performTextInput("123456")
        assertEquals(listOf("visible:true", "code:123456"), calls)
        runOnIdle { shown.value = false }
        waitForIdle()
        assertEquals("visible:false", calls.last())
    }

    @Test fun televisionCodeEntryKeepsOneActionableRecoveryPanel() = runDesktopComposeUiTest(1280, 900) {
        val calls = mutableListOf<String>()
        setContent {
            TelevisionControllersSettings(target().copy(role = NaviampConnectUiRole.Controller,
                recovery = NaviampConnectRecoveryUi(NaviampConnectRecoveryProblem.DiscoveryUnavailable)),
                actions(calls).copy(onRetryConnection = { calls += "retry" }), NaviampColors.Dark, FocusRequester())
        }
        onNodeWithTag("connect-recovery-retry").assertIsDisplayed()
        onNodeWithText("Connect a device").performClick()
        onNodeWithTag("connect-code-entry").performScrollTo().assertIsDisplayed()
        onAllNodesWithTag("connect-recovery-retry").assertCountEquals(1)
        onNodeWithTag("connect-recovery-retry").performClick()
        assertEquals("retry", calls.last())
    }

    @Test fun firstRunInstructionsNameNavoAndTheDevicesNavigationPath() = runComposeUiTest {
        setContent { TelevisionFirstRunConnectSetup(target(), actions(mutableListOf()), NaviampColors.Dark) }
        onNodeWithText("Navo → Settings → Devices → Connect a device", substring = true).assertIsDisplayed()
    }

    private fun target() = NaviampConnectSettingsUi(available = true, role = NaviampConnectUiRole.Target,
        localDeviceName = "Living Room TV")

    private fun actions(calls: MutableList<String>) = NaviampConnectSettingsActions(
        onStartPairingMode = { calls += "start" }, onStopPairingMode = { calls += "stop" },
        onTargetSelected = {}, onTrustedDeviceSelected = {},
        onPlaybackDeviceSelected = {}, onLocalDeviceNameChanged = {}, onTrustedDeviceAliasChanged = { _, _ -> },
        onForgetTrustedDevice = {}, onPairingCodeChanged = { calls += "code:$it" }, onSubmitPairingCode = {},
        onRemotePrevious = {}, onRemotePlayPause = {}, onRemoteNext = {}, onStopControlling = {},
        onRemoteHandoffQueue = {}, onReceiveRemoteQueue = {}, onProvisionTarget = {},
        onDismissSourceMismatchRecovery = {},
        remoteNowPlayingActions = NaviampNowPlayingActions({}, {}, {}, {}, {}, {}, {}),
        onConnectScreenVisibilityChanged = { calls += "visible:$it" },
    )
}

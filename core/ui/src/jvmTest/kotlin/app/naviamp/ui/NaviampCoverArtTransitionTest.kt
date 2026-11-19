package app.naviamp.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.naviamp.domain.settings.InterfaceSettings
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NaviampCoverArtTransitionTest {
    @Test
    fun failedInformationArtworkFallsBackToProviderArtwork() = runComposeUiTest {
        val bytes = requireNotNull(
            jvmGeneratedCoverArtBytes(
                "naviamp-radio-tile://cover?label=F&from=CC2244&to=551122",
            ),
        )
        val requestedUrls = mutableListOf<String>()
        resetNaviampCoverArtCache()
        setJvmPlatformCoverArtByteLoader { url ->
            requestedUrls += url
            if (url == "test://provider-cover") bytes else error("external artwork unavailable")
        }

        try {
            setContent {
                NaviampCoverArt(
                    url = "https://external.test/missing.webp",
                    colors = NaviampColors.Dark,
                    size = 128.dp,
                    cornerRadius = 8.dp,
                    fallbackUrl = "test://provider-cover",
                    mediaKind = SharedMediaItemKind.Album,
                )
            }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag("cover-art-image", useUnmergedTree = true).fetchSemanticsNodes().size == 1
            }
            assertEquals(
                listOf("https://external.test/missing.webp", "test://provider-cover"),
                requestedUrls,
            )
            onNodeWithTag("cover-art-image", useUnmergedTree = true).assertExists()
        } finally {
            resetJvmPlatformCoverArtByteLoader()
            resetNaviampCoverArtCache()
        }
    }

    @Test
    fun failedReplacementArtworkDoesNotLeaveThePreviousImageVisible() = runComposeUiTest {
        val artworkUrl = mutableStateOf<String?>("test://working")
        val bytes = requireNotNull(
            jvmGeneratedCoverArtBytes(
                "naviamp-radio-tile://cover?label=B&from=0044CC&to=001133",
            ),
        )
        resetNaviampCoverArtCache()
        setJvmPlatformCoverArtByteLoader { url ->
            if (url == "test://working") bytes else error("missing artwork")
        }

        try {
            setContent {
                NaviampCoverArt(artworkUrl.value, NaviampColors.Dark, 128.dp, 8.dp, mediaKind = SharedMediaItemKind.Album)
            }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag("cover-art-image", useUnmergedTree = true).fetchSemanticsNodes().size == 1
            }
            onNodeWithTag("cover-art-image", useUnmergedTree = true).assertExists()

            runOnIdle { artworkUrl.value = "test://missing" }

            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag("cover-art-image", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
            }
            onNodeWithTag("cover-art-image", useUnmergedTree = true).assertDoesNotExist()
        } finally {
            resetJvmPlatformCoverArtByteLoader()
            resetNaviampCoverArtCache()
        }
    }

    @Test
    fun transientMissingArtworkDoesNotResetPlayerColorsToFallback() = runComposeUiTest {
        val artworkUrl = mutableStateOf<String?>("test://blue-current-media")
        val fallback = NaviampPlayerColors.fallback(NaviampColors.Dark)
        var observed = fallback
        val bytes = requireNotNull(
            jvmGeneratedCoverArtBytes(
                "naviamp-radio-tile://cover?label=B&from=0044CC&to=001133",
            ),
        )
        resetNaviampCoverArtCache()
        setJvmPlatformCoverArtByteLoader { bytes }

        try {
            setContent {
                observed = rememberNaviampCoverArtPlayerColors(artworkUrl.value, NaviampColors.Dark)
            }
            waitUntil(timeoutMillis = 5_000) { observed != fallback }
            val loadedColors = observed
            assertNotEquals(fallback, loadedColors)

            mainClock.autoAdvance = false
            runOnIdle { artworkUrl.value = null }
            mainClock.advanceTimeBy(500)

            runOnIdle { assertEquals(loadedColors, observed) }
        } finally {
            resetJvmPlatformCoverArtByteLoader()
            resetNaviampCoverArtCache()
        }
    }

    @Test
    fun playerBackgroundRetainsPendingArtworkAndTransitionAcrossRemounts() = runComposeUiTest {
        val redUrl = "test://retained-player-red"
        val blueUrl = "test://retained-player-blue"
        val artworkUrl = mutableStateOf(redUrl)
        val expanded = mutableStateOf(false)
        val settings = InterfaceSettings(auroraColorSteps = 5)
        val blueRequested = CompletableDeferred<Unit>()
        val blueReady = CompletableDeferred<ByteArray>()
        val redBytes = requireNotNull(jvmGeneratedCoverArtBytes("naviamp-radio-tile://cover?label=R&from=CC2222&to=CC2222"))
        val blueBytes = requireNotNull(jvmGeneratedCoverArtBytes("naviamp-radio-tile://cover?label=B&from=2222CC&to=2222CC"))
        var target = NaviampPlayerColors.fallback(NaviampColors.Dark)
        resetNaviampCoverArtCache()
        setJvmPlatformCoverArtByteLoader { url ->
            when (url) {
                redUrl -> redBytes
                blueUrl -> {
                    blueRequested.complete(Unit)
                    blueReady.await()
                }
                else -> error("Unexpected artwork: $url")
            }
        }
        try {
            setContent {
                val background = rememberNaviampPlayerBackground(settings, artworkUrl.value, NaviampColors.Dark)
                SideEffect { target = background.background.targetPlayerColors }
                key(expanded.value) {
                    NaviampPlayerBackground(background, NaviampColors.Dark,
                        Modifier.size(128.dp).testTag("retained-player-background"))
                }
            }
            fun pixel() = onNodeWithTag("retained-player-background").captureToImage().toPixelMap()[2, 2]
            waitUntil(timeoutMillis = 5_000) { target.backgroundStart.red > target.backgroundStart.blue * 2 }
            mainClock.advanceTimeBy(500)
            val red = pixel()
            mainClock.autoAdvance = false
            runOnIdle { artworkUrl.value = blueUrl }
            mainClock.advanceTimeByFrame()
            waitUntil(timeoutMillis = 5_000) { blueRequested.isCompleted }
            runOnIdle { expanded.value = true }
            mainClock.advanceTimeBy(64)
            assertEquals(red, pixel(), "Expanding while replacement artwork loads must retain the current palette")

            blueReady.complete(blueBytes)
            waitUntil(timeoutMillis = 5_000) {
                mainClock.advanceTimeByFrame()
                target.backgroundStart.blue > target.backgroundStart.red * 2
            }
            mainClock.advanceTimeBy(128)
            val transitioning = pixel()
            assertNotEquals(red, transitioning, "New artwork must still animate")
            runOnIdle { expanded.value = false }
            mainClock.advanceTimeByFrame()
            val collapsed = pixel()
            mainClock.advanceTimeBy(256)
            val completed = pixel()
            mainClock.advanceTimeBy(500)
            val blue = pixel()
            assertTrue(blue.blue > blue.red * 2)
            assertNotEquals(blue, transitioning)
            assertNotEquals(blue, collapsed, "Collapsing must not snap an in-flight transition to its target")
            assertEquals(blue, completed, "Remounting must not restart the transition's duration")
        } finally {
            blueReady.cancel()
            resetJvmPlatformCoverArtByteLoader()
            resetNaviampCoverArtCache()
        }
    }
}

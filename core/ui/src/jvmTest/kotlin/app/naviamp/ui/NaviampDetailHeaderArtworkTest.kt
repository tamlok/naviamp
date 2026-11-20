package app.naviamp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.onAllNodesWithTag
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFalse
import app.naviamp.domain.settings.AlbumCollectionLayout
import app.naviamp.domain.settings.AlbumSortOrder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalTestApi::class)
class NaviampDetailHeaderArtworkTest {
    @Test
    fun failedPrimaryUsesFallbackButSuccessfulPrimaryDoesNotFetchIt() = runTest {
        val red = coverBytes("FF2200", "CC1155")
        val requested = mutableListOf<String>()
        resetNaviampCoverArtCache()
        setJvmPlatformCoverArtByteLoader { url ->
            synchronized(requested) { requested += url }
            if (url == "test://broken") byteArrayOf() else red
        }
        try {
            val recovered = assertNotNull(loadNaviampDetailHeaderPalette(listOf("test://broken"), "test://fallback"))
            assertEquals(listOf("test://broken", "test://fallback"), requested)
            requested.clear()
            assertEquals(recovered, loadNaviampDetailHeaderPalette(listOf("test://primary"), "test://unused"))
            assertEquals(listOf("test://primary"), requested)
        } finally {
            resetJvmPlatformCoverArtByteLoader()
            resetNaviampCoverArtCache()
        }
    }

    @Test
    fun collageIncludesMultipleCoversAndReusesCachedSamples() = runTest {
        val images = mapOf("test://red" to coverBytes("FF2200", "CC1155"), "test://blue" to coverBytes("0044FF", "11CCDD"))
        val loads = ConcurrentHashMap<String, Int>()
        resetNaviampCoverArtCache()
        setJvmPlatformCoverArtByteLoader { url -> loads.merge(url, 1, Int::plus); images[url] ?: byteArrayOf() }
        try {
            val combined = assertNotNull(loadNaviampDetailHeaderPalette(images.keys.toList()))
            assertNotEquals(combined, loadNaviampDetailHeaderPalette(listOf("test://red")))
            assertNotEquals(combined, loadNaviampDetailHeaderPalette(listOf("test://blue")))
            assertEquals(combined, loadNaviampDetailHeaderPalette(images.keys.toList()))
            assertEquals(mapOf("test://red" to 1, "test://blue" to 1), loads.toMap())
            assertEquals(combined, loadNaviampDetailHeaderPalette(images.keys.toList() + "test://missing"))
        } finally {
            resetJvmPlatformCoverArtByteLoader()
            resetNaviampCoverArtCache()
        }
    }

    @Test
    fun missingAndUndecodableArtworkHaveNoSyntheticPalette() = runTest {
        resetNaviampCoverArtCache()
        setJvmPlatformCoverArtByteLoader { byteArrayOf(1, 2, 3) }
        try {
            assertNull(loadNaviampDetailHeaderPalette(emptyList()))
            assertNull(loadNaviampDetailHeaderPalette(listOf("test://broken")))
            assertNull(loadNaviampDetailHeaderPalette(listOf("test://broken"), "test://also-broken"))
        } finally {
            resetJvmPlatformCoverArtByteLoader()
            resetNaviampCoverArtCache()
        }
    }

    @Test
    fun switchingDetailsClearsOldPaletteWhileReplacementLoadsOrHasNoArtwork() = runDesktopComposeUiTest(400, 200) {
        val red = coverBytes("FF2200", "CC1155")
        val blue = coverBytes("0044FF", "11CCDD")
        val releaseBlue = CompletableDeferred<Unit>()
        val urls = mutableStateOf(listOf("test://red"))
        val colors = NaviampColors.Light
        resetNaviampCoverArtCache()
        setJvmPlatformCoverArtByteLoader { url ->
            if (url == "test://blue") { releaseBlue.await(); blue } else red
        }
        try {
            setContent {
                Box(Modifier.testTag("header")) {
                    NaviampDetailHeader(colors, rememberNaviampDetailHeaderColors(urls.value.firstOrNull(), urls.value, null, colors)) {
                        Box(Modifier.height(100.dp))
                    }
                }
            }
            fun pixel() = onNodeWithTag("header").captureToImage().toPixelMap()[200, 50]
            waitUntil(timeoutMillis = 5_000) { pixel() != colors.background }
            val first = pixel()
            runOnIdle { urls.value = listOf("test://blue") }
            assertEquals(colors.background, pixel(), "Previous detail artwork must not color the new header")
            releaseBlue.complete(Unit)
            waitUntil(timeoutMillis = 5_000) { pixel() != colors.background }
            assertNotEquals(first, pixel())
            runOnIdle { urls.value = emptyList() }
            assertEquals(colors.background, pixel(), "An artwork-free detail must not inherit the previous palette")
        } finally {
            releaseBlue.complete(Unit)
            resetJvmPlatformCoverArtByteLoader()
            resetNaviampCoverArtCache()
        }
    }

    @Test
    fun cachedThumbnailStillSuppliesItsPaletteAfterOtherPalettesAreEvicted() = runDesktopComposeUiTest(160, 160) {
        val bytes = coverBytes("FF2200", "CC1155")
        val shown = mutableStateOf("test://primary")
        val loads = ConcurrentHashMap<String, Int>()
        resetNaviampCoverArtCache()
        setJvmPlatformCoverArtByteLoader { url -> loads.merge(url, 1, Int::plus); bytes }
        try {
            setContent {
                key(shown.value) {
                    NaviampCoverArt(shown.value, NaviampColors.Light, 128.dp, 4.dp,
                        decodeSize = with(LocalDensity.current) { 128.toDp() }, mediaKind = SharedMediaItemKind.Album)
                }
            }
            fun awaitImage() = waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag("cover-art-image", useUnmergedTree = true).fetchSemanticsNodes().size == 1
            }
            awaitImage()
            val expected = runBlocking { assertNotNull(loadNaviampDetailHeaderPalette(listOf("test://primary"))) }
            // Keep the primary bitmap hot in the LRU while its untouched palette ages out.
            repeat(241) { index ->
                runOnIdle { shown.value = "test://other-$index" }
                awaitImage()
                runOnIdle { shown.value = "test://primary" }
                awaitImage()
            }
            val recovered = runBlocking { loadNaviampDetailHeaderPalette(listOf("test://primary"), "test://fallback") }
            assertEquals(expected, recovered)
            assertEquals(1, loads["test://primary"], "A healthy cached bitmap must not be fetched again")
            assertFalse(loads.containsKey("test://fallback"), "A healthy primary must not be replaced by fallback artwork")
        } finally {
            resetJvmPlatformCoverArtByteLoader()
            resetNaviampCoverArtCache()
        }
    }

    @Test
    fun transparentPrimaryWithNoSamplesUsesFallback() = runTest {
        val transparent = java.io.ByteArrayOutputStream().also {
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", it)
        }.toByteArray()
        val blue = coverBytes("0044FF", "11CCDD")
        resetNaviampCoverArtCache()
        setJvmPlatformCoverArtByteLoader { if (it == "test://transparent") transparent else blue }
        try {
            val expected = assertNotNull(loadNaviampDetailHeaderPalette(listOf("test://blue")))
            assertEquals(expected, loadNaviampDetailHeaderPalette(listOf("test://transparent"), "test://blue"))
        } finally {
            resetJvmPlatformCoverArtByteLoader()
            resetNaviampCoverArtCache()
        }
    }

    @Test
    fun loadingToHeroArtworkAndLateFallbackKeepResolvedTintWhileReplacementLoads() = runDesktopComposeUiTest(800, 600) {
        val colors = NaviampColors.Light
        val bytes = coverBytes("0044FF", "11CCDD")
        val heroBytes = coverBytes("FF2200", "CC1155")
        val page = mutableStateOf(-1)
        val loaded = mutableStateOf(false)
        val fallback = mutableStateOf<String?>(null)
        val gate = AtomicReference<CompletableDeferred<Unit>?>(null)
        val item = SharedMediaItemUi("media", "Title", "Artist", coverArtUrl = "test://cover?size=512")
        resetNaviampCoverArtCache()
        setJvmPlatformCoverArtByteLoader { url -> gate.get()?.await(); if (url.endsWith("size=1200")) heroBytes else bytes }
        try {
            setContent {
                Box(Modifier.testTag("detail")) {
                    key(page.value) {
                        val loadedItem = item.copy(coverArtUrl = "test://cover?size=1200", fallbackCoverArtUrl = fallback.value)
                        when (page.value) {
                            0 -> NaviampAlbumDetailContent(colors,
                                NaviampAlbumDetailScreenUi(selectedAlbum = item,
                                    detail = if (loaded.value) SharedAlbumDetailUi(loadedItem, emptyList()) else null),
                                actions = NaviampAlbumDetailActions({}, {}, {}))
                            1 -> NaviampArtistDetailContent(colors,
                                NaviampArtistDetailScreenUi(selectedArtist = item,
                                    detail = if (loaded.value) SharedArtistDetailUi(loadedItem, emptyList()) else null),
                                albumCollectionLayout = AlbumCollectionLayout.List,
                                albumSortOrder = AlbumSortOrder.ReleaseYearAscending, groupAlbumsByReleaseType = false,
                                actions = NaviampArtistDetailActions({}, {}, {}, {}))
                            2 -> NaviampPlaylistDetailContent(colors,
                                NaviampPlaylistDetailScreenUi(selectedPlaylist = item,
                                    detail = if (loaded.value) SharedPlaylistDetailUi(loadedItem, emptyList()) else null),
                                actions = NaviampPlaylistDetailActions({}, {}, { _, _, _ -> }, {}),
                                playlistsActions = NaviampPlaylistsActions({}, {}, NaviampSmartPlaylistActions(
                                    onSave = {}, onUpdate = { _, _ -> }, onSaveWithPassword = { _, _ -> },
                                    onUpdateWithPassword = { _, _, _ -> }, onLoad = { error("Not used") },
                                    onLoadWithPassword = { _, _ -> error("Not used") })),
                                playlistChoices = emptyList())
                        }
                    }
                }
            }
            fun pixel() = onNodeWithTag("detail").captureToImage().toPixelMap()[750, 10]
            for (kind in 0..2) {
                runOnIdle { loaded.value = false; fallback.value = null; page.value = kind }
                waitUntil(timeoutMillis = 5_000) { pixel() != colors.background }
                gate.set(CompletableDeferred())
                resetNaviampCoverArtCache()
                runOnIdle { loaded.value = true }
                assertNotEquals(colors.background, pixel(), "Loading-to-loaded transition lost the palette on detail $kind")
                val beforeFallback = pixel()
                runOnIdle { fallback.value = "test://late-fallback" }
                assertEquals(beforeFallback, pixel(), "Late fallback metadata cleared the successful primary palette on detail $kind")
                gate.getAndSet(null)?.complete(Unit)
                waitUntil(timeoutMillis = 5_000) { pixel() != beforeFallback && pixel() != colors.background }
            }
        } finally {
            gate.getAndSet(null)?.complete(Unit)
            runOnIdle { page.value = -1 }
            resetJvmPlatformCoverArtByteLoader()
            resetNaviampCoverArtCache()
        }
    }

    private fun coverBytes(from: String, to: String): ByteArray = assertNotNull(
        jvmGeneratedCoverArtBytes("naviamp-radio-tile://cover?label=A&from=$from&to=$to"),
    )
}

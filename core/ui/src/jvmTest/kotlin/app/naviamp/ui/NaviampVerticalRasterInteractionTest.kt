package app.naviamp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class NaviampVerticalRasterInteractionTest {
    @Test fun verticalFallbackResumesTheOriginalClockAndMovesCachedPixels() = runComposeUiTest {
        mainClock.autoAdvance = false
        val clock = kotlin.time.TestTimeSource()
        val bitmap = androidx.compose.ui.graphics.ImageBitmap(20, 100)
        androidx.compose.ui.graphics.Canvas(bitmap).drawRect(Rect(0f, 30f, 20f, 40f),
            androidx.compose.ui.graphics.Paint().apply { color = Color.White })
        val layer = NaviampRasterLayer(bitmap,
            translation = NaviampLayerMotion(listOf(0f, -40f), listOf(0L, 4000L)),
            translationAxis = NaviampRasterTranslationAxis.Vertical, translationStarted = clock.markNow())
        clock += kotlin.time.Duration.parse("500ms")
        setContent {
            Box(Modifier.size(20.dp, 50.dp).background(Color.Black).testTag("vertical-fallback")) {
                NaviampAnimatedRaster(listOf(layer), Modifier.size(20.dp, 50.dp))
            }
        }
        mainClock.advanceTimeBy(100)
        assertEquals(Color.White, onNodeWithTag("vertical-fallback").captureToImage().toPixelMap()[10, 27])
        mainClock.advanceTimeBy(1000)
        assertEquals(Color.White, onNodeWithTag("vertical-fallback").captureToImage().toPixelMap()[10, 17])
        assertEquals(Color.Black, onNodeWithTag("vertical-fallback").captureToImage().toPixelMap()[10, 27])
    }

    @Test fun replacementTilesShareTheOriginalVerticalTimelineThroughRebindingAndCompletion() {
        val clock = kotlin.time.TestTimeSource()
        val started = clock.markNow()
        val motion = NaviampLayerMotion(listOf(0f, -300f), listOf(0L, 3000L))
        val bitmap = androidx.compose.ui.graphics.ImageBitmap(20, 100)
        val retained = NaviampRasterLayer(bitmap, translation = motion,
            translationAxis = NaviampRasterTranslationAxis.Vertical, translationStarted = started)
        clock += kotlin.time.Duration.parse("1500ms")
        val incoming = retained.copy(origin = Offset(0f, 200f))
        val first = retained.forPresentation()
        val second = incoming.forPresentation()
        assertEquals(-150f, requireNotNull(first.translation).valueAt(0L))
        assertEquals(first.translation, second.translation)
        assertEquals(200f, second.origin.y - first.origin.y)
        assertNull(second.translationStarted)
        clock += kotlin.time.Duration.parse("2s")
        val finished = incoming.forPresentation()
        assertNull(finished.translation)
        assertEquals(-100f, finished.origin.y)
    }
}

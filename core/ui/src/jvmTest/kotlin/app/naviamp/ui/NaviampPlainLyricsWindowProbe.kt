package app.naviamp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.naviamp.domain.playback.PlaybackProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skiko.SkikoRenderDelegate
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.time.TimeSource

/** Opt-in visible production-compositor probe; no provider, audio device or user profile is used. */
fun main() {
    configureNaviampDesktopRasterLayers()
    application {
        val state = rememberWindowState(width = 1000.dp, height = 740.dp)
        var phase by remember { mutableStateOf("static") }
        var fontSize by remember { mutableIntStateOf(20) }
        val progress = remember { MutableStateFlow(PlaybackProgress(30.0, 240.0)) }
        val waveform = remember { mutableFloatStateOf(.2f) }
        val lines = remember { List(80) { NaviampLyricLineUi(null, "A steady line of lyrics ${it + 1} across the song") } }
        val amplitudes = remember { List(256) { ((it * 17) % 101) / 100f } }
        val siblings = remember { AtomicLong() }
        var lyricBounds = Rect.Zero
        var siblingBounds = Rect.Zero
        var presenterName: String? = null
        val movingLyrics = phase in setOf("lyrics", "combined", "tile-boundary", "hidden", "restored")
        val marquee = phase in setOf("marquee", "combined", "hidden", "restored")
        val smoothWaveform = phase in setOf("waveform", "combined", "hidden", "restored")
        LaunchedEffect(phase) {
            val duration = if (phase == "tile-boundary") 20.0 else 240.0
            val start = if (phase == "tile-boundary") 0.0 else 30.0
            val generation = progress.value.seekGeneration + 1
            progress.value = PlaybackProgress(start, duration, seekGeneration = generation)
            val clock = TimeSource.Monotonic.markNow()
            while (movingLyrics || smoothWaveform) {
                delay(1000)
                val elapsed = clock.elapsedNow().inWholeMilliseconds / 1000.0
                if (movingLyrics) progress.value = PlaybackProgress((start + elapsed).coerceAtMost(duration), duration, seekGeneration = generation)
                if (smoothWaveform) waveform.floatValue = .2f + elapsed.toFloat() / 300f
            }
        }
        Window(onCloseRequest = ::exitApplication, title = "Naviamp plain lyrics motion probe", state = state, alwaysOnTop = true) {
            NaviampDesktopRasterHost(window, state) {
                val presenter = LocalNaviampRasterPresenter.current
                SideEffect { presenterName = presenter?.javaClass?.simpleName }
                androidx.compose.material3.MaterialTheme {
                    Row(Modifier.fillMaxSize().background(NaviampColors.Dark.background).padding(24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            repeat(3) { index ->
                                BouncingTitleText("Long scrolling metadata $index across the player viewport",
                                    color = NaviampColors.Dark.primaryText, fontSize = 16,
                                    marqueeEnabled = marquee, modifier = Modifier.fillMaxWidth())
                            }
                            WaveformScrubber(amplitudes, .2f, drawValue = { waveform.floatValue }, enabled = true,
                                smoothProgress = smoothWaveform, durationSeconds = 300.0, colors = NaviampColors.Dark,
                                onValueChange = {}, onValueChangeFinished = {}, modifier = Modifier.fillMaxWidth().height(32.dp))
                        }
                        NaviampCompactLyricsCard(
                            NowPlayingUi(id = "plain-probe", title = "Probe", subtitle = "Probe", stateLabel = phase,
                                isPlaying = movingLyrics, isPaused = !movingLyrics, lyricsAvailable = true,
                                canRefreshLyrics = true, lyricsLines = lines, compactLyricsFontSizeSp = fontSize),
                            progress, NaviampColors.Dark, {}, {},
                            Modifier.width(320.dp).fillMaxHeight().onGloballyPositioned { lyricBounds = it.boundsInWindow() },
                        )
                        Column(Modifier.weight(1f).fillMaxHeight().drawBehind { siblings.incrementAndGet() }
                            .onGloballyPositioned { siblingBounds = it.boundsInWindow() }) {
                            repeat(30) { Text("Unchanging library row ${it + 1}", color = NaviampColors.Dark.secondaryText) }
                        }
                    }
                }
            }
            LaunchedEffect(window) {
                try {
                    val output = File("core/ui/build/plain-lyrics-probe").apply { mkdirs() }
                    val robot = Robot(window.graphicsConfiguration.device)
                    fun capture(label: String): BufferedImage = robot.createScreenCapture(Rectangle(window.locationOnScreen, window.size)).also {
                        ImageIO.write(it, "png", File(output, "$label.png"))
                        val marker = it.getRGB(window.insets.left + 10, window.insets.top + 10)
                        val background = NaviampColors.Dark.background.toArgb()
                        check(abs((marker and 255) - (background and 255)) <= 8 &&
                            abs((marker shr 8 and 255) - (background shr 8 and 255)) <= 8 &&
                            abs((marker shr 16 and 255) - (background shr 16 and 255)) <= 8) {
                            "Desktop capture is unavailable or the probe is occluded; keep the remote desktop visible"
                        }
                    }
                    fun changes(before: BufferedImage, after: BufferedImage, region: Rect, footer: Int = 0): Int {
                        val scale = window.graphicsConfiguration.defaultTransform.scaleX
                        val left = (window.insets.left + region.left / scale + 8).toInt()
                        val right = (window.insets.left + region.right / scale - 8).toInt()
                        val top = (window.insets.top + region.top / scale + 8).toInt()
                        val bottom = (window.insets.top + region.bottom / scale - 8 - footer).toInt()
                        var changed = 0
                        for (y in top until bottom) for (x in left until right) {
                            val a = before.getRGB(x, y)
                            val b = after.getRGB(x, y)
                            if (abs((a and 255) - (b and 255)) > 10 ||
                                abs((a shr 8 and 255) - (b shr 8 and 255)) > 10 ||
                                abs((a shr 16 and 255) - (b shr 16 and 255)) > 10) changed++
                        }
                        return changed
                    }
                    delay(2500)
                    check(presenterName != null && presenterName != "DesktopSkiaRasterPresenter") { "Native compositor unavailable: $presenterName" }
                    val layer = requireNotNull(findSkiaLayer(window))
                    val delegate = requireNotNull(layer.renderDelegate)
                    val frames = AtomicLong()
                    layer.renderDelegate = object : SkikoRenderDelegate {
                        override fun onRender(canvas: org.jetbrains.skia.Canvas, width: Int, height: Int, nanoTime: Long) {
                            frames.incrementAndGet()
                            delegate.onRender(canvas, width, height, nanoTime)
                        }
                    }
                    val cpu = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
                    robot.mouseMove(window.x + window.width - 3, window.y + window.height - 3)
                    capture("primed")
                    println("PLAIN_LYRICS renderer=$presenterName pid=${ProcessHandle.current().pid()} bounds=${window.bounds} scale=${window.graphicsConfiguration.defaultTransform} refresh=${window.graphicsConfiguration.device.displayMode.refreshRate}")
                    val phases = System.getenv("NAVIAMP_PLAIN_LYRICS_PHASES")?.split(',')
                        ?: listOf("static", "lyrics", "marquee", "waveform", "combined", "tile-boundary", "paused", "hidden", "restored")
                    for (next in phases) {
                        phase = next
                        state.isMinimized = next == "hidden"
                        delay(5000)
                        if (next != "hidden") window.toFront()
                        val before = if (next != "hidden") capture("$next-before") else null
                        val beforeFrames = frames.get()
                        val beforeSiblings = siblings.get()
                        val beforeCpu = cpu.processCpuTime
                        val start = System.nanoTime()
                        val gpu = if (System.getProperty("os.name").startsWith("Windows")) async(Dispatchers.IO) {
                            val process = ProcessBuilder("pwsh", "-NoProfile", "-File", "scripts/animation-probe/measure-windows.ps1",
                                "-ApplicationProcessIds", ProcessHandle.current().pid().toString(), "-SampleCount", "5")
                                .redirectErrorStream(true).start()
                            val text = process.inputStream.bufferedReader().use { it.readText() }
                            check(process.waitFor() == 0) { text }
                            File(output, "$next-gpu.json").writeText(text)
                            text
                        } else null
                        delay(10000)
                        val gpuResult = gpu?.await()
                        val percent = (cpu.processCpuTime - beforeCpu) * 100.0 / (System.nanoTime() - start)
                        val parentFrames = frames.get() - beforeFrames
                        val siblingFrames = siblings.get() - beforeSiblings
                        println("PLAIN_LYRICS phase=$next cpu_one_core=$percent parent_frames=$parentFrames sibling_frames=$siblingFrames")
                        gpuResult?.let { println("PLAIN_LYRICS_GPU $next ${it.replace('\n', ' ')}") }
                        check(parentFrames == 0L && siblingFrames == 0L) { "Continuous motion repainted unrelated content in $next" }
                        check(percent < 10.0) { "Synthetic CPU budget exceeded in $next: $percent" }
                        if (before != null) {
                            val after = capture("$next-after")
                            val difference = changes(before, after, lyricBounds, footer = 64)
                            check(changes(before, after, siblingBounds) == 0) { "Sibling pixels changed in $next" }
                            if (next in listOf("lyrics", "combined", "tile-boundary", "restored")) check(difference > 100) { "Lyrics did not visibly move in $next" }
                            else check(difference == 0) { "Paused/static lyrics moved in $next" }
                            println("PLAIN_LYRICS pixels=$difference phase=$next")
                        } else check(window.extendedState and java.awt.Frame.ICONIFIED != 0) { "Window did not minimize" }
                    }
                    state.isMinimized = false
                    phase = "manual"
                    delay(700)
                    val beforeManual = capture("manual-before")
                    val client = window.contentPane.locationOnScreen
                    val scale = window.graphicsConfiguration.defaultTransform
                    robot.mouseMove(client.x + (lyricBounds.center.x / scale.scaleX).toInt(),
                        client.y + (lyricBounds.center.y / scale.scaleY).toInt())
                    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                    robot.mouseWheel(3)
                    delay(600)
                    val afterManual = capture("manual-after")
                    check(changes(beforeManual, afterManual, lyricBounds, footer = 64) > 100) { "Wheel scrolling failed" }
                    robot.keyPress(KeyEvent.VK_PAGE_DOWN)
                    robot.keyRelease(KeyEvent.VK_PAGE_DOWN)
                    delay(500)
                    check(changes(afterManual, capture("keyboard-after"), lyricBounds, footer = 64) > 100) { "Keyboard scrolling failed" }
                    progress.value = PlaybackProgress(240.0, 240.0, seekGeneration = progress.value.seekGeneration + 1)
                    delay(500)
                    capture("seek-end")
                    fontSize = 64
                    state.size = DpSize(900.dp, 520.dp)
                    delay(700)
                    capture("large-font-resized")
                    println("PLAIN_LYRICS input_seek_resize_complete")
                    exitApplication()
                } catch (failure: Throwable) {
                    failure.printStackTrace()
                    kotlin.system.exitProcess(1)
                }
            }
        }
    }
}

package app.naviamp.ui

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.getTextLayoutResult
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import app.naviamp.domain.playback.PlaybackProgress
import app.naviamp.domain.settings.normalizedCompactLyricsFontSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/** Untimed lyrics have one shared text layout and a small, continuously translated raster window. */
@Composable
internal fun NaviampPlainLyricsText(
    nowPlaying: NowPlayingUi,
    playbackProgress: StateFlow<PlaybackProgress>?,
    colors: NaviampColors,
    modifier: Modifier = Modifier,
) {
    // Merely collecting does not subscribe this composition: .value is read by raster snapshotFlow.
    val progress = playbackProgress?.collectAsState()
    val fallback = PlaybackProgress(nowPlaying.positionSeconds, nowPlaying.durationSeconds)
    val text = remember(nowPlaying.lyricsLines) { AnnotatedString(nowPlaying.lyricsLines.joinToString("\n") { it.text }) }
    val fontSize = normalizedCompactLyricsFontSize(nowPlaying.compactLyricsFontSizeSp)
    val style = LocalTextStyle.current.merge(TextStyle(fontSize = fontSize.sp, lineHeight = (fontSize * 1.5f).sp)).copy(color = Color.Unspecified)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val measurer = rememberTextMeasurer(cacheSize = 0)
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val layout = remember(text, style, viewport.width, density, direction, measurer) {
        if (viewport.width <= 0) null else measurer.measure(text, style, constraints = Constraints(maxWidth = viewport.width))
    }
    val scope = rememberCoroutineScope()
    val controller = remember(nowPlaying.id, nowPlaying.lyricsSourceId) {
        val clock = TimeSource.Monotonic.markNow()
        NaviampPlainLyricsScroll({ clock.elapsedNow().inWholeMilliseconds })
    }
    val owner = remember(nowPlaying.id, nowPlaying.lyricsSourceId, layout, viewport, density, direction, colors.primaryText) {
        NaviampPlainLyricsContentOwner(layout, viewport, density, direction, colors.primaryText, scope, controller)
    }
    DisposableEffect(owner) { onDispose { owner.close() } }
    val content = remember(owner, progress, fallback, nowPlaying.isPlaying) {
        NaviampRasterContent(
            value = {
                val sample = progress?.value ?: fallback
                val resolved = if ((sample.positionSeconds == null && fallback.positionSeconds != null) ||
                    (sample.durationSeconds == null && fallback.durationSeconds != null)) {
                    sample.copy(positionSeconds = sample.positionSeconds ?: fallback.positionSeconds,
                        durationSeconds = sample.durationSeconds ?: fallback.durationSeconds)
                } else sample
                owner.invalidation to resolved
            },
            render = { (_, sample) -> owner.render(sample, nowPlaying.isPlaying) },
        )
    }
    val scrollState = remember(owner) {
        val delegate = ScrollableState { delta -> owner.scrollBy(delta) }
        object : ScrollableState by delegate {
            override suspend fun scroll(scrollPriority: MutatePriority, block: suspend ScrollScope.() -> Unit) {
                owner.beginManual()
                try { delegate.scroll(scrollPriority, block) } finally { owner.endManual() }
            }
            override val canScrollForward: Boolean get() = owner.controller.offset() < owner.controller.maxOffset
            override val canScrollBackward: Boolean get() = owner.controller.offset() > 0f
        }
    }
    val noFling = remember { object : FlingBehavior {
        override suspend fun ScrollScope.performFling(initialVelocity: Float): Float = initialVelocity
    } }
    val keyStep = with(density) { (fontSize * 1.5f).sp.toPx() }
    val focusRequester = remember { FocusRequester() }
    val input = Modifier
        .pointerInput(owner) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                focusRequester.requestFocus()
                owner.beginManual()
                try { waitForUpOrCancellation() } finally { owner.endManual() }
            }
        }
        .scrollable(scrollState, Orientation.Vertical, reverseDirection = true, flingBehavior = noFling, overscrollEffect = null)
        .onPreviewKeyEvent { event ->
            val delta = when (event.key) {
                Key.DirectionDown -> keyStep
                Key.DirectionUp -> -keyStep
                Key.PageDown -> viewport.height.toFloat()
                Key.PageUp -> -viewport.height.toFloat()
                Key.MoveHome -> -owner.controller.maxOffset
                Key.MoveEnd -> owner.controller.maxOffset
                else -> null
            }
            if (delta == null) false else {
                if (event.type == KeyEventType.KeyDown) {
                    owner.beginKey()
                    owner.scrollBy(delta)
                } else if (event.type == KeyEventType.KeyUp) owner.endKey()
                true
            }
        }
        .onFocusChanged { if (!it.isFocused) owner.endKey() }
        .focusRequester(focusRequester)
        .focusable()
    Box(modifier.fillMaxSize().testTag("compact-lyrics-viewport").clipToBounds().onSizeChanged { viewport = it }) {
        NaviampAnimatedRaster(content, Modifier.fillMaxSize().testTag("compact-lyrics-plain-text").then(input)
            .semantics {
                this.text = text
                getTextLayoutResult { results -> layout?.let { results.add(it) } ?: false }
                verticalScrollAxisRange = ScrollAxisRange(
                    value = { owner.controller.offset() }, maxValue = { owner.controller.maxOffset },
                )
                scrollBy { _, y -> owner.scrollBy(y); true }
            })
    }
}

/** Owns telemetry, input and tile deadlines without making them composition dependencies. */
internal class NaviampPlainLyricsContentOwner(
    private val layout: TextLayoutResult?,
    private val viewport: IntSize,
    private val density: Density,
    private val direction: LayoutDirection,
    private val color: Color,
    private val scope: CoroutineScope,
    val controller: NaviampPlainLyricsScroll,
) {
    var invalidation by mutableIntStateOf(0)
        private set
    private var manualDepth = 0
    private var keyHeld = false
    private var boundary: Job? = null
    private var scheduledRevision = -1L
    private var renderedRevision = -1L
    private var renderedRange = IntRange.EMPTY
    private var layers = emptyList<NaviampRasterLayer>()
    private val tiles = NaviampPlainLyricsTileCache { index ->
        val textLayout = requireNotNull(layout)
        val top = index * PlainLyricsTileHeight
        ImageBitmap(viewport.width, minOf(PlainLyricsTileHeight, textLayout.size.height - top)).also { image ->
            CanvasDrawScope().draw(density, direction, Canvas(image), Size(image.width.toFloat(), image.height.toFloat())) {
                drawText(textLayout, color = color, topLeft = Offset(0f, -top.toFloat()))
            }
        }
    }

    fun render(sample: PlaybackProgress, playing: Boolean): List<NaviampRasterLayer> {
        val height = layout?.size?.height ?: 0
        controller.update(Unit, sample.positionSeconds, sample.durationSeconds, playing, sample.seekGeneration,
            (height - viewport.height).coerceAtLeast(0).toFloat(), geometry = this)
        val range = plainLyricsTileRange(controller.offset(), viewport.height, height)
        if (renderedRevision != controller.revision || renderedRange != range) {
            tiles.retain(range)
            // A tile boundary changes only the images. Every tile, old or new, shares the
            // original motion and TimeMark; rebinding must never start a fresh song clock.
            val motion = controller.motion
            val started = controller.translationStarted
            val staticY = if (motion == null) -controller.offset() else 0f
            val count = range.count()
            // Compositor caches are indexed by slot. A ring keeps retained images in place.
            layers = (0 until count).map { slot ->
                val index = range.first + (slot + count - range.first % count) % count
                NaviampRasterLayer(tiles[index], origin = Offset(0f, index * PlainLyricsTileHeight + staticY),
                    translation = motion, translationAxis = NaviampRasterTranslationAxis.Vertical, translationStarted = started)
            }
            renderedRange = range
            renderedRevision = controller.revision
        }
        if (scheduledRevision != controller.revision || boundary == null) {
            boundary?.cancel()
            scheduledRevision = controller.revision
            boundary = controller.nextTileBoundaryDelay(viewport.height)?.let { wait ->
                scope.launch {
                    delay(wait)
                    boundary = null
                    invalidation++
                }
            }
        }
        return layers
    }

    fun beginManual() {
        if (manualDepth++ == 0) { controller.beginManual(); invalidation++ }
    }

    fun endManual() {
        if (manualDepth > 0 && --manualDepth == 0) { controller.endManual(); invalidation++ }
    }

    fun scrollBy(delta: Float): Float {
        val discrete = manualDepth == 0
        if (discrete) beginManual()
        val consumed = controller.scrollBy(delta)
        if (consumed != 0f) invalidation++
        if (discrete) endManual()
        return consumed
    }

    fun beginKey() { if (!keyHeld) { keyHeld = true; beginManual() } }
    fun endKey() { if (keyHeld) { keyHeld = false; endManual() } }
    fun close() {
        boundary?.cancel()
        if (manualDepth > 0) {
            manualDepth = 0
            keyHeld = false
            controller.endManual()
        }
    }
}

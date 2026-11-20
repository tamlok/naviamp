package app.naviamp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import app.naviamp.ui.generated.resources.Res
import app.naviamp.ui.generated.resources.now_playing_volume
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
internal fun NaviampVolumeControl(volumePercent: Int, colors: NaviampColors, onVolumeChanged: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val volume = remember { mutableFloatStateOf(volumePercent.coerceIn(0, 100) / 100f) }
    var dragging by remember { mutableStateOf(false) }
    val muted by remember { derivedStateOf { volume.floatValue <= 0f } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(volumePercent) {
        if (!dragging) volume.floatValue = volumePercent.coerceIn(0, 100) / 100f
    }
    Box {
        NaviampTransportIconButton(
            enabled = true,
            icon = if (muted) NaviampTransportIcons.VolumeMuted else NaviampTransportIcons.Volume,
            contentDescription = stringResource(Res.string.now_playing_volume),
            colors = colors,
            selected = expanded,
            buttonSize = 48.dp,
            iconSize = 17.dp,
            modifier = Modifier.testTag("volume-popup-button"),
            onClick = { expanded = !expanded },
        )
        NaviampAnchoredPopup(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(64.dp),
            offset = DpOffset.Zero,
            containerColor = colors.menuSurface,
            shape = RoundedCornerShape(4.dp),
            shadowElevation = 8.dp,
            properties = PopupProperties(focusable = true),
            cursorPosition = null,
            initialFocusRequester = focus,
        ) {
            Box(Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
                VerticalVolumeSlider(
                    value = { volume.floatValue },
                    isChangingVolume = { dragging = it },
                    onValueChanged = {
                        val percent = (it * 100).roundToInt().coerceIn(0, 100)
                        volume.floatValue = percent / 100f
                        onVolumeChanged(percent)
                    },
                    colors = colors,
                    focusRequester = focus,
                    modifier = Modifier.width(48.dp).height(160.dp),
                )
            }
        }
    }
}

@Composable
private fun VerticalVolumeSlider(
    value: () -> Float,
    isChangingVolume: (Boolean) -> Unit,
    onValueChanged: (Float) -> Unit,
    colors: NaviampColors,
    focusRequester: FocusRequester,
    modifier: Modifier,
) {
    var heightPx by remember { mutableFloatStateOf(1f) }
    var focused by remember { mutableStateOf(false) }
    val label = stringResource(Res.string.now_playing_volume)
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChanged by rememberUpdatedState(onValueChanged)
    val currentIsChangingVolume by rememberUpdatedState(isChangingVolume)
    val thumbRadiusPx = with(LocalDensity.current) { 6.dp.toPx() }

    fun updateFromY(y: Float) {
        val trackHeight = (heightPx - thumbRadiusPx * 2f).coerceAtLeast(1f)
        currentOnValueChanged(((heightPx - thumbRadiusPx - y) / trackHeight).coerceIn(0f, 1f))
    }

    fun commit(target: Float) {
        currentIsChangingVolume(true)
        try { currentOnValueChanged(target.coerceIn(0f, 1f)) }
        finally { currentIsChangingVolume(false) }
    }

    Canvas(modifier.testTag("now-playing-volume-slider")
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val target = when (event.key) {
                Key.DirectionLeft, Key.DirectionDown -> currentValue() - .05f
                Key.DirectionRight, Key.DirectionUp -> currentValue() + .05f
                Key.PageDown -> currentValue() - .1f
                Key.PageUp -> currentValue() + .1f
                Key.MoveHome -> 0f
                Key.MoveEnd -> 1f
                else -> return@onPreviewKeyEvent false
            }
            commit(target)
            true
        }
        .focusRequester(focusRequester)
        .onFocusChanged { focused = it.isFocused }
        .focusable()
        .semantics {
            contentDescription = label
            progressBarRangeInfo = ProgressBarRangeInfo(currentValue(), 0f..1f)
            setProgress { target ->
                if (!target.isFinite()) false else {
                    commit(target)
                    true
                }
            }
        }
        .onSizeChanged { heightPx = it.height.toFloat().coerceAtLeast(1f) }
        .pointerInput(heightPx, thumbRadiusPx) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                down.consume()
                focusRequester.requestFocus()
                currentIsChangingVolume(true)
                try {
                    var lastPosition = down.position
                    updateFromY(lastPosition.y)
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        event.changes.forEach { it.consume() }
                        if (change.position != lastPosition) {
                            lastPosition = change.position
                            updateFromY(lastPosition.y)
                        }
                        if (!change.pressed || (down.type == PointerType.Mouse && !event.buttons.isPrimaryPressed)) break
                    }
                } finally {
                    currentIsChangingVolume(false)
                }
            }
        }) {
        val centerX = size.width / 2f
        val bottom = (size.height - thumbRadiusPx).coerceAtLeast(thumbRadiusPx)
        val thumbY = bottom - (bottom - thumbRadiusPx) * currentValue()
        drawLine(colors.primaryText.copy(alpha = .24f), Offset(centerX, thumbRadiusPx), Offset(centerX, bottom),
            strokeWidth = 5f, cap = StrokeCap.Round)
        drawLine(colors.accent, Offset(centerX, thumbY), Offset(centerX, bottom),
            strokeWidth = 5f, cap = StrokeCap.Round)
        drawCircle(Color.Black.copy(alpha = .32f), thumbRadiusPx + 2f, Offset(centerX, thumbY))
        drawCircle(colors.accent, thumbRadiusPx, Offset(centerX, thumbY))
        drawCircle(colors.primaryText.copy(alpha = .86f), thumbRadiusPx * .42f, Offset(centerX, thumbY))
        if (focused) drawRoundRect(colors.primaryText, cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(2.dp.toPx()))
    }
}

package app.naviamp.ui

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Owns the bounded list viewport, including the blank area of short or empty lists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NaviampPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    canPull: () -> Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    val refreshing by rememberUpdatedState(isRefreshing)
    val atTop by rememberUpdatedState(canPull)
    val scope = rememberCoroutineScope()
    val dispatcher = remember { NestedScrollDispatcher() }
    val connection = remember { object : NestedScrollConnection {} }
    val emptyAreaScroll = remember {
        object : ScrollableState by ScrollableState({ 0f }) {
            // Accept touch drags but never capture wheel events from the actual list.
            override val canScrollForward: Boolean get() = false
            override val canScrollBackward: Boolean get() = false
        }
    }
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = state,
        modifier = modifier,
    ) {
        Box(
            Modifier.fillMaxSize()
                .nestedScroll(connection, dispatcher)
                .pointerInput(Unit) {
                    // Compose scrollables handle touch, but deliberately exclude mouse drags.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.type != PointerType.Mouse || !currentEvent.buttons.isPrimaryPressed ||
                            refreshing || !atTop() || state.isAnimating
                        ) return@awaitEachGesture
                        var pulling = false
                        var consumedY = 0f
                        var released = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.isConsumed || refreshing) break
                                if (!change.pressed) {
                                    released = true
                                    break
                                }
                                val total = change.position - down.position
                                if (!pulling) {
                                    if (abs(total.x) > viewConfiguration.touchSlop ||
                                        total.y < -viewConfiguration.touchSlop
                                    ) break
                                    if (total.y <= viewConfiguration.touchSlop) continue
                                    if (!atTop()) break
                                    pulling = true
                                }
                                val delta = Offset(0f, change.positionChange().y)
                                val pre = dispatcher.dispatchPreScroll(delta, NestedScrollSource.UserInput)
                                val post = dispatcher.dispatchPostScroll(
                                    Offset.Zero, delta - pre, NestedScrollSource.UserInput,
                                )
                                consumedY += pre.y + post.y
                                change.consume()
                            }
                        } finally {
                            if (pulling) {
                                if (released && !refreshing) {
                                    scope.launch {
                                        val consumed = dispatcher.dispatchPreFling(Velocity.Zero)
                                        dispatcher.dispatchPostFling(consumed, Velocity.Zero)
                                    }
                                } else if (!refreshing) {
                                    // Clear the node's pull distance without committing a refresh.
                                    // A newly busy node owns its own indicator animation.
                                    dispatcher.dispatchPreScroll(Offset(0f, -consumedY), NestedScrollSource.UserInput)
                                    scope.launch { state.animateToHidden() }
                                }
                            }
                        }
                    }
                }
                .scrollable(emptyAreaScroll, Orientation.Vertical, overscrollEffect = null),
            content = content,
        )
    }
}

package app.naviamp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.naviamp.ui.generated.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

internal enum class NaviampNavigationPlacement { Rail, Bottom, Drawer }

internal fun naviampNavigationPlacement(widthDp: Float, heightDp: Float): NaviampNavigationPlacement = when {
    widthDp < 360f || heightDp < 480f || (widthDp > heightDp && widthDp < 600f) ->
        NaviampNavigationPlacement.Drawer
    widthDp > heightDp -> NaviampNavigationPlacement.Rail
    else -> NaviampNavigationPlacement.Bottom
}

internal val NaviampMainRoutes: List<SharedRoute> = listOf(
    SharedRoute.Home, SharedRoute.Playlists, SharedRoute.Library, SharedRoute.Radio, SharedRoute.My,
)

internal fun naviampMainNavigationSelection(route: SharedRoute): SharedRoute? = when (route) {
    SharedRoute.Home, SharedRoute.Playlists, SharedRoute.Library, SharedRoute.Radio, SharedRoute.My -> route
    SharedRoute.Settings, SharedRoute.Downloads -> SharedRoute.My
    SharedRoute.ArtistMix, SharedRoute.AlbumMix, SharedRoute.GenreMix, SharedRoute.SonicPath, SharedRoute.SonicMix -> SharedRoute.Home
    SharedRoute.Search -> null
}

@Composable
internal fun NaviampMainScaffold(
    selectedRoute: SharedRoute,
    colors: NaviampColors,
    onRouteSelected: (SharedRoute) -> Unit,
    modifier: Modifier = Modifier,
    showChrome: Boolean = true,
    miniPlayer: @Composable () -> Unit,
    content: @Composable () -> Unit,
) = NaviampMainScaffold(
    selectedRoute = selectedRoute,
    colors = colors,
    onRouteSelected = onRouteSelected,
    systemInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
    keyboardInsets = WindowInsets.ime,
    modifier = modifier,
    showChrome = showChrome,
    miniPlayer = miniPlayer,
    content = content,
)

// Explicit insets also let shared rendering tests exercise the same viewport as native hosts.
@Composable
internal fun NaviampMainScaffold(
    selectedRoute: SharedRoute,
    colors: NaviampColors,
    onRouteSelected: (SharedRoute) -> Unit,
    systemInsets: WindowInsets,
    keyboardInsets: WindowInsets,
    modifier: Modifier = Modifier,
    showChrome: Boolean = true,
    miniPlayer: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!showChrome) {
        Box(modifier.fillMaxSize().windowInsetsPadding(systemInsets)
            .naviampContentInsets(systemInsets = WindowInsets(0), keyboardInsets = keyboardInsets)
            .testTag("main-route-content")) {
            content()
        }
        return
    }
    val textDirection = LocalLayoutDirection.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val toggleFocus = remember { FocusRequester() }
    val drawerFocus = remember { List(NaviampMainRoutes.size + 1) { FocusRequester() } }
    var focusedDrawerItem by remember { mutableIntStateOf(0) }
    var restoreToggleFocus by remember { mutableStateOf(false) }
    val selected = naviampMainNavigationSelection(selectedRoute)
    val currentSelected by rememberUpdatedState(selected)
    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }
    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

    BoxWithConstraints(
        modifier.fillMaxSize().windowInsetsPadding(systemInsets),
    ) {
        // Placement uses the safe viewport, not the keyboard-reduced content height.
        val viewportHeight = maxHeight
        val placement = naviampNavigationPlacement(maxWidth.value, viewportHeight.value)
        val drawerVisible = placement == NaviampNavigationPlacement.Drawer &&
            (drawerState.currentValue == DrawerValue.Open || drawerState.targetValue == DrawerValue.Open)
        val drawerWidth = minOf(280.dp, (maxWidth - 48.dp).coerceAtLeast(0.dp))

        LaunchedEffect(placement) {
            if (placement != NaviampNavigationPlacement.Drawer) drawerState.snapTo(DrawerValue.Closed)
        }
        LaunchedEffect(drawerState.isOpen, placement) {
            if (placement != NaviampNavigationPlacement.Drawer) {
                restoreToggleFocus = false
            } else if (drawerState.isOpen) {
                restoreToggleFocus = true
                // Material requests its first focus on opening. Select our destination afterwards.
                withFrameNanos { }
                drawerFocus[NaviampMainRoutes.indexOf(currentSelected).takeIf { it >= 0 }?.plus(1) ?: 1]
                    .requestFocus()
            } else if (drawerState.targetValue == DrawerValue.Closed && restoreToggleFocus) {
                restoreToggleFocus = false
                toggleFocus.requestFocus()
            }
        }

        // The modal wrapper and route-content callsite remain stable through every placement change.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            ModalNavigationDrawer(
                modifier = Modifier.fillMaxSize()
                    .naviampContentInsets(systemInsets = WindowInsets(0), keyboardInsets = keyboardInsets)
                    .onPreviewKeyEvent { event ->
                        if (!drawerVisible || event.type != KeyEventType.KeyDown) false
                        else when (event.key) {
                            Key.Escape -> { closeDrawer(); true }
                            Key.Tab -> {
                                val step = if (event.isShiftPressed) -1 else 1
                                val next = (focusedDrawerItem + step + drawerFocus.size) % drawerFocus.size
                                drawerFocus[next].requestFocus()
                                true
                            }
                            else -> false
                        }
                    },
                drawerState = drawerState,
                gesturesEnabled = placement == NaviampNavigationPlacement.Drawer && drawerState.isOpen,
                drawerContent = {
                    ModalDrawerSheet(
                        modifier = Modifier.width(drawerWidth).fillMaxHeight(),
                        drawerContainerColor = colors.controlSurface,
                        drawerContentColor = colors.primaryText,
                        windowInsets = WindowInsets(0),
                    ) {
                        // Closed/offscreen drawer controls must never participate in focus traversal.
                        if (drawerVisible) {
                            CompositionLocalProvider(LocalLayoutDirection provides textDirection) {
                                Column(
                                    Modifier.fillMaxSize().testTag("main-navigation-drawer")
                                        .focusProperties { onExit = { cancelFocusChange() } }.focusGroup()
                                        .verticalScroll(rememberScrollState()).selectableGroup()
                                        .padding(8.dp),
                                ) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            stringResource(Res.string.navigation_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                                                .semantics { heading() },
                                        )
                                        NavigationToggle(
                                            open = true, colors = colors, onClick = closeDrawer,
                                            modifier = Modifier.focusRequester(drawerFocus[0])
                                                .onFocusChanged { if (it.isFocused) focusedDrawerItem = 0 },
                                        )
                                    }
                                    NaviampMainRoutes.forEachIndexed { index, route ->
                                        MainNavigationItem(
                                            route, selected == route, colors, horizontal = true, revealKey = viewportHeight,
                                            onClick = { onRouteSelected(route); closeDrawer() },
                                            modifier = Modifier.fillMaxWidth().focusRequester(drawerFocus[index + 1])
                                                .onFocusChanged { if (it.isFocused) focusedDrawerItem = index + 1 },
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        if (placement == NaviampNavigationPlacement.Rail) {
                            CompositionLocalProvider(LocalLayoutDirection provides textDirection) {
                                MainNavigationRail(selected, colors, onRouteSelected)
                            }
                        }
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            if (placement == NaviampNavigationPlacement.Drawer) {
                                // Physical left, including RTL; do not reserve this row in other modes.
                                Row(Modifier.fillMaxWidth().height(48.dp)) {
                                    NavigationToggle(
                                        open = false, colors = colors, onClick = openDrawer,
                                        modifier = Modifier.focusRequester(toggleFocus),
                                    )
                                }
                            }
                            CompositionLocalProvider(LocalLayoutDirection provides textDirection) {
                                Box(
                                    Modifier.fillMaxWidth().weight(1f).testTag("main-route-content")
                                        .mainNavigationEdgeGesture(
                                            enabled = placement == NaviampNavigationPlacement.Drawer && !drawerVisible,
                                            onOpen = openDrawer,
                                        ),
                                ) { content() }
                            }
                        }
                    }
                    CompositionLocalProvider(LocalLayoutDirection provides textDirection) {
                        Box(Modifier.fillMaxWidth().testTag("main-mini-player")) { miniPlayer() }
                        if (placement == NaviampNavigationPlacement.Bottom) {
                            MainNavigationBottom(selected, colors, onRouteSelected)
                        }
                    }
                }
            }
        }
        if (drawerVisible) {
            NaviampPopupPresence()
            // Register after content so nested route/detail handlers cannot consume this dismissal.
            NaviampSystemBackHandler(enabled = true, onBack = closeDrawer)
        }
    }
}

@Composable
private fun NavigationToggle(open: Boolean, colors: NaviampColors, onClick: () -> Unit, modifier: Modifier) {
    val label = stringResource(if (open) Res.string.navigation_close else Res.string.navigation_open)
    IconButton(onClick = onClick, modifier = modifier.size(48.dp)
        .testTag(if (open) "main-navigation-close" else "main-navigation-open")) {
        Icon(if (open) NaviampIcons.Close else NaviampTransportIcons.Menu, label, tint = colors.primaryText)
    }
}

@Composable
private fun MainNavigationRail(selected: SharedRoute?, colors: NaviampColors, onRouteSelected: (SharedRoute) -> Unit) {
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    Column(
        Modifier.width(88.dp).fillMaxHeight().background(colors.controlSurface)
            .testTag("main-navigation-rail").onSizeChanged { viewportSize = it }.verticalScroll(rememberScrollState()).selectableGroup(),
    ) {
        NaviampMainRoutes.forEach { route ->
            MainNavigationItem(route, selected == route, colors, onClick = { onRouteSelected(route) },
                modifier = Modifier.fillMaxWidth(), revealKey = viewportSize)
        }
    }
}

@Composable
internal fun MainNavigationBottom(
    selected: SharedRoute?,
    colors: NaviampColors,
    onRouteSelected: (SharedRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth().background(colors.controlSurface).testTag("main-navigation-bottom")) {
        val viewportWidth = maxWidth
        val cellWidth = (viewportWidth / NaviampMainRoutes.size).coerceAtLeast(72.dp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup()) {
            NaviampMainRoutes.forEach { route ->
                MainNavigationItem(route, selected == route, colors, onClick = { onRouteSelected(route) },
                    modifier = Modifier.width(cellWidth), revealKey = viewportWidth)
            }
        }
    }
}

@Composable
private fun MainNavigationItem(
    route: SharedRoute,
    selected: Boolean,
    colors: NaviampColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    horizontal: Boolean = false,
    revealKey: Any? = null,
) {
    val label = stringResource(when (route) {
        SharedRoute.Home -> Res.string.home_music_title
        SharedRoute.Playlists -> Res.string.playlists_title
        SharedRoute.Library -> Res.string.library_title
        SharedRoute.Radio -> Res.string.nav_radio
        SharedRoute.My -> Res.string.nav_my
        else -> error("Not a main destination: $route")
    })
    val bringIntoView = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    LaunchedEffect(selected, focused, density.fontScale, revealKey) {
        if (selected || focused) {
            withFrameNanos { }
            bringIntoView.bringIntoView()
        }
    }
    val foreground = if (selected) colors.onAccent else colors.primaryText
    val itemModifier = modifier.heightIn(min = if (horizontal) 48.dp else 64.dp)
        .bringIntoViewRequester(bringIntoView)
        .onFocusChanged { focused = it.isFocused }
        .clip(RoundedCornerShape(8.dp))
        .background(if (selected) colors.accent else colors.controlSurface)
        .testTag("main-destination-${route.name}")
        .selectable(selected = selected, role = Role.Tab, onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 8.dp)
    if (horizontal) {
        Row(itemModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(route.icon, contentDescription = null, tint = foreground, modifier = Modifier.size(24.dp))
            Text(label, color = foreground, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        }
    } else {
        Column(itemModifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(route.icon, contentDescription = null, tint = foreground, modifier = Modifier.size(24.dp))
            Text(label, color = foreground, style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Modifier.mainNavigationEdgeGesture(enabled: Boolean, onOpen: () -> Unit): Modifier {
    val currentOnOpen by rememberUpdatedState(onOpen)
    return if (!enabled) this else pointerInput(Unit) {
        val edgeWidth = 24.dp.toPx()
        val openingDistance = 56.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.position.x > edgeWidth) return@awaitEachGesture
            val intent = SwipeGestureIntentLock(viewConfiguration.touchSlop)
            var distance = 0f
            var released = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed) break
                val movement = change.position - down.position
                distance = movement.x
                if (!change.pressed) { released = true; break }
                when (intent.update(distance, movement.y)) {
                    SwipeGestureIntent.Pending -> Unit
                    SwipeGestureIntent.Vertical -> break
                    SwipeGestureIntent.Horizontal -> if (distance > 0f) change.consume()
                }
            }
            if (released && intent.intent == SwipeGestureIntent.Horizontal && distance >= openingDistance) currentOnOpen()
        }
    }
}

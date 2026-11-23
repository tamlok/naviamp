package app.naviamp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.pointerInput
import app.naviamp.domain.provider.DiscoveryBrowseAction

/** One visible-destination policy for native phone Back and shared mouse navigation. */
enum class NaviampBackTarget { NowPlaying, GeneratedRadio, DiscoveryRequests, DiscoveryArtist, DiscoveryBrowser, HomeCollection, Page }

fun naviampBackTarget(shell: NaviampAppShellUiState): NaviampBackTarget? = when {
    shell.shellChrome.nowPlayingOpen -> NaviampBackTarget.NowPlaying
    shell.generatedRadio.open -> NaviampBackTarget.GeneratedRadio
    shell.albumDetail.selectedAlbum != null -> NaviampBackTarget.Page
    shell.artistDetail.selectedArtist != null -> NaviampBackTarget.Page
    shell.playlistDetail.selectedPlaylist != null -> NaviampBackTarget.Page
    shell.shellChrome.selectedRoute == SharedRoute.Home && shell.home.providerDiscovery?.requestsOpen == true -> NaviampBackTarget.DiscoveryRequests
    shell.shellChrome.selectedRoute == SharedRoute.Home && shell.home.providerDiscovery?.let { it.artistPage && it.artistDetail != null } == true -> NaviampBackTarget.DiscoveryArtist
    shell.shellChrome.selectedRoute == SharedRoute.Home && shell.home.providerDiscovery?.browser != null -> NaviampBackTarget.DiscoveryBrowser
    shell.shellChrome.selectedRoute == SharedRoute.Home && shell.home.collectionPage != null -> NaviampBackTarget.HomeCollection
    shell.shellChrome.canNavigateBack -> NaviampBackTarget.Page
    else -> null
}

internal fun performNaviampBack(target: NaviampBackTarget, actions: NaviampAppShellActions) = when (target) {
    NaviampBackTarget.NowPlaying -> actions.navigationActions.onCloseNowPlaying()
    NaviampBackTarget.GeneratedRadio -> actions.generatedRadioActions.onAction(GeneratedRadioActionRequest(GeneratedRadioAction.Close))
    NaviampBackTarget.DiscoveryRequests -> actions.homeActions.onDiscoveryRequestsDismiss()
    NaviampBackTarget.DiscoveryArtist -> actions.homeActions.onDiscoveryArtistDismiss()
    NaviampBackTarget.DiscoveryBrowser -> actions.homeActions.onDiscoveryBrowse(DiscoveryBrowseAction.Close)
    NaviampBackTarget.HomeCollection -> actions.homeActions.onCollectionBack()
    NaviampBackTarget.Page -> actions.navigationActions.onBack()
}

/** React only to the press edge; held buttons and pointer moves never pop further pages. */
@Composable
internal fun Modifier.naviampMouseBack(onBack: () -> Unit): Modifier {
    val latest by rememberUpdatedState(onBack)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            var pressed = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val down = event.buttons.isBackPressed
                if (down && !pressed && event.changes.none { it.isConsumed }) {
                    latest()
                    event.changes.forEach { it.consume() }
                }
                pressed = down
            }
        }
    }
}

@Composable
internal fun NaviampBackNavigation(shell: NaviampAppShellUiState, actions: NaviampAppShellActions,
    modifier: Modifier, remoteKeys: Boolean = false, content: @Composable (Modifier) -> Unit) {
    val dispatcher = LocalNaviampSystemBackDispatcher.current ?: remember { NaviampSystemBackDispatcher() }
    val target = naviampBackTarget(shell)
    CompositionLocalProvider(LocalNaviampSystemBackDispatcher provides dispatcher) {
        NaviampSystemBackHandler(target != null) { target?.let { performNaviampBack(it, actions) } }
        Box(modifier.fillMaxSize().then(if (remoteKeys) Modifier.naviampRemoteBack(dispatcher) else Modifier)
            .naviampMouseBack { dispatcher.currentHandler?.invoke() }) {
            content(Modifier.fillMaxSize())
        }
    }
}

/** Bubble after focused controls; one physical press invokes only the deepest shared handler. */
internal fun Modifier.naviampRemoteBack(dispatcher: NaviampSystemBackDispatcher): Modifier = composed {
    var held by remember { mutableStateOf(false) }
    onKeyEvent { event ->
        if (event.key != Key.Back && event.key != Key.Escape && event.key != Key.Menu) false
        else when (event.type) {
            KeyEventType.KeyDown -> {
                if (held) true else dispatcher.currentHandler?.let {
                    held = true
                    it()
                    true
                } ?: false
            }
            KeyEventType.KeyUp -> held.also { held = false }
            else -> false
        }
    }
}

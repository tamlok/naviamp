package app.naviamp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.naviamp.domain.playback.sanitizedPlaybackDiagnosticUrl
import app.naviamp.ui.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun NaviampMyContent(
    uiState: NaviampAppShellUiState,
    actions: NaviampAppShellActions,
    colors: NaviampColors,
    modifier: Modifier = Modifier,
) {
    val account = uiState.connectionSettings.connection.savedConnections.firstOrNull { it.current }
    val favoriteArtists = uiState.home.content.collectionSections.firstOrNull {
        it.visible && it.titleResource == SharedHomeCollectionTitleResource.FavoriteArtists
    }
    Column(modifier.fillMaxSize()) {
        NaviampPageHeader(stringResource(Res.string.nav_my), colors)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).testTag("my-hub"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            account?.let {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(it.displayName, color = colors.primaryText)
                    if (it.username.isNotBlank()) Text(it.username, color = colors.secondaryText)
                    Text(it.serverUrl.substringBefore('?').substringBefore('#').sanitizedPlaybackDiagnosticUrl(),
                        color = colors.secondaryText)
                }
            }
            MyLink(stringResource(Res.string.tv_accounts), "my-accounts", colors, actions.connectionActions.onOpenAccounts)
            if (favoriteArtists != null) {
                MyLink(stringResource(Res.string.my_favorite_artists), "my-favorite-artists", colors) {
                    actions.navigationActions.onRouteSelected(SharedRoute.Home)
                    actions.homeActions.onCollectionSelected(favoriteArtists.id)
                }
            }
            if (uiState.shellChrome.supportsDownloads) {
                MyLink(stringResource(Res.string.downloads_offline_title), "my-downloads", colors) {
                    actions.navigationActions.onRouteSelected(SharedRoute.Downloads)
                }
            }
            MyLink(stringResource(Res.string.nav_settings), "my-settings", colors) {
                actions.navigationActions.onRouteSelected(SharedRoute.Settings)
            }
        }
    }
}

@Composable
private fun MyLink(label: String, tag: String, colors: NaviampColors, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag),
        colors = ButtonDefaults.textButtonColors(contentColor = colors.primaryText, containerColor = colors.controlSurface),
    ) {
        Text(label, modifier = Modifier.fillMaxWidth())
    }
}

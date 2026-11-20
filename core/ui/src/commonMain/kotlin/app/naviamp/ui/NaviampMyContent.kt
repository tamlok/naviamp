package app.naviamp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.naviamp.domain.playback.sanitizedPlaybackDiagnosticUrl
import app.naviamp.domain.source.normalizedBaseUrl
import app.naviamp.ui.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun NaviampMyContent(
    uiState: NaviampAppShellUiState,
    actions: NaviampAppShellActions,
    colors: NaviampColors,
    selectedRoute: SharedRoute,
    stateHolder: SaveableStateHolder,
    modifier: Modifier = Modifier,
    settingsContent: @Composable () -> Unit,
) {
    val supportsDownloads = uiState.shellChrome.supportsDownloads
    val tabs = remember(supportsDownloads) {
        if (supportsDownloads) listOf(SharedRoute.My, SharedRoute.Downloads, SharedRoute.Settings)
        else listOf(SharedRoute.My, SharedRoute.Settings)
    }
    val activeRoute = selectedRoute.takeIf { it in tabs } ?: SharedRoute.My
    val focusRequesters = remember(tabs) { tabs.map { FocusRequester() } }
    val layoutDirection = LocalLayoutDirection.current
    Column(modifier.fillMaxSize().testTag("my-hub")) {
        NaviampPageHeader(stringResource(Res.string.nav_my), colors)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp).padding(bottom = 8.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tabs.forEachIndexed { index, route ->
                val label = stringResource(when (route) {
                    SharedRoute.Downloads -> Res.string.downloads_offline_title
                    SharedRoute.Settings -> Res.string.nav_settings
                    else -> Res.string.tv_accounts
                })
                val tag = when (route) {
                    SharedRoute.Downloads -> "my-downloads"
                    SharedRoute.Settings -> "my-settings"
                    else -> "my-accounts"
                }
                TextButton(
                    onClick = { if (activeRoute != route) actions.navigationActions.onRouteSelected(route) },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = if (activeRoute == route) colors.accent else colors.controlSurface,
                        contentColor = if (activeRoute == route) colors.onAccent else colors.primaryText,
                    ),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(tag)
                        .focusRequester(focusRequesters[index])
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown ||
                                (event.key != Key.DirectionLeft && event.key != Key.DirectionRight)) false
                            else {
                                val step = if (event.key == Key.DirectionLeft) -1 else 1
                                val next = index + if (layoutDirection == LayoutDirection.Rtl) -step else step
                                focusRequesters[next.coerceIn(tabs.indices)].requestFocus()
                                true
                            }
                        }
                        .semantics { role = Role.Tab; selected = activeRoute == route },
                ) { Text(label, textAlign = TextAlign.Center) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            stateHolder.SaveableStateProvider(activeRoute.name) {
                when (activeRoute) {
                    SharedRoute.Settings -> settingsContent()
                    SharedRoute.Downloads -> NaviampDownloadsContent(
                        colors = colors,
                        screen = uiState.downloads,
                        actions = actions.downloadsActions,
                        playlistChoices = uiState.playlistChoices,
                        playlistActionStatus = uiState.playlists.status,
                        showTitle = false,
                    )
                    else -> MyAccountsContent(uiState.connectionSettings, actions.connectionActions, colors)
                }
            }
        }
    }
}

@Composable
private fun MyAccountsContent(
    settings: NaviampConnectionSettingsUi,
    actions: NaviampConnectionSettingsActions,
    colors: NaviampColors,
) {
    val account = settings.connection.savedConnections.firstOrNull { it.current }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("my-account-info"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (account != null) {
            val baseUrl = normalizedBaseUrl(account.serverUrl)
            val serverUrl = baseUrl.substringBefore('?').substringBefore('#').sanitizedPlaybackDiagnosticUrl()
            val displayName = if (normalizedBaseUrl(account.displayName) == baseUrl) serverUrl else account.displayName
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(displayName, color = colors.primaryText)
                if (account.username.isNotBlank()) Text(account.username, color = colors.secondaryText)
                if (displayName != serverUrl) Text(serverUrl, color = colors.secondaryText)
            }
        } else {
            Text(stringResource(if (settings.connection.savedConnections.isEmpty()) Res.string.settings_source_no_connections
                else Res.string.tv_accounts_title), color = colors.secondaryText)
        }
        TextButton(
            onClick = actions.onOpenAccounts,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("my-account-chooser"),
            colors = ButtonDefaults.textButtonColors(contentColor = colors.primaryText, containerColor = colors.controlSurface),
        ) {
            Text(stringResource(Res.string.my_view_accounts), modifier = Modifier.fillMaxWidth())
        }
    }
}

package app.naviamp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.naviamp.ui.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Shared Radio navigation; provider actions and collection history remain with Core. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NaviampRadioContent(
    colors: NaviampColors,
    home: NaviampHomeScreenUi,
    internetRadio: NaviampInternetRadioScreenUi,
    homeActions: NaviampHomeActions,
    radioActions: NaviampInternetRadioActions,
    mediaActions: NaviampMediaActions,
    sourceId: String? = null,
    television: Boolean = false,
    topNavigationFocusRequester: FocusRequester? = null,
    entryFocusGeneration: Int? = null,
    onEntryFocusHandled: (Int) -> Unit = {},
) {
    var selectedTab by rememberSaveable(sourceId) { mutableIntStateOf(0) }
    val tabFocus = remember { List(2) { FocusRequester() } }
    val tabState = rememberSaveableStateHolder()
    val headerFocus = remember { FocusRequester() }
    var pendingTabFocus by remember(sourceId) { mutableStateOf(false) }
    val selectTab: (Int) -> Unit = { index ->
        if (selectedTab != index) {
            pendingTabFocus = television
            selectedTab = index
        }
    }
    home.collectionPage?.takeIf { it.section.id in SharedServerRadioSectionIds }?.let { page ->
        key(page.section.id) { NaviampCollectionPage(page, colors, homeActions, mediaActions) }
        return
    }
    LaunchedEffect(selectedTab, pendingTabFocus, entryFocusGeneration) {
        if (!pendingTabFocus) return@LaunchedEffect
        if (entryFocusGeneration == null) {
            for (attempt in 1..5) {
                withFrameNanos { }
                if (tabFocus[selectedTab].requestFocus()) break
            }
        }
        pendingTabFocus = false
    }
    val header: @Composable (@Composable RowScope.() -> Unit) -> Unit = { controls ->
        Column {
            if (television) {
                FlowRow(Modifier.fillMaxWidth().testTag("radio-header"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    itemVerticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.nav_radio), color = colors.primaryText, fontSize = 30.sp,
                        fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).widthIn(min = 120.dp).padding(vertical = 8.dp))
                    Row(Modifier.focusRequester(headerFocus).onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                            Key.DirectionUp -> topNavigationFocusRequester?.requestFocus() == true
                            Key.DirectionDown -> tabFocus[selectedTab].requestFocus()
                            else -> false
                        }
                    }, verticalAlignment = Alignment.CenterVertically, content = controls)
                }
            } else {
                NaviampPageHeader(stringResource(Res.string.nav_radio), colors,
                    modifier = Modifier.testTag("radio-header"), actions = controls)
            }
            Row(Modifier.fillMaxWidth().testTag("radio-tabs"), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(Res.string.radio_server_title, Res.string.tv_library_internet_radio).forEachIndexed { index, resource ->
                    val label = stringResource(resource)
                    val modifier = Modifier.weight(1f).heightIn(min = 48.dp).focusRequester(tabFocus[index])
                        .semantics { role = Role.Tab; selected = selectedTab == index }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                                Key.DirectionLeft -> tabFocus[(index - 1).coerceAtLeast(0)].requestFocus()
                                Key.DirectionRight -> tabFocus[(index + 1).coerceAtMost(1)].requestFocus()
                                Key.DirectionUp -> if (television) headerFocus.requestFocus()
                                    else topNavigationFocusRequester?.requestFocus() == true
                                else -> false
                            }
                        }
                    if (television) {
                        TelevisionTextButton(label, colors, selected = selectedTab == index,
                            onClick = { selectTab(index) }, modifier = modifier, contentPadding = PaddingValues(8.dp))
                    } else {
                        NaviampTextButton(onClick = { selectTab(index) }, modifier = modifier,
                            shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(4.dp, 8.dp),
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = if (selectedTab == index) colors.accent else colors.controlSurface,
                                contentColor = if (selectedTab == index) colors.onAccent else colors.primaryText,
                            )) {
                            Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxSize().padding(horizontal = if (television) 30.dp else 0.dp)) {
        tabState.SaveableStateProvider(selectedTab) {
            if (selectedTab == 0) {
                Column(Modifier.fillMaxSize()) {
                    header {
                        val refresh = stringResource(Res.string.library_refresh)
                        if (television) {
                            TelevisionIconButton(!home.refreshing, NaviampIcons.Refresh, refresh, colors,
                                onClick = homeActions.onRefresh)
                        } else {
                            NaviampPageHeaderAction(NaviampIcons.Refresh, refresh, colors,
                                enabled = !home.refreshing, onClick = homeActions.onRefresh)
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        ServerRadioContent(home, colors, homeActions, mediaActions, television, tabFocus[0],
                            entryFocusGeneration, onEntryFocusHandled)
                    }
                }
            } else if (television) {
                TelevisionInternetRadio(internetRadio, colors, radioActions, tabFocus[1],
                    entryFocusGeneration, onEntryFocusHandled, modifier = Modifier,
                    contentUpFocusRequester = tabFocus[1], header = header)
            } else {
                NaviampInternetRadioContent(colors, internetRadio, radioActions, header = header)
            }
        }
    }
}

@Composable
private fun ServerRadioContent(
    home: NaviampHomeScreenUi,
    colors: NaviampColors,
    actions: NaviampHomeActions,
    mediaActions: NaviampMediaActions,
    television: Boolean,
    tabFocus: FocusRequester,
    entryFocusGeneration: Int?,
    onEntryFocusHandled: (Int) -> Unit,
) {
    val scrollState = rememberScrollState()
    val sections = home.content.serverRadioSections
    LaunchedEffect(entryFocusGeneration, sections.isEmpty()) {
        if (television && sections.isEmpty()) entryFocusGeneration?.let {
            withFrameNanos { }
            tabFocus.requestFocus()
            onEntryFocusHandled(it)
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (television && sections.isNotEmpty()) {
            TelevisionCollectionSections(
                sections = televisionHomeSections(sections).map { HomeScreenSlot(it.id, it) },
                home = home, colors = colors, actions = actions, mediaActions = mediaActions,
                discoveryPlayback = home.samplePlayback, entryFocusGeneration = entryFocusGeneration,
                onEntryFocusHandled = onEntryFocusHandled, topNavigationFocusRequester = tabFocus,
            )
        } else {
            NaviampPullToRefreshBox(isRefreshing = home.refreshing, onRefresh = actions.onRefresh,
                canPull = { !scrollState.canScrollBackward }, modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize().verticalScroll(scrollState), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (sections.isEmpty()) Text(
                        stringResource(if (home.refreshing) Res.string.tv_loading_your_music else Res.string.radio_server_empty),
                        color = colors.secondaryText, fontSize = if (television) 20.sp else 13.sp)
                    sections.forEach { section ->
                        NaviampCollectionSection(section, colors, actions, mediaActions,
                            onTitleSelected = { actions.onCollectionSelected(section.id) },
                            onItemSelected = { dispatchHomeCollectionItem(it, actions, mediaActions) })
                    }
                }
            }
        }
    }
}

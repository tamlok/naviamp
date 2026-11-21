package app.naviamp.ui

import app.naviamp.ui.generated.resources.Res
import app.naviamp.ui.generated.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun NaviampTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(
        contentColor = LocalNaviampTheme.current.colors.actionText,
    ),
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
internal fun NaviampCompactSearchField(
    value: String,
    placeholder: String,
    colors: NaviampColors,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    showClear: Boolean = value.isNotBlank(),
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.primaryText),
        cursorBrush = SolidColor(colors.primaryText),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier
            .naviampTextInputFocus()
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(colors.primaryText.copy(alpha = 0.13f)),
        decorationBox = { innerTextField ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp),
            ) {
                Icon(
                    imageVector = NaviampIcons.Search,
                    contentDescription = null,
                    tint = colors.secondaryText,
                    modifier = Modifier.size(17.dp),
                )
                Box(modifier = Modifier.weight(1f).padding(horizontal = 9.dp)) {
                    if (value.isEmpty()) {
                        Text(placeholder, color = colors.secondaryText, style = MaterialTheme.typography.bodyMedium)
                    }
                    innerTextField()
                }
                if (showClear) {
                    Icon(
                        imageVector = NaviampIcons.Close,
                        contentDescription = null,
                        tint = colors.secondaryText,
                        modifier = Modifier
                            .size(18.dp)
                            .semantics { contentDescription = "Clear search" }
                            .clickable(onClick = onClear),
                    )
                }
            }
        },
    )
}

@Composable
internal fun NaviampTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    colors: NaviampColors,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    isPassword: Boolean = false,
    inputKind: NaviampTextInputKind = NaviampTextInputKind.NaturalLanguage,
    forceFloatingLabel: Boolean = false,
    onSubmit: (() -> Unit)? = null,
    imeAction: ImeAction = if (onSubmit != null) ImeAction.Search else ImeAction.Default,
    onImeAction: (() -> Unit)? = null,
) {
    val displayValue = if (forceFloatingLabel && value.isEmpty()) FloatingLabelSentinel else value
    OutlinedTextField(
        colors = naviampOutlinedTextFieldColors(colors),
        value = displayValue,
        onValueChange = { nextValue ->
            onValueChange(nextValue.replace(FloatingLabelSentinel, ""))
        },
        label = { Text(label, color = colors.secondaryText) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (isPassword && value.isNotEmpty()) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        keyboardOptions = (if (isPassword) NaviampTextInputKind.Password else inputKind).keyboardOptions(
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onNext = { onImeAction?.invoke() },
            onDone = { onImeAction?.invoke() },
            onSearch = { onSubmit?.invoke() ?: onImeAction?.invoke() },
        ),
        modifier = modifier.naviampTextInputFocus().then(
            if (onSubmit != null) {
                Modifier.onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp && event.key == Key.Enter) {
                        onSubmit()
                        true
                    } else {
                        false
                    }
                }
            } else {
                Modifier
            },
        ),
    )
}

internal enum class NaviampTextInputKind {
    NaturalLanguage,
    Technical,
    Url,
    Password,
}

internal fun NaviampTextInputKind.keyboardOptions(imeAction: ImeAction): KeyboardOptions =
    when (this) {
        NaviampTextInputKind.NaturalLanguage -> KeyboardOptions(imeAction = imeAction)
        NaviampTextInputKind.Technical -> KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = imeAction,
        )
        NaviampTextInputKind.Url -> KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = imeAction,
        )
        NaviampTextInputKind.Password -> KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = imeAction,
        )
    }

private const val FloatingLabelSentinel = "\u200B"

@Composable
internal fun PrimaryButton(
    label: String,
    colors: NaviampColors,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accent,
            contentColor = colors.onAccent,
            disabledContainerColor = colors.controlSurface,
            disabledContentColor = colors.mutedText,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(label)
    }
}

@Composable
internal fun SmallPill(label: String, colors: NaviampColors, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
        modifier = Modifier.height(40.dp),
    ) {
        Text(label)
    }
}

@Composable
fun SharedTransportControls(
    colors: NaviampColors,
    isPlaying: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    shuffleEnabled: Boolean = false,
    repeatEnabled: Boolean = false,
    onShuffle: (() -> Unit)? = null,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    onRepeat: (() -> Unit)? = null,
) {
    val transportSize = if (compact) 38.dp else 46.dp
    val sideSize = if (compact) 32.dp else 36.dp
    Row(
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        TransportIconButton(
            icon = NaviampTransportIcons.Shuffle,
            contentDescription = stringResource(Res.string.transport_shuffle),
            colors = colors,
            onClick = onShuffle ?: {},
            enabled = onShuffle != null,
            selected = shuffleEnabled,
            size = sideSize,
        )
        TransportIconButton(
            icon = NaviampTransportIcons.Previous,
            contentDescription = stringResource(Res.string.transport_previous),
            colors = colors,
            onClick = onPrevious ?: {},
            enabled = onPrevious != null,
            size = sideSize,
        )
        TransportIconButton(
            icon = if (isPlaying) NaviampTransportIcons.Pause else NaviampTransportIcons.Play,
            contentDescription = if (isPlaying) {
                stringResource(Res.string.transport_pause)
            } else {
                stringResource(Res.string.transport_play)
            },
            colors = colors,
            onClick = if (isPlaying) onPause else onResume,
            size = transportSize,
        )
        TransportIconButton(
            icon = NaviampTransportIcons.Stop,
            contentDescription = stringResource(Res.string.transport_stop),
            colors = colors,
            onClick = onStop,
            size = sideSize,
        )
        TransportIconButton(
            icon = NaviampTransportIcons.Next,
            contentDescription = stringResource(Res.string.transport_next),
            colors = colors,
            onClick = onNext ?: {},
            enabled = onNext != null,
            size = sideSize,
        )
        TransportIconButton(
            icon = NaviampTransportIcons.Repeat,
            contentDescription = stringResource(Res.string.transport_repeat),
            colors = colors,
            onClick = onRepeat ?: {},
            enabled = onRepeat != null,
            selected = repeatEnabled,
            inactive = !repeatEnabled,
            size = sideSize,
        )
    }
}

@Composable
private fun TransportIconButton(
    icon: ImageVector,
    contentDescription: String,
    colors: NaviampColors,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean = false,
    inactive: Boolean = false,
    size: Dp = 46.dp,
) {
    NaviampTooltip(contentDescription, colors) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(size / 2))
                .background(
                    when {
                        !enabled -> colors.controlSurface.copy(alpha = 0.55f)
                        selected -> colors.primaryText
                        inactive -> colors.controlSurface
                        else -> colors.accent
                    },
                ),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = when {
                    !enabled -> colors.mutedText
                    inactive -> naviampTransportIconTint(colors, enabled = true, inactive = true)
                    selected -> colors.background
                    else -> colors.onAccent
                },
                modifier = Modifier.size(size * 0.48f),
            )
        }
    }
}

@Composable
internal fun MiniPlayerIconButton(
    colors: NaviampColors,
    enabled: Boolean,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    size: Dp = 38.dp,
) {
    NaviampTooltip(contentDescription, colors) {
        IconButton(
            enabled = enabled,
            onClick = onClick,
            modifier = Modifier
                .size(size)
                .semantics { this.selected = selected }
                .clip(RoundedCornerShape(size / 2f))
                .background(if (selected && enabled && icon != NaviampTransportIcons.HeartFilled) colors.primaryText.copy(alpha = 0.14f) else Color.Transparent),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = when {
                    icon == NaviampTransportIcons.HeartFilled -> colors.favorite
                    !enabled -> colors.mutedText
                    else -> colors.primaryText
                },
                modifier = Modifier.size(size * 0.58f),
            )
        }
    }
}

@Composable
internal fun SectionHeader(text: String, colors: NaviampColors) {
    Text(text, color = colors.primaryText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
}

@Composable
internal fun PlaceholderTile(text: String, colors: NaviampColors) {
    Text(
        text,
        color = colors.secondaryText,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.controlSurface)
            .padding(12.dp),
    )
}

@Composable
fun NaviampDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    containerColor: Color = LocalNaviampTheme.current.colors.menuSurface,
    properties: PopupProperties = PopupProperties(focusable = true),
    cursorPosition: IntOffset? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalNaviampOwnedPopupWindows.current || cursorPosition != null) {
        NaviampWindowDropdownMenu(expanded, onDismissRequest, modifier.widthIn(min = 220.dp),
            offset, containerColor, RoundedCornerShape(4.dp), 8.dp, properties, cursorPosition, content)
        return
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = offset,
        shape = RoundedCornerShape(4.dp),
        containerColor = containerColor,
        properties = properties,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        modifier = modifier.widthIn(min = 220.dp).naviampMouseBack { if (properties.dismissOnBackPress) onDismissRequest() }.onPreviewKeyEvent { event ->
            if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                onDismissRequest()
                true
            } else false
        },
        content = {
            NaviampPopupPresence()
            NaviampMenuActivationEffect(onDismissRequest, properties.dismissOnBackPress)
            content()
        },
    )
}

@Composable
fun NaviampDropdownMenuItem(
    label: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalNaviampTheme.current.colors
    DropdownMenuItem(
        text = {
            Text(
                label,
                fontSize = 13.sp,
            )
        },
        leadingIcon = icon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = if (it == NaviampTransportIcons.HeartFilled) colors.favorite else if (enabled) colors.menuText else colors.menuText.copy(alpha = 0.42f),
                    modifier = Modifier.size(17.dp),
                )
            }
        },
        enabled = enabled,
        modifier = Modifier.background(
            if (selected) colors.menuText.copy(alpha = 0.12f) else Color.Transparent,
        ),
        colors = MenuDefaults.itemColors(
            textColor = colors.menuText,
            disabledTextColor = colors.menuText.copy(alpha = 0.42f),
        ),
        onClick = onClick,
    )
}

data class NaviampRowMenuItem(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val dividerBefore: Boolean = false,
)

data class NaviampDetailAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val selected: Boolean = false,
)

internal fun responsiveVisibleActionCount(
    availableWidthDp: Float,
    actionCount: Int,
    actionSlotWidthDp: Float = 44f,
): Int {
    if (actionCount <= 0 || availableWidthDp <= 0f) return 0
    val availableSlots = (availableWidthDp / actionSlotWidthDp).toInt().coerceAtLeast(1)
    return if (actionCount <= availableSlots) actionCount else (availableSlots - 1).coerceAtLeast(0)
}

@Composable
fun NaviampResponsiveActionRow(
    colors: NaviampColors,
    actions: List<NaviampDetailAction>,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val visibleCount = responsiveVisibleActionCount(maxWidth.value, actions.size)
        val visibleActions = actions.take(visibleCount)
        val hiddenActions = actions.drop(visibleCount)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            visibleActions.forEach { action ->
                MiniPlayerIconButton(
                    colors = colors,
                    enabled = action.enabled,
                    icon = action.icon,
                    contentDescription = action.label,
                    onClick = action.onClick,
                    selected = action.selected,
                )
            }
            if (hiddenActions.isNotEmpty()) {
                NaviampRowOverflowMenu(
                    colors = colors,
                    items = hiddenActions.map { action ->
                        NaviampRowMenuItem(action.label, action.icon, action.onClick, action.enabled)
                    },
                    buttonSize = 38.dp,
                    iconSize = 22.dp,
                    selected = hiddenActions.any { it.selected },
                )
            }
        }
    }
}

internal class NaviampItemMenuState {
    var expanded by mutableStateOf(false)
        private set
    /** Compose window coordinates, captured once at the secondary-button release. */
    var cursorPosition by mutableStateOf<IntOffset?>(null)
        private set
    var coordinates: LayoutCoordinates? = null

    fun openAt(position: IntOffset) {
        cursorPosition = position
        expanded = true
    }

    fun toggleFromButton() {
        cursorPosition = null
        expanded = !expanded
    }

    fun dismiss() {
        // Keep the anchor through the exit transition, including the Popup-backed path.
        expanded = false
    }
}

private val LocalNaviampItemMenu = compositionLocalOf<NaviampItemMenuState?> { null }

/** Share one menu between the row's secondary click and its nested overflow button. */
@Composable
internal fun NaviampItemMenuRow(
    itemKey: Any,
    occurrenceIndex: Int = 0,
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: @Composable RowScope.() -> Unit,
) {
    val menu = remember(itemKey, occurrenceIndex) { NaviampItemMenuState() }
    CompositionLocalProvider(LocalNaviampItemMenu provides menu) {
        Row(
            modifier = Modifier.naviampItemContextMenu(menu).then(modifier),
            horizontalArrangement = horizontalArrangement,
            verticalAlignment = verticalAlignment,
            content = content,
        )
    }
}

internal fun Modifier.naviampItemContextMenu(menu: NaviampItemMenuState, enabled: Boolean = true): Modifier =
    if (!enabled) this else onGloballyPositioned { menu.coordinates = it }.pointerInput(menu) {
        awaitEachGesture {
            // awaitFirstDown filters out secondary mouse buttons on Desktop.
            val down = awaitPointerEvent(PointerEventPass.Initial).changes
                .firstOrNull { it.changedToDownIgnoreConsumed() } ?: return@awaitEachGesture
            if (down.type != PointerType.Mouse || !currentEvent.buttons.isSecondaryPressed ||
                currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
            down.consume()
            var cancelled = false
            // Consume the entire secondary gesture before swipe, click and drag handlers see it.
            // Open on release so the opening click cannot dismiss its own popup.
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (event.buttons.isPrimaryPressed ||
                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    cancelled = true
                }
                event.changes.forEach { it.consume() }
                if (!event.buttons.isSecondaryPressed) {
                    val coordinates = menu.coordinates
                    if (!cancelled && change.position.x >= 0 && change.position.x < size.width &&
                        change.position.y >= 0 && change.position.y < size.height &&
                        coordinates != null && coordinates.isAttached) {
                        menu.openAt(coordinates.localToWindow(change.position).round())
                    }
                    break
                }
            }
        }
    }

@Composable
fun NaviampRowOverflowMenu(
    colors: NaviampColors,
    items: List<NaviampRowMenuItem>,
    modifier: Modifier = Modifier,
    buttonSize: androidx.compose.ui.unit.Dp = 28.dp,
    iconSize: androidx.compose.ui.unit.Dp = 17.dp,
    selected: Boolean = false,
    contentDescription: String = stringResource(Res.string.playlist_item_more_actions),
    button: (@Composable (onClick: () -> Unit) -> Unit)? = null,
) {
    if (items.isEmpty()) return

    val menu = LocalNaviampItemMenu.current ?: remember { NaviampItemMenuState() }
    Box(modifier = modifier) {
        if (button != null) {
            button(menu::toggleFromButton)
        } else {
            NaviampTooltip(contentDescription, colors) {
                IconButton(
                    onClick = menu::toggleFromButton,
                    modifier = Modifier
                        .size(buttonSize)
                        .clip(RoundedCornerShape(buttonSize / 2))
                        .background(if (selected) colors.primaryText.copy(alpha = 0.14f) else Color.Transparent),
                ) {
                    Icon(
                        imageVector = NaviampTransportIcons.MoreVertical,
                        contentDescription = contentDescription,
                        tint = if (selected) colors.primaryText else colors.mutedText,
                        modifier = Modifier.size(iconSize),
                    )
                }
            }
        }
        NaviampDropdownMenu(
            expanded = menu.expanded,
            onDismissRequest = menu::dismiss,
            cursorPosition = menu.cursorPosition,
        ) {
            items.forEach { item ->
                if (item.dividerBefore) androidx.compose.material3.HorizontalDivider()
                NaviampDropdownMenuItem(
                    label = item.label,
                    icon = item.icon,
                    enabled = item.enabled,
                    onClick = {
                        menu.dismiss()
                        item.onClick()
                    },
                )
            }
        }
    }
}


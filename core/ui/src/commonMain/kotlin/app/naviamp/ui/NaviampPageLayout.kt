package app.naviamp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.naviamp.ui.generated.resources.Res
import app.naviamp.ui.generated.resources.playlist_item_more_actions
import org.jetbrains.compose.resources.stringResource

@Composable
fun NaviampPageTitle(
    title: String,
    colors: NaviampColors,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = title,
        color = colors.primaryText,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Bold,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .heightIn(min = 40.dp)
            .wrapContentHeight(Alignment.CenterVertically),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NaviampPageHeader(
    title: String,
    colors: NaviampColors,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    FlowRow(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        NaviampPageTitle(
            title = title,
            colors = colors,
            maxLines = 1,
            modifier = Modifier.weight(1f).widthIn(min = 120.dp).heightIn(min = 48.dp).semantics { heading() }
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

@Composable
internal fun NaviampPageHeaderAction(
    icon: ImageVector,
    contentDescription: String,
    colors: NaviampColors,
    enabled: Boolean = true,
    selected: Boolean? = null,
    stateDescription: String? = null,
    onClick: () -> Unit,
) {
    NaviampTooltip(contentDescription, colors) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (selected == true) colors.accent else Color.Transparent,
                contentColor = if (selected == true) colors.onAccent else colors.primaryText,
                disabledContentColor = colors.primaryText.copy(alpha = 0.38f),
            ),
            modifier = Modifier.size(48.dp).semantics {
                if (selected != null) this.selected = selected
                if (stateDescription != null) this.stateDescription = stateDescription
            },
        ) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
internal fun NaviampPageHeaderOverflow(
    colors: NaviampColors,
    items: List<NaviampRowMenuItem>,
) {
    val label = stringResource(Res.string.playlist_item_more_actions)
    NaviampRowOverflowMenu(
        colors = colors,
        items = items,
        contentDescription = label,
        button = { onClick ->
            NaviampPageHeaderAction(
                icon = NaviampTransportIcons.MoreVertical,
                contentDescription = label,
                colors = colors,
                onClick = onClick,
            )
        },
    )
}

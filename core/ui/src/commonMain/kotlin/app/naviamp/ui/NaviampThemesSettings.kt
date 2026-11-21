package app.naviamp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.naviamp.domain.settings.InterfaceSettings
import app.naviamp.domain.settings.NaviampBuiltinTheme
import app.naviamp.domain.settings.NaviampForestTheme
import app.naviamp.domain.settings.NaviampThemeMode
import app.naviamp.domain.settings.NaviampThemePack
import app.naviamp.domain.settings.NaviampThemePalette
import app.naviamp.domain.settings.NaviampThemes
import app.naviamp.ui.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun NaviampThemesSettings(
    colors: NaviampColors,
    interfaceSettings: InterfaceSettings,
    onInterfaceSettingsChanged: (InterfaceSettings) -> Unit,
) {
    val theme = remember(interfaceSettings.theme) { interfaceSettings.theme.normalized() }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingsSectionTitle(stringResource(Res.string.settings_themes_mode), colors)
        Column(Modifier.selectableGroup()) {
            NaviampThemeMode.entries.forEach { mode ->
                SelectableSettingsRow(
                    colors = colors,
                    title = stringResource(when (mode) {
                        NaviampThemeMode.Light -> Res.string.settings_themes_light
                        NaviampThemeMode.Dark -> Res.string.settings_themes_dark
                        NaviampThemeMode.System -> Res.string.settings_themes_system
                    }),
                    selected = theme.mode == mode,
                    onClick = {
                        onInterfaceSettingsChanged(interfaceSettings.copy(theme = theme.copy(mode = mode)))
                    },
                )
            }
        }
        SettingsSectionTitle(stringResource(Res.string.settings_themes_catalog), colors)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NaviampThemes.packs.forEach { pack ->
                key(pack.id) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SelectableSettingsRow(
                            colors = colors,
                            title = when (pack.id) {
                                NaviampBuiltinTheme.id -> stringResource(Res.string.settings_themes_builtin)
                                NaviampForestTheme.id -> stringResource(Res.string.settings_themes_forest)
                                else -> pack.name
                            },
                            subtitle = pack.author,
                            selected = theme.selectedPackId == pack.id,
                            onClick = {
                                onInterfaceSettingsChanged(interfaceSettings.copy(
                                    theme = theme.copy(selectedPackId = pack.id),
                                ))
                            },
                        )
                        ThemePackSwatches(pack, colors)
                    }
                }
            }
        }
        NaviampPlayerBackgroundSettings(colors, interfaceSettings, onInterfaceSettingsChanged)
    }
}

@Composable
private fun ThemePackSwatches(pack: NaviampThemePack, colors: NaviampColors) {
    ThemePaletteSwatches(stringResource(Res.string.settings_themes_light), pack.light, colors)
    ThemePaletteSwatches(stringResource(Res.string.settings_themes_dark), pack.dark, colors)
}

@Composable
private fun ThemePaletteSwatches(label: String, palette: NaviampThemePalette, colors: NaviampColors) {
    val previewColors = remember(palette) { palette.toNaviampColors() }
    val swatches = remember(previewColors) {
        listOf(previewColors.primaryText, previewColors.secondaryText, previewColors.accent, previewColors.controlSurface)
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(previewColors.background, RoundedCornerShape(6.dp))
            .border(1.dp, colors.border, RoundedCornerShape(6.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = previewColors.primaryText, modifier = Modifier.weight(1f), fontSize = 13.sp)
        swatches.forEach { color ->
            Box(Modifier.size(20.dp).background(color, CircleShape).border(1.dp, previewColors.border, CircleShape))
        }
    }
}

@Composable
internal fun NaviampThemeModeAction(
    colors: NaviampColors,
    interfaceSettings: InterfaceSettings,
    onInterfaceSettingsChanged: (InterfaceSettings) -> Unit,
) {
    val mode = interfaceSettings.theme.mode
    NaviampPageHeaderAction(
        icon = when (mode) {
            NaviampThemeMode.Light -> NaviampIcons.Sun
            NaviampThemeMode.Dark -> NaviampIcons.Moon
            NaviampThemeMode.System -> NaviampIcons.BrightnessAuto
        },
        contentDescription = stringResource(Res.string.settings_themes_mode),
        stateDescription = stringResource(when (mode) {
            NaviampThemeMode.Light -> Res.string.settings_themes_light
            NaviampThemeMode.Dark -> Res.string.settings_themes_dark
            NaviampThemeMode.System -> Res.string.settings_themes_system
        }),
        colors = colors,
        onClick = {
            val nextMode = when (mode) {
                NaviampThemeMode.Light -> NaviampThemeMode.Dark
                NaviampThemeMode.Dark -> NaviampThemeMode.System
                NaviampThemeMode.System -> NaviampThemeMode.Light
            }
            onInterfaceSettingsChanged(interfaceSettings.copy(theme = interfaceSettings.theme.copy(mode = nextMode)))
        },
    )
}

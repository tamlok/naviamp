package app.naviamp.domain.settings

import kotlinx.serialization.Serializable

@Serializable
enum class NaviampThemeMode { Light, Dark, System }

data class NaviampThemePalette(
    val background: String,
    val backgroundWarm: String,
    val backgroundOlive: String,
    val primaryText: String,
    val secondaryText: String,
    val mutedText: String,
    val border: String,
    val accent: String,
    val onAccent: String,
    val controlSurface: String,
    val playerSurface: String?,
    val playerSecondaryText: String,
    val playerMutedText: String,
    val albumArtPlaceholder: String,
    val favorite: String,
    val menuSurface: String,
    val menuText: String,
    val error: String,
    val onError: String,
    val errorContainer: String,
    val onErrorContainer: String,
    val success: String,
    val onSuccess: String,
    val playerSurfaceOpacityPercent: Int = 100,
)

data class NaviampThemePack(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val author: String? = null,
    val light: NaviampThemePalette,
    val dark: NaviampThemePalette,
)

@Serializable
data class NaviampThemeSettings(
    val mode: NaviampThemeMode = NaviampThemeMode.Dark,
    val selectedPackId: String = NaviampBuiltinTheme.id,
) {
    fun normalized(catalog: NaviampThemeCatalog = NaviampThemes): NaviampThemeSettings =
        if (catalog.find(selectedPackId) != null) this else copy(selectedPackId = NaviampBuiltinTheme.id)
}

class NaviampThemeCatalog(definitions: List<NaviampThemePack>) {
    val packs: List<NaviampThemePack> = object : AbstractList<NaviampThemePack>() {
        private val registered = definitions.toList()
        override val size: Int get() = registered.size
        override fun get(index: Int): NaviampThemePack = registered[index]
    }
    private val byId: Map<String, NaviampThemePack>

    init {
        val index = HashMap<String, NaviampThemePack>(packs.size)
        for (pack in packs) {
            pack.validate()
            require(index.put(pack.id, pack) == null) { "Duplicate theme ID: ${pack.id}" }
        }
        require(index[NaviampBuiltinTheme.id] == NaviampBuiltinTheme) { "The builtin theme must be registered unchanged" }
        byId = index
    }

    fun find(id: String): NaviampThemePack? = byId[id]

    fun selectedPack(id: String): NaviampThemePack = find(id) ?: NaviampBuiltinTheme
}

data class NaviampResolvedTheme(
    val pack: NaviampThemePack,
    val isDark: Boolean,
    val palette: NaviampThemePalette,
)

fun resolveNaviampTheme(
    settings: NaviampThemeSettings,
    systemDark: Boolean,
    catalog: NaviampThemeCatalog = NaviampThemes,
): NaviampResolvedTheme {
    val pack = catalog.selectedPack(settings.selectedPackId)
    val dark = when (settings.mode) {
        NaviampThemeMode.Light -> false
        NaviampThemeMode.Dark -> true
        NaviampThemeMode.System -> systemDark
    }
    return NaviampResolvedTheme(pack, dark, if (dark) pack.dark else pack.light)
}

val NaviampBuiltinTheme = NaviampThemePack(
    schemaVersion = 1,
    id = "naviamp",
    name = "Naviamp",
    light = NaviampThemePalette(
        background = "#F8F9FB",
        backgroundWarm = "#EAE1DC",
        backgroundOlive = "#E9EEE4",
        primaryText = "#171A21",
        secondaryText = "#4F5663",
        mutedText = "#727A86",
        border = "#BAC1CC",
        accent = "#315D9E",
        onAccent = "#FFFFFF",
        controlSurface = "#FFFFFF",
        playerSurface = null,
        playerSecondaryText = "#272C35",
        playerMutedText = "#353C47",
        albumArtPlaceholder = "#D3DBE8",
        favorite = "#EF5350",
        menuSurface = "#FFFFFF",
        menuText = "#171A21",
        error = "#BA1A1A",
        onError = "#FFFFFF",
        errorContainer = "#FFDAD6",
        onErrorContainer = "#410002",
        success = "#176B3A",
        onSuccess = "#FFFFFF",
    ),
    dark = NaviampThemePalette(
        background = "#2D353B",
        backgroundWarm = "#52231F",
        backgroundOlive = "#11190B",
        primaryText = "#FFFFFF",
        secondaryText = "#B9BDC7",
        mutedText = "#8F96A3",
        border = "#59606D",
        accent = "#315D9E",
        onAccent = "#FFFFFF",
        controlSurface = "#393E46",
        playerSurface = null,
        playerSecondaryText = "#B9BDC7",
        playerMutedText = "#8F96A3",
        albumArtPlaceholder = "#43536B",
        favorite = "#EF5350",
        menuSurface = "#292C36",
        menuText = "#E7E8EF",
        error = "#FFB4AB",
        onError = "#690005",
        errorContainer = "#93000A",
        onErrorContainer = "#FFDAD6",
        success = "#81D99D",
        onSuccess = "#00391A",
    ),
)

private val ThemeIdPattern = Regex("[a-z][a-z0-9-]{0,63}")
private val ThemeColorPattern = Regex("#[0-9a-fA-F]{6}")

private fun NaviampThemePack.validate() {
    require(schemaVersion == 1) { "Unsupported theme schema: $schemaVersion" }
    require(ThemeIdPattern.matches(id)) { "Invalid theme ID: $id" }
    name.validateMetadata(80)
    author?.validateMetadata(120)
    light.validate()
    dark.validate()
}

private fun String.validateMetadata(maxLength: Int) {
    require(length in 1..maxLength && isNotBlank() && none(Char::isISOControl)) { "Invalid theme metadata" }
}

private fun String.validateColor() {
    require(ThemeColorPattern.matches(this)) { "Theme colors must be opaque #RRGGBB values: $this" }
}

private fun NaviampThemePalette.validate() {
    background.validateColor()
    backgroundWarm.validateColor()
    backgroundOlive.validateColor()
    primaryText.validateColor()
    secondaryText.validateColor()
    mutedText.validateColor()
    border.validateColor()
    accent.validateColor()
    onAccent.validateColor()
    controlSurface.validateColor()
    playerSurface?.validateColor()
    require(playerSurfaceOpacityPercent in 0..100) { "Player surface opacity must be between 0 and 100 percent" }
    playerSecondaryText.validateColor()
    playerMutedText.validateColor()
    albumArtPlaceholder.validateColor()
    favorite.validateColor()
    menuSurface.validateColor()
    menuText.validateColor()
    error.validateColor()
    onError.validateColor()
    errorContainer.validateColor()
    onErrorContainer.validateColor()
    success.validateColor()
    onSuccess.validateColor()
}

val NaviampThemes = NaviampThemeCatalog(listOf(NaviampBuiltinTheme, NaviampForestTheme))

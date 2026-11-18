package app.naviamp.domain.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class NaviampThemeTest {
    @Test
    fun explicitModesIgnoreSystemAppearanceAndSystemFollowsBothStates() {
        val custom = NaviampForestTheme.copy(id = "custom-forest", name = "Custom Forest")
        val catalog = NaviampThemeCatalog(listOf(NaviampBuiltinTheme, NaviampForestTheme, custom))
        for (pack in catalog.packs) {
            for (mode in NaviampThemeMode.entries) for (systemDark in listOf(false, true)) {
                val settings = NaviampThemeSettings(mode, pack.id)
                val resolved = resolveNaviampTheme(settings, systemDark, catalog)
                val expectedDark = mode == NaviampThemeMode.Dark || (mode == NaviampThemeMode.System && systemDark)
                assertEquals(expectedDark, resolved.isDark)
                assertSame(pack, resolved.pack)
                assertSame(if (expectedDark) pack.dark else pack.light, resolved.palette)
            }
        }
    }

    @Test
    fun catalogCopiesRegistrationAndPreservesDefinitionsWithoutRevalidatingLookups() {
        val custom = NaviampForestTheme.copy(id = "custom-forest", name = "夜の海", author = "Zoë",
            light = NaviampForestTheme.light.copy(accent = "#a1b2c3"))
        val definitions = mutableListOf(NaviampBuiltinTheme, custom)
        val catalog = NaviampThemeCatalog(definitions)
        definitions.clear()
        assertEquals(listOf(NaviampBuiltinTheme, custom), catalog.packs)
        assertSame(custom, catalog.find(custom.id))
        assertSame(custom, catalog.selectedPack(custom.id))
        assertNull(catalog.find("missing"))
        assertSame(NaviampBuiltinTheme, catalog.selectedPack("missing"))
        val settings = NaviampThemeSettings(NaviampThemeMode.System, custom.id)
        assertSame(settings, settings.normalized(catalog))
    }

    @Test
    fun unknownSelectionFallsBackWithoutChangingAnyMode() {
        val builtinOnly = NaviampThemeCatalog(listOf(NaviampBuiltinTheme))
        for (mode in NaviampThemeMode.entries) {
            val selected = NaviampThemeSettings(mode, NaviampForestTheme.id)
            assertSame(selected, selected.normalized())
            val normalized = selected.normalized(builtinOnly)
            assertEquals(NaviampThemeSettings(mode, NaviampBuiltinTheme.id), normalized)
            assertSame(normalized, normalized.normalized(builtinOnly))
            for (systemDark in listOf(false, true)) {
                val fallback = resolveNaviampTheme(selected, systemDark, builtinOnly)
                assertSame(NaviampBuiltinTheme, fallback.pack)
                val dark = mode == NaviampThemeMode.Dark || (mode == NaviampThemeMode.System && systemDark)
                assertEquals(dark, fallback.isDark)
                assertSame(if (dark) NaviampBuiltinTheme.dark else NaviampBuiltinTheme.light, fallback.palette)
            }
            assertEquals(normalized, selected.copy(selectedPackId = "missing").normalized())
        }
    }

    @Test
    fun catalogRejectsUnsupportedSchemasDuplicateIdsAndMissingOrReplacedBuiltin() {
        for (version in listOf(0, 2, Int.MAX_VALUE)) {
            assertInvalid(NaviampForestTheme.copy(schemaVersion = version))
        }
        assertFailsWith<IllegalArgumentException> {
            NaviampThemeCatalog(listOf(NaviampBuiltinTheme, NaviampForestTheme, NaviampForestTheme.copy(name = "Duplicate")))
        }
        assertFailsWith<IllegalArgumentException> { NaviampThemeCatalog(emptyList()) }
        assertFailsWith<IllegalArgumentException> { NaviampThemeCatalog(listOf(NaviampForestTheme)) }
        assertFailsWith<IllegalArgumentException> {
            NaviampThemeCatalog(listOf(NaviampBuiltinTheme.copy(name = "Replacement")))
        }
    }

    @Test
    fun identifiersAndBoundedMetadataAreValidatedAtRegistration() {
        for (id in listOf("", "Forest", "../pack", "a/b", "a\\b", "a:b", "a.b", "-pack", " pack", "a".repeat(65))) {
            assertInvalid(NaviampForestTheme.copy(id = id))
        }
        for (name in listOf("", "  ", "x".repeat(81), "A\nB", "A\u0000B", "A\u007FB", "A\u0085B", "\tName")) {
            assertInvalid(NaviampForestTheme.copy(name = name))
        }
        for (author in listOf("", "  ", "x".repeat(121), "A\nB", "A\u0000B", "\tAuthor")) {
            assertInvalid(NaviampForestTheme.copy(author = author))
        }
        val boundary = NaviampForestTheme.copy(id = "a".repeat(64), name = "海".repeat(80), author = "é".repeat(120))
        assertSame(boundary, NaviampThemeCatalog(listOf(NaviampBuiltinTheme, boundary)).find(boundary.id))
    }

    @Test
    fun everyNonNullSemanticColorInBothVariantsMustBeCompleteAndOpaque() {
        for (color in listOf("", "#FFF", "#FFFFFFFF", "transparent", "123456", "#GG0000", " #123456")) {
            for (palette in invalidPalettes(NaviampForestTheme.light, color)) {
                assertInvalid(NaviampForestTheme.copy(light = palette))
            }
            for (palette in invalidPalettes(NaviampForestTheme.dark, color)) {
                assertInvalid(NaviampForestTheme.copy(dark = palette))
            }
        }
    }

    private fun assertInvalid(pack: NaviampThemePack) {
        assertFailsWith<IllegalArgumentException> { NaviampThemeCatalog(listOf(NaviampBuiltinTheme, pack)) }
    }

    private fun invalidPalettes(palette: NaviampThemePalette, color: String): List<NaviampThemePalette> = listOf(
        palette.copy(background = color),
        palette.copy(backgroundWarm = color),
        palette.copy(backgroundOlive = color),
        palette.copy(primaryText = color),
        palette.copy(secondaryText = color),
        palette.copy(mutedText = color),
        palette.copy(border = color),
        palette.copy(accent = color),
        palette.copy(onAccent = color),
        palette.copy(controlSurface = color),
        palette.copy(playerSurface = color),
        palette.copy(albumArtPlaceholder = color),
        palette.copy(favorite = color),
        palette.copy(menuSurface = color),
        palette.copy(menuText = color),
        palette.copy(error = color),
        palette.copy(onError = color),
        palette.copy(errorContainer = color),
        palette.copy(onErrorContainer = color),
        palette.copy(success = color),
        palette.copy(onSuccess = color),
    )
}

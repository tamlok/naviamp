package app.naviamp.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NaviampChangelogTest {
    @Test fun release110DescribesLocalizationNoticesAndConnectProfiles() {
        val about = NaviampAboutUi()
        assertEquals("v1.1.0", about.version)
        assertEquals("67", about.buildNumber)
        assertEquals(listOf("Improvements", "Bug fixes", "Upgrade notes", "Known issues", "Thanks"),
            about.changelog.map { it.title })
        val sections = about.changelog.associate { it.title to it.entries }
        val improvements = sections.getValue("Improvements")
        assertTrue(improvements.first().contains("Simplified Chinese"))
        assertTrue(improvements.any { it.contains("Connect") && it.contains("controlling device") })
        val fixes = sections.getValue("Bug fixes")
        assertTrue(fixes.any { it.contains("notice stack") && it.contains("focus-aware") })
        assertTrue(fixes.any { it.contains("gapless") && it.contains("crossfade") })
        assertTrue(fixes.any { it.contains("playlist name") && it.contains("rename") })
        assertTrue(fixes.any { it.contains("A LeTalk Production Based on Naviamp") })
        val upgrades = sections.getValue("Upgrade notes")
        assertTrue(upgrades.any { it.contains("No special upgrade steps") && it.contains("1.0.0") })
        assertTrue(upgrades.any { it.contains("both Connect devices") && it.contains("Older peers") })
        assertTrue(upgrades.any { it.contains("uninstall") && it.contains("build 67") })
        assertTrue(upgrades.any { it.contains("sync schema 2") })
        val limitations = sections.getValue("Known issues")
        assertTrue(limitations.any { it.contains("not publisher-signed") })
        assertTrue(limitations.any { it.contains("Only Windows x64 and Android") })
        val thanks = sections.getValue("Thanks")
        assertTrue(thanks.any { it.contains("@ursasmar") && it.contains("upstream") })
        assertTrue(about.changelog.flatMap { it.entries }.none { it.contains("https://") })
    }
}

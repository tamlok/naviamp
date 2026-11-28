package app.naviamp.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NaviampChangelogTest {
    @Test fun release111DescribesConnectConsentOfflineRecoveryAndBackupCompatibility() {
        val about = NaviampAboutUi()
        assertEquals("v1.1.1", about.version)
        assertEquals("68", about.buildNumber)
        assertEquals(listOf("Improvements", "Bug fixes", "Upgrade notes", "Known issues", "Thanks"),
            about.changelog.map { it.title })
        val sections = about.changelog.associate { it.title to it.entries }
        val improvements = sections.getValue("Improvements")
        assertTrue(improvements.first().contains("six-digit code") && improvements.first().contains("active account"))
        assertTrue(improvements.any { it.contains("Offline Mode") && it.contains("Android reports no network") && it.contains("without interrupting") })
        val fixes = sections.getValue("Bug fixes")
        assertTrue(fixes.any { it.contains("Android backups") && it.contains("Keystore") })
        val upgrades = sections.getValue("Upgrade notes")
        assertTrue(upgrades.any { it.contains("No special installation steps") && it.contains("1.1.0") })
        assertTrue(upgrades.any { it.contains("both devices to 1.1.1") && it.contains("older devices") })
        assertTrue(upgrades.any { it.contains("only to a device you trust") && it.contains("without another approval") })
        assertTrue(upgrades.any { it.contains("fresh code") && it.contains("duplicate device entries") })
        assertTrue(upgrades.any { it.contains("uninstall") && it.contains("build 68") })
        assertTrue(upgrades.any { it.contains("sync schema 2") })
        val limitations = sections.getValue("Known issues")
        assertTrue(limitations.any { it.contains("not publisher-signed") })
        assertTrue(limitations.any { it.contains("Only Windows x64 and Android") })
        assertTrue(limitations.any { it.contains("Windows startup") && it.contains("provider timeouts") })
        val thanks = sections.getValue("Thanks")
        assertTrue(thanks.any { it.contains("@ursasmar") && it.contains("upstream Connect and offline-recovery") && it.contains("testing the Connect flow") })
        assertTrue(about.changelog.flatMap { it.entries }.none { it.contains("https://") })
    }
}

package app.naviamp.domain.app

import app.naviamp.domain.network.NaviampApplicationId
import app.naviamp.domain.network.NaviampClientName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class NaviampApplicationIdentityTest {
    @Test
    fun releaseAndDevelopmentStorageRemainIndependentOfEachOtherAndUpstream() {
        val release = naviampApplicationProfileId(development = false)
        val development = naviampApplicationProfileId(development = true)

        assertEquals("cn.com.letalk.navo", release)
        assertEquals("$release.development", development)
        assertNotEquals(release, development)
        val upstreamDirectories = setOf("Naviamp", "Naviamp Development", "naviamp", "naviamp-development")
        assertFalse(release in upstreamDirectories)
        assertFalse(development in upstreamDirectories)
    }

    @Test
    fun linuxDesktopEntryMatchesTheInstalledNativeOrFlatpakLauncher() {
        assertEquals("$NaviampApplicationId-$NaviampClientName", naviampLinuxDesktopEntryId(null))
        val flatpakVariant = "$NaviampApplicationId.preview"
        assertEquals(flatpakVariant, naviampLinuxDesktopEntryId(flatpakVariant))
    }
}

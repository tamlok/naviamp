package app.naviamp.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NaviampDownloadStatusTest {
    @Test
    fun keepDownloadedStatusesUseTheCollectionNameAndPreferRealErrors() {
        assertEquals(
            "Road trip will no longer be kept downloaded. Existing files were kept.",
            keepDownloadedDisabledStatus("Road trip"),
        )
        assertEquals(
            "Could not keep Road trip downloaded.",
            keepDownloadedErrorStatus("Road trip", IllegalStateException()),
        )
        assertEquals(
            "Server unavailable",
            keepDownloadedRefreshErrorStatus("Road trip", IllegalStateException("Server unavailable")),
        )
    }

    @Test
    fun maintenanceStatusesKeepCountsAndSingularPluralNouns() {
        for (count in 0..2) {
            val noun = if (count == 1) "download" else "downloads"
            assertTrue(Regex("\\b$count $noun\\b").containsMatchIn(downloadsDeletedStatus(count)))
            if (count > 0) {
                assertTrue(Regex("\\b$count missing $noun\\b").containsMatchIn(downloadsRefreshStatus(count)))
            }
        }
    }
}

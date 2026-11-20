package app.naviamp.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class NaviampPlaybackTimeLabelTest {
    @Test fun clockPadsMinutesAndUsesCompletedSeconds() {
        assertEquals("01:01", 61.9.paddedDurationLabel())
        assertEquals("05:38", 338.0.paddedDurationLabel())
        assertEquals("00:00", 0.0.paddedDurationLabel())
        assertEquals("00:00", (-10.0).paddedDurationLabel())
        assertEquals("100:00", 6000.0.paddedDurationLabel())
    }

    @Test fun unknownAndNonFiniteTimesStayUnknown() {
        val unknown: Double? = null
        assertEquals("--:--", unknown.paddedDurationLabel())
        assertEquals("--:--", Double.NaN.paddedDurationLabel())
        assertEquals("--:--", Double.POSITIVE_INFINITY.paddedDurationLabel())
        assertEquals("--:--", Double.NEGATIVE_INFINITY.paddedDurationLabel())
    }
}

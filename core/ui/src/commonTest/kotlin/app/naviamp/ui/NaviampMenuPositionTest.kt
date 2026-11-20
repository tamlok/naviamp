package app.naviamp.ui

import androidx.compose.ui.unit.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NaviampMenuPositionTest {
    @Test fun menuUsesAnchorThenFlipsWhenItWouldLeaveTheViewport() {
        val window = IntSize(1000, 740)
        val content = IntSize(220, 200)
        assertEquals(IntOffset(100, 140), naviampMenuPosition(IntRect(100, 100, 140, 140),
            window, content, LayoutDirection.Ltr, IntOffset.Zero, 48))
        assertEquals(IntOffset(740, 400), naviampMenuPosition(IntRect(920, 600, 960, 640),
            window, content, LayoutDirection.Ltr, IntOffset.Zero, 48))
    }

    @Test fun rtlOffsetsFollowTheStartEdge() {
        assertEquals(IntOffset(212, 147), naviampMenuPosition(IntRect(400, 100, 440, 140),
            IntSize(1000, 740), IntSize(220, 200), LayoutDirection.Rtl, IntOffset(8, 7), 48))
    }

    @Test fun smallOrResizedWindowsNeverProduceNegativePlacement() {
        for (direction in LayoutDirection.entries) {
            val position = naviampMenuPosition(IntRect(920, 600, 960, 640), IntSize(160, 100),
                IntSize(220, 200), direction, IntOffset.Zero, 48)
            assertTrue(position.x >= 0 && position.y >= 0)
        }
    }

    @Test fun cursorAnchorUsesThePointerAndFlipsAtViewportEdges() {
        val window = IntSize(1000, 740)
        val content = IntSize(220, 200)
        fun position(cursor: IntOffset, direction: LayoutDirection = LayoutDirection.Ltr) =
            naviampMenuPosition(IntRect(cursor, IntSize.Zero), window, content, direction, IntOffset.Zero, 0)
        assertEquals(IntOffset(100, 12), position(IntOffset(100, 12)))
        assertEquals(IntOffset(770, 530), position(IntOffset(990, 730)))
        assertEquals(IntOffset(280, 120), position(IntOffset(500, 120), LayoutDirection.Rtl))
        assertEquals(IntOffset(10, 120), position(IntOffset(10, 120), LayoutDirection.Rtl))
    }

    @Test fun cursorAnchorStaysInBoundsAfterWindowShrinks() {
        val position = naviampMenuPosition(IntRect(IntOffset(990, 730), IntSize.Zero),
            IntSize(300, 240), IntSize(220, 200), LayoutDirection.Ltr, IntOffset.Zero, 0)
        assertEquals(IntOffset(80, 40), position)
    }

    @Test fun buttonOpeningClearsCursorWhileDismissalRetainsItsExitAnchor() {
        val menu = NaviampItemMenuState()
        val cursor = IntOffset(120, 80)
        menu.openAt(cursor)
        assertTrue(menu.expanded)
        menu.dismiss()
        assertFalse(menu.expanded)
        assertEquals(cursor, menu.cursorPosition)
        menu.toggleFromButton()
        assertTrue(menu.expanded)
        assertNull(menu.cursorPosition)
        menu.toggleFromButton()
        assertFalse(menu.expanded)
    }
}

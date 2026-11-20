package app.naviamp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class NaviampRatingRowTest {
    @Test fun starsSetClearAndRespectCapabilityWithStaleMetadata() = runComposeUiTest {
        val rate = mutableStateOf(true)
        val rating = mutableStateOf<Int?>(4)
        val ratings = mutableListOf<Int?>()
        setContent { MaterialTheme { RatingRow(rating.value, rate.value, NaviampColors.Dark,
            { ratings += it; rating.value = it }) } }
        onNodeWithContentDescription("3 star rating").performClick()
        onNodeWithContentDescription("3 star rating").performClick()
        assertEquals(listOf<Int?>(3, null), ratings)
        runOnIdle { rate.value = false; rating.value = 4 }
        (1..5).forEach { onNodeWithContentDescription("$it star rating").assertDoesNotExist() }
    }
}

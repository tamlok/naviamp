package app.naviamp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import app.naviamp.domain.home.HomeContent
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class NaviampHomeFavoriteSongsUiTest {
    @Test
    fun compactHomeRetainsThemeAndFavoritesActionsAndAllowsRetryAfterFailure() = runDesktopComposeUiTest(280, 500) {
        val home = mutableStateOf(NaviampHomeScreenUi(supportsFavoriteSongs = true))
        var attempts = 0
        val actions = favoriteSongsActions {
            attempts++
            home.value = home.value.copy(favoriteSongsPlaybackStatus = FavoriteSongsPlaybackStatus.Loading)
        }
        setContent {
            MaterialTheme {
                SharedHomeRoute(NaviampColors.Dark, home.value, actions, NaviampMediaActions({}, {}),
                    DiscoverySamplePlaybackUi(), headerActions = {
                        NaviampPageHeaderAction(NaviampIcons.Settings, "Theme", NaviampColors.Dark, onClick = {})
                    })
            }
        }
        onNodeWithContentDescription("Play favorite songs").assertIsDisplayed().performClick()
        onNodeWithContentDescription("Play favorite songs").assertIsNotEnabled()
        onNodeWithContentDescription("Theme").assertIsDisplayed()
        runOnIdle { home.value = home.value.copy(favoriteSongsPlaybackStatus = FavoriteSongsPlaybackStatus.Failed) }
        onNodeWithText("Could not load favorite songs. Try again.").assertIsDisplayed()
        onNodeWithContentDescription("Play favorite songs").assertIsEnabled().performClick()
        runOnIdle {
            assertEquals(2, attempts)
            home.value = home.value.copy(supportsFavoriteSongs = false)
        }
        onNodeWithContentDescription("Play favorite songs").assertDoesNotExist()
        onNodeWithText("Could not load favorite songs. Try again.").assertDoesNotExist()
    }

    @Test
    fun televisionRetainsSectionEntryFocusAndCanReachFavoriteSongs() = runDesktopComposeUiTest(1000, 650) {
        val home = NaviampHomeScreenUi(
            content = HomeContent().toSharedHomeUi(coverArtUrl = { null }),
            supportsFavoriteSongs = true,
        )
        var played = false
        setContent {
            MaterialTheme {
                TelevisionHome(home, NaviampColors.Dark, favoriteSongsActions { played = true },
                    NaviampMediaActions({}, {}), DiscoverySamplePlaybackUi(), entryFocusGeneration = 1)
            }
        }
        onNodeWithContentDescription("Play favorite songs").assertIsDisplayed().assertIsNotFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        onNodeWithContentDescription("Play favorite songs").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        runOnIdle { kotlin.test.assertTrue(played) }
    }
}

private fun favoriteSongsActions(play: () -> Unit) = NaviampHomeActions(
    onRefresh = {}, onRecentRadioSelected = {}, onInternetRadioStationSelected = {},
    onMixBuilderSelected = {}, onStationSelected = {}, onSonicDiscoveryTrackAction = {},
    onRecentlyPlayedTrackAction = {}, onCollectionSelected = {}, onCollectionBack = {},
    onCollectionPageLayoutChanged = { _, _ -> }, onPlayFavoriteSongs = play,
)

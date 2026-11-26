package app.naviamp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.LayoutDirection
import app.naviamp.domain.network.NaviampClientName
import app.naviamp.domain.settings.InterfaceLanguage
import app.naviamp.ui.generated.resources.Res
import app.naviamp.ui.generated.resources.settings_language_use_subtitle
import app.naviamp.ui.generated.resources.playlist_item_more_actions
import app.naviamp.ui.generated.resources.playlist_item_unfavorite
import app.naviamp.ui.generated.resources.tv_favorite
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import app.naviamp.domain.playback.SleepTimerTarget
import app.naviamp.ui.generated.resources.ui_remove_album_favorite
import app.naviamp.ui.generated.resources.ui_favorite_artist
import app.naviamp.ui.generated.resources.download_stop_keeping_collection
import app.naviamp.ui.generated.resources.ui_sleep_after_track
import app.naviamp.ui.generated.resources.ui_sleep_in_duration
import app.naviamp.ui.generated.resources.ui_timer_minutes_seconds
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalTestApi::class)
class NaviampLanguageResourcesUiTest {
    // Check actual localized output, including Android's legacy Indonesian locale code.
    private val expected = mapOf(
        "en" to "Use this language for $NaviampClientName",
        "ar" to "استخدم هذه اللغة في $NaviampClientName",
        "de" to "Diese Sprache für $NaviampClientName verwenden",
        "es" to "Usar este idioma en $NaviampClientName",
        "fr" to "Utiliser cette langue pour $NaviampClientName",
        "hi" to "$NaviampClientName के लिए इस भाषा का उपयोग करें",
        "id" to "Gunakan bahasa ini untuk $NaviampClientName",
        "it" to "Usa questa lingua per $NaviampClientName",
        "ja" to "$NaviampClientName でこの言語を使用します",
        "ko" to "${NaviampClientName}에 이 언어를 사용합니다",
        "nl" to "Gebruik deze taal voor $NaviampClientName",
        "pl" to "Używaj tego języka w $NaviampClientName",
        "pt-BR" to "Usar este idioma no $NaviampClientName",
        "ru" to "Использовать этот язык в $NaviampClientName",
        "tr" to "$NaviampClientName için bu dili kullan",
        "uk" to "Використовувати цю мову в $NaviampClientName",
        "zh-CN" to "在 $NaviampClientName 中使用此语言",
    )

    @Test fun everyLanguageResolvesItsCatalogWithoutRecreatingUiOrLosingFocus() = runComposeUiTest {
        var language by mutableStateOf(InterfaceLanguage.English)
        var creations = 0
        var direction: LayoutDirection? = null
        val effect = createNaviampLocaleEffect()
        setContent {
            NaviampLocaleEnvironment(language, effect) {
                remember { creations++ }
                direction = LocalLayoutDirection.current
                Column {
                    Text(stringResource(Res.string.settings_language_use_subtitle), Modifier.testTag("localized"))
                    Button(onClick = {}) { Text("focus anchor") }
                }
            }
        }
        onNodeWithText("focus anchor").performSemanticsAction(SemanticsActions.RequestFocus)
        InterfaceLanguage.entries.filter { it.languageTag != null }.forEach { next ->
            runOnIdle { language = next }
            onNodeWithTag("localized").assertTextEquals(expected.getValue(next.languageTag!!))
            onNodeWithText("focus anchor").assertIsFocused()
            runOnIdle {
                assertEquals(1, creations)
                assertEquals(if (next == InterfaceLanguage.Arabic) LayoutDirection.Rtl else LayoutDirection.Ltr, direction)
            }
        }
    }

    @Test fun openPlaylistMenuRelocalizesWithoutChangingMediaOrActions() = runComposeUiTest {
        var language by mutableStateOf(InterfaceLanguage.English)
        val original = SharedTrackRowUi("unchanged-id", "Night Drive 夜行", "Artist 艺人", favoriteActive = true)
        var track by mutableStateOf(original)
        val requests = mutableListOf<SharedTrackRowActionRequest>()
        val effect = createNaviampLocaleEffect()
        setContent {
            NaviampLocaleEnvironment(language, effect) {
                StandardPlaylistManagementList(
                    colors = NaviampColors(),
                    initialTracks = listOf(track),
                    onTrackSelected = {},
                    onSave = {},
                    onTrackAction = { request ->
                        requests += request
                        track = track.copy(favoriteActive = !track.favoriteActive)
                    },
                )
            }
        }
        val moreActions = runBlocking { getString(Res.string.playlist_item_more_actions) }
        val englishUnfavorite = runBlocking { getString(Res.string.playlist_item_unfavorite) }
        onNodeWithContentDescription(moreActions).performClick()
        onNodeWithText(englishUnfavorite).assertIsDisplayed()
        runOnIdle { language = InterfaceLanguage.ChineseSimplified }
        waitForIdle()
        val chineseUnfavorite = runBlocking { getString(Res.string.playlist_item_unfavorite) }
        onNodeWithText(englishUnfavorite).assertDoesNotExist()
        onNodeWithText(chineseUnfavorite).assertIsDisplayed().performClick()
        runOnIdle {
            assertEquals(SharedTrackRowAction.ToggleFavorite, requests.single().action)
            assertEquals(original, requests.single().track)
        }
        onNodeWithText(original.title).assertIsDisplayed()
        onNodeWithText(original.subtitle).assertIsDisplayed()
        val chineseMoreActions = runBlocking { getString(Res.string.playlist_item_more_actions) }
        val chineseFavorite = runBlocking { getString(Res.string.tv_favorite) }
        onNodeWithContentDescription(chineseMoreActions).performClick()
        onNodeWithText(chineseFavorite).assertIsDisplayed()
        onNodeWithText(chineseUnfavorite).assertDoesNotExist()
    }

    @Test fun cachedActionVariantsAndTimerDurationFollowLiveLanguageChanges() = runComposeUiTest {
        var language by mutableStateOf(InterfaceLanguage.English)
        val effect = createNaviampLocaleEffect()
        val examples = listOf(
            albumRowActions(canFavorite = true, favoriteActive = true).single() to Res.string.ui_remove_album_favorite,
            artistRowActions(canFavorite = true).single() to Res.string.ui_favorite_artist,
            playlistRowActions(canKeepDownloaded = true, keepDownloadedActive = true).single() to Res.string.download_stop_keeping_collection,
            NaviampActionSpec(NaviampAction.SleepTimer,
                sleepTimer = NaviampSleepTimerUi(active = true, target = SleepTimerTarget.TrackEnd)) to Res.string.ui_sleep_after_track,
        )
        val duration = NaviampActionSpec(NaviampAction.SleepTimer,
            sleepTimer = NaviampSleepTimerUi(active = true, target = SleepTimerTarget.Duration, remainingMillis = 61_000))
        setContent {
            NaviampLocaleEnvironment(language, effect) {
                Column {
                    examples.forEachIndexed { index, (action, _) -> Text(action.localizedLabel(), Modifier.testTag("variant-$index")) }
                    Text(duration.localizedLabel(), Modifier.testTag("duration"))
                }
            }
        }
        listOf(InterfaceLanguage.English, InterfaceLanguage.ChineseSimplified, InterfaceLanguage.English).forEach { next ->
            runOnIdle { language = next }
            waitForIdle()
            examples.forEachIndexed { index, (_, resource) ->
                onNodeWithTag("variant-$index").assertTextEquals(runBlocking { getString(resource) })
            }
            val expectedDuration = runBlocking {
                getString(Res.string.ui_sleep_in_duration, getString(Res.string.ui_timer_minutes_seconds, 1L, "01"))
            }
            onNodeWithTag("duration").assertTextEquals(expectedDuration)
        }
    }

    @Test fun explicitLanguageSurvivesTheHostRestoringTheSystemLocale() {
        val system = Locale.getDefault()
        Locale.setDefault(Locale.GERMAN)
        try {
            runComposeUiTest {
                var hostConfiguration by mutableStateOf(0)
                var dialogOpen by mutableStateOf(false)
                val native = createNaviampLocaleEffect()
                val effect = object : NaviampLocaleEffect {
                    override fun applyLanguage(languageTag: String?) = native.applyLanguage(languageTag)
                    @Composable override fun nativeLocaleRevision(): Any? = hostConfiguration
                }
                setContent {
                    NaviampLocaleEnvironment(InterfaceLanguage.English, effect) {
                        Column {
                            // Resource lookups also observe the host configuration, as Android's dark theme query does.
                            hostConfiguration
                            Text(stringResource(Res.string.settings_language_use_subtitle), Modifier.testTag("screen"))
                            if (dialogOpen) {
                                Text(stringResource(Res.string.settings_language_use_subtitle), Modifier.testTag("dialog"))
                            }
                        }
                    }
                }
                onNodeWithTag("screen").assertTextEquals(expected.getValue("en"))
                runOnIdle {
                    // Android restores the system LocaleList on every process configuration change.
                    Locale.setDefault(Locale.GERMAN)
                    hostConfiguration++
                    dialogOpen = true
                }
                onNodeWithTag("screen").assertTextEquals(expected.getValue("en"))
                onNodeWithTag("dialog").assertTextEquals(expected.getValue("en"))
            }
        } finally { Locale.setDefault(system) }
    }
}

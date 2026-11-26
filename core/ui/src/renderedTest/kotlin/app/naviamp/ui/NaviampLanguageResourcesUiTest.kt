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

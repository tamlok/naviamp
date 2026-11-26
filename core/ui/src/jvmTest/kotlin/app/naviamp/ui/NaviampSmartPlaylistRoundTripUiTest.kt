package app.naviamp.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import app.naviamp.domain.smartplaylist.SmartPlaylistDefinition
import app.naviamp.domain.smartplaylist.SmartPlaylistDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import app.naviamp.domain.settings.InterfaceLanguage
import app.naviamp.domain.smartplaylist.SmartPlaylistTemplates
import app.naviamp.ui.generated.resources.Res
import app.naviamp.ui.generated.resources.smart_playlist_use_template
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString

@OptIn(ExperimentalTestApi::class)
class NaviampSmartPlaylistRoundTripUiTest {
    @Test
    fun switchingAnInvalidTrackLimitToUnlimitedRestoresSaving() = runComposeUiTest {
        var saved: SmartPlaylistDefinition? = null
        setContent {
            SmartPlaylistBuilderDialog(colors = NaviampColors(), initialDraft = imported().copy(
                limitMode = app.naviamp.domain.smartplaylist.SmartPlaylistLimitMode.TrackCount, limit = 0),
                onDismissRequest = {}, onSave = { saved = it })
        }
        onNodeWithTag(SmartPlaylistSaveTestTag).assertIsNotEnabled()
        onNodeWithText("Track count").performScrollTo().performClick()
        onNodeWithText("Unlimited").performClick()
        onNodeWithTag(SmartPlaylistSaveTestTag).assertIsEnabled().performClick()
        waitUntil(timeoutMillis = 5_000) { saved != null }
        assertNull(saved!!.limit)
        assertNull(saved!!.limitPercent)
    }
    private fun imported(properties: String = "") = SmartPlaylistDraft.fromDefinition(
        SmartPlaylistDefinition.fromNspJson("""{"name":"Mix","all":[{"is":{"loved":true}}]$properties}"""),
    )

    @Test
    fun unlimitedImportShowsUnlimitedAndSavesWithoutImplicitLimitOrSort() = runComposeUiTest {
        var saved: SmartPlaylistDefinition? = null
        setContent {
            SmartPlaylistBuilderDialog(colors = NaviampColors(), initialDraft = imported(),
                onDismissRequest = {}, onSave = { saved = it })
        }
        onNodeWithText("Unlimited").performScrollTo().assertExists()
        assertEquals(0, onAllNodesWithText("Track limit").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText("Limit percent").fetchSemanticsNodes().size)
        onNodeWithTag(SmartPlaylistSaveTestTag).performClick()
        waitUntil(timeoutMillis = 5_000) { saved != null }
        assertNull(saved!!.limit)
        assertNull(saved!!.limitPercent)
        assertTrue(saved!!.sort.isEmpty())
    }

    @Test
    fun lastSortCanBeRemovedAndTrackLimitCanBeChangedToUnlimited() = runComposeUiTest {
        var saved: SmartPlaylistDefinition? = null
        setContent {
            SmartPlaylistBuilderDialog(colors = NaviampColors(), initialDraft = imported(",\"sort\":\"-rating\",\"limit\":25"),
                onDismissRequest = {}, onSave = { saved = it })
        }
        onNodeWithText("Remove").performScrollTo().performClick()
        onNodeWithText("Track count").performScrollTo().performClick()
        onNodeWithText("Unlimited").performClick()
        onNodeWithTag(SmartPlaylistSaveTestTag).performClick()
        waitUntil(timeoutMillis = 5_000) { saved != null }
        assertNull(saved!!.limit)
        assertTrue(saved!!.sort.isEmpty())
    }

    @Test
    fun unrepresentableImportIsInspectableAndKeepsTheCurrentDraft() = runComposeUiTest {
        var saved: SmartPlaylistDefinition? = null
        setContent {
            SmartPlaylistBuilderDialog(colors = NaviampColors(), initialDraft = imported(),
                onDismissRequest = {}, onSave = { saved = it })
        }
        onNodeWithTag("smart-playlist-advanced-toggle").performScrollTo().performClick()
        onNodeWithText("Import JSON").performScrollTo().performClick()
        onNodeWithContentDescription("Paste .nsp or Navidrome smart playlist JSON").performScrollTo().performTextInput(
            """{"name":"Broken","all":[{"gt":{"rating":3.75}}]}""",
        )
        onNodeWithText("Validate and load").performScrollTo().performClick()
        onNodeWithTag("smart-playlist-original-definition").assertExists()
        onNodeWithText("Close").performClick()
        onNodeWithTag(SmartPlaylistSaveTestTag).performClick()
        waitUntil(timeoutMillis = 5_000) { saved != null }
        assertEquals("Mix", saved!!.name)
        assertEquals(imported().toDefinition().rules, saved!!.rules)
    }
    @Test
    fun newTemplateDraftsKeepTheirDefinitionCommentsRatherThanDescriptions() = runComposeUiTest {
        var saved: SmartPlaylistDefinition? = null
        val generation = mutableStateOf(0)
        val effect = createNaviampLocaleEffect()
        setContent {
            NaviampLocaleEnvironment(InterfaceLanguage.English, effect) {
                key(generation.value) {
                    SmartPlaylistBuilderDialog(colors = NaviampColors(),
                        onDismissRequest = {}, onSave = { saved = it })
                }
            }
        }
        val useLabel = runBlocking { getString(Res.string.smart_playlist_use_template) }
        SmartPlaylistTemplates.recommended.forEachIndexed { index, template ->
            if (index > 0) runOnIdle { saved = null; generation.value++ }
            onAllNodesWithText(useLabel)[index].performScrollTo().performClick()
            onNodeWithTag(SmartPlaylistSaveTestTag).performClick()
            waitUntil(timeoutMillis = 5_000) { saved != null }
            assertEquals(template.definition.name, saved!!.name)
            assertEquals(template.definition.comment, saved!!.comment)
            assertEquals(template.definition.rules, saved!!.rules)
        }
    }

    @Test
    fun applyingTemplatesToEditsAndDuplicatesPreservesUserNamesAndComments() = runComposeUiTest {
        var saved: SmartPlaylistDefinition? = null
        val duplicate = mutableStateOf(false)
        val original = imported().copy(name = "Road Trip 夜行", comment = "Personal notes 个人备注")
        val effect = createNaviampLocaleEffect()
        setContent {
            NaviampLocaleEnvironment(InterfaceLanguage.ChineseSimplified, effect) {
                key(duplicate.value) {
                    SmartPlaylistBuilderDialog(colors = NaviampColors(), initialDraft = original,
                        currentPlaylistId = if (duplicate.value) null else "original-id",
                        preserveTemplateIdentity = true,
                        onDismissRequest = {}, onSave = { saved = it })
                }
            }
        }
        val useLabel = runBlocking { getString(Res.string.smart_playlist_use_template) }
        repeat(2) { index ->
            if (index > 0) runOnIdle { saved = null; duplicate.value = true }
            onAllNodesWithText(useLabel)[0].performScrollTo().performClick()
            onNodeWithTag(SmartPlaylistSaveTestTag).performClick()
            waitUntil(timeoutMillis = 5_000) { saved != null }
            assertEquals(original.name, saved!!.name)
            assertEquals(original.comment, saved!!.comment)
            assertEquals(SmartPlaylistTemplates.recommended.first().definition.rules, saved!!.rules)
        }
    }
}

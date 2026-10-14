package ksqlc.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class BigCellEditorTest {
    @get:Rule val rule = createComposeRule()

    @Test fun prettyJsonIndentsOrRejects() {
        assertEquals("{\n    \"a\": 1,\n    \"b\": [\n        2\n    ]\n}", prettyJson("""{"a":1,"b":[2]}"""))
        assertNull(prettyJson("{oops"))
        kotlin.test.assertTrue(isJsonType("jsonb") && !isJsonType("text"))
    }

    @Test fun formatsJsonAndSaves() {
        var saved: String? = null
        rule.setContent { ksqlc.app.ui.KsqlcTheme(ksqlc.storage.ThemeMode.LIGHT) { BigCellEditor("doc (json)", """{"a":1}""", true, { saved = it }, {}) } }
        rule.onNodeWithTag("big-editor-text").performTextReplacement("{oops")
        rule.onNodeWithTag("big-editor-format").performClick()
        rule.onNodeWithTag("big-editor-error").assertExists()
        rule.onNodeWithTag("big-editor-text").performTextReplacement("""{"a":1}""")
        rule.onNodeWithTag("big-editor-format").performClick()
        rule.onNodeWithTag("big-editor-ok").performClick()
        assertEquals("{\n    \"a\": 1\n}", saved)
    }

    @Test fun plainTextHasNoFormatButton() {
        rule.setContent { ksqlc.app.ui.KsqlcTheme(ksqlc.storage.ThemeMode.LIGHT) { BigCellEditor("t", "x", false, {}, {}) } }
        rule.onNodeWithTag("big-editor-format").assertDoesNotExist()
    }
}

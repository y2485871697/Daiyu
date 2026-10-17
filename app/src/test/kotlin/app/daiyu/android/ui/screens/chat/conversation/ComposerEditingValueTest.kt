package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class ComposerEditingValueTest {
    @Test fun sameTextSelectionChangesPreserveImeComposition() {
        val original = TextFieldValue("compose", TextRange(2, 4), TextRange(1, 5))
        for (range in listOf(TextRange(2, 4), TextRange(4), TextRange(4, 2))) {
            val actual = original.forComposer("compose", range.start, range.end)
            assertEquals(range, actual.selection)
            assertEquals(original.composition, actual.composition)
        }
    }

    @Test fun externalTextReplacementClearsCompositionAndClampsSelection() {
        val original = TextFieldValue("compose", TextRange(2, 4), TextRange(1, 5))
        val actual = original.forComposer("new", -1, 100)
        assertEquals("new", actual.text)
        assertEquals(TextRange(0, 3), actual.selection)
        assertNull(actual.composition)
    }
}

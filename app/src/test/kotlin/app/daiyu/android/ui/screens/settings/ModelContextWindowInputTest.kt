package app.daiyu.android.ui.screens.settings

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class ModelContextWindowInputTest {
    @Test fun existingWindowsOpenInTheAppropriateUnitWithoutRounding() {
        for ((tokens, text, unit) in listOf(
            Triple("", "", ModelContextWindowUnit.K),
            Triple("8192", "8.192", ModelContextWindowUnit.K),
            Triple("128000", "128", ModelContextWindowUnit.K),
            Triple("999999", "999.999", ModelContextWindowUnit.K),
            Triple("1000000", "1", ModelContextWindowUnit.M),
            Triple("1048576", "1.048576", ModelContextWindowUnit.M),
            Triple(Int.MAX_VALUE.toString(), "2147.483647", ModelContextWindowUnit.M),
        )) {
            val input = ModelContextWindowInput.fromTokens(tokens)
            assertEquals(text, input.text)
            assertEquals(unit, input.unit)
            assertEquals(tokens, input.tokenText())
        }
    }

    @Test fun switchingUnitsPreservesTheTokenCountAndStripsTrailingZeros() {
        val input = ModelContextWindowInput("128", ModelContextWindowUnit.K)
        val switched = input.switchUnit()
        assertEquals(ModelContextWindowInput("0.128", ModelContextWindowUnit.M), switched)
        assertEquals("128000", switched.tokenText())
        assertEquals(input, switched.switchUnit())
    }

    @Test fun repeatedSwitchesPreserveNonRoundWindowsAndIntLimit() {
        for (tokens in listOf(1, 999, 8192, 131072, 999999, 1000001, 1048576, Int.MAX_VALUE)) {
            var input = ModelContextWindowInput.fromTokens(tokens.toString())
            repeat(20) {
                input = input.switchUnit()
                assertEquals("tokens=$tokens", tokens.toString(), input.tokenText())
            }
        }
    }

    @Test fun decimalInputNormalizesToTheExistingIntegerTokenContract() {
        for ((text, unit, tokens) in listOf(
            Triple("128", ModelContextWindowUnit.K, "128000"),
            Triple("1.5", ModelContextWindowUnit.M, "1500000"),
            Triple("8.192", ModelContextWindowUnit.K, "8192"),
            Triple("0.001", ModelContextWindowUnit.K, "1"),
            Triple("0.000001", ModelContextWindowUnit.M, "1"),
            Triple("2147.483647", ModelContextWindowUnit.M, Int.MAX_VALUE.toString()),
            Triple("001.5000", ModelContextWindowUnit.M, "1500000"),
        )) assertEquals(tokens, ModelContextWindowInput(text, unit).tokenText())
    }

    @Test fun emptyInputStillMeansAutomaticAfterSwitchingAndClearing() {
        val empty = ModelContextWindowInput.fromTokens("").switchUnit()
        assertEquals("", empty.text)
        assertEquals("", empty.tokenText())
        assertEquals(ModelContextWindowUnit.M, empty.unit)
        assertEquals("", empty.copy(text = "1.5").copy(text = "").tokenText())
    }

    @Test fun invalidFractionalNegativeAndOverflowValuesCannotBeSaved() {
        for ((unit, values) in listOf(
            ModelContextWindowUnit.K to listOf("bad", ".", "0", "-1", "0.0001", "2147483.648", "1e10000", "1e3", "+1", " 1"),
            ModelContextWindowUnit.M to listOf("bad", "0", "-0.001", "0.0000001", "2147.483648", "999999999999"),
        )) for (text in values) assertNull("$text $unit", ModelContextWindowInput(text, unit).tokenText())
    }

    @Test fun invalidDraftIsNotClearedBySwitchingUnits() {
        val input = ModelContextWindowInput("invalid", ModelContextWindowUnit.K).switchUnit()
        assertEquals("invalid", input.text)
        assertEquals(ModelContextWindowUnit.M, input.unit)
        assertNull(input.tokenText())
        assertEquals("1500000", input.copy(text = "1.5").tokenText())
    }

    @Test fun formattingIsIndependentOfDeviceLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1.5", ModelContextWindowInput.fromTokens("1500000").text)
            assertEquals("1500", ModelContextWindowInput.fromTokens("1500000").switchUnit().text)
            assertEquals("1500000", ModelContextWindowInput("1.5", ModelContextWindowUnit.M).tokenText())
            assertNull(ModelContextWindowInput("1,5", ModelContextWindowUnit.M).tokenText())
        } finally {
            Locale.setDefault(original)
        }
    }
}

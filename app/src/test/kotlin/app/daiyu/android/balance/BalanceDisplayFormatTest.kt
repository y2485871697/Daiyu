package app.daiyu.android.balance

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class BalanceDisplayFormatTest {
    @Test fun integersFractionsAndZeroKeepTwoDecimalPlaces() = withLocale(Locale.US) {
        for ((raw, expected) in listOf("0" to "0.00", "14" to "14.00", "14.1" to "14.10",
            "14.12" to "14.12", "-12.3" to "-12.30", "123456789.5" to "123,456,789.50")) {
            assertEquals(raw, expected, formatBalanceDisplay(raw))
        }
    }

    @Test fun roundingUsesTheExistingComposerPolicyWithoutChangingMoney() = withLocale(Locale.US) {
        for ((raw, expected) in listOf("14.123608" to "14.12", "14.129" to "14.13",
            "12.345" to "12.34", "12.355" to "12.36", "-12.345" to "-12.34")) {
            val money = BalanceMoney(raw.toBigDecimal(), "USD")
            assertEquals(expected, formatBalanceDisplay(money.amount.toPlainString()))
            assertEquals(raw.toBigDecimal(), money.amount)
        }
    }

    @Test fun localeFormattingStillMatchesTheComposer() = withLocale(Locale.GERMANY) {
        assertEquals("1.234,50", formatBalanceDisplay("1234.5"))
    }

    private fun withLocale(locale: Locale, block: () -> Unit) {
        val original = Locale.getDefault()
        try { Locale.setDefault(locale); block() } finally { Locale.setDefault(original) }
    }
}

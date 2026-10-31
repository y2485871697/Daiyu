package app.daiyu.android.ui.screens.settings

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelContextWindowLabelTest {
    @Test fun smallerAndUnknownWindowsKeepTheirExistingLabels() {
        for ((tokens, label) in listOf(
            null to "上下文未知", 0 to "0K 上下文", 999 to "999 上下文",
            1024 to "1024 上下文", 8000 to "8K 上下文", 128000 to "128K 上下文",
            999000 to "999K 上下文", 999999 to "999999 上下文"
        )) assertEquals("tokens=$tokens", label, modelContextWindowLabel(tokens))
    }

    @Test fun millionAndLargerWindowsUseMWithoutTrailingZeros() {
        for ((tokens, label) in listOf(
            1000000 to "1M 上下文", 1500000 to "1.5M 上下文", 2000000 to "2M 上下文"
        )) assertEquals("tokens=$tokens", label, modelContextWindowLabel(tokens))
    }

    @Test fun fractionalMillionsKeepTheExactTokenValue() {
        for ((tokens, label) in listOf(
            1000001 to "1.000001M 上下文", 1024000 to "1.024M 上下文",
            1048576 to "1.048576M 上下文", 1250000 to "1.25M 上下文",
            Int.MAX_VALUE to "2147.483647M 上下文"
        )) assertEquals("tokens=$tokens", label, modelContextWindowLabel(tokens))
    }

    @Test fun millionDecimalSeparatorDoesNotDependOnDeviceLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1.5M 上下文", modelContextWindowLabel(1500000))
        } finally {
            Locale.setDefault(original)
        }
    }
}

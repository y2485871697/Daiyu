package app.daiyu.android.balance

import app.daiyu.android.balance.ui.BalanceEffectLevel
import app.daiyu.android.balance.ui.balanceEffectLevel
import app.daiyu.android.balance.ui.formatBalanceDelta
import app.daiyu.android.balance.ui.hasBalanceIndicatorContent
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.math.BigDecimal

/** Ports frozen UI policy tests; composable rendering/system motion still needs device validation. */
class ProviderBalanceIndicatorTest {
    private val available = BalanceResult.Available(BalanceMoney(BigDecimal("120.00"), "CNY"), 1L)

    @Test fun onlySuccessfulMoneyOccupiesIndicatorSpaceEvenOnRefreshOrFailure() {
        assertFalse(hasBalanceIndicatorContent(null))
        assertFalse(hasBalanceIndicatorContent(BalanceState(refreshing = true)))
        assertFalse(hasBalanceIndicatorContent(BalanceState(result = BalanceResult.Failed(BalanceFailure.NETWORK))))
        assertTrue(hasBalanceIndicatorContent(BalanceState(result = available)))
        assertTrue(hasBalanceIndicatorContent(BalanceState(refreshing = true, lastSuccessful = available)))
        val failed = BalanceState(result = BalanceResult.Failed(BalanceFailure.AUTHORIZATION), lastSuccessful = available)
        assertTrue(hasBalanceIndicatorContent(failed))
        assertEquals("CNY 120", failed.project().balanceText)
        assertTrue(failed.project().statusText.contains("未授权"))
        assertFalse(failed.project().balanceText!!.contains("未授权"))
    }

    @Test fun effectLevelFollowsPreservedRoundedDeductionThresholds() {
        assertEquals(BalanceEffectLevel.Light, balanceEffectLevel(BigDecimal("-0.52")))
        assertEquals(BalanceEffectLevel.Light, balanceEffectLevel(BigDecimal("-0.994")))
        assertEquals(BalanceEffectLevel.Strong, balanceEffectLevel(BigDecimal("-0.995")))
        assertEquals(BalanceEffectLevel.Strong, balanceEffectLevel(BigDecimal("-1")))
        assertEquals(BalanceEffectLevel.Strong, balanceEffectLevel(BigDecimal("-12.30")))
        assertEquals(BalanceEffectLevel.Increase, balanceEffectLevel(BigDecimal("5")))
        assertNull(balanceEffectLevel(BigDecimal("-0.000000001")))
        assertNull(balanceEffectLevel(BigDecimal.ZERO))
    }

    @Test fun deltaTextHasSignAndTwoDecimalsWithoutGrouping() {
        assertEquals("-0.52", formatBalanceDelta(BigDecimal("-0.52")))
        assertEquals("-1.52", formatBalanceDelta(BigDecimal("-1.520000000001")))
        assertEquals("+5.00", formatBalanceDelta(BigDecimal("5")))
        assertEquals("-1234.50", formatBalanceDelta(BigDecimal("-1234.5")))
    }

    @Test fun frozenStaticNeutralReadonlySourceContractSurvivesThemeAdapter() {
        val source = productionSource("ProviderBalanceIndicator.kt")
        assertTrue(source.contains("MiuixTheme.colorScheme.onSurfaceVariantSummary"))
        assertTrue(source.contains("tint = neutral"))
        assertTrue(source.contains("color = neutral"))
        for (forbidden in listOf("state.error", "updatedAtMillis", "produceState", "delay(", "clickable", "onClick")) {
            assertFalse(forbidden, source.contains(forbidden))
        }
        assertTrue(source.contains("state?.successful?.money ?: return"))
        assertTrue(source.contains("key(state.revision)"))
        assertTrue(source.contains("Icon(MoneyBag02"))
        assertTrue(source.contains("iconSize: Dp = 12.dp"))
        assertTrue(source.contains("AnimatedProviderBalanceIndicator(state, amount, modifier, textStyle, iconSize)"))
        assertTrue(source.contains("ProviderBalanceAmount(amount, modifier, textStyle, iconSize)"))
        assertTrue(source.contains(".padding(end = 4.dp).size(iconSize)"))
        assertTrue(source.contains("formatBalanceDisplay(money.amount.toPlainString())"))
        assertFalse(source.contains("money.currency"))
        assertFalse(source.contains("DaiyuIcons.Balance"))
        val animation = productionSource("ProviderBalanceAnimation.kt")
        assertTrue(animation.contains("Icon(MoneyBag02"))
        assertTrue(animation.contains("iconSize: Dp = 12.dp"))
        assertTrue(animation.contains(".padding(end = 4.dp).size(iconSize).graphicsLayer"))
        assertTrue(animation.contains("rollingActive -> formatBalanceDisplay(rolling.value.toString())"))
        assertTrue(animation.contains("pendingStrongFrom != null -> formatBalanceDisplay(pendingStrongFrom.toPlainString())"))
        assertFalse(animation.contains("money.currency"))
        val screen = productionSource("BalanceSettingsScreen.kt")
        assertTrue(screen.contains("fun BalanceSettingsScreen(runtime: BalanceRuntime, onBack: () -> Unit"))
        assertFalse(screen.contains("rememberSaveable" + "("))
        assertFalse(screen.contains("runtime.start(")) // scope ownership belongs to the parent, not page entry
        assertFalse(screen.contains("ChatController"))
    }

    private fun productionSource(name: String): String = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .map { File(it, "app/src/main/kotlin/app/daiyu/android/balance/ui/$name") }
        .first { it.isFile }.readText()
}

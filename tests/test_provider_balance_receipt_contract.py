"""Balance test receipts reuse the composer amount without saving the draft."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"

class ProviderBalanceReceiptContractTest(unittest.TestCase):
    def setUp(self):
        self.page = (SRC / "ui/screens/settings/ProviderBalanceFields.kt").read_text()

    def test_token_helper_is_removed_without_removing_token_editor(self):
        self.assertNotIn("从 NewAPI 的个人安全设置获取", self.page)
        self.assertIn('tag = "provider-balance-token"', self.page)
        binding = (SRC / "balance/BalanceModels.kt").read_text()
        self.assertIn('option?.accessToken?.ifBlank { apiKey } ?: apiKey', binding)

    def test_success_is_structured_and_never_a_status_sentence(self):
        self.assertIn("internal data class ProviderBalanceTestUi", self.page)
        self.assertIn("var money by remember { mutableStateOf<BalanceMoney?>(null) }", self.page)
        self.assertIn("money = (result as? BalanceResult.Available)?.money", self.page)
        self.assertIn("is BalanceResult.Available -> null", self.page)
        self.assertNotIn("余额测试成功", self.page)

    def test_result_group_is_centered_and_reuses_composer_visuals(self):
        self.assertIn('testTag("provider-balance-test-result")', self.page)
        self.assertIn("contentAlignment = Alignment.Center", self.page)
        self.assertIn("ProviderBalanceAmount(formatBalanceDisplay(money.amount.toPlainString())", self.page)
        self.assertIn("iconSize = 16.dp", self.page)
        self.assertIn("fontSize = 14.sp, lineHeight = 28.sp", self.page)
        shared = (SRC / "balance/ui/ProviderBalanceIndicator.kt").read_text()
        self.assertIn("Icon(MoneyBag02", shared)
        self.assertIn('DecimalFormat("#,##0.00"',  (SRC / "balance/ProviderBalanceFetcher.kt").read_text())

    def test_money_is_cleared_on_cancel_and_before_next_request(self):
        self.assertIn("request = null; status = null; money = null", self.page)
        self.assertIn("request = flag; status = null; money = null", self.page)
        for guard in ("request === flag && currentEnabled && currentOwner == owner", "flag.set(true); request = null", "Lifecycle.Event.ON_PAUSE", "DisposableEffect(owner, enabled, tester != null)"):
            self.assertIn(guard, self.page)

    def test_failure_feedback_and_existing_request_gates_remain(self):
        self.assertIn("else -> BalanceState(configured = true, result = result).project().statusText", self.page)
        self.assertIn("test.status?.let { SettingsNote(it) }", self.page)
        self.assertIn("enabled = enabled && testBalance != null && valid && !test.running", self.page)
        self.assertNotIn("saveConfiguration", self.page)

if __name__ == "__main__":
    unittest.main()

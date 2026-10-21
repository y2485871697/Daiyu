"""Source contracts only; selector rendering and layout regressions live in androidTest."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/kotlin/app/daiyu/android'


def source(path):
    return (SOURCE / path).read_text()


class ModelPickerBalanceContract(unittest.TestCase):
    def test_host_passes_each_provider_slots_state_without_currency_projection(self):
        host = source('MainActivity.kt')
        mapping = host.split('val groups = registry.providers.filter { it.enabled }.map { provider ->', 1)[1]
        mapping = mapping.split('ChatModelCatalogLoad(', 1)[0]
        self.assertIn('ChatModelProviderUi(provider.slotId, provider.name,', mapping)
        self.assertIn('balance = controller.providerBalances.get(provider.slotId).state.value)', mapping)
        for forbidden in ('project()', 'balanceText', 'activeSlotId', 'manualRefresh(', 'startPolling('):
            self.assertNotIn(forbidden, mapping)

    def test_provider_header_uses_static_shared_balance_component(self):
        picker = source('ui/screens/chat/conversation/ChatModelPicker.kt')
        self.assertIn('val balance: BalanceState? = null', picker)
        header = picker.split('provider.balance?.let { balance ->', 1)[1].split('Icon(DaiyuIcons.ChevronDown', 1)[0]
        self.assertIn('ProviderBalanceIndicator(state = balance,', header)
        self.assertIn('chat-model-provider-balance:${provider.id}', header)
        self.assertIn('widthIn(max = 96.dp)', header)
        self.assertIn('TextStyle(fontSize = 12.sp, lineHeight = 18.sp)', header)
        for forbidden in ('Text(balance', 'animateChanges = true', 'statusText', 'result.reason', 'clickable('):
            self.assertNotIn(forbidden, header)
        self.assertNotIn('DaiyuIcons.Balance', picker)

    def test_shared_indicator_keeps_money_bag_numeric_success_only_policy(self):
        indicator = source('balance/ui/ProviderBalanceIndicator.kt')
        self.assertIn('state?.successful?.money ?: return', indicator)
        self.assertIn('Icon(MoneyBag02', indicator)
        self.assertIn('formatBalanceDisplay(money.amount.toPlainString())', indicator)
        self.assertIn('animateChanges: Boolean = false', indicator)
        for forbidden in ('money.currency', 'statusText', 'clickable(', 'onClick'):
            self.assertNotIn(forbidden, indicator)

    def test_selector_preserves_collaboration_accent_and_selected_row(self):
        picker = source('ui/screens/chat/conversation/ChatModelPicker.kt')
        self.assertIn('val colors = usagePalette()', picker)
        self.assertIn('background(if (selected) colors.bubble else Color.Transparent)', picker)
        self.assertIn('background(if (selected) colors.accent else Color.Transparent)', picker)
        usage = source('ui/components/UsagePresentation.kt')
        collaboration = source('ui/screens/assistants/AssistantPreviewComponents.kt')
        def accent_values(text):
            line = next(line for line in text.splitlines() if 'val accent =' in line)
            return [value.lower() for value in re.findall(r'0[xX][0-9a-fA-F]{8}', line)]
        self.assertEqual(accent_values(usage), accent_values(collaboration))
        self.assertEqual(['0xffc6b6d5', '0xff82718f'], accent_values(usage))

    def test_settings_receipts_and_top_bar_boundary_are_unchanged(self):
        models = source('balance/BalanceModels.kt')
        self.assertIn('balanceText = available?.money?.display()', models)
        self.assertIn('$currency', models)
        self.assertNotIn('ProviderBalanceIndicator', source('ui/screens/chat/conversation/ChatTopBar.kt'))
        settings = source('ui/screens/settings/SettingsModelPicker.kt')
        self.assertNotIn('providerBalances', settings)
        self.assertNotIn('balance =', settings)


if __name__ == '__main__':
    unittest.main()

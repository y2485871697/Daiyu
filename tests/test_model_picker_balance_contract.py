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
        mapping = host.split('ChatModelProviderUi(provider.slotId, provider.name,', 1)[1]
        mapping = mapping.split('ChatModelCatalogLoad(', 1)[0]
        self.assertIn('registry.providers.filter { it.enabled', host)
        self.assertIn('balance = controller.providerBalances.get(provider.slotId).state.value)', mapping)
        for forbidden in ('project()', 'balanceText', 'activeSlotId', 'manualRefresh(', 'startPolling('):
            self.assertNotIn(forbidden, mapping)

    def test_provider_header_uses_static_shared_balance_component(self):
        picker = source('ui/screens/chat/conversation/ChatModelPicker.kt')
        self.assertIn('val balance: BalanceState? = null', picker)
        header = picker.split('modelPickerProviderBalance(provider)?.let { balance ->', 1)[1].split('Icon(DaiyuIcons.ChevronDown', 1)[0]
        self.assertIn('ProviderBalanceIndicator(state = balance,', header)
        self.assertIn('chat-model-provider-balance:${provider.id}', header)
        self.assertIn('TextStyle(fontSize = 12.sp, lineHeight = 18.sp)', header)
        for forbidden in ('Text(balance', 'animateChanges = true', 'statusText', 'result.reason', 'clickable('):
            self.assertNotIn(forbidden, header)
        self.assertNotIn('DaiyuIcons.Balance', picker)

    def test_provider_balance_is_a_trailing_sibling_before_expand(self):
        picker = source('ui/screens/chat/conversation/ChatModelPicker.kt')
        name_column = picker.split('Column(Modifier.weight(1f), verticalArrangement', 1)[1]
        name_column, trailing = name_column.split('modelPickerProviderBalance(provider)?.let { balance ->', 1)
        self.assertIn('chat-model-count:${provider.id}', name_column)
        self.assertRegex(name_column, r'chat-model-count:\$\{provider.id\}.*?\)\)\s*}')
        self.assertNotIn('ProviderBalanceIndicator', name_column)
        self.assertLess(trailing.index('ProviderBalanceIndicator'), trailing.index('Icon(DaiyuIcons.ChevronDown'))
        self.assertIn('verticalAlignment = Alignment.CenterVertically', picker.split('item(key = "heading:${provider.id}")', 1)[1].split('Column(Modifier.weight(1f)', 1)[0])

    def test_all_picker_entrances_share_provider_scoped_read_only_balances(self):
        for path in ('ui/screens/chat/conversation/LocalCatalogChatModelPicker.kt',
                     'ui/screens/settings/SettingsModelPicker.kt',
                     'ui/components/subagent/SubAgentModelPicker.kt', 'VoiceSettingsDialogs.kt'):
            self.assertIn('ChatModelPicker(', source(path), path)
        binding = source('ui/screens/chat/conversation/ModelPickerProviderBalance.kt')
        for required in ('applicationContext as? ChatApplication', 'providerBalanceHost',
                         'ProviderRegistry.validSlotId(provider.id)', 'key(host, provider.id)',
                         'host.cached(provider.id)', 'host.get(provider.id)',
                         'key(balanceRuntime)', 'collectAsStateWithLifecycle()', 'throw cancelled'):
            self.assertIn(required, binding)
        for forbidden in ('start(', 'manualRefresh(', 'requestRefresh(', 'activeSlotId',
                          'EncryptedBalancePersistence(', 'BalanceRuntime(', 'runBlocking', '?: provider.balance'):
            self.assertNotIn(forbidden, binding)

    def test_shared_indicator_keeps_money_bag_numeric_success_only_policy(self):
        indicator = source('balance/ui/ProviderBalanceIndicator.kt')
        self.assertIn('state?.successful?.money ?: return', indicator)
        self.assertIn('Icon(MoneyBag02', indicator)
        self.assertIn('formatBalanceDisplay(money.amount.toPlainString())', indicator)
        self.assertIn('animateChanges: Boolean = false', indicator)
        for forbidden in ('money.currency', 'statusText', 'clickable(', 'onClick'):
            self.assertNotIn(forbidden, indicator)

    def test_animated_amount_wraps_short_text_without_changing_ellipsis_budget(self):
        animation = source('balance/ui/ProviderBalanceAnimation.kt')
        amount = animation.split('Text(text = displayText,', 1)[1].split('val current = burst', 1)[0]
        self.assertIn('Modifier.width(IntrinsicSize.Max).graphicsLayer', amount)
        self.assertIn('maxLines = 1, overflow = TextOverflow.Ellipsis', amount)
        self.assertNotIn('widthIn(min', amount)
        self.assertNotIn('padding', amount)

    def test_selector_preserves_collaboration_accent_and_selected_row(self):
        picker = source('ui/screens/chat/conversation/ChatModelPicker.kt')
        self.assertIn('val colors = usagePalette()', picker)
        self.assertIn('val selectedBackground = colors.selectionContainer', picker)
        self.assertIn('background(if (selected) selectedBackground else Color.Transparent)', picker)
        self.assertIn('background(if (selected) colors.accent else Color.Transparent)', picker)
        usage = source('ui/components/UsagePresentation.kt')
        collaboration = source('ui/screens/assistants/AssistantPreviewComponents.kt')
        def accent_values(text):
            line = next(line for line in text.splitlines() if 'val accent =' in line).rsplit('?:', 1)[1]
            line = line.split('theme.surface) else ', 1)[1]
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

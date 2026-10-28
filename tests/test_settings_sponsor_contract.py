"""Sponsor entry wiring and exact original QR assets; Android tests cover interaction."""
from pathlib import Path
import hashlib
import json
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'
SETTINGS = SRC / 'ui/screens/settings'


class SettingsSponsorContractTest(unittest.TestCase):
    def test_entry_is_sponsor_with_its_own_heart_icon(self):
        catalog = (SETTINGS / 'SettingsCatalog.kt').read_text()
        self.assertIn('SettingsEntry("donate", "赞助", DaiyuIcons.Sponsor, SettingControl.ACTION)', catalog)
        self.assertNotIn('给煮波恰饭', catalog)
        self.assertNotIn('🍚', catalog)
        icons = (SRC / 'ui/icons/DaiyuIcons.kt').read_text()
        self.assertIn('name = "Daiyu.Sponsor"', icons)

    def test_community_entries_use_their_exact_brand_paths(self):
        catalog = (SETTINGS / 'SettingsCatalog.kt').read_text()
        icons = (SRC / 'ui/icons/CommunityBrandIcons.kt').read_text()
        import xml.etree.ElementTree as ET
        manifest = json.loads((ROOT / 'THIRD_PARTY/community-icons/sources.json').read_text())
        for entry in manifest['files']:
            data = (ROOT / entry['path']).read_bytes()
            self.assertEqual(hashlib.sha256(data).hexdigest(), entry['sha256'])
        for id_, title, brand, name in [('telegram', 'Telegram 频道', 'Telegram', 'telegram'),
                                       ('qq', 'QQ 群', 'TencentQQ', 'tencentqq'),
                                       ('source', '源代码', 'GitHub', 'github')]:
            self.assertIn(f'SettingsEntry("{id_}", "{title}", CommunityBrandIcons.{brand}', catalog)
            svg = ET.fromstring((ROOT / f'THIRD_PARTY/community-icons/upstream/{name}.svg').read_bytes())
            path = svg.find('{http://www.w3.org/2000/svg}path').attrib['d']
            self.assertIn(f'addPathNodes("{path}")', icons)

    def test_relay_children_use_the_same_left_row_inset(self):
        home = (SETTINGS / 'SettingsHomeScreen.kt').read_text()
        self.assertIn('SettingsExpandableContent(relayExpanded) {', home)
        self.assertNotIn('SettingsExpandableContent(relayExpanded, Modifier.padding(start = 66.dp))', home)
        self.assertIn('SettingsRow(child.title, onClick = { open(child) })', home)

    def test_entry_opens_local_sheet_only_under_existing_lifecycle_gate(self):
        home = (SETTINGS / 'SettingsHomeScreen.kt').read_text()
        self.assertNotIn('onClick = if (entry.id == "donate") null else onClick', home)
        self.assertNotIn('暂未开放，不提供支付入口', home)
        self.assertIn('"donate" -> showSponsor = true', home)
        self.assertIn('if (showSponsor) SettingsSponsorDialog(onDismiss = { showSponsor = false })', home)
        self.assertIn('localPage != actionPage', home)
        self.assertIn('Lifecycle.State.RESUMED', home)
        self.assertIn('showSponsor = false\n                    localPage =', home)

    def test_sheet_has_no_title_and_reuses_new_shell_and_action_colors(self):
        sheet = (SETTINGS / 'SettingsSponsorDialog.kt').read_text()
        self.assertIn('ChatSelectionSheet(title = "", onDismiss = onDismiss)', sheet)
        self.assertNotIn('WindowDialog', sheet)
        self.assertNotIn('给煮波恰饭', sheet)
        self.assertNotIn('🍚', sheet)
        self.assertIn('primary = wechatSelected, secondary = !wechatSelected', sheet)
        self.assertIn('primary = !wechatSelected, secondary = wechatSelected', sheet)
        self.assertIn('ContentScale.Fit', sheet)
        self.assertNotIn('startActivity', sheet)
        self.assertNotIn('Uri.parse', sheet)

    def test_payment_selection_defaults_to_wechat_and_maps_both_qrs(self):
        sheet = (SETTINGS / 'SettingsSponsorDialog.kt').read_text()
        self.assertIn('remember { mutableStateOf(true) }', sheet)
        self.assertIn('if (wechatSelected) "donate_wechat.png" else "donate_alipay.jpg"', sheet)
        self.assertIn('assets.open("sponsor/$name")', sheet)
        self.assertIn('if (wechatSelected) "微信收款码" else "支付宝收款码"', sheet)
        self.assertIn('wechatSelected = true', sheet)
        self.assertIn('wechatSelected = false', sheet)

    def test_qr_images_are_byte_identical_to_original_eta(self):
        expected = {
            'donate_wechat.png': '125f3c2b915a8475387de9c150dd890a7154a28120d4c885d7347edf4e8a9383',
            'donate_alipay.jpg': '9597129b69f475b157149995f5ed4e13b24f69a47d58ee848378c60fa12c5095',
        }
        for name, digest in expected.items():
            with self.subTest(name=name):
                data = (ROOT / 'app/src/main/assets/sponsor' / name).read_bytes()
                self.assertEqual(hashlib.sha256(data).hexdigest(), digest)

    def test_sponsor_manifest_matches_the_exact_packaged_asset_set(self):
        manifest = json.loads((ROOT / 'THIRD_PARTY/sponsor-assets/sources.json').read_text())
        self.assertEqual(len(manifest['source_revision']), 40)
        files = manifest['files']
        self.assertEqual({Path(f['path']).name for f in files}, {'donate_wechat.png', 'donate_alipay.jpg'})
        self.assertEqual({p.name for p in (ROOT / 'app/src/main/assets/sponsor').iterdir()},
                         {Path(f['path']).name for f in files})
        for entry in files:
            path = ROOT / entry['path']
            self.assertFalse(path.is_symlink())
            self.assertEqual(path.stat().st_size, entry['bytes'])
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), entry['sha256'])


if __name__ == '__main__':
    unittest.main()

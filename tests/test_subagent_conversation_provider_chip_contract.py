"""Narrow source contracts for the conversation-only provider chip; no Android runtime."""
from pathlib import Path
import unittest

ROW = (Path(__file__).resolve().parents[1] /
       "app/src/main/kotlin/app/daiyu/android/ui/components/SubAgentProfileRow.kt")


class ConversationProviderChipContractTest(unittest.TestCase):
    def test_provider_chip_uses_resolved_name_and_conversation_palette_only(self):
        source = ROW.read_text()
        chat = source.split("} else if (LocalConversationAgentStyle.current) {", 1)[1].split("\n        } else {", 1)[0]
        chip = chat.split("config?.providerName?.takeIf { it.isNotBlank() }?.let { providerName ->", 1)[1].split("\n                        }", 1)[0]
        self.assertIn("Text(providerName, Modifier.widthIn(max = 96.dp)", chip)
        self.assertIn('testTag("subagent-model-provider-${profile.id}")', chip)
        self.assertIn(".background(colors.soft, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))", chip)
        self.assertIn("fontSize = 11.sp, lineHeight = 16.sp, color = colors.accent", chip)
        self.assertIn("maxLines = 1, overflow = TextOverflow.Ellipsis", chip)
        for forbidden in ("providerId", "clickable", "onClick", "Role.Button", "IconButton"):
            self.assertNotIn(forbidden, chip)
        self.assertEqual(1, source.count('testTag("subagent-model-provider-${profile.id}")'))
        self.assertLess(chat.index('testTag("subagent-model-name-${profile.id}")'), chat.index("Text(providerName,"))
        self.assertLess(chat.index("Text(providerName,"), chat.index("Icon(Icons.Rounded.ExpandMore"))
        self.assertIn('Modifier.weight(1f).testTag("subagent-model-name-${profile.id}")', chat)
        self.assertIn('Modifier.size(20.dp).testTag("subagent-model-arrow-${profile.id}")', chat)
        self.assertIn(".heightIn(min = 48.dp)", chat)
        self.assertIn("ModelBrandIcon(config?.model.orEmpty()", chat)
        self.assertIn("role = Role.Button, enabled = usable", chat)
        self.assertIn("onClick = { if (currentUsable) { modelPicker = true } }", chat)
        self.assertIn("onLongClick = { if (currentUsable && canThink) { thinkingPicker = true } }", chat)
        self.assertIn("if (showGptSpeed) {", chat)


if __name__ == "__main__":
    unittest.main()

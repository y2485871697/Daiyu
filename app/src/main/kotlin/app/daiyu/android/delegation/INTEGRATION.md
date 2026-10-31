# C1 — preserved sub-agent configuration UI

Sources are the read-only `[historical-source-snapshot]/kotlin/` and `[historical-source-snapshot]/res/` snapshot.
No MainActivity or ChatController changes are included. Material3 scaffolds, Material icons,
strings, dimensions, dialog surfaces and interactions follow the original sub-agent UI.
The existing no-ripple helper, TouchHaptics and horizontalCutoutPadding are reused.
The existing Miuix scaffold/dropdown helpers are deliberately not substituted for the
original Material3 scaffold and SubAgentDropdownMenu because their appearance differs.

## Entry points (same app module)

- `ui.SubAgentSettingsScreen(onBack, repositoryFactory = null, isCurrentRoute = true, modelSource = SubAgentModelSource.Empty)`
- `ui.components.ConversationCollaborationDialog(show, conversationId, onDismiss, enabled = true, onEnabledChange = {}, taskRunning = false, ownerMatches = { true }, repositoryFactory = null, modelSource = SubAgentModelSource.Empty)`

Pass the **same** repository/store for both screens and runtime configuration. For example,
`repositoryFactory = { repository }`. The optional default is a separate application
SharedPreferences file named `subagent_configuration`; it does not read any old ProviderRepository.
Pass `taskRunning` and `ownerMatches` from the currently selected conversation/run state.
`conversationId = null` retains the original no-owner state; opening/reading does not create
a configuration. A changed owner disposes the old editor and all retained callbacks remain fenced.
`enabled`/`onEnabledChange` are preserved legacy signature parameters; the original panel uses
the persisted config's `enabled` field and editor writes, not those legacy parameters.

`SubAgentModelSource` is read-only: a StateFlow of provider DTOs and a flattened models list.
The caller projects its existing provider/model registry to `SubAgentProvider`/`SubAgentModel`;
never pass credentials. IDs are selection IDs; modelId is the actual API request model.
Parallel limits and runtime pools are independent per owner and profile ID, including across model changes; 0 adds no limit.
The old provider/API-model limits are read only as initial values for profiles saved before per-profile limits. Supply enabled states, display names, explicit GPT speed/media capability,
reasoning capability/default values and verified Grok-image status. Preserve provider/model
ordering and omit providers that cannot be selected (e.g. missing credential). No lookup fallback
or alternate model is selected by the UI. Keep this source stable and update its StateFlow.

## Migrated files

All old paths below are relative to `[historical-source-snapshot]/kotlin/`; targets are relative to
`app/src/main/kotlin/app/daiyu/android/`.

| Old | New |
| --- | --- |
| ui/SubAgentSettingsScreen.kt | ui/SubAgentSettingsScreen.kt |
| ui/components/ConversationCollaborationDialog.kt | ui/components/ConversationCollaborationDialog.kt |
| ui/components/ConversationSubAgentEditor.kt | ui/components/ConversationSubAgentEditor.kt (includes SubAgentEditorState) |
| ui/components/SubAgentPresetActionsSheet.kt | ui/components/SubAgentPresetActionsSheet.kt |
| ui/components/SubAgentPresetCard.kt | ui/components/SubAgentPresetCard.kt (includes SubAgentPresetDirectory) |
| ui/components/SubAgentPresetNameDialog.kt | ui/components/SubAgentPresetNameDialog.kt |
| ui/components/SubAgentProfileRow.kt | ui/components/SubAgentProfileRow.kt |
| ui/components/SubAgentProfileConfigDialog.kt | ui/components/SubAgentProfileConfigDialog.kt (includes SubAgentProfileDraftSession) |
| ui/components/SubAgentTaskTierButton.kt | ui/components/SubAgentTaskTierButton.kt (includes SubAgentDropdownMenu) |
| ui/components/SubAgentSettingRow.kt | ui/components/SubAgentSettingRow.kt |
| ui/components/SubAgentParallelLimitRow.kt | ui/components/SubAgentParallelLimitRow.kt |
| ui/components/SubAgentGptSpeedButton.kt | ui/components/SubAgentGptSpeedButton.kt |
| ui/components/ThinkingSpeedAnimation.kt (used functions only) | ui/components/SubAgentThinkingSpeedAnimation.kt |
| ui/components/DaiyuFormTextField.kt | ui/components/DaiyuFormTextField.kt |
| ui/components/AgentChatInputBar.kt (ThinkingEffortPickerDialog + discrete-slider helper only) | ui/components/subagent/ThinkingEffortPicker.kt (SubAgent-prefixed functions) |
| ui/TtsModelPickerDialog.kt (model chooser only) | ui/components/subagent/SubAgentModelPicker.kt |
| ui/model/AgentModelPickerUiState.kt (model picker projection only) | ui/components/subagent/SubAgentModelPicker.kt (read-only DTO projection) |
| agent/delegation/ConversationSubAgentPreferences.kt | delegation/ConversationSubAgentPreferences.kt |
| agent/delegation/SubAgentProfile.kt | delegation/SubAgentProfile.kt |
| agent/delegation/SubAgentPresetCatalog.kt | delegation/SubAgentPresetCatalog.kt |
| agent/delegation/SubAgentTaskTier.kt | delegation/SubAgentTaskTier.kt |
| agent/delegation/SubAgentModelDefaults.kt | delegation/SubAgentModelDefaults.kt |
| data/model/Reasoning.kt + agent/delegation/SubAgentPreferences.kt (UI value/normalization subset only) | delegation/SubAgentModelSource.kt |

New adapter file: `delegation/SubAgentModelSource.kt` (no old ProviderRepository/RuntimeConfigRepository).
Original GptSpeedMode is mapped to the new project's existing `model.GptSpeedMode`.
Original ic_atom.xml was copied to its sole `app/src/main/res/drawable/ic_atom.xml` path;
this is the same asset also used by the parallel reasoning migration, not a renamed duplicate.
Original action_close/provider_empty/reasoning_picker_title values are in
`res/values{,-b+zh+Hans,-b+zh+Hant}/subagent.xml` with subagent_ resource-name prefixes.
Hard-coded original UI strings remain unchanged; no additional UI strings were introduced.

## Tests

`app/src/test/kotlin/app/daiyu/android/delegation/ConversationSubAgentPreferencesTest.kt` uses
JUnit4 and a synchronous JVM SharedPreferences fixture. Covers preset CRUD durability,
independent copies between preset and conversations, automatic-delegation persistence,
zero-write reads/rejections, failed-commit durability fencing and explicit recovery.
Build/test execution is delegated to the supervising agent.

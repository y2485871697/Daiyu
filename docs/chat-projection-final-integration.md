# Retained chat / sidebar projection final integration (non-Main changes)

Baseline inspected: `af18af3`. This change deliberately does **not** edit MainActivity,
controller message-operation APIs, speech backends, Message time fields or SQLite schema.
The following Main seams must be applied by the integrating parent; until then the new
settings UI is controlled state only and Main's old preference methods still omit it.

## 1. Appearance persistence and exact listeners

Use `app.daiyu.android.data.model.AppearanceSettingsStore` (internal, app module):

```kotlin
private val appearanceStore by lazy { AppearanceSettingsStore(this) }
private fun loadAppearance(): AppearanceSettings = appearanceStore.load()
private fun saveAppearance(value: AppearanceSettings) { guard {
    appearance = appearanceStore.save(value)
    publishChat() // reproject flags immediately; not only when another message arrives
} }
```

Keep `AppearanceSettingsScreen(state = appearance, onChange = { saveAppearance(it) },
onBack = ..., onOpenIcon = ...)`. The exact retained setting callbacks are now:

* `SettingsSwitchRow("圆形加载指示器", state.morphLoadingIndicator)` →
  `onChange(state.copy(morphLoadingIndicator = it))`.
* `SettingsSwitchRow("仅消息生成前", state.morphLoadingBeforeResponseOnly)` →
  `onChange(state.copy(morphLoadingBeforeResponseOnly = it))`.

The file is still `appearance-settings`; existing theme/scale/timestamp/Monet/palette/
accent/blur keys are unchanged. Added loading keys are `morph_loading` (default true)
and `morph_before_response_only` (default false). Store also round-trips the existing
model's gesture/icon fields. `save` normalizes and requires a successful synchronous
commit before returning the value for publication. No preference listener is necessary
for the single Activity-owned writer; if another writer is introduced, observe this same
file with `SharedPreferences.OnSharedPreferenceChangeListener`, reload and republish on
the Activity thread, and unregister on disposal. Do not independently overwrite the
loaded model with `AppearanceSettings()` when one setting changes.

## 2. Composer loading projection (not a voice/control rewrite)

In `publishChat`, continue using the existing `controlOwner` + `controller.runState`
for control ownership and `composerPhaseForRun(state?.pauseRequested)`. Append these
**named optional parameters** when constructing `ComposerUi`:

```kotlin
executionPhase = state?.phase,
hasAssistantOutput = controlOwner?.let(::assistantOutputExists),
morphLoadingIndicator = appearance.morphLoadingIndicator,
morphLoadingBeforeResponseOnly = appearance.morphLoadingBeforeResponseOnly,
```

`assistantOutputExists(Message)` lives in the conversation package. Pass the **current
active assistant owner**, not the whole `shown` history and not a synthetic empty
assistant. If the owner cannot be read, pass null (unknown). It checks nonblank text,
tools and images, including earlier assistant history segments; user supplements do
not count as assistant output. Thus output from before a supplement/reconnect does
not incorrectly become “before response” again. Ensure the owner is the current
message snapshot, not a stale original captured at send time.

`ComposerUi.showGenerationLoading()` requires the real runtime phase and the existing
RUNNING action state, not RUNNING alone. Active phases are IDLE/BOUNDARY/STREAMING/
RECONNECTING/TOOL_EXECUTING. PAUSED/WAITING_USER/TERMINAL/unknown do not animate generation.
With before-response-only on, only **known false** output existence permits generation
animation; true or unknown does not. With the overall loading switch off there is no
generation animation. These flags do not authorize pause/resume/send or alter execution.

Preserve the already-real interactive-mode contract: `controller.interactiveMode.observe
{ interactiveModeEnabled = it }`, `chatState.copy(interactiveModeEnabled =
interactiveModeEnabled)`, and `ChatScreenActions.onInteractiveModeChange = { enabled ->
if (activityUsable()) controller.interactiveMode.setEnabled(enabled) }`; close the
observation on disposal. Do not replace this persistent owner with a local remember
boolean or attach the loading switches to interactive-mode settings.

`SpeechMorphButton` and `ContainedMorphLoadingIndicator` visual/gesture/animation code
is unchanged. Composer feeds its existing `generating` input with that projection.
The static circle remains a voice touch target when generation animation is disabled;
listening still animates and expands exactly as before. Keep P32's actual voice state
and `onVoice`/`onVoiceLongPress` listeners; do not infer listening from execution phase.

## 3. Search hit → DB window → exact rendered segment

`MessageSearchHit` retains `messageId` and `ownerMessageId` and appends optional
`segmentIndex: Int? = null`. Reader now emits all matching expanded segments rather
than discarding later segments of the same assistant id. Index is the zero-based
occurrence of `(runId, messageId)` in authoritative expanded history, **not** the index
of a search result, loaded window or all owner segments. Supplemental user id differs
from its DB owner. Assistant segments can share an id, so index is necessary even when
messageId == ownerMessageId.

Keep `openHistoryHit`'s SQL `seq` lookup and owner-centered window read using
`hit.ownerMessageId`. Replace its final navigation call:

```kotlin
// publishChat() after assigning shown = window, as before
chatState = chatState.copy(scrollRequest = ChatScrollRequest(
    serial = ++scrollSerial,
    messageId = hit.messageId,
    followBottom = false,
    segmentIndex = hit.segmentIndex,
))
```

Or extend Main's `requestScroll(id: String? = null, follow: Boolean = true,
segmentIndex: Int? = null)` and construct `ChatScrollRequest(++scrollSerial, id, follow,
segmentIndex)`; call **`requestScroll(hit.messageId, false, hit.segmentIndex)`**, never
`requestScroll(hit.ownerMessageId, false)`. Pass complete hits through both search
screens/dialog callbacks. Never reconstruct identity by matching the snippet.

`ChatTurnMapper.map(messages, timestamp = ...)` still expands stored owners and keeps
message/action ids. It appends `ownerMessageId` and `segmentIndex` to each turn, leaving
stable length-prefixed list keys unchanged. Supply persisted owners for complete DB
owner metadata; already-expanded input remains accepted but cannot reconstruct a
supplement's DB owner once discarded by its caller. The exact-navigation index is the
same for either input if full owner segments are present.

`ChatScrollRequest` appends optional `segmentIndex` and `listKey`; its original three
positional args remain valid. `listKey` takes precedence when explicitly supplied.
`ChatMessageList` resolves exact requests without owner/first-segment fallback, keeps
an absent target pending until turns arrive, consumes after successful scroll, and
cancels pending navigation on user touch. Exact targets disable bottom-follow. Do not
split a stored owner's history when loading the DB window; occurrence numbering would
otherwise lack its prefix. New serials are required for new requests.

### Timestamp seam for P35

Mapper API is unchanged: `timestamp: (Message) -> String = { "" }` is its optional
second parameter and trailing-lambda calls remain source-compatible. Parent can pass
P35's real timestamp formatter via:

```kotlin
turns = ChatTurnMapper.map(shown.map { it.second }, timestamp = { message ->
    if (appearance.messageTimestampsEnabled) /* P35 real formatter(message) */ "" else ""
})
```

The empty expression is intentionally a **placeholder**, not an implemented timestamp
formatter. Replace it with P35's actual helper after integration; this subtask does not
invent a helper name/signature or use conversation updatedAt/current time as message
time. Existing rendering omits blank labels. Timezone/clock/test options, if provided
by P35, belong to that formatter, not the mapper.

## 4. Sidebar real assistants and callbacks (no style changes)

Main already collects `assistantStore.assistants` as `assistantProfiles` and
`assistantStore.selectedId` as `activeAssistant`. New app-internal projection APIs:

* `AssistantProfile.toAssistantDisplay(avatarBitmap: (String?) -> Bitmap? = { null })`
* `List<AssistantProfile>.toAssistantDisplays(assets: AssistantAssets?)`

Use the latter with the **real** `assistantAssets` to decode stored avatar filenames.
Decode outside composition/on IO and retain a generation-keyed result; discard a late
result if profiles changed or the host was disposed. Preserve the store's ordering and
do not synthesize a default profile/selected id. No-assets projection retains exact id
and name and the existing avatar fallback, not another app's assets.

Inject both args at `ConversationSidePaneScaffold`:

```kotlin
assistants = projectedAssistantDisplays,
selectedAssistantId = activeAssistant,
onSelectAssistant = { id -> guard {
    assistantStore.select(id) // store validates id, persists and publishes selectedId
    // refresh/publish via the existing assistantProfiles/activeAssistant LaunchedEffect
} },
onEditAssistant = { id -> guard {
    checkNotNull(assistantStore.read(id)) { "助手已不存在" }
    dismissSidebar()
    assistantEditingId = id
    retainedPage = "assistant-edit"
} },
```

This uses the existing `assistant-edit` route and `assistantEditingId` field; that
branch already calls `AssistantEditScreen(assistantId = id, store = assistantStore,
assets = assistantAssets, ...)`. Do not discard the id with the current
`{ openAssistantPicker() }` callback. Keep
`onOpenAssistants` as the existing global management/picker action. Sidebar selection
is the global assistant default; it must not silently rewrite an existing
conversation's bound assistant. Existing conversation-specific picker/admission policy
remains authoritative.

No sidebar layout/style/default-avatar rendering changed. The optional empty defaults
remain for isolated callers, but production Main must explicitly supply real values.

## 5. Completion dot evidence boundary

`ConversationSummaryUi.hasCompletionMarker` defaults false. Current retained summaries
read conversation metadata, latest message and active owner; there is no read/unread
watermark or “completed while unseen” record in these inspected sources. This patch
therefore leaves markers false. In particular, `latest.status == COMPLETED` is **not**
evidence that every historical conversation deserves a completion dot. Only wire a
future verified completion/read source if one exists; no schema or guessed marker was
added here.

## 6. Directed test sources and validation status

Added `ChatProjectionTest`, `AssistantDisplayProjectionTest`,
`AppearanceSettingsPersistenceTest`; extended `RetainedSearchDataTest` with a real
SQLite same-owner/later-segment search case and adjusted `SettingsChromeActivityTest`
to assert actual switch changes. They cover exact supplemental identity, same-id later
segments, stable prepend keys, missing target no-fallback, runtime/output truth table,
assistant name/id/avatar input, preference reload, and no historical completion marks.
No build, test execution, network, manifest/CI or old-历史自有源码 changes were performed by this
implementation subtask. Parent must compile and run selected tests after Main/P35
integration; source inspection is not runtime verification.

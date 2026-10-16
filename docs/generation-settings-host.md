# Generation settings: production editor host (not mounted globally)

## Scope

`ui/GenerationSettingsHost.kt` really calls the existing controlled `GenerationSettingsScreen`.
`GenerationSettingsEditor.kt` is app-private (`internal`) and owns the asynchronous editing logic.
This addition does **not** change Main, FeatureCatalog, SettingsCatalog, any manifest, Controller,
GenerationApprovalHost, or generation-session integration. The parent still decides whether to mount
the route. Opening/saving settings cannot authorize a paid run, discover models or send HTTP.

The only store change is an optional fourth `save` parameter. Existing callers remain source-compatible:

```kotlin
fun save(
    values: GenerationSettingsValues,
    expectedRevision: String?,
    confirmIncompleteSaveRecovery: Boolean = false,
    admitSave: () -> Boolean = { true },
): GenerationSettingsSnapshot
```

## Exact parent API and mounting example

All three declarations are app-private; import them from the app module, not a library API:

```kotlin
// app.daiyu.android.ui
@Composable
internal fun rememberGenerationSettingsEditor(): GenerationSettingsEditor

@Composable
internal fun GenerationSettingsHost(
    editor: GenerationSettingsEditor,
    onBack: () -> Unit,
    ownerCurrent: () -> Boolean,
    modifier: Modifier = Modifier,
)
```

The factory remembers a lightweight editor, with application context. Preference/store construction,
`loadForEditing`, conversion, validation and `save` run on `Dispatchers.IO`. Page-state publication runs
on `Dispatchers.Main.immediate`. The host itself starts the first load and owns disposal/back/destroy
revocation; the parent need not launch loading/saving jobs, own drafts, or implement error handling.

For a parent with a thread-safe **per-visit** identity slot, this is the complete route body. The
surrounding navigation owner creates a fresh `Any()` on every entry, sets `activeVisit` before mounting,
and clears/replaces it synchronously on **every** navigation departure, before any delayed transition:

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import app.daiyu.android.ui.GenerationSettingsHost
import app.daiyu.android.ui.rememberGenerationSettingsEditor
import java.util.concurrent.atomic.AtomicReference

@Composable
internal fun ParentGenerationSettingsRoute(
    visit: Any,
    activeVisit: AtomicReference<Any?>,
    leaveRoute: () -> Unit,
) {
    key(visit) {
        val editor = rememberGenerationSettingsEditor()
        GenerationSettingsHost(
            editor = editor,
            onBack = {
                activeVisit.compareAndSet(visit, null)
                leaveRoute()
            },
            ownerCurrent = { activeVisit.get() === visit },
        )
    }
}
```

Do not supply merely `{ routeName == "settings" }`: A -> B -> A is a different visit. The predicate
must be fast, thread-safe, side-effect-free, and safe to call from the store's I/O thread. It must not
read a UI-only mutable object or acquire locks held during disk I/O. Predicate failure fails closed.
The host's own permanently revoked Page also prevents a previously disposed visit from reviving even
if the parent predicate later returns true. If a parent reuses the same editor, it must still key the
host by visit so each mounting obtains a fresh Page. The predicate is captured once for that Page;
it is not forwarded through `rememberUpdatedState` to a later visit's callback. A disposed/revoked
Page cannot be restarted.

## Editing and failure behavior

- `Page.state` uses Compose `mutableStateOf`; load/save/error/credential warnings actually recompose
  the controlled screen. All UI event methods are UI-thread-only.
- Only `GenerationSettingsFailure.MISSING` produces `GenerationSettingsDraftUi()`. A loading or corrupt
  source has **no draft** and no save button. Corrupt source is not repaired or replaced by defaults.
- Editing load retains saved scope, revision, profile IDs, exact non-secret fields and every available
  key. `credentialUnavailableIds` and `incompleteSave` are passed unchanged to the controlled screen.
- No trimming, inferred protocol, reset-on-selection, or implicit host-list review is introduced.
  Conversion/validation errors leave the complete raw draft visible. Loaded downloads still need the
  existing explicit host-list review before saving. Incomplete saves need the existing separate
  `confirmIncompleteSaveRecovery` switch; it is never automatically checked.
- Revision/scope are adopted only from real readback, not from an event payload. A successful save
  adopts the store's verified snapshot, including minted IDs/revision; it does not reuse a pre-save
  draft as proof of persistence. The screen is disabled while a save is pending.
- Failure text is constructed **from `GenerationSettingsFailure`**, never from exception messages,
  `toString`, endpoints, keys, or raw input. In particular a readback failure displays the specific
  `SAVE_UNCONFIRMED` message ("生成配置未确认持久保存；禁止运行，请重新加载并检查存储").
- A failed save keeps the candidate raw draft and previous revision; it is not falsely published as
  saved. The explicit **重新加载（放弃未保存修改）** button fetches the actual editing record/revision.
  This is necessary after an unconfirmed partial save and deliberately discards unsaved local edits.
- No draft/key uses `rememberSaveable`, Bundle, SavedStateHandle, logging, analytics or plaintext
  persistence. Revocation clears the UI state immediately. A queued/in-progress worker can retain its
  local immutable candidate only until it drains; this is not a key zeroization guarantee.

## Admission, cancellation and races

Every mounted host calls `editor.openPage(...)`; there is no global editor Page. System back and the
screen's back action revoke **before** invoking the parent. Composition disposal and lifecycle
`ON_DESTROY` also revoke. The host checks a false owner predicate at composition, and workers check it
before publication and before save admission. Revocation is memory-only: it does not call the store,
wait on the store monitor, interrupt disk I/O or cancel an already-admitted save.

`GenerationSettingsStore.save` invokes `admitSave` once **inside the existing shared synchronized
store lock**, at entry, before validation, metadata reads, ID minting, fencing or any write. The Page's
owner check has its own short memory-only monitor, not the store lock. Therefore:

1. An old Page queued on a dispatcher or blocked behind another store operation is rejected after it
   acquires the lock if it has left before admission. A stale callback cannot write merely because its
   coroutine started while the route was current. No metadata/credential write or fence is created.
2. If admission wins first, subsequent departure does not cancel the durable save sequence. All
   credential/manifest readback still completes and is checked by the store. The detached Page cannot
   publish success, failure, keys or revision into itself or a replacement Page.
3. A new Page loads from the actual store under the same lock. It is not directly populated by the old
   Page's result. Existing optimistic-revision checking still prevents concurrent stale editors.

The Page's worker scope is independent of composition cancellation and is released when a revoked
Page's pending tasks drain. Calls that complete before disposal may update that current Page normally.
Settings persistence has no relationship to approval authorization or paid-run ownership.

## Added regression sources and verification limit

`app/src/test/kotlin/app/daiyu/android/GenerationSettingsEditorTest.kt` (Robolectric) covers:

- missing-only blank draft, corrupt fail-closed behavior and sanitized messages;
- exact raw field/scope/ID/revision retention with absent credentials;
- incomplete-save loading, explicit recovery and preservation of candidate revision;
- observable state, real-readback-only adoption, and A -> B -> A queued old Page rejection;
- departure before late load completion and inert retained UI callbacks;
- predicate invalidation before composition disposal, including permanently latching an owner loss
  observed only by the worker before a later predicate returns true;
- readback failure -> specific enum message -> explicit reload/recovery;
- a real worker **blocked on the actual store monitor**, revoked while waiting, then denied with no
  credential read/write or manifest change after acquiring that lock;
- an already-admitted save held in credential I/O, revoked, then fully read back with no publication
  to either old or replacement Page.

`app/src/androidTest/kotlin/app/daiyu/android/GenerationSettingsHostActivityTest.kt` mounts the actual
host in the existing test Activity and exercises actual controlled-field edit/save callbacks,
observable success/readback, system-back disposal, corrupt reload without editable defaults, then
missing-source reload. Its preference/vault adapters assert I/O is not on the Android main thread.

These tests are **written but not run** by the implementation agent. No Gradle, CI, network, device,
or compiler invocation was performed. Build compatibility, actual test outcomes, and device lifecycle
behavior must be verified by the supervising agent. Existing store tests remain applicable; these
additions are not a claim of crash-proof storage or proof of paid-run integration.

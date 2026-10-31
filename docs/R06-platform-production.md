# R06 — structured platform read tools

## Scope and source evidence

The initial leaf change extended the central Catalog with app-identity, read-only Android adapters. The production-settings follow-up reuses all 13 existing `PlatformReadTool` leaves without rewriting them, and wires `MainActivity`, `ChatController` and the minimal Manifest permissions. It does **not** extend the legacy `ReadOnlyTool` enum, introduce a shell path, automatically acquire grants, or claim device execution. GUI execution tools, terminal, CI and the unique reconstruction checklist are unchanged. The same `RunDeviceResources` run/task identity and close/drain gate remain the owner; there is no second platform catalog or owner. Historical candidate provenance below is retained as earlier recorded evidence, not independently reverified hashes in this follow-up.

Reviewed current sources: `docs/重构清单.md` (R06, candidate-reuse and task-07 notes), existing `platform/AndroidBatteryTool.kt`, `AndroidToolRegistry`, `RunDeviceResources`, domain `ToolSchema`, `ToolRegistry` and `ToolDispatcher`. The checklist records historical candidate `03f0f3ae`; candidate existence is not acceptance evidence.

Direct access to `/workspace/daiyu-provider-handoff/` and the first hidden `.agent/` copy was denied by the assigned workspace tool. The parent then supplied a readable, **new-project** snapshot at `docs/reference-device/`, from `/workspace/daiyu-provider-handoff/controller-storage-recovery-local/sources/app/src/main/kotlin/app/daiyu/android/device/`. All seven Kotlin candidates and their supplied `provenance.json` were read. No historical maintainer-owned code live tree was read. The reference directory is not production source and must not be included in the change's commit. Source identities below are the parent's recorded SHA256 values, not hashes independently computed by this shell-less agent:

| Preserved candidate | Supplied SHA256 | Production reuse / exclusion |
|---|---|---|
| DeviceReads.kt | `773956fb041372d5fb92bd54c390ea287cf66f92b2e3314af53cd17cb33fae1e` | `platform/DeviceReads.kt`: network/time/audio read bodies; existing battery/status kept rather than duplicated. Location not exposed in this scope. |
| DeviceQueries.kt | `b8bca8b540a07cd4a834f354b65c7889a4a96b6247df1bf37c7f5ccbfd372518` | `platform/DeviceQueries.kt`: contacts/calendar/media/SAF directory and own-notification read bodies. Add run cancellation checks, total-result/field bounds, field truncation flags; keep selected-media authorization label. |
| DeviceResultPolicy.kt | `06655e730ed92ce0b62aa43a847a1a692d916e990ee6e0b29833b892eec10a55` | `platform/DeviceResultPolicy.kt`: preserve immutable destination-specific consent and default private-result withholding. Extend private names to the new Catalog leaves; shared-result adapter requires both destinations. |
| DeviceToolRegistry.kt | `c4074933df8192a28b60d3e7ec900c2bf94852f953bd66c10cc1f5a24dd5c063` | Only read-result/permission/query helpers extracted to `DeviceReadResults.kt`. The old registry/write approval/schema objects are **not** installed alongside the current Catalog. |
| DeviceNotificationListener.kt | `272118691ff7dd5ec731ccff3c252b9febda8a2c36be0d628d7b2452455c584f` | Reviewed, not copied or advertised as available: no current service declaration/explicit system access; no process-global listener handle introduced. |
| DeviceActions.kt | `233d7a0dc9270b6844c53dd99fa311ccad47b16b353d50dd5122e078a3a6c83f` | Reviewed, writes excluded. Audio's fixed stream map is reused without a dependency on the write adapter. |
| StrictDeviceJson.kt | `67ac0299a302e9e0ac00b4a74f1bf65f5ad7fc033b27344e2d939ff569264660` | Reviewed, not duplicated: existing strict domain ToolSchema validates Catalog input. |

The actual new adapters are `AndroidPlatformReads` and `PlatformReadTool`, the central registration/owner seam, visible-app search, single granted-document metadata/text, and the shared-result consent bridge. This is not a second implementation of the preserved device module or a blanket copy of its historical host.

## Catalog leaves and actual boundaries

All leaves use `ToolEffect.READ_ONLY`. Rows default to 20, maximum 50. Results have a 65,536-character ceiling. Display fields are limited to 256 UTF-16 code units without splitting a surrogate pair; identifiers are not silently cut into different valid-looking URIs. Collection responses flag field clipping as well as row limits. Backend exception messages, provider SQL, paths and URI details do not enter errors or ordinary logs.

| Tool / capability | SDK implementation / scope | Required system access |
|---|---|---|
| `network_status` / `platform.network.read` | Preserved ConnectivityManager transports/validation/metering and optional WiFi/Bluetooth enabled state; no SSID/IP/MAC, HTTP probes or sockets | ACCESS_NETWORK_STATE; WiFi/Bluetooth subsections independently check ACCESS_WIFI_STATE and BLUETOOTH_CONNECT (31+) / BLUETOOTH (older) |
| `device_time` / `platform.time.read` | Preserved current time, timezone, ISO local time and UTC offset | No runtime permission |
| `device_audio` / `platform.audio.read` | Preserved audio stream volume ranges/ringer/music-active state; no media-key or volume writes | No permission acquisition; SDK restrictions are reported |
| `device_environment` / `platform.environment.read` | Non-unique build/model, locale/timezone, configuration, PowerManager state; no serial, Android ID, accounts or location | No runtime permission |
| `platform_permissions` / `platform.permissions.read` | Current app permission observations and scope descriptions only | No runtime permission; never requests any grant |
| `search_apps` / `platform.apps.read` | PackageManager packages visible under Android filtering; scans at most 512, explicitly **not** a complete inventory | Existing package visibility only; no QUERY_ALL_PACKAGES request; private-result consent also required |
| `search_media` / `platform.media.read` | Preserved MediaStore image/video/audio metadata, MIME/size/date and content URI; no DATA path or bytes | READ_EXTERNAL_STORAGE through API32; corresponding READ_MEDIA_* on API33+. API34 selected-visual access is allowed only as explicitly labelled `selected_only`, not full library access |
| `list_files` / `platform.files.list` | Preserved SAF exact persisted-read-granted tree's **direct children** only, max 500 scanned; no recursion or raw paths | Existing exact persisted tree read grant; does not obtain grants |
| `file_metadata` / `platform.files.metadata.read` | OpenableColumns for **one explicitly granted content URI** | Existing URI read grant; no SAF launch, grant acquisition or ambient filesystem access |
| `read_text_file` / `platform.files.text.read` | Existing granted URI, text/* MIME, strict UTF-8, at most 8192 bytes; oversized/invalid encoding rejected, not silently cut | Same explicit URI grant; no file://, network URI, URI user-info or fragment |
| `calendar_events` / `platform.calendar.read` | Preserved CalendarContract instances; required [start_ms,end_ms], max 31 days; bounded title and all-day flag | READ_CALENDAR |
| `search_contacts` / `platform.contacts.read` | Preserved Contacts name filter and display names/IDs only; Catalog requires nonblank filter, no full dump/numbers/notes/photos | READ_CONTACTS |
| `app_notifications` / `platform.notifications.read` | Preserved NotificationManager read narrowed to active notifications **posted by this app only**, bounded title/text; no historical cache | No listener required for own notifications. POST_NOTIFICATIONS governs posting, not access to other apps' notifications |

Existing `get_battery_status` and `device_status` continue to use `AndroidReadOnlyTools`. No whole-device notifications, cached location, privileged database, arbitrary filesystem traversal or Root/Shizuku operation is advertised. Such access is not emulated by empty success or shell commands. No new privileged interface is added; existing explicitly authorized Root/Shizuku modules remain the only privileged route.

The Manifest retains INTERNET/VIBRATE/RECORD_AUDIO and now declares ACCESS_NETWORK_STATE/ACCESS_WIFI_STATE, BLUETOOTH (`maxSdkVersion=30`)/BLUETOOTH_CONNECT, READ_CONTACTS, READ_CALENDAR, READ_EXTERNAL_STORAGE (`maxSdkVersion=32`), READ_MEDIA_IMAGES/VIDEO/AUDIO and READ_MEDIA_VISUAL_USER_SELECTED. Runtime requests are user-clicked and API-gated: Bluetooth enabled-state read on API31+, legacy storage through API32, per-kind media on API33+, with selected-visual choice on API34+. There is no Bluetooth scan/location grant or paired-device enumeration. No WRITE_*, all-files/all-packages grant, notification posting grant or notification listener is added. Own active notifications do not require POST_NOTIFICATIONS. Network status uses its normal manifest permission, not a dangerous-runtime prompt. Missing protected permissions still return `ok:false`, `error:permission_denied` before protected provider/service reads; private projections retain the diagnostic without data. Declaration is **not** evidence permission was obtained or user data read. Empty authorized provider results differ from denial. Revocation between check and SDK access yields a sanitized permission_denied response.

**Safe refusal ordering exception: `list_files`.** Unlike contacts/calendar/media and explicitly granted single-URI checks, `list_files` tests BOTH outbound consents **before** inspecting its SAF tree or the resolver's persisted grants. Without both consents it returns `private_result_consent_required` with redaction and does not touch the resolver, even for an invalid/ungranted tree. Only after both consents does `DeviceQueries.files` validate the tree and exact persisted read grant before querying direct children. This is intentional safe refusal, not a claim that missing-tree diagnostics always take precedence. Settings explains the required exact tree without probing provider data.

## Production settings, freeze and unchanged owner seams

1. `RunDeviceResources(context, resultPolicy = DeviceResultPolicy())` constructs the existing battery backend and `AndroidPlatformReads`. `AndroidToolRegistry.create(resources, additionalTools, enableUserBarrier)` registers its real platform leaves; no placeholder adapter is registered. The public `create(context, additionalTools, enableUserBarrier, resultPolicy)` follows the same path but remains unbound and cannot execute without a host run binding, as before.
2. Keep using the **same** resources instance already owned by `RunCatalogTools`. Its existing `bind(owner, authorization, capabilities)` binds the exact task/frozen capability object. New reads use the same in-flight counter, close fence and acknowledgement. Pure admission verifies owner/task identity **before ledger replay** as well as fresh reads; execution rechecks under the read-lease lock.
3. `ChatController.platformSettings` is a separate `PlatformToolSettings` store (`platform-read-settings` preferences). It never loads provider credentials, migrates the battery/status switches, or defaults to any enabled leaf. Each selection/model-consent/history-consent click persists one tool name and one decision only. `sendPrepared` calls `platformSettings.freeze()` once, before draft/history/model preparation I/O, capturing immutable selection and `DeviceResultPolicy`. It uses:

   ```kotlin
   val enabledCapabilities = AndroidToolRegistry.capabilitiesFor(
       config.enabledTools(), // existing Set<ReadOnlyTool>
       platformAllowed = frozenPlatformSelection
   )
   ```

   The production invocation passes `platformSnapshot.platformAllowed` and combines it with other modules in the existing `SessionCapabilities`. `RunDeviceResources(application, resultPolicy = platformSnapshot.resultPolicy)` is used by both `AndroidToolRegistry.create` and `RunCatalogTools`, which binds that same resources instance to Runner's real owner/task/capabilities and retains the existing drain fence. Virtual-display-exclusive runs continue to exclude battery/status/platform reads, preserving that existing boundary. All platform selections default empty. Edits affect the next run; they do not mutate a running policy/capability object. Stop the active run for immediate cancellation; Android permission or URI-grant revocation is also rechecked at actual SDK access. Registration alone never grants access.
4. **Preserve private result consent independently of Android and Catalog grants.** The preserved policy defaults to withholding app inventory, notifications, contacts, calendar, media and files. This Catalog has one payload shared by ledger/model/history, not separate transient-local and persistent destinations. Therefore only the intersection of explicit model and history consents can be delivered, and without both consents the adapter does not perform a private data read. Example host-only construction after explicit user choices:

   ```kotlin
   val policy = DeviceResultPolicy(
       modelPrivateTools = frozenModelPrivateToolNames,
       historyPrivateTools = frozenHistoryPrivateToolNames
   )
   val devices = RunDeviceResources(context, resultPolicy = policy)
   val registry = AndroidToolRegistry.create(devices, contextTools, enableUserBarrier)
   // Give this same devices to RunCatalogTools, with the frozen capabilities above.
   ```

   Use new Catalog tool names (e.g. `search_contacts`), not blanket private-domain enablement. Policy input sets are copied. Model-only/history-only consent cannot leak local private results into the common ledger. Runtime permission and explicit single-URI denial diagnostics precede consent withholding; **`list_files` is the safe-refusal exception described above**. No-outbound-consent returns `private_result_consent_required` with `privacy_redacted:true` without private-content reads. Capability enablement is not destination consent.
5. Exact UI entry: **Settings → Tools → Platform read tools and private outbound / 设置 → 工具 → 平台只读工具与私密出站** (`SettingsCatalog.platform_tools`, Main route `platform-settings`, screen tag `platform-tool-settings`). Settings → Permissions → Permission check (`permission_check`) opens the same page. All 13 leaf choices show system-access scope/denial requirements; the eight private leaves have independent model and history/ledger consent switches. Runtime grant buttons request only the clicked permission (API34 visual requests also offer selected-only access); application system-settings link permits revocation.

   The same page offers explicit **选择目录树（list_files）** and **选择单个文档（metadata/text）** actions. Main's Activity Result launchers issue read-only ACTION_OPEN_DOCUMENT_TREE / ACTION_OPEN_DOCUMENT intents. Only a current page-owner result with RESULT_OK, a valid exact content URI, and returned persistable+read flags may call `takePersistableUriPermission` with **READ only**. A tree-prefixed descendant cannot masquerade as an ancestor tree. `PlatformSafGrants` tracks only grants picked on this page, intersects them with live persisted read permissions, and offers exact read-grant revocation. URI is displayed/copied only on explicit user action; no URI or file contents are automatically sent to a model. URI revocation cannot retract already persisted results. Cancelled/stale/page-left results do not persist grants or alter settings. The leaves themselves still never launch a permission/SAF/root/Shizuku prompt. No all-files permission is needed.
6. Whole-device notifications would require a separately reviewed, declared and explicitly enabled NotificationListenerService with a trusted, revocable run adapter. No service is declared here; do not relabel `app_notifications` as all-device support or add an unbound historical cache as an empty-success leaf.

## Bounds, cancellation and sensitive data

Schemas reject unknown fields, wrong types and limits outside 1..50. Calendar cross-field validation and blank contact filters reject the whole batch before ledger reservation or any read. Model arguments cannot supply owner, task, capability, delivery policy or elevated identity. Cancellation is checked before SDK access and during bounded cursor/text consumption. A synchronous Android provider/stream call may still block; close acknowledges only when it returns. This is cooperative cancellation, not a claim of instantaneous binder cancellation.

The PackageManager API materializes its Android-filtered list before the 512-item scan bound; the adapter bounds consumption/results, not the platform's internal allocation. SAF providers may ignore query hints; cursor loops remain bounded. On a provider failure, no partial payload is declared a successful complete read. Oversized aggregate results are rejected rather than returned without bounds.

With both destination consents, requested private results necessarily enter the existing tool ledger/history/model channel. Those are **sensitive result channels**, not ordinary diagnostic logs. This change adds no logging; exception details are discarded. Existing export and retention policy still applies. A read grant is not permission to emit raw data to logcat. Supplied text is untrusted tool-result content, not executable instructions.

## Verification status

Added `PlatformCatalogTest`: explicit capability exposure; owner/task admission including cached receipts; replay without another backend sample; whole-batch schema/time-window rejection; closed/unbound/cancelled paths and schema bounds. Its injected backend emits a labelled test-only result, not a production data source.

Added `DeviceResultPolicyTest`: preserved default withholding, copied consent sets, separate destination projections, shared-channel intersection and retained safe permission diagnostics.

Added Android `PlatformPermissionBoundaryTest`: fixture ContextWrappers whose resolver/service/package inventory access throws. These denial-only fixtures check missing permission/URI grant, URI scheme boundaries, cancellation/invalid intervals, permission observations, surrogate-safe bounds, and that private data is not touched without both consents even when synthetic system grants exist. No fixture reads real user contacts, calendar, media, apps, notifications or files.

Added Android `DeviceQueryBoundsTest`: an in-memory, unregistered fixture provider deliberately ignores query-limit hints; assertions cover bounded rows/field clipping, truncation metadata, cursor close and cancellation before provider access. This fixture is not a production data source and never connects to an installed user-data provider.

Production follow-up test sources:

- `PlatformProductionSettingsTest` (Robolectric, synthetic isolated preferences/URIs): default-all-off despite legacy switches; per-tool persisted selection; model-only/history-only/both intersection; copied per-run values; unknown/corrupt stored values; API26/30/31/32/33/34/37 request planning; read-only SAF intents, exact URI rejection, persistence/revocation/system-loss and denied-grant paths using injected grant functions. No live ContentResolver/provider is used.
- `PlatformManifestBoundaryTest`: source Manifest contract for read permissions, old-storage/old-Bluetooth version caps and forbidden broad/write/notification permissions/services.
- `ControllerDeviceResourcesTest.testProductionSendFreezesPlatformSelectionPolicyAndSameResourceBindingWithoutDataReads`: production `sendPrepared` with a synthetic blocked model, verifies same real resources/owner/task/capability object and frozen result policy across setting edits, then ordinary resource cleanup. No platform leaf is executed. Existing fixture authorization reflection was updated to current `boundAuthorization`; existing battery drain tests remain.
- `PlatformToolSettingsActivityTest`: explicit settings/permission-check callbacks; independent persisted selection/model/history actions, permission action without implied consent, and stale-page save rejection. All callbacks are synthetic; no system prompt is launched. `SettingsPresentationTest` tools inventory is updated to include the new entry and the full current count (39).
- Existing `PlatformPermissionBoundaryTest` still covers the deliberate `list_files` no-consent-before-resolver refusal. The 13 production leaves and their bounds/denial tests are unchanged.

No build, host test, instrumentation, shell, full-suite run, permission prompt, paid request or actual user-data read was executed by this implementation agent. The tool only permits source reads/edits; build/test and a **nonempty commit** must be performed by the parent, without branch creation or push. No checklist change was made. Source presence and compilation are not API26/API37.2 or real-device execution evidence. Remaining parent verification: unified source/host and instrumentation tests; Android26/31/32/33/34/37.2 permission denial/revocation/selected-media behavior; SAF picker cancellation, stale/recreated Activity results and exact read-grant persistence/revocation using fixture documents only; confirm ordinary chat/image/video/terminal/browser/Shizuku/virtual-display entry behavior remains unchanged. R06 as a whole must not be marked complete on this source change alone.

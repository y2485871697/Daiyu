# R06 bounded notification increment — implementation evidence

## Sources checked and scope

Read-only evidence: `docs/重构清单.md` R06 row / device-adapter authorization notes; `docs/R06-platform-production.md`; current `PlatformReadTool`, `AndroidPlatformReads`, `DeviceQueries`, `DeviceResultPolicy`, `AndroidToolRegistry`, `RunDeviceResources`, platform settings and manifest. The starting catalog has **13 platform leaves**, including `app_notifications` implemented by the own-app NotificationManager query. This increment does not rewrite that leaf, its `own_app_only` observation, or legacy battery/status tools. No historical candidate source tree, credentials, network, device, shell or Git operation was accessed. The unique checklist and update notes were not edited.

Two independent read-only leaves are added:

| Leaf | Scope |
| --- | --- |
| `current_notifications` / `platform.notifications.current.read` | Cross-app currently active notifications exposed by the connected Android listener; at most 500 scanned, 50 returned (20 default), 48KiB UTF-8 row payload budget. Not a historical inventory. |
| `notification_history` / `platform.notifications.history.read` | Posted-event records collected **only after an explicit local-recording opt-in**, optional exact package/text/capture-time filters. No OS notification-history database, backfill, pre-grant data, or snapshot-on-connect. |

Both use strict `ToolSchema`, the existing registry, exact run/task/session owner and capability checks, run close/drain, and immutable `DeviceResultPolicy`. Both names are private. The same payload is persisted and sent to the model, so **both** model and conversation-history destination consents are required **before** any current snapshot or history content read. Local recording opt-in is a fifth, separate decision, not the conversation-history destination consent.

## Special access and connection

`CrossAppNotificationListener` is a dedicated `NotificationListenerService` in the app process. The manifest exports only its system binding surface, protected by `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` and the precise listener action. This is a service binding restriction, **not** a `uses-permission` grant. No `POST_NOTIFICATIONS`, all-packages, all-files, privileged permission, root, Shizuku, or new network dependency is introduced.

The platform settings rows explicitly open `ACTION_NOTIFICATION_LISTENER_SETTINGS`; opening the page does not request access. The callback never grants run capability or outbound consent. API27+ uses `NotificationManager.isNotificationListenerAccessGranted` for this exact component; API26 compares exact components in the system `enabled_notification_listeners` setting. The app's connected-service identity is checked separately. System granted + disconnected is an error, not empty success. Authorization is checked before and after a snapshot and after filtering. Revocation, disconnected/changed listener and cancellation discard pending content. No blanket current-notification cache is maintained.

The platform backend's admission method is checked before ledger replay, outside the run lock. Successful notification admission freezes its listener/capture generation for that run. Reconnect, recording epoch changes or observed revoke cannot authorize reuse of a prior generation's cached receipt. An in-flight notification read rechecks run ownership/close/cancellation/live special access before publishing. Stopping a run does not disable user-chosen independent background recording; the separate local-recording switch does.

## Local recording lifecycle and storage

`ChatApplication.notificationAccess` is the single process host. `NotificationHistoryStore` uses **the controller's existing `ConversationDatabase` helper**, not a second SQLiteOpenHelper or process. Database version 17 installs `notification_capture` and `notification_records` on create and upgrade, before existing >=13 migration early return. There is no data backfill or migration grant. The parent must compose this schema version if another simultaneous task also changes it.

- Default is off; system access, connection, selecting a tool and either outbound consent do not enable recording.
- Enabling requires actual access and connection, erases any prior local capture, and starts a new timestamped epoch. Only future posted callbacks extract bounded package/title/text fields. Removal events, icons, intents, extras dump, notification keys, actions and attachments are not stored.
- Off immediately stops field extraction and deletes these independent local records. It does **not** silently alter previously delivered chat/tool-history messages; the UI says so.
- Fixed logical TTL: **7 days**, at most **500 rows**, at most **512KiB serialized UTF-8 row content**, at most **4096 bytes/row**. Oldest rows are evicted. Each record has separate `captured_at` and `posted_at` when sourced from Android. History time filters apply to capture time.
- Expired and future-dated records are purged on store append/query, listener connection, and periodic connected-listener maintenance (one minute). No alarm, foreground service, wake lock or network job is introduced. Android may kill/suspend the process; physical cleanup then runs at the next maintenance/access opportunity, while the query always enforces the TTL. SQLite deletion is not a claim of secure physical-media erasure.
- Revocation observed by listener lifecycle, periodic maintenance, settings, admission or read clears local records and disables capture. Disconnection alone stops new events and makes reads fail; retained capture is explicitly best effort, never represented as gap-free.
- History disabled returns `notification_history_not_recording`. Authorized enabled empty history returns `coverage: best_effort_since_opt_in_only`, `recording_since_ms`, `complete_inventory:false`, `empty_means_no_notifications:false`. No records before opt-in or during gaps is **not** evidence of no recent notifications.

Background collection has no model/ledger callback. The store is internal and not a Catalog leaf; the only production delivery path is the existing platform read/dispatcher/policy path. Exception messages and notification content are never logged or included in diagnostics. SDK runtime/security errors use existing sanitized read failures. The cache is not added to neutral export/import; normal app backup remains disabled.

## Tests authored, not executed by this agent

`CrossAppNotificationsTest` (Android synthetic store/source fixture, no actual OS grants or notifications):

1. No grant vs granted/disconnected vs authorized empty snapshot.
2. Neither / model-only / history-only destination consent rejects before source access.
3. Revocation during snapshot discards content, clears capture and fences admission.
4. Disconnect during snapshot and stale source identity.
5. Separate recording opt-in, zero pre-opt-in extraction, no backfill, honest empty status, posted capture, off/clear and off stop-extraction.
6. Exact package, text, capture-time and limit filtering; excessive time window denial.
7. Byte/count/7-day eviction and persisted explicit recording state across host replacement.
8. Version-16 upgrade installs disabled/empty capture without rewriting an existing conversation.

`NotificationRunBoundaryTest` (JVM synthetic backend): run capability cannot be replaced by OS grant; exact authorization identity; successful receipt replay does not resample; revoke/disconnect/stop deny cached receipts; stop or revoke during a snapshot does not publish the old private payload; cancellation before execution does not read.

Existing platform manifest test now verifies the sole dedicated listener's signature binding restriction/action/process boundary while retaining the ban on POST_NOTIFICATIONS and privileged `uses-permission` substitutes. Existing settings roundtrip test covers the extended catalog without fixing a cardinality that conflicts with the parallel location increment.

## Verification limits / parent integration

Only source inspection and scoped source edits were possible. **No build, unit/instrumentation test, lint, actual listener grant, device interaction, background retention timing measurement, branch, commit or push was performed.** Parent must run production compilation and the named tests; synthetic tests do not establish real OEM listener behavior, sensitive-notification system redaction, managed-profile coverage, process-death callback timing or actual special-access lifecycle. Results deliberately say incomplete inventory.

Shared-file hunks to compose with location: `PlatformReadTool`, `DeviceResultPolicy` private names, `AndroidPlatformReads` when/admission, `PlatformSettingsAccess.status`, the one listener-settings branch in `MainActivity`, the small notification row invocation + neutral catalog-count wording in `PlatformToolSettingsScreen`, and application singleton/schema integration. Existing stale hardcoded database-version assertions elsewhere in the repository were observed (some already expected 10/14/15 before this change); they were not mass-edited or represented as passing.

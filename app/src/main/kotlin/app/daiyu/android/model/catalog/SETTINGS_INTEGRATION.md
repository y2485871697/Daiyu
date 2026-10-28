# Current remote synchronization behavior

The production wrapper now performs two phases: a nonempty successful fetch first removes
missing records explicitly marked `CatalogModelSource.REMOTE` through the existing host batch
deletion callback, then offers only not-yet-added normalized IDs for explicit append. Comparison
uses trim plus lowercase; wire IDs, stable local identities and local settings remain intact.
Empty/failed responses do not prune. Dismissing the add dialog does not undo committed cleanup.
Both production hosts reject synchronization from unsaved connection drafts. Batch completion
must be delivered even after cancellation; the session and local screen own publication fences.

The optional source codec field defaults to LOCAL for older unmarked records. Their remote or
manual origin cannot be recovered from this old schema, so they remain protected from automatic
pruning. New remote imports persist REMOTE; manual/fixed-catalog records stay LOCAL.

The older single-provider integration notes below are historical, not the current host wiring.

# P06: host-owned catalog integration seam

This patch supplies a real file transaction backend and a complete settings wrapper, not a
read-then-save adapter. `SavedModelCatalogStore` and its reviewed contract are unchanged.
The baseline has **one active `ModelSettings`**, not a durable provider/model registry.
`FileSavedModelCatalogPersistence` is to become the **only catalog storage owned by that
settings store**. Do not instantiate another catalog in Main/Compose, mirror its model list in
preferences, or continually reconstruct it from the active model/remote fetch. The one-time
migration below is the only use of active settings as a catalog source. Credentials stay solely
in the existing settings store.

## Parent-owned minimal `ModelSettingsStore` change (not applied in this worktree)

All code below is within the app module, so the internal APIs are accessible.

```kotlin
// Imports from app.daiyu.android.model.catalog and java.io.File.
// One backend, one fixed host slot. NEVER derive the filename/identity from a URL.
internal val modelCatalog = FileSavedModelCatalogPersistence(
    File(context.filesDir, "model-catalog/primary.bin"), CatalogModelParametersCodec
)

internal fun modelCatalogSnapshot(): SavedModelCatalogSnapshot<CatalogModelParameters> =
    modelCatalog.registerFrom {
        // This supplier runs only once, under the catalog's lock, before registration commits.
        // loadForEditing MAY decrypt the credential internally. Do not re-encrypt/save it or
        // put it in the catalog. Keep the EditingSettings flags, not only its fallback settings.
        val editing = loadForEditing()
        val current = editing.settings
        check(current.endpoint.isNotBlank() && current.model.isNotBlank()) {
            "请先保存服务商配置"
        }
        check(current.protocol != ProviderProtocol.UNKNOWN)
        // fromEditing rejects requestOptionsUnavailable; defaults from a corrupt policy must
        // never become a durable migrated record. The user must repair/save the policy first.
        listOf(CatalogModelParameters.fromEditing(editing))
    }
```

`credentialUnavailable` alone does not mean model parameters are invalid: credentials are not
catalog fields. `requestOptionsUnavailable` does mean the returned request options are only a
fallback, not the saved policy, so migration fails before registration writes anything. This is
not a claim that `loadForEditing` avoids decryption; the existing implementation can decrypt the
credential as part of loading. This migration simply neither re-encrypts nor persists it.

Wrap the existing complete bodies of **save**, **selectModel**, and **applyNonSecretRestore**:

```kotlin
fun save(/* UNCHANGED existing arguments */): Unit =
    modelCatalog.configurationChange {
        synchronized(lock) {
            // UNCHANGED existing save body, validation, key handling, rollback and publication.
            // Existing return@synchronized statements retain their meaning.
        }
    }
```

Use the same outer boundary on selectModel and applyNonSecretRestore. Acquire the catalog
boundary BEFORE the existing settings lock everywhere. Nested selectModel -> save is supported:
the OS file lease is reentrant on the same thread. Two nested edits may advance revision twice;
this is deliberately conservative. Even failed/no-op config saves may invalidate a fetch, never
revive a stale session. Initialization before the first provider registration writes no metadata.
Do not add catalog fields to the existing credential rollback list or backup restore schema.
A catalog-only append must **not** invoke any of those three active-settings mutation APIs.

For future actual provider deletion/replacement, delete the old identity via
`modelCatalog.deleteProvider(oldIdentity)` inside its configuration boundary BEFORE deleting
host configuration. Only explicit re-registration allocates a new UUID; ordinary endpoint,
protocol, credential or model edits retain the UUID and advance revision. A tombstone rejects
all callbacks for a deleted identity, even after reopening or recreating a provider. Distinct
host provider slots must have distinct host-owned files; no operation touches other slot files.

## Parent-owned Main/settings call (not applied in this worktree)

At `modelSettings()` opening, after obtaining `editingSettings`, obtain and retain
`controller.settings.modelCatalogSnapshot()` for this editing session. This opening/migration
step also does disk/lock work: run it on an IO worker and apply it on UI only if the same host
editing/lifecycle token is still current. If there is no saved provider, show “请先保存服务商配置”
and keep the existing plain settings screen; never invent an identity from the draft. After a first successful settings save, the next settings opening
can migrate/register it. A failed/corrupt catalog must surface an unavailable error, not silently
allocate a replacement identity.

Replace the settings rendering call, not the chat/current-model picker:

```kotlin
ProviderCatalogSettingsScreen(
    draft = value,
    onDraftChange = { changeModelDraft(it) },
    onSave = { saveModelSettings(it) },
    onBack = { returnToChat() },
    providerIdentity = editingCatalogSnapshot.providerIdentity,
    persistence = controller.settings.modelCatalog, // SAME store-owned object
    createModel = { CatalogModelParameters.newModel(it) },
    enabled = controller.ready && controller.active == null && !controller.dataBusy,
    errorText = modelError,
    credentialUnavailable = editingSettings?.credentialUnavailable == true,
    requestOptionsUnavailable = editingSettings?.requestOptionsUnavailable == true,
    fetchCatalog = { value, cancelled, deliver ->
        if (!activityUsable() || retainedPage != "models" || modelDraft != value ||
            controller.dataBusy || cancelled()) {
            false
        } else {
            val base = checkNotNull(editingSettings).settings
            controller.fetchModelCatalog(value.toSettings(base), cancelled) { models ->
                if (activityUsable() && retainedPage == "models" && modelDraft == value &&
                    !cancelled()) deliver(models)
            }
        }
    },
)
```

Here `value.toSettings(base)` is Main's **existing** conversion/validation path; no new credential
lookup is necessary. The wrapper invokes this callback only after capturing the provider ID
and current revision on an IO worker. Both the initial snapshot and the fetch-completion
revision read suspend off UI, then recheck the same token on UI; draft edits, replacement
requests, and composition disposal invalidate pending reads as well as network callbacks.
The existing controller API uses null for failure;
null/rejection/empty catalogs do not open a selection dialog. The wrapper marshals callbacks to
its UI scope, cancels obsolete requests on edits/disposal, and writes only on confirm. It does
not use Main's old remote-result list or its draft-model-click callbacks.

Do not call Main's old `fetchCatalog` in addition to the callback above: that old method populates
the legacy clickable list rather than delivering to the import session. Remove/deactivate that
old settings path when replacing the call; the separate chat picker remains unchanged.

## Transaction and cancellation boundaries

- On-disk UUID and revision survive reopen. Missing file is eligible for explicit registration;
  malformed/truncated/unknown-version files fail closed, never auto-reset.
- A canonical-path monitor plus an OS lock covers read/check/write across backend instances.
- New records are validated/encoded before writing. Old encoded records are copied verbatim;
  no re-encoding of old parameters, model selection, or whole-settings `save` occurs.
- **Process-crash atomicity, not power-loss durability:** a bounded, file-fsynced temporary
  file is atomically renamed. No non-atomic fallback is allowed. The parent directory is **not
  fsynced**, including on initial directory/file creation; sudden power loss or OS crash may
  lose the rename/registration. Neither old-or-new recovery nor persistence after power loss is
  promised. `COMMITTED`/`Applied` here mean a successful atomic replacement visible to subsequent
  process reads, not a certificate that directory metadata reached stable storage. The generic
  store contract has no explicit power-loss outcome; this backend must not be used where that
  stronger guarantee is required without revisiting the backend **and** result/host contracts.
  Cancellation is checked immediately before rename. After rename, no cancellation check,
  codec invocation, or exception from cleanup can turn the committed result into `FAILED` or
  `CANCELLED` and invite a retry. We deliberately do not bolt on a fallible post-rename directory
  sync and report ordinary failure: it would violate the unchanged store contract.
- All-existing selections still check ID/revision under the transaction lock, but write nothing.
- Cancellation after success never rolls back. A disposed screen may stop displaying a result;
  the durable catalog remains authoritative when reopened.
- This is app-private Android/JVM filesystem storage using `ATOMIC_MOVE` (API 26+). It assumes
  cooperating writers use the backend and host edit boundary; arbitrary external file edits,
  alternate catalog mirrors, and cross-module bypasses are not a supported source of truth.

## Incremental verification supplied (not executed by subagent)

`FileSavedModelCatalogPersistenceTest`: restart identity/revision, exact old records,
config-version fences, delete/recreate, other-provider isolation, cancellation around rename,
real two-instance races, codec failure, corrupt-file fail-closed, nested host edits, empty CAS.

`CatalogImportSessionTest`: fetch -> checked append, no fetch write, stale results, cancelled
requests, stale revision/provider deletion, failing factory retry, post-commit cancellation,
rejected/forged/empty confirmation. Latch-controlled pending initial/completion reads verify
worker-thread disk access, UI-thread publication, and invalidation/replacement/rejection while
IO is suspended; an obsolete initial read must not admit a network request.

`CatalogModelParametersTest`: current-record migration, detached defaults/local UUIDs,
no credential/endpoint fields, strict malformed-record rejection, unavailable-policy migration
leaves no catalog/pending file and can retry only after repair (not credential decryption).

`ProviderCatalogSettingsScreenTest` (instrumented): actual wrapper + real file backend,
selected-only confirmation with unchanged current draft/no settings save, cancellation/draft
change/late callback, and revision-change rejection. Its persistence wrapper rejects any UI-thread
read, and tests wait for asynchronous admission/dialog delivery. Existing dialog tests remain
unchanged. These tests do not simulate or prove power-loss durability.

Build/test/integration are intentionally left to the parent. No Main, controller, database,
assistant store, manifest, or reviewed public store-contract changes are part of this patch.

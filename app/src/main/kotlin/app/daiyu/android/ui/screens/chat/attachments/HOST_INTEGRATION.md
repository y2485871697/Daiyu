# P17 image attachment UI — host integration boundary

This directory implements the standalone attachment UI and its UI state machine. It does **not**
wire `ChatComposer`, `ChatScreenActions`, `MainActivity`, SAF, request encoding or asset ownership.
The only attachment model is the existing `media.ImageAttachment`; no `MessageBlock.Image` fields
are assumed. The existing `ChatScreenActions.onAttach` / image menu action remain host entry points.

## Render and callbacks

The host owns `ImageAttachmentsState(ImageAttachmentDraft(owner, revision), restoredReadyRefs)`.
Collect `holder.state` (prefer lifecycle-aware collection), then render `ImageAttachments` alongside
its composer. Supply the same Miuix theme already used by the chat. Example callback mapping:

```kotlin
ImageAttachments(
    state = uiState,
    actions = ImageAttachmentsActions(
        onSelect = { renderedDraft ->
            holder.requestSelection(renderedDraft)?.let { ticket ->
                // Host launches its multiple-image SAF picker, retaining THIS ticket.
                launchPicker(ticket)
            }
        },
        onPreview = { holder.openPreview(it) },
        onRemove = { key ->
            holder.remove(key)?.let { removed ->
                // Host cancels import for Importing; deletes only owned draft storage for Ready.
                // A failed/stale remove returns null; do not delete anything in that case.
                disposeRemovedDraftItem(removed)
            }
        },
        onDismissPreview = { holder.closePreview(it) },
    ),
    loadPreview = { ref, maxEdge ->
        // Existing AndroidImageAttachments.decodePreview(ref.owner, ref, maxEdge),
        // adapted to ImageBitmap by a host-owned cache. UI never receives a provider URI.
        previewCache.load(ref, maxEdge)
    },
)
```

The preview loader runs on IO; it must respect cancellation and return an immutable/shared image
that stays valid while displayed. The UI doesn't recycle host bitmaps. Thumbnail edge is 256;
full-screen preview requests 2048 and supports pan/zoom without changing original bytes. Decode
failure offers a retry and keeps the attachment. Cache eviction/lifetime remains a host concern.

## Picker and import

* Capture the `ImageAttachmentSelection` **before** launching SAF. Store the association with the
  launcher result in the host, never by consulting whichever conversation is currently visible.
* A null/empty/cancel result calls `cancelSelection(ticket)` (or `beginImport(ticket, 0)`). It does
  not clear any existing images. A launcher error also cancels that ticket; host presents its error.
* Validate returned count against `ticket.remaining` before importing; oversized results are not
  silently truncated. `beginImport(ticket, count)` reserves ordered keys for the chosen sources.
* For each reserved key, import using **ticket.draft.owner**, then call
  `importSucceeded(key, existingImageAttachment)` or `importFailed(key, failure)`.
  Per-item cancellation removes just its empty slot; other failures stay visible/removable.
* An adoption result of `false` means the produced asset was NOT adopted (removed/stale/wrong
  owner/duplicate/limit). The host must dispose an orphan under the **original owner**. Check the
  store's current reference ownership before deleting: duplicate/already-retained IDs must NOT
  be deleted. Likewise, rejected late UI callbacks never authorize deleting a new draft's files.
* UI limits are an early guard, not a replacement for media validation or freeze checks.

## Owner, revision, Activity recreation

`ImageAttachmentOwner` contains conversation AND draft IDs. Increment `revision` whenever the
same draft ID is cleared/replaced. `activate(newDraft, restoredReadyRefs)` invalidates every
old picker, import, preview and send token; switching away and back also changes the private
session. Rebinding an unchanged draft is intentionally a no-op.

Persist only owner/revision and validated Ready attachment references, **not** UI item keys,
selection/submission tokens, URIs, closures, loading/error state or preview state. When reconstructing
the Activity's attachment owner, create a NEW holder from those Ready references. Its fresh session
rejects old callbacks even if owner/revision is unchanged. A restored SAF callback with no live
matching ticket must be dropped, not attached to the newly visible draft. If the host deliberately
retains the holder across configuration changes, its original ticket is valid only for that same
unchanged draft; any new draft must use a new owner/revision via `activate`.

The holder is synchronized and allows completions on different dispatchers. Media work and its
cancellation remain with the host. Preview composition is keyed by the item identity; old loads
cannot publish an image into a replacement draft.

## Sending and acceptance

1. Capture `beginSubmission(renderedDraft)`; it returns null if a picker/import is pending, another
   submission is active, or there are no Ready images. Failed slots are not included in the send.
2. Freeze `submission.attachments` with the existing media API, under `submission.draft.owner`.
   The host performs model capability checks, text/attachment association and request dispatch.
3. Translate the **authoritative host receipt** into `finishSubmission(submission, outcome)`.
   `ChatScreenActions.onSend: (String) -> Unit` alone is NOT an acceptance receipt. Returning from
   that callback, enqueueing a coroutine or an HTTP transport completion does not imply Accepted.
4. Only `Accepted` consumes the exact captured entries, after the host has completed asset takeover.
   Additions made after send started survive. Captured images cannot be removed while pending.
   `Rejected`, `Unknown`, and `Cancelled` keep all Ready images and restore removability.
   Repeated/stale receipts do nothing. A changed owner/revision is never cleared by an old receipt.
5. For `Unknown`, host must reconcile/idempotently resolve the request and gate retries as needed;
   attachment retention alone cannot establish that sending it again is safe. Acceptance after an
   already final Unknown must be reconciled by the host; stale/finished tickets are not reopened.

This branch contains component/state tests, not an SAF-to-model end-to-end test. Parent integration
must connect launchers, freeze/acceptance, disposal and text clearing; run the consolidated build,
JVM and instrumentation suites after that integration.

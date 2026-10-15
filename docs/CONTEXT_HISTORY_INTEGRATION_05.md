# 05 context integration on 03 history artifact

Scope: independent adaptation of ContextService / ContextPolicy against the supplied 03 history implementation (main baseline 8785e22; history artifact 444bfd09). No changes to Conversation, MessageHistoryCodec, ContextStorage, ConversationDatabase, backup owners, or transaction implementation.

## Contracts used (source evidence)

- `core-domain/.../Conversation.kt`: actual `MessageHistorySegment(messageId, role, blocks, protocolItems)`, default empty `Message.historySegments`, and `MessageHistory.expand`. Expansion validates cross-row supplement identities/content on a complete list and preserves authoritative segment order. A segment-only user identity is legal.
- `app/.../storage/MessageHistoryCodec.kt`: actual encode/decode/validate APIs. Validation of one owner does not replace whole-list expansion checks.
- `app/.../context/ContextStorage.kt`: ArchiveCodec already persists nonempty `history_segments` through the actual codec and omits the member for legacy empty histories. No additional ContextStorage change is required for this adaptation.
- Existing 03 `MessageHistoryTest` and storage database fixtures were read before implementation.

## Behavior

- Revision hashes keep the legacy field sequence exactly for empty histories. Nonempty histories add a versioned framed suffix including ordered segment identities, roles, blocks and protocol items. Reordering or protocol-only changes invalidate summaries.
- Estimates and summarizer input use expanded authoritative history, not the display projection. Revision, archive contents and `prefix_count` continue to describe original physical rows. Archives retain owner segments and present standalone supplements without rewriting them.
- Service reads decode and validate history with the real codec and perform complete-list expansion validation. Branch/revise writes encode history with that codec.
- Compression cuts are physical counts and must close owner/supplement references, complete runs, and authoritative tool batches. Segmented histories use conservative whole-run grouping, retaining at least `keepTurns` completed runs; legacy pair policy remains unchanged. An unsafe injected boundary rolls back to a safe cut or returns null before invoking the model. Persisted old active archives are rechecked for closure before application and fall back to originals when unsafe.
- Branch identities are mapped consistently by `(conversation, run, message)` including segment-only users. Physical rows and their segments share mapped identities. Provider text/tool/protocol item IDs and payloads are not rewritten. Attachments follow the mapped physical message ID.
- Branch/revise reject a physical cut separating an owner and a present standalone supplement. In particular, deleting an owner while retaining its supplement is rejected rather than changing its semantic position.
- **Conservative revise limitation:** selecting a standalone supplement referenced by a retained owner is explicitly rejected, with or without replacement text. Merely synchronizing its text would preserve assistant output that followed the old supplement. Full provider-turn cropping is not implemented here. Ordinary user corrections before the owner safely remove that owner and its later rows; they do not leave history references behind.
- Existing idle checks, whole-history revision race check and transactional writes remain. Model failures, empty/nonshrinking summaries, unsafe cuts and detected races do not apply an archive or rewrite original rows. Race fixtures intentionally change one row externally and verify the compressor adds no further changes.

## Added fixtures and execution status

- `core-domain/src/test/kotlin/app/daiyu/android/domain/context/ContextHistoryPolicyTest.kt`: pure JUnit against real domain types; authoritative/display distinction, standalone de-duplication, frozen legacy digest framing, segment order/identity/role/block/protocol changes, physical closure, incomplete tools, run closure and conflicting rows.
- `app/src/androidTest/kotlin/app/daiyu/android/context/ContextHistoryServiceTest.kt`: real Android SQLite/JSON/blob fixtures; archive/summary/reopen, injected boundary and unsafe old archive, branch/revise/reopen with attachment and segment-only identity, failure/nonshrinking/idle/protocol race, conflicting supplement rejection.

Implementation agent has no shell/device capability. Neither new host tests nor instrumentation execution is claimed in this note; host execution and Android source compilation are delegated to the parent agent. Android platform fixtures require later platform execution; compiling them is not instrumentation execution.

## Handoff / out-of-scope risk

No ContextStorage increment is requested: the supplied 03 ArchiveCodec already provides the necessary nonempty segment representation and legacy empty-member omission. Cross-row expansion checks are performed by this service, including full archives before paging/verification.

The parent review identified an existing `NeutralBackupServiceTest` hardcoded schema version 4 while the current database schema is 5. This is a 03/18-owned integration/test expectation risk, not modified by 05. Resolve in that owner's integration, not by weakening history persistence or changing schema here.

## 01本批接收接线（随代码同次提交）

本批选择 `context-complete-from-444bfd09.patch`，不叠加旧service/policy/history路线。Controller现于写入新user/assistant前调用 `ContextService.prepareSend().messages`，只在Responses Session编码边界权威展开；prepareSend保持owner物理行，不提前展开。新建会话仍走原 `database.send`，无猜测的append或save(user)。ContextStorage仍集中整合，不用旧05文件副本覆盖。

新增app JVM测试接线与正式CI task；215项host结果只是domain+纯codec/请求夹具，Android SQLite/重开/branch/压缩用例待API26+37.2执行。压缩generation/取消及运行中补充UI不因本批接线计完成。

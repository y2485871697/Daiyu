# P18 video sidecar database integration candidate

- Schema v12 adds `message_video_inputs`, a same-database child of the physical message row.
- v1..10 retain the reviewed generation v11 migration; v11 only adds video metadata. Both fresh/upgrade install the same table.
- `ConversationDatabase.send(..., videoInputs=...)` inserts and reads back metadata inside the exact accepted user/assistant transaction. Caller must prepare and verify actual message-owned original files before entering SQL; metadata does not grant upload permission.
- SQLiteText reads are byte chunked, and video payloads are bounded to the codec's 1 MiB rather than the generic 64 MiB ceiling.
- Every read/runtime update validates retained sidecar against the physical/expanded message. An incompatible rewrite fails instead of silently orphaning the references.
- `videoInputs(physicalHistory)` reads all supplied accepted rows under one transaction and rejects stale snapshots. Caller must supply complete history, never only UI pagination.
- Conversation deletion verifies metadata deletion before deleting the original rows. It does not claim filesystem GC.
- Legacy text export explicitly rejects videos rather than producing a lossy backup. Full asset backup/restore and parent Controller are not yet integrated.
- Candidate only: codec review, schema/runtime tests, all-history Controller freezing and original-video UI still pending. No build/tests have been run.

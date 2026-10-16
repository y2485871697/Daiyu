package app.daiyu.android.storage

import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.MessageHistory
import app.daiyu.android.domain.MessageHistorySegment
import app.daiyu.android.domain.MessageId
import app.daiyu.android.domain.RunId
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.media.video.MessageVideoOwner
import app.daiyu.android.media.video.MessageVideoReference
import app.daiyu.android.media.video.VIDEO_MIMES
import app.daiyu.android.model.videos.MessageVideoInput
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MessageVideoInputCodecTest {
    private val user = Message(MessageId("user-1"), ConversationId("conversation-1"), RunId("run-1"),
        Message.USER_ROLE, listOf(MessageBlock.Text("text", "not part of the sidecar")), RunStatus.COMPLETED)
    private val owner = MessageVideoOwner(user.conversationId.value, user.id.value)
    private fun ref(owner: MessageVideoOwner = this.owner, index: Int = 1) = MessageVideoReference(
        owner, "12345678-1234-1234-1234-${index.toString().padStart(12, '0')}", "video/mp4",
        91_234L + index, "0123456789abcdef".repeat(4), 12_345L + index, 1920, 1080)
    private fun input(ref: MessageVideoReference = ref()) = MessageVideoInput(ref.owner, listOf(ref))
    private fun encoded() = MessageVideoInputCodec.encode(user, listOf(input()))
    private fun reference(json: JSONObject) = json.getJSONArray("inputs").getJSONObject(0)
        .getJSONArray("references").getJSONObject(0)
    private fun inputOwner(json: JSONObject) = json.getJSONArray("inputs").getJSONObject(0).getJSONObject("owner")
    private fun mutated(change: (JSONObject) -> Unit): String = JSONObject(encoded()).also(change).toString()
    private fun rejected(code: MessageVideoCodecFailure? = null, block: () -> Unit): MessageVideoCodecException {
        val thrown = runCatching(block).exceptionOrNull()
        assertTrue("Expected redacted codec exception, got ${thrown?.javaClass}", thrown is MessageVideoCodecException)
        val failure = thrown as MessageVideoCodecException
        if (code != null) assertEquals(code, failure.failure)
        assertEquals("Message video sidecar: ${failure.failure.name}", failure.message)
        assertNull(failure.cause)
        return failure
    }
    private fun rejectJson(text: String, code: MessageVideoCodecFailure? = null) =
        rejected(code) { MessageVideoInputCodec.decode(user, text) }

    @Test fun allMetadataAndOrderingRoundTripWithoutChangingTextOrProtocol() {
        val refs = VIDEO_MIMES.mapIndexed { index, mime -> ref(index = index + 1).copy(mime = mime) }
        val encoded = MessageVideoInputCodec.encode(user, listOf(MessageVideoInput(owner, refs)))
        val decoded = MessageVideoInputCodec.decode(user, encoded)
        assertEquals(owner, decoded.single().owner)
        assertEquals(refs, decoded.single().references)
        assertEquals(encoded, MessageVideoInputCodec.encode(user, decoded))
        assertFalse(encoded.contains("not part of the sidecar"))
        assertFalse(encoded.contains("data:"))
        assertFalse(encoded.contains("base64"))
    }

    @Test fun validJsonEscapesAndWhitespaceKeepExactIdentityAndCanonicalOutput() {
        val encoded = encoded()
        val escaped = " \n" + encoded.replace("conversation-1", "\\u0063onversation-1")
            .replace("video/mp4", "video\\/mp4") + "\t\r"
        assertEquals(encoded, MessageVideoInputCodec.encode(user, MessageVideoInputCodec.decode(user, escaped)))
        val quoted = user.copy(id = MessageId("user\"quoted"))
        val quotedRef = ref(MessageVideoOwner(quoted.conversationId.value, quoted.id.value))
        assertEquals(quotedRef, MessageVideoInputCodec.decode(quoted,
            MessageVideoInputCodec.encode(quoted, listOf(input(quotedRef)))).single().references.single())
    }

    @Test fun emptySidecarIsExplicitVersionedAndStillBound() {
        val encoded = MessageVideoInputCodec.encode(user, emptyList())
        assertEquals(1, JSONObject(encoded).getInt("version"))
        assertTrue(MessageVideoInputCodec.decode(user, encoded).isEmpty())
        rejected(MessageVideoCodecFailure.OWNER_MISMATCH) {
            MessageVideoInputCodec.decode(user.copy(runId = RunId("run-2")), encoded)
        }
    }

    @Test fun authoritativeHistoryUsesExpandedUsersNotPhysicalAssistantOwner() {
        val second = user.copy(id = MessageId("user-2"))
        val history = history(user, second)
        assertEquals(listOf(user.id, history.id, second.id), MessageHistory.expand(listOf(history)).map { it.id })
        val secondRef = ref(MessageVideoOwner("conversation-1", "user-2"), 2)
        val source = listOf(input(secondRef), input()) // Preserve explicit input order too.
        val decoded = MessageVideoInputCodec.decode(history, MessageVideoInputCodec.encode(history, source))
        assertEquals(source.map { it.owner }, decoded.map { it.owner })
        assertEquals(source.map { it.references }, decoded.map { it.references })
        rejected(MessageVideoCodecFailure.OWNER_MISMATCH) {
            MessageVideoInputCodec.encode(history, listOf(input(ref(MessageVideoOwner("conversation-1", "assistant-1")))))
        }
        val moved = history.copy(id = MessageId("assistant-2"), historySegments = history.historySegments.map {
            if (it.role == Message.ASSISTANT_ROLE) it.copy(messageId = MessageId("assistant-2")) else it
        })
        rejected(MessageVideoCodecFailure.OWNER_MISMATCH) {
            MessageVideoInputCodec.decode(moved, MessageVideoInputCodec.encode(history, source))
        }
    }

    private fun history(vararg users: Message): Message {
        val assistantId = MessageId("assistant-1")
        val segments = users.mapIndexed { index, row ->
            MessageHistorySegment(row.id, Message.USER_ROLE, row.blocks).let {
                if (index == 0) listOf(it, MessageHistorySegment(assistantId, Message.ASSISTANT_ROLE, emptyList()))
                else listOf(it)
            }
        }.flatten()
        return user.copy(id = assistantId, role = Message.ASSISTANT_ROLE, historySegments = segments)
    }

    @Test fun ownerConversationMessageAndRunCannotBeRebound() {
        for (key in listOf("conversation", "message", "run")) {
            rejectJson(mutated { it.getJSONObject("owner").put(key, "foreign") }, MessageVideoCodecFailure.OWNER_MISMATCH)
            rejectJson(mutated { inputOwner(it).put(key, "foreign") }, MessageVideoCodecFailure.OWNER_MISMATCH)
        }
        rejected(MessageVideoCodecFailure.OWNER_MISMATCH) {
            MessageVideoInputCodec.encode(user, listOf(input(ref(MessageVideoOwner("other", "user-1")))))
        }
        rejected(MessageVideoCodecFailure.OWNER_MISMATCH) {
            MessageVideoInputCodec.encode(user, listOf(MessageVideoInput(owner, listOf(ref().copy(owner = MessageVideoOwner("other", "user-1"))))))
        }
    }

    @Test fun ambiguousHistoryAndNonUserOrProtocolReplayTargetsFailClosed() {
        rejected(MessageVideoCodecFailure.INVALID_MESSAGE) {
            MessageVideoInputCodec.encode(history(user, user), listOf(input()))
        }
        rejected(MessageVideoCodecFailure.OWNER_MISMATCH) {
            MessageVideoInputCodec.encode(user.copy(role = Message.ASSISTANT_ROLE), listOf(input()))
        }
        rejected(MessageVideoCodecFailure.OWNER_MISMATCH) {
            MessageVideoInputCodec.encode(user.copy(protocolItems = listOf("{}")), listOf(input()))
        }
        rejected(MessageVideoCodecFailure.INVALID_MESSAGE) {
            MessageVideoInputCodec.encode(user.copy(role = "tool"), emptyList())
        }
    }

    @Test fun duplicateOwnersAndReferenceKeysRejectedButDifferentOwnersCanShareAssetId() {
        rejected(MessageVideoCodecFailure.DUPLICATE_OWNER) {
            MessageVideoInputCodec.encode(user, listOf(input(), input()))
        }
        rejectJson(mutated {
            val inputs = it.getJSONArray("inputs"); inputs.put(inputs.getJSONObject(0))
        }, MessageVideoCodecFailure.DUPLICATE_OWNER)
        rejected(MessageVideoCodecFailure.DUPLICATE_REFERENCE) {
            MessageVideoInputCodec.encode(user, listOf(MessageVideoInput(owner, listOf(ref(), ref().copy(bytes = 1)))))
        }
        rejectJson(mutated {
            val refs = it.getJSONArray("inputs").getJSONObject(0).getJSONArray("references")
            refs.put(refs.getJSONObject(0))
        }, MessageVideoCodecFailure.DUPLICATE_REFERENCE)
        val secondOwner = MessageVideoOwner(owner.conversationId, "user-2")
        val decoded = MessageVideoInputCodec.decode(history(user, user.copy(id = MessageId("user-2"))),
            MessageVideoInputCodec.encode(history(user, user.copy(id = MessageId("user-2"))),
                listOf(input(), input(ref().copy(owner = secondOwner)))))
        assertEquals(2, decoded.size)
    }

    @Test fun schemaIsClosedAtEveryLevelAndFieldsAreRequiredAndTyped() {
        val nodes: List<(JSONObject) -> JSONObject> = listOf({ it }, { it.getJSONObject("owner") },
            { it.getJSONArray("inputs").getJSONObject(0) }, { inputOwner(it) }, { reference(it) })
        nodes.forEach { node ->
            rejectJson(mutated { node(it).put("extra", "secret") }, MessageVideoCodecFailure.INVALID_FIELDS)
            rejectJson(mutated { val o = node(it); o.remove(o.keys().next()) }, MessageVideoCodecFailure.INVALID_FIELDS)
        }
        rejectJson(mutated { it.put("inputs", "[]") }, MessageVideoCodecFailure.INVALID_FIELDS)
        rejectJson(mutated { it.put("owner", JSONObject.NULL) })
        rejectJson(mutated { reference(it).put("mime", 123) }, MessageVideoCodecFailure.INVALID_FIELDS)
        rejectJson(mutated { reference(it).put("bytes", "91235") }, MessageVideoCodecFailure.INVALID_FIELDS)
        rejectJson(mutated { it.put("version", 2) }, MessageVideoCodecFailure.UNSUPPORTED_VERSION)
        rejectJson(mutated { it.put("version", "1") }, MessageVideoCodecFailure.INVALID_FIELDS)
    }

    @Test fun numericTokensNeverRoundTruncateOrCoerceOnAnyField() {
        val json = encoded()
        val values = listOf("1.0", "1.5", "1e0", "1E+3", "-1.0", "9223372036854775808", "-9223372036854775809",
            "+1", "01", "0x1", "NaN", "Infinity", "true", "null")
        val fields = listOf("version" to "1", "bytes" to "91235", "duration_ms" to "12346", "width" to "1920", "height" to "1080")
        fields.forEach { (key, original) -> values.forEach { raw ->
            rejectJson(json.replace("\"$key\":$original", "\"$key\":$raw"))
        } }
        for (key in listOf("bytes", "duration_ms", "width", "height")) {
            for (raw in listOf(0L, -1L, Long.MAX_VALUE, Long.MIN_VALUE)) {
                rejectJson(mutated { reference(it).put(key, raw) }, MessageVideoCodecFailure.INVALID_REFERENCE)
            }
        }
        rejectJson(mutated { reference(it).put("width", 4_294_969_216L) }, MessageVideoCodecFailure.INVALID_REFERENCE)
    }

    @Test fun strictGrammarAlsoRejectsAndroidJsonExtensionsAndDecodedDuplicateKeys() {
        val json = encoded()
        listOf("$json garbage", "/* comment */$json", json.replaceFirst("\"version\"", "version"),
            json.replaceFirst("\"version\"", "'version'"), json.replaceFirst(":1,", ":1;"),
            json.dropLast(1) + ",}", json.replace("\"references\":[", "\"references\":[,"),
            json.replaceFirst("{", "{\"version\":1,"),
            json.replaceFirst("{", "{\"\\u0076ersion\":1,"),
            json.replace("\"bytes\":91235", "\"bytes\":91235,\"bytes\":1"),
            json.replace("user-1", "user\\x2d1"), json.replace("user-1", "user\n1"))
            .forEach { rejectJson(it, MessageVideoCodecFailure.INVALID_JSON) }
    }

    @Test fun metadataBoundsAreStorageHardMaximaNotDefaultOrModelLimits() {
        val max = ref().copy(bytes = 256L * 1024 * 1024, durationMs = 86_400_000, width = 40_000_000, height = 1)
        val refs = listOf(max, max.copy(id = ref(index = 2).id))
        assertEquals(refs, MessageVideoInputCodec.decode(user,
            MessageVideoInputCodec.encode(user, listOf(MessageVideoInput(owner, refs)))).single().references)
        val bad = listOf(max.copy(bytes = max.bytes + 1), max.copy(durationMs = max.durationMs + 1),
            max.copy(width = 40_000_001), max.copy(width = Int.MAX_VALUE, height = Int.MAX_VALUE),
            ref().copy(id = "not-a-uuid"), ref().copy(sha256 = "A".repeat(64)),
            ref().copy(sha256 = "a".repeat(63)), ref().copy(mime = "Video/MP4"), ref().copy(mime = "image/jpeg"))
        bad.forEach { rejected(MessageVideoCodecFailure.INVALID_REFERENCE) { MessageVideoInputCodec.encode(user, listOf(input(it))) } }
        rejected(MessageVideoCodecFailure.TOO_LARGE) {
            MessageVideoInputCodec.encode(user, listOf(MessageVideoInput(owner,
                refs + max.copy(id = ref(index = 3).id, bytes = 1))))
        }
    }

    @Test fun emptyInputAndCollectionBudgetsFailBeforeSerialization() {
        rejected(MessageVideoCodecFailure.INVALID_REFERENCE) {
            MessageVideoInputCodec.encode(user, listOf(MessageVideoInput(owner, emptyList())))
        }
        rejectJson(mutated { it.getJSONArray("inputs").getJSONObject(0).put("references", org.json.JSONArray()) },
            MessageVideoCodecFailure.INVALID_REFERENCE)
        rejectJson(mutated {
            val refs = it.getJSONArray("inputs").getJSONObject(0).getJSONArray("references")
            repeat(8) { refs.put(refs.getJSONObject(0)) }
        }, MessageVideoCodecFailure.TOO_LARGE)
        rejected(MessageVideoCodecFailure.TOO_LARGE) {
            MessageVideoInputCodec.encode(user, List(MessageVideoInputCodec.MAX_INPUTS + 1) { input() })
        }
        rejected(MessageVideoCodecFailure.TOO_LARGE) {
            MessageVideoInputCodec.encode(user, listOf(MessageVideoInput(owner, List(9) { ref(index = it + 1) })))
        }
        val users = (1..129).map { user.copy(id = MessageId("u$it")) }
        val inputs = users.map { row -> MessageVideoInput(MessageVideoOwner(row.conversationId.value, row.id.value),
            List(8) { ref(MessageVideoOwner(row.conversationId.value, row.id.value), it + 1) }) }
        val message = history(*users.toTypedArray())
        val accepted = MessageVideoInputCodec.encode(message, inputs.dropLast(1))
        assertEquals(1024, MessageVideoInputCodec.decode(message, accepted).sumOf { it.references.size })
        rejected(MessageVideoCodecFailure.TOO_LARGE) { MessageVideoInputCodec.encode(message, inputs) }
        // Syntactically valid but oversized raw arrays, including the decode-only path.
        rejectJson(mutated { it.put("inputs", org.json.JSONArray(List(257) { JSONObject(encoded()).getJSONArray("inputs").get(0) })) },
            MessageVideoCodecFailure.TOO_LARGE)
    }

    @Test fun totalUtf8BudgetCountsBytesNotUtf16CharactersAndRejectsBrokenUnicode() {
        val prefix = "\"" + "界".repeat(MessageVideoInputCodec.MAX_UTF8_BYTES / 3 + 1)
        assertTrue(prefix.length < MessageVideoInputCodec.MAX_UTF8_BYTES)
        rejectJson(prefix, MessageVideoCodecFailure.TOO_LARGE)
        rejectJson(" ".repeat(MessageVideoInputCodec.MAX_UTF8_BYTES + 1), MessageVideoCodecFailure.TOO_LARGE)
        rejectJson(encoded() + "\ud800", MessageVideoCodecFailure.INVALID_JSON)
        rejectJson(encoded().replace("user-1", "user\\ud800"), MessageVideoCodecFailure.INVALID_JSON)
        val unicode = user.copy(id = MessageId("用户-\ud83c\udfa5"))
        val unicodeRef = ref(MessageVideoOwner(unicode.conversationId.value, unicode.id.value))
        assertEquals(unicodeRef, MessageVideoInputCodec.decode(unicode,
            MessageVideoInputCodec.encode(unicode, listOf(input(unicodeRef)))).single().references.single())
        val padded = encoded().padEnd(MessageVideoInputCodec.MAX_UTF8_BYTES, ' ')
        assertEquals(ref(), MessageVideoInputCodec.decode(user, padded).single().references.single())
    }

    @Test fun pathsUrisDataUrlsAndSecretsNeverEscapeFailureMessages() {
        listOf("data:video/mp4;base64,SECRET", "/private/SECRET.mp4", "file:///SECRET", "content://SECRET",
            "https://SECRET", "C:\\SECRET.mp4", "../SECRET", "..", " user-1", "user-1\n").forEach { unsafe ->
            rejectJson(mutated { inputOwner(it).put("message", unsafe) }, MessageVideoCodecFailure.INVALID_IDENTITY)
            rejected(MessageVideoCodecFailure.INVALID_IDENTITY) {
                MessageVideoInputCodec.encode(user.copy(id = MessageId(unsafe)), emptyList())
            }
            rejectJson(mutated { reference(it).put("id", unsafe) }, MessageVideoCodecFailure.INVALID_REFERENCE)
            rejectJson(mutated { reference(it).put("mime", unsafe) }, MessageVideoCodecFailure.INVALID_REFERENCE)
        }
        rejectJson(mutated { reference(it).put("uri", "content://SECRET") }, MessageVideoCodecFailure.INVALID_FIELDS)
    }
}

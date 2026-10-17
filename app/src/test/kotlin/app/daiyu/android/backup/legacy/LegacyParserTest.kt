package app.daiyu.android.backup.legacy

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class LegacyParserTest {
    private fun parse(bytes: ByteArray) = LegacyParser.parse(LegacyArchive.read(ByteArrayInputStream(bytes)))
    private fun rejects(block: () -> Unit) { try { block(); fail("Accepted unsafe legacy input") } catch (_: Exception) {} }

    @Test fun everyJsonAndZipVersionRecognized() {
        (1..3).forEach { version ->
            val doc = LegacyFixtures.single(version)
            listOf(LegacyFixtures.bytes(doc), LegacyFixtures.zip(doc)).forEach { data ->
                val plan = parse(data); assertEquals("eta-conversation", plan.format); assertEquals(version, plan.version)
                assertEquals(1, plan.conversations.size); assertEquals(1, plan.conversations.single().messages.size)
            }
        }
        (1..4).forEach { version ->
            val doc = LegacyFixtures.full(version)
            listOf(LegacyFixtures.bytes(doc), LegacyFixtures.zip(doc)).forEach { data ->
                val plan = parse(data); assertEquals("eta-backup", plan.format); assertEquals(version, plan.version)
            }
        }
    }
    @Test fun unknownVersionsFormatsAndSchemaOverflowRejected() {
        listOf(LegacyFixtures.single(4), LegacyFixtures.full(5), LegacyFixtures.single().put("format", "neutral-backup"),
            LegacyFixtures.single().put("schemaVersion", 4294967299L)).forEach { rejects { parse(LegacyFixtures.bytes(it)) } }
    }
    @Test fun v2AndV3HashSizeReferencePathAndExactEntrySetVerified() {
        (2..3).forEach { version ->
            val (doc, original) = LegacyFixtures.attached(version)
            assertEquals(1, parse(LegacyFixtures.zip(doc, original)).originals.size)
            val noOriginal = parse(LegacyFixtures.bytes(doc))
            assertFalse(noOriginal.conversations.single().media.first { it.purpose == "source" }.available)
            rejects { parse(LegacyFixtures.zip(doc, original.first to "TAMPERED".toByteArray())) } // same size, valid ZIP CRC, wrong SHA
            rejects { parse(LegacyFixtures.zip(doc)) }
            rejects { parse(LegacyFixtures.zip(doc, original, "attachments/imports/extra" to byteArrayOf(1))) }
            val attachment = doc.getJSONArray("attachments").getJSONObject(0)
            attachment.put("size", 1); rejects { parse(LegacyFixtures.zip(doc, original)) }; attachment.put("size", original.second.size)
            attachment.put("reference", "/eta-attachments/../escape"); rejects { parse(LegacyFixtures.zip(doc, original)) }
        }
    }
    @Test fun duplicatesAttachmentDeclarationsOwnersAndOrdersRejected() {
        val (doc, original) = LegacyFixtures.attached()
        val a = doc.getJSONArray("attachments").getJSONObject(0)
        doc.put("attachmentCount", 2).getJSONArray("attachments").put(a)
        rejects { parse(LegacyFixtures.zip(doc, original)) }
        val wrong = LegacyFixtures.single(); wrong.getJSONArray("messages").getJSONObject(0).put("conversationId", "another")
        rejects { parse(LegacyFixtures.bytes(wrong)) }
        val duplicate = LegacyFixtures.single(); duplicate.getJSONArray("messages").put(LegacyFixtures.message(id = "other"))
        rejects { parse(LegacyFixtures.bytes(duplicate)) }
        val checkpoint = LegacyFixtures.single().put("contextCheckpoint", JSONObject().put("conversationId", "wrong").put("historyJson", "[]"))
        rejects { parse(LegacyFixtures.bytes(checkpoint)) }
    }
    @Test fun externalPayloadUsesInternalIdNotFilenameAndChecksCountsAndOwners() {
        val first = LegacyFixtures.single(owner = "a"); val second = LegacyFixtures.single(owner = "b")
        second.getJSONArray("messages").getJSONObject(0).put("id", "ui-2")
        val full = LegacyFixtures.full().put("conversations", JSONArray().put(first.getJSONObject("conversation")).put(second.getJSONObject("conversation")))
            .put("messagesExternal", true).put("messages", JSONArray()).put("messageCount", 2)
        fun archive() = LegacyFixtures.zip(full, "conversations/b.json" to LegacyFixtures.bytes(first), "conversations/a.json" to LegacyFixtures.bytes(second))
        assertEquals(listOf("a", "b"), parse(archive()).conversations.map { it.sourceId })
        full.put("messageCount", 3); rejects { parse(archive()) }; full.put("messageCount", 2)
        rejects { parse(LegacyFixtures.zip(full, "conversations/anything.json" to LegacyFixtures.bytes(first))) }
        rejects { parse(LegacyFixtures.zip(full, "conversations/1.json" to LegacyFixtures.bytes(first), "conversations/2.json" to LegacyFixtures.bytes(first))) }
        second.getJSONArray("messages").getJSONObject(0).put("conversationId", "a"); rejects { parse(archive()) }
    }
    @Test fun v1AbsolutePathsOnlyLocateZipEntriesAndWrongOwnerIsRejected() {
        val path = "/data/user/77/frozen.pkg/cache/eta-chat-images/source/picture.jpg"
        val doc = LegacyFixtures.single(1).put("attachmentCount", 1)
        doc.getJSONArray("messages").getJSONObject(0).put("imagesJson", JSONArray().put(path).put("file://$path").toString())
        val entry = "attachments/chat-images/source/picture.jpg"
        val original = "ZIP ORIGINAL NOT LOCAL FILE".toByteArray()
        val plan = parse(LegacyFixtures.zip(doc, entry to original))
        assertEquals(setOf(entry), plan.conversations.single().media.mapNotNull { it.entry }.toSet())
        assertArrayEquals(original, plan.originals.getValue(entry))
        rejects { parse(LegacyFixtures.zip(doc, "attachments/chat-images/other/picture.jpg" to original)) }
        assertNull(LegacyMediaMapper.legacyEntry("/data/user/0/frozen.pkg/shared_prefs/keys.xml", "source"))
        assertEquals("attachments/imports/a.pdf", LegacyMediaMapper.legacyEntry("/data/data/old.pkg/files/terminal-user/workspace/imports/a.pdf", "source"))
    }
    @Test fun uiHistoryCheckpointAndMediaStaySeparateAndCodeProseRemainLiteral() {
        val types = listOf("user", "assistant", "thinking", "tool", "tool_summary", "system_notice", "error_reconnect", "context_compacted", "question")
        val doc = LegacyFixtures.single()
        val content = "![image](/eta-attachments/id/pic)\n`![code](/not/a/reference)`\n```text\n![fence](/not/a/reference)\n```\nplain /not/a/reference"
        doc.put("messages", JSONArray(types.mapIndexed { index, type -> LegacyFixtures.message("m-$index", type = type, index = index).put("content", content) }))
        val history = JSONArray().put(JSONObject().put("role", "tool").put("toolCallId", "already-executed")
            .put("contentJson", JSONArray().put(JSONObject().put("type", "text").put("text", "literal history")).toString())).toString()
        doc.getJSONObject("conversation").put("historyJson", history)
        val pic = "picture".toByteArray(); val ref = "/eta-attachments/id/pic"; val entry = "attachments/imports/id/pic"
        doc.put("attachmentCount", 1).put("attachments", JSONArray().put(JSONObject().put("reference", ref).put("entry", entry).put("size", pic.size).put("sha256", digest(pic))))
        doc.put("contextCheckpoint", JSONObject().put("conversationId", "source").put("historyJson", history))
        val conv = parse(LegacyFixtures.zip(doc, entry to pic)).conversations.single()
        assertEquals(types, conv.messages.map { it.type }); assertEquals(content, conv.messages.first().content)
        assertEquals(history, conv.history); assertEquals(history, conv.checkpoint)
        assertEquals(listOf(ref), LegacyMediaMapper.markdownDestinations(content))
        assertTrue(conv.raw.contains("already-executed"))
    }
    @Test fun literalAndIncompleteMarkdownRemainRawInJsonAndZip() {
        val cases = listOf(
            "这是字面文本 ](/not/a/reference)",
            "[missing destination close](/not/a/reference",
            "[missing destination close after whitespace](/not/a/reference trailing",
            "![missing angle close](</not/a/reference)",
            "[missing outer close](</not/a/reference>",
            "[missing title close](/not/a/reference \"title)",
            "[missing final close](/not/a/reference \"title\"",
            "[unquoted title](/not/a/reference title)",
            "> ~~~text\n> ![quoted code](/not/a/reference)\n> ~~~",
            "> > ~~~~text\n> > [nested code](/not/a/reference)\n> > ~~~\n> > ![still code](/not/a/reference)\n> > ~~~~",
            "> ~~~\n> ![unclosed quoted code](/not/a/reference)",
            "~~~\n![code](/not/a/reference)\n~~~not-a-closing-fence\n[still code](/not/a/reference)\n~~~",
            "\\[escaped](/not/a/reference) and !\\[escaped image](/not/a/reference)",
            "`[inline](/not/a/reference)` and `` ![image](/not/a/reference) ` literal ``",
            "`multiline\n![inline](/not/a/reference)\ncode`",
            "    ![indented code](/not/a/reference)",
            "plain /not/a/reference and [unknown][reference]"
        )
        cases.forEach { content ->
            assertEquals(content, emptyList<String>(), LegacyMediaMapper.markdownDestinations(content))
            (1..3).forEach { version ->
                val doc = LegacyFixtures.single(version)
                doc.getJSONArray("messages").getJSONObject(0).put("content", content)
                val history = JSONArray().put(JSONObject().put("role", "user").put("content", content))
                    .put(JSONObject().put("role", "assistant").put("contentJson", JSONArray()
                        .put(JSONObject().put("type", "text").put("text", content)).toString())).toString()
                doc.getJSONObject("conversation").put("historyJson", history)
                doc.put("contextCheckpoint", JSONObject().put("conversationId", "source").put("historyJson", history))
                listOf(LegacyFixtures.bytes(doc), LegacyFixtures.zip(doc)).forEach { bytes ->
                    val conv = parse(bytes).conversations.single()
                    assertEquals(content, conv.messages.single().content)
                    assertEquals(history, conv.history); assertEquals(history, conv.checkpoint)
                    assertEquals(content, JSONObject(conv.raw).getJSONArray("messages").getJSONObject(0).getString("content"))
                    assertTrue(conv.media.isEmpty())
                }
            }
        }
    }
    @Test fun completeMarkdownLocalReferencesStillRequireDeclarationsAndZipOriginals() {
        val ref = "/eta-attachments/id/pic"; val entry = "attachments/imports/id/pic"; val pic = "picture".toByteArray()
        val cases = listOf(
            "![image]($ref)", "see [link]($ref)", "![image](<$ref> \"title\")",
            "1 < 2 ![image]($ref) > 0",
            "[link]($ref 'title')", "[link]($ref (title))", "> [quoted]($ref)",
            "> ~~~\n> ![code](/not/a/reference)\n> ~~~\n\n![image]($ref)",
            "> ~~~\n> ![unclosed code](/not/a/reference)\n![outside quote]($ref)",
            "`unmatched backtick before ![real image]($ref)",
            "`[code](/not/a/reference)` ![real image]($ref)"
        )
        cases.forEach { content ->
            assertEquals(content, listOf(ref), LegacyMediaMapper.markdownDestinations(content))
            (2..3).forEach { version ->
                val doc = LegacyFixtures.single(version)
                doc.getJSONArray("messages").getJSONObject(0).put("content", content)
                listOf(LegacyFixtures.bytes(doc), LegacyFixtures.zip(doc)).forEach { bytes -> rejects { parse(bytes) } }
                doc.put("attachmentCount", 1).put("attachments", JSONArray().put(JSONObject().put("reference", ref)
                    .put("entry", entry).put("size", pic.size).put("sha256", digest(pic))))
                assertEquals(ref, parse(LegacyFixtures.bytes(doc)).conversations.single().media.single().reference)
                assertTrue(parse(LegacyFixtures.zip(doc, entry to pic)).conversations.single().media.single().available)
                rejects { parse(LegacyFixtures.zip(doc)) }
                rejects { parse(LegacyFixtures.zip(doc, entry to "changed".toByteArray())) }
            }
        }
        assertEquals(listOf("/eta-attachments/id/a(b)"), LegacyMediaMapper.markdownDestinations("[link](/eta-attachments/id/a(b))"))
        assertEquals(listOf("/eta-attachments/id/a(b)"), LegacyMediaMapper.markdownDestinations("![image](/eta-attachments/id/a\\(b\\))"))
    }
    @Test fun markdownBudgetExhaustionRejectsWholeValidationInsteadOfReturningPartialReferences() {
        val ref = "/eta-attachments/id/pic"
        val exhausted = "[broken](".repeat(16) + "x".repeat(1024) + " ![real]($ref)"
        // Cover empty inventories, already collected references, and an already flushed paragraph.
        listOf(exhausted, "![first]($ref) $exhausted", "![first]($ref)\n\n$exhausted").forEach { content ->
            try {
                LegacyMediaMapper.markdownDestinations(content)
                fail("Returned a partial Markdown attachment inventory")
            } catch (e: IllegalArgumentException) {
                assertEquals("Markdown attachment scan budget exhausted", e.message)
            }
            (1..3).forEach { version ->
                val doc = LegacyFixtures.single(version)
                doc.getJSONArray("messages").getJSONObject(0).put("content", content)
                listOf(LegacyFixtures.bytes(doc), LegacyFixtures.zip(doc)).forEach { bytes -> rejects { parse(bytes) } }
            }
        }
    }
    @Test fun structuredMediaOutsideManifestIsStillRejectedEvenWhenProseIsLiteral() {
        (2..3).forEach { version ->
            val doc = LegacyFixtures.single(version)
            doc.getJSONArray("messages").getJSONObject(0).put("content", "字面文本 ](/not/a/reference)")
                .put("imagesJson", JSONArray().put("/eta-attachments/id/undeclared").toString())
            listOf(LegacyFixtures.bytes(doc), LegacyFixtures.zip(doc)).forEach { bytes -> rejects { parse(bytes) } }
        }
    }
    @Test fun historyNestedMediaUrlsToolRolesAndPreviewNeverBecomeVideoOriginal() {
        val doc = LegacyFixtures.single(1).put("attachmentCount", 2)
        val root = "/data/user/0/pkg/files/terminal/workspace/imports/"
        doc.getJSONObject("conversation").put("historyJson", JSONArray().put(JSONObject().put("role", "tool").put("contentJson", JSONArray()
            .put(JSONObject().put("type", "video_file").put("path", root + "video"))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", root + "preview")))
            .put(JSONObject().put("type", "video_url").put("video_url", JSONObject().put("url", "https://invalid.example/video"))).toString())).toString())
        val plan = parse(LegacyFixtures.zip(doc, "attachments/imports/video" to "original".toByteArray(), "attachments/imports/preview" to "preview".toByteArray()))
        val media = plan.conversations.single().media
        assertEquals(2, media.count { it.available }); assertEquals(1, media.count { it.purpose == "video-original" && it.available })
        assertFalse(media.single { it.reference.startsWith("https:") }.available)
    }
    @Test fun subAgentGenerationAndSchemaFailClosedWithoutDecodingPreResetPayload() {
        val old = LegacyFixtures.single().put("subAgentConfigJson", "not even JSON")
        val oldPlan = parse(LegacyFixtures.bytes(old)); assertFalse(oldPlan.conversations.single().raw.contains("not even JSON"))
        assertTrue(oldPlan.report.issues.any { it.code == "SUB_AGENT_PRE_RESET_DISABLED" })
        old.put("subAgentConfigGeneration", "unknown"); rejects { parse(LegacyFixtures.bytes(old)) }
        old.put("subAgentConfigGeneration", "1").put("subAgentConfigJson", "{\"version\":2}"); rejects { parse(LegacyFixtures.bytes(old)) }
        old.put("subAgentConfigJson", "{\"version\":1,\"enabled\":true,\"agents\":[]}")
        assertTrue(parse(LegacyFixtures.bytes(old)).conversations.single().raw.contains("subAgentCompatibility"))
        old.put("schemaVersion", 2); rejects { parse(LegacyFixtures.bytes(old)) }
        old.put("schemaVersion", 3).put("subAgentConfigJson", "{\"version\":1,\"agents\":[{}]}"); rejects { parse(LegacyFixtures.bytes(old)) }
    }
    @Test fun credentialBearingRootFieldsAreNeverRetained() {
        val full = LegacyFixtures.full().put("providers", JSONArray().put(JSONObject().put("apiKey", "SYNTHETIC-NOT-A-KEY")))
            .put("mcpTokens", JSONObject().put("server", "SYNTHETIC-NOT-A-TOKEN"))
            .put("settings", JSONObject().put("secret", "SYNTHETIC-SETTING"))
        val plan = parse(LegacyFixtures.bytes(full))
        assertFalse(plan.conversations.single().raw.contains("SYNTHETIC-NOT")); assertTrue(plan.report.issues.any { it.code == "EXCLUDED_GLOBAL_CONFIGURATION" })
        full.getJSONArray("conversations").getJSONObject(0).put("unknownCredential", "SYNTHETIC")
        rejects { parse(LegacyFixtures.bytes(full)) }
    }
}

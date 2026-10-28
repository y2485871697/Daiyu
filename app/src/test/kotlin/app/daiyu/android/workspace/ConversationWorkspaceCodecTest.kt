package app.daiyu.android.workspace

import org.junit.Assert.*
import org.junit.Test

class ConversationWorkspaceCodecTest {
    @Test fun defaultIsNullAndRoundTrips() {
        assertNull(ConversationWorkspaceCodec.encode(ConversationWorkspaceBinding.Private()))
        assertEquals(ConversationWorkspaceBinding.Private(), ConversationWorkspaceCodec.decode(null))
    }
    @Test fun explicitBindingsRoundTripWithoutInterpretation() {
        for (binding in listOf(ConversationWorkspaceBinding.Private("/data/user/0/test/files/project"),
            ConversationWorkspaceBinding.Rootfs("debian", "generation-1", "/root/项目"))) {
            assertEquals(binding, ConversationWorkspaceCodec.decode(ConversationWorkspaceCodec.encode(binding)))
        }
    }
    @Test fun malformedOrUnknownBindingIsNotDefaulted() {
        for (raw in listOf("", "{}", "{\"version\":1,\"kind\":\"other\"}",
            "{\"version\":2,\"kind\":\"private\",\"path\":\"/x\"}",
            "{\"version\":1,\"kind\":\"private\",\"path\":3}",
            "{\"version\":1,\"kind\":\"private\",\"path\":\"/x\",\"extra\":true}",
            "汉".repeat(1366))) {
            try { ConversationWorkspaceCodec.decode(raw); fail("accepted $raw") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun legacyValuesBecomeSingleChoicesWithoutRewritingExpectedRaw() {
        val legacy = "{ \"path\":\"/private/project\", \"kind\":\"private\", \"version\":1 }"
        val choices = ConversationWorkspaceCodec.decodeWorkspaces(legacy)
        assertEquals(1, choices.bindings.size)
        assertEquals(ConversationWorkspaceBinding.Private("/private/project"), choices.active)
        assertEquals(0, ConversationWorkspaceCodec.decodeWorkspaces(null).activeIndex)
    }

    @Test fun mixedCollectionRoundTripsAndRuntimeUsesOnlyActiveChoice() {
        val choices = ConversationWorkspaces(listOf(ConversationWorkspaceBinding.Private(),
            ConversationWorkspaceBinding.Private("/private/project"),
            ConversationWorkspaceBinding.Rootfs("debian", "generation-1", "/root/项目")), 1)
        val encoded = ConversationWorkspaceCodec.encode(choices)
        assertEquals(choices, ConversationWorkspaceCodec.decodeWorkspaces(encoded))
        assertEquals(choices.active, ConversationWorkspaceCodec.decode(encoded))
        assertEquals(choices.select(2), ConversationWorkspaceCodec.decodeWorkspaces(
            ConversationWorkspaceCodec.encode(choices.select(2))))
    }

    @Test fun emptyCollectionRoundTripsWithoutReintroducingDeletedChoices() {
        val empty = ConversationWorkspaces(emptyList(), -1)
        val encoded = ConversationWorkspaceCodec.encode(empty)
        assertNotNull(encoded)
        assertEquals(empty, ConversationWorkspaceCodec.decodeWorkspaces(encoded))
        assertEquals(ConversationWorkspaceBinding.Private(), ConversationWorkspaceCodec.decode(encoded))
    }

    @Test fun invalidCollectionsNeverFallbackToDefault() {
        for (raw in listOf(
            "{\"version\":2,\"kind\":\"collection\",\"active\":0,\"bindings\":[null]}TRAILING",
            "{\"version\":2,\"kind\":\"collection\",\"active\":9,\"active\":0,\"bindings\":[null]}",
            "{'version':2,'kind':'collection','active':0,'bindings':[null]}",
            "{\"version\":2,\"kind\":\"collection\",\"active\":0,\"bindings\":[]}",
            "{\"version\":2,\"kind\":\"collection\",\"active\":-1,\"bindings\":[null]}",
            "{\"version\":2,\"kind\":\"collection\",\"active\":0,\"bindings\":[null,null]}",
            "{\"version\":3,\"kind\":\"collection\",\"active\":0,\"bindings\":[null]}",
            "{\"version\":2,\"kind\":\"collection\",\"active\":0.5,\"bindings\":[null]}",
            "{\"version\":2,\"kind\":\"collection\",\"active\":0,\"bindings\":[3]}",
            "{\"version\":2,\"kind\":\"collection\",\"active\":0,\"bindings\":[null],\"extra\":true}",
            "{\"version\":2,\"kind\":\"collection\",\"active\":0,\"bindings\":[{\"version\":2,\"kind\":\"collection\",\"active\":-1,\"bindings\":[]}]}",
        )) {
            try { ConversationWorkspaceCodec.decode(raw); fail("accepted $raw") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun collectionHonorsExistingDatabaseUtf8Budget() {
        val choices = ConversationWorkspaces((0..30).map {
            ConversationWorkspaceBinding.Private("/private/" + "项".repeat(60) + it)
        }, 0)
        try { ConversationWorkspaceCodec.encode(choices); fail("accepted oversized list") }
        catch (failure: IllegalArgumentException) { assertEquals("workspace_collection_too_large", failure.message) }
    }

}

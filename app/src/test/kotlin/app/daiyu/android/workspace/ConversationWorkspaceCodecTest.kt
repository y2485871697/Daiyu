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
}

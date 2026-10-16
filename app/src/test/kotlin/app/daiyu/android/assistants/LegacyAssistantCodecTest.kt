package app.daiyu.android.assistants

import org.junit.Assert.*
import org.junit.Test

class LegacyAssistantCodecTest {
    @Test fun roundTripSevenFieldsOrderSelectionAndNullableAvatar() {
        val original=LegacyAssistantCodec.Snapshot(listOf(
            AssistantProfile("b"," B ","raw\ntext",null,2,false,listOf("s2","s1")),
            AssistantProfile("a","A","", "a.png",3,true,emptyList())),"a")
        assertEquals(original,LegacyAssistantCodec.decode(LegacyAssistantCodec.encode(original)))
    }

    @Test fun neverResetsCorruptOrMissingSelection() {
        listOf("null", "{}", "{\"profiles\":[],\"activeId\":\"default\"}",
            "{\"profiles\":[{\"id\":\"a\",\"name\":\"a\"}],\"activeId\":\"missing\"}",
            "{\"profiles\":[{\"id\":\"a\",\"name\":\"a\",\"memoryEnabled\":\"false\"}],\"activeId\":\"a\"}",
            "{\"profiles\":[{\"id\":\"a\",\"name\":\"a\"},{\"id\":\"a\",\"name\":\"duplicate\"}],\"activeId\":\"a\"}"
        ).forEach { raw ->
            val result=runCatching { LegacyAssistantCodec.decode(raw) }
            assertTrue("must reject: $raw",result.isFailure)
        }
    }

    @Test fun legacyOmittedOptionalFieldsRetainOriginalDefaults() {
        val parsed=LegacyAssistantCodec.decode("{\"profiles\":[{\"id\":\"a\",\"name\":\"a\"}],\"activeId\":\"a\"}")
        assertEquals(AssistantProfile("a","a",""),parsed.profiles.single())
    }
}

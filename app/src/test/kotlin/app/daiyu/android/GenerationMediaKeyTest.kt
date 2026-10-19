package app.daiyu.android

import org.junit.Assert.*
import org.junit.Test

class GenerationMediaKeyTest {
    @Test fun exactSourcesAndBoundaryIndexes() {
        for (source in listOf("live", "history", "static")) for (index in listOf(0, 31)) {
            assertEquals(GenerationMediaKey(source, "item_1-x", index), GenerationMediaKey.parse("$source:item_1-x:$index"))
        }
    }
    @Test fun malformedKeysNeverFallBackToAnotherAsset() {
        for (key in listOf(null, "", "live:item:32", "live:item:-1", "live:item:01", "live:item:+1",
            "live:item:1 ", "Live:item:1", "other:item:1", "live::1", "live:a:b:1", "live:item:1\n",
            "live:${"a".repeat(129)}:1", "history:item:1:2", "static:../x:0")) assertNull(key, GenerationMediaKey.parse(key))
    }
}

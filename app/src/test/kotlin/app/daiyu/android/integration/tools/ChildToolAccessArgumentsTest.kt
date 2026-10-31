package app.daiyu.android.integration.tools

import app.daiyu.android.domain.delegation.*
import app.daiyu.android.domain.tools.ToolSchema
import org.junit.Assert.*
import org.junit.Test

class ChildToolAccessArgumentsTest {
    private val schema = ToolSchema(ChildToolAccessArguments.fields)
    @Test fun omittedFieldsUseParentCeilingAndFullBrowser() {
        assertEquals(ChildToolAccess.DEFAULT, ChildToolAccessArguments.parse(schema.parse("{}")))
    }
    @Test fun explicitEmptyToolsAndBrowserDisableRemainDistinctFromDefaults() {
        assertEquals(ChildToolAccess.NONE, ChildToolAccessArguments.parse(schema.parse("{\"tool_names\":[],\"browser_access\":\"disabled\"}")))
        val read = ChildToolAccessArguments.parse(schema.parse("{\"tool_names\":[\"read_file\"],\"browser_access\":\"read_only\"}"))
        assertEquals(setOf("read_file"), read.toolNames); assertEquals(ChildBrowserAccess.READ_ONLY, read.browser)
    }
    @Test fun unknownModesAndOversizedToolListsAreRejectedBeforeDispatch() {
        for (json in listOf("{\"browser_access\":\"root\"}", "{\"tool_names\":[1]}",
            "{\"tool_names\":[" + List(129) { "\"read_file\"" }.joinToString(",") + "]}")) {
            assertThrows(IllegalArgumentException::class.java) { schema.parse(json) }
        }
    }
}

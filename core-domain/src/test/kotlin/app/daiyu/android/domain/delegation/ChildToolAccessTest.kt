package app.daiyu.android.domain.delegation

import org.junit.Assert.*
import org.junit.Test

class ChildToolAccessTest {
    @Test fun codecPreservesUnspecifiedEmptyAndExplicitCeilings() {
        for (access in listOf(ChildToolAccess.DEFAULT, ChildToolAccess.NONE,
            ChildToolAccess.of(setOf("read_file", "browser_read"), ChildBrowserAccess.READ_ONLY))) {
            assertEquals(access, ChildToolAccess.fromJson(access.toJson()))
        }
        assertNull(ChildToolAccess.DEFAULT.toolNames)
        assertEquals(emptySet<String>(), ChildToolAccess.NONE.toolNames)
    }
    @Test fun accessDefensivelyCopiesNamesAndRejectsInvalidJournalValues() {
        val names = mutableSetOf("read_file")
        val access = ChildToolAccess.of(names); names += "write_file"
        assertFalse(access.permits("write_file"))
        assertThrows(UnsupportedOperationException::class.java) { (access.toolNames as MutableSet).add("write_file") }
        for (json in listOf("{}", "{\"browser_access\":\"ROOT\"}", "{\"browser_access\":\"FULL\",\"extra\":true}")) {
            assertThrows(IllegalArgumentException::class.java) { ChildToolAccess.fromJson(json) }
        }
    }
}

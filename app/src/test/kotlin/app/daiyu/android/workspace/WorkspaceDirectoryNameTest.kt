package app.daiyu.android.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceDirectoryNameTest {
    @Test fun namesArePreservedWithoutTrimmingOrRenaming() {
        for (name in listOf("项目", " 项目 '🐟' 空格 ", ".hidden", "a".repeat(255), "鱼".repeat(85)))
            assertEquals(name, workspaceDirectoryName(name))
    }
    @Test fun invalidNamesCannotAddressOtherDirectoriesOrHiddenStaging() {
        for (name in listOf("", " ", ".", "..", "a/b", "a\\b", "a\u0000b", "a\nb", "a\u007fb",
            "a".repeat(256), "鱼".repeat(86), ".workspace-import-hidden")) {
            try { workspaceDirectoryName(name); fail("Accepted invalid name: $name") }
            catch (failure: IllegalArgumentException) { assertEquals("workspace_invalid_directory_name", failure.message) }
        }
    }
}

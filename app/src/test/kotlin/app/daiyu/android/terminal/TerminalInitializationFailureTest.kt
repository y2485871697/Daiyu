package app.daiyu.android.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalInitializationFailureTest {
    @Test fun catalogCodeIsPreservedWithoutArbitraryExceptionText() {
        listOf("linux_catalog_symlink", "linux_catalog_corrupt", "linux_catalog_version",
            "linux_catalog_noncanonical", "linux_catalog_duplicate_reference", "linux_path_identity_unavailable",
            "linux_catalog_store_limit", "linux_environment_store_limit").forEach { code ->
            assertEquals(code, terminalInitializationFailureCode(IllegalStateException(code)))
        }
        assertEquals("initialization_failed", terminalInitializationFailureCode(IllegalStateException("linux_catalog_symlink /private/secret")))
        assertEquals("initialization_failed", terminalInitializationFailureCode(IllegalStateException("linux_catalog_symlink\nprivate")))
        assertEquals("initialization_failed", terminalInitializationFailureCode(IllegalStateException("/private/user/catalog.json secret")))
        assertEquals("initialization_failed", terminalInitializationFailureCode(IllegalStateException("linux_user_supplied_secret")))
    }

    @Test fun platformFailuresExposeOnlyFixedCategories() {
        assertEquals("security_denied", terminalInitializationFailureCode(SecurityException("private details")))
        assertEquals("io_failed", terminalInitializationFailureCode(java.io.IOException("private path")))
        assertEquals("catalog_json_failed", terminalInitializationFailureCode(org.json.JSONException("private JSON")))
        assertEquals("initialization_failed", terminalInitializationFailureCode(IllegalArgumentException("private value")))
    }
}

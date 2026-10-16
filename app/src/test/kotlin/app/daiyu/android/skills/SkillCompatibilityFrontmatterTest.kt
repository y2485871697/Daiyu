package app.daiyu.android.skills

import org.junit.Assert.*
import org.junit.Test

class SkillCompatibilityFrontmatterTest {
    private fun markdown(extra: String = "") = "---\nname: demo\ndescription: Portable instructions\n${extra}---\nUse ordinary tools."

    @Test fun missingCompatibilityStaysAbsentAndExistingHeaderConstructorWorks() {
        val header = SkillFrontmatter.parse(markdown())
        assertEquals(SkillHeader("demo", "Portable instructions"), header)
        assertNull(header.compatibility)
        assertTrue(header.metadata.isEmpty())
    }

    @Test fun retainsFlatMetadataAndFoldedCompatibilityWithoutYamlEvaluation() {
        val header = SkillFrontmatter.parse(markdown("""
            metadata:
              author: example
              runtime: 'apple'
            compatibility: >-
              Requires the Apple
              runtime.
        """.trimIndent() + "\n"))
        assertEquals(mapOf("author" to "example", "runtime" to "apple"), header.metadata)
        assertEquals("Requires the Apple runtime.", header.compatibility)
        @Suppress("UNCHECKED_CAST")
        val mutable = header.metadata as MutableMap<String, String>
        try {
            mutable["runtime"] = "android"
            fail("parsed metadata must not be mutable")
        } catch (_: UnsupportedOperationException) { }
    }

    @Test fun emptyMetadataAndQuotedCompatibilityAreRetained() {
        val header = SkillFrontmatter.parse(markdown("metadata: {}\ncompatibility: \"Portable\"\n"))
        assertTrue(header.metadata.isEmpty())
        assertEquals("Portable", header.compatibility)
    }

    @Test fun duplicateMetadataAndUnsupportedNestedObjectsRejectRatherThanDisappear() {
        for (extra in listOf(
            "metadata:\n  runtime: apple\n  runtime: android\n",
            "metadata:\n  runtime:\n    platform: ios\n",
            "metadata: {runtime: apple}\n",
        )) {
            try {
                SkillFrontmatter.parse(markdown(extra))
                fail("invalid metadata must not be discarded")
            } catch (failure: IllegalArgumentException) {
                assertEquals("invalid_frontmatter", failure.message)
            }
        }
    }
}

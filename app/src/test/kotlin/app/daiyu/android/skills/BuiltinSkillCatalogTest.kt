package app.daiyu.android.skills

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BuiltinSkillCatalogTest {
    @Test fun packagedBodiesAreNamedCompatibleAndDetached() {
        val assets = File("src/main/assets/agent-builtin-skills-v1")
        val names = setOf("self-improving-agent", "skill-creator", "skill-installer")
        val catalog = BuiltinSkillCatalog(names.associateWith { File(assets, "$it/SKILL.md").readBytes() })
        assertEquals(names, catalog.names)
        for (name in names) {
            val bundle = catalog.bundle(name)
            val markdown = SkillFiles.text(bundle.files.getValue("SKILL.md"))
            assertEquals(name, SkillFrontmatter.parse(markdown).name)
            SkillCompatibilityPolicy.requireAvailable(markdown)
            bundle.files.getValue("SKILL.md").fill(0)
            assertEquals(name, SkillFrontmatter.parse(SkillFiles.text(catalog.bundle(name).files.getValue("SKILL.md"))).name)
        }
    }

    @Test fun mismatchedNameDoesNotBecomeTrustedBundle() {
        val catalog = BuiltinSkillCatalog(mapOf("sample" to "---\nname: other\ndescription: Mismatch\n---\nbody".toByteArray()))
        try { catalog.bundle("sample"); fail("mismatch accepted") }
        catch (failure: IllegalArgumentException) { assertEquals("builtin_package_mismatch", failure.message) }
    }
}

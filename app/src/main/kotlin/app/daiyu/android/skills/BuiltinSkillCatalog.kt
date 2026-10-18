package app.daiyu.android.skills

import android.content.Context
import java.util.Collections

/** Trusted app-authored package list, separate from user/GitHub input and runtime writes. */
class BuiltinSkillCatalog internal constructor(private val resources: Map<String, ByteArray>) {
    constructor(context: Context) : this(NAMES.associateWith { name ->
        context.assets.open("agent-builtin-skills-v1/$name/SKILL.md").use { input ->
            input.readBytes().also { require(it.size <= SkillStore.MAX_FILE_BYTES) }
        }
    })
    val names: Set<String> = Collections.unmodifiableSet(resources.keys.toSet())
    internal fun bundle(name: String): SkillBundle {
        val bytes = resources[name] ?: error("builtin_not_found")
        require(SkillFrontmatter.parse(SkillFiles.text(bytes)).name == name) { "builtin_package_mismatch" }
        return SkillBundle(mapOf("SKILL.md" to bytes.copyOf()))
    }
    companion object {
        private val NAMES = listOf("self-improving-agent", "skill-creator", "skill-installer")
        internal fun empty() = BuiltinSkillCatalog(emptyMap())
    }
}

package app.daiyu.android.skills

/**
 * 安装前与读取前共用的 Skill 运行时检测。
 * 只根据 SKILL.md 元数据和正文做静态判断，不执行脚本。
 *
 * Shared static compatibility policy; see docs/skills-compatibility-source.md.
 */
object SkillCompatibilityChecker {
    fun evaluate(entry: SkillHeader): SkillCompatibilityResult = evaluate(
        id = entry.name,
        name = entry.name,
        description = entry.description,
        compatibility = entry.compatibility,
        metadata = entry.metadata,
        body = "",
    )

    fun evaluate(
        id: String,
        name: String = id,
        description: String,
        compatibility: String? = null,
        metadata: Map<String, String> = emptyMap(),
        body: String = "",
    ): SkillCompatibilityResult {
        val haystack = buildString {
            append(id)
            append('\n')
            append(name)
            append('\n')
            append(compatibility.orEmpty())
            append('\n')
            append(description)
            append('\n')
            append(metadata.values.joinToString("\n"))
            append('\n')
            append(body)
        }.lowercase()
        return when {
            containsAny(haystack, APPLE_RUNTIME) ->
                SkillCompatibilityResult(available = false, reason = "不支持 Apple 专属运行时")
            containsAny(haystack, MINIS_ANDROID_CLI) ->
                SkillCompatibilityResult(available = false, reason = "依赖 MiniS 专属 Android CLI，代鱼无法运行")
            containsAny(haystack, MINIS_IOS_RUNTIME) ->
                SkillCompatibilityResult(available = false, reason = "依赖 MiniS / iSH 的 iOS 沙箱，代鱼无法运行")
            mentionsIosOnly(haystack) ->
                SkillCompatibilityResult(available = false, reason = "该 Skill 标注为 iOS 专属")
            else -> SkillCompatibilityResult(available = true)
        }
    }

    private fun mentionsIosOnly(haystack: String): Boolean {
        if (!IOS_TOKEN.containsMatchIn(haystack)) return false
        return !containsAny(haystack, ANDROID_MARKERS)
    }

    private fun containsAny(haystack: String, needles: Array<String>): Boolean =
        needles.any { it in haystack }

    private val APPLE_RUNTIME = arrayOf(
        "apple-",
        "homekit",
        "healthkit",
        "apple health",
        "apple reminders",
        "ios shortcut",
        "icloud drive",
    )
    private val MINIS_ANDROID_CLI = arrayOf(
        "android-a11y-cli",
        "minis accessibility service",
        "bundled android-a11y-cli",
        "android-open clis",
        "/usr/local/bin/android-open",
    )
    private val MINIS_IOS_RUNTIME = arrayOf(
        "codex-on-ish",
        "inside the minis/ish",
        "ish alpine",
        "generative-ui-minis",
        "minis ios render",
        "app store connect cli",
    )
    private val ANDROID_MARKERS = arrayOf("android", "termux")
    private val IOS_TOKEN = Regex("""\bios\b|iphone|ipad|ios-only""")
}

data class SkillCompatibilityResult(val available: Boolean, val reason: String? = null)

/** A typed, pre-publication denial, not an ambiguous storage/transaction failure. */
class SkillIncompatible(val compatibility: SkillCompatibilityResult) : IllegalArgumentException("skill_incompatible")

/** Thin host adapter. Do not substitute tool-name scans or an executor for the retained policy. */
internal object SkillCompatibilityPolicy {
    fun evaluate(header: SkillHeader, markdown: String): SkillCompatibilityResult =
        SkillCompatibilityChecker.evaluate(
            id = header.name,
            name = header.name,
            description = header.description,
            compatibility = header.compatibility,
            metadata = header.metadata,
            body = SkillFrontmatter.body(markdown),
        )

    fun requireAvailable(result: SkillCompatibilityResult) {
        if (!result.available) throw SkillIncompatible(result)
    }

    fun requireAvailable(markdown: String) {
        requireAvailable(evaluate(SkillFrontmatter.parse(markdown), markdown))
    }
}

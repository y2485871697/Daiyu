package app.daiyu.android.domain.appearance

/** Prints exactly 64 aliases for insertion under <application>, AFTER the target <activity>.
 * Run with the core-domain runtime classpath; this tool does not modify any manifest.
 */
object Task17ManifestGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 1..2) { "Usage: Task17ManifestGenerator <applicationId> [fully.qualified.TargetActivity]" }
        println(Task17IconManifest.fragment(args[0], args.getOrElse(1) { "${args[0]}.MainActivity" }))
    }
}

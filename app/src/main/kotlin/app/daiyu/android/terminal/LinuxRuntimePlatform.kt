package app.daiyu.android.terminal

/** Shared production gate for packaged AND manually registered Linux environments.
 * Files/hash/catalog approval cannot establish platform execution support. Android PTY is separate.
 * No runtime download, helper substitution, root fallback or minSdk change is authorized here. */
internal object LinuxRuntimePlatform {
    fun requireSupported(sdk: Int, is64Bit: Boolean, machine: String): String {
        check(sdk >= 34) { "linux_helpers_unavailable_api_below_34" }
        check(is64Bit) { "linux_helpers_unavailable_32_bit_process" }
        return when (machine) {
            "aarch64" -> "arm64-v8a"
            "x86_64" -> "x86_64"
            else -> error("linux_helpers_unavailable_process_abi")
        }
    }

    fun requireSupported(): String = requireSupported(android.os.Build.VERSION.SDK_INT,
        android.os.Process.is64Bit(), android.system.Os.uname().machine)

    fun unavailableReason(): String? = runCatching { requireSupported() }.exceptionOrNull()?.let {
        it.message ?: "linux_helpers_unavailable_platform"
    }
}

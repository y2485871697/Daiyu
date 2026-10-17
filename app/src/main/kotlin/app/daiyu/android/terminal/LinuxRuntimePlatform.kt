package app.daiyu.android.terminal

/** Shared production gate for packaged AND manually registered Linux environments.
 * Files/hash/catalog approval cannot establish platform execution support. Android PTY is separate.
 * No runtime download, helper substitution, root fallback or minSdk change is authorized here. */
internal object LinuxRuntimePlatform {
    fun requireSupported(sdk: Int, is64Bit: Boolean, machine: String, mode: LinuxExecutionMode = LinuxExecutionMode.PROOT): String {
        if (mode == LinuxExecutionMode.CHROOT) {
            check(sdk >= 26) { "linux_chroot_unavailable_api_below_26" }
            check(is64Bit) { "linux_chroot_unavailable_32_bit_process" }
            return processAbi(is64Bit, machine)
        }
        check(sdk >= 34) { "linux_helpers_unavailable_api_below_34" }
        check(is64Bit) { "linux_helpers_unavailable_32_bit_process" }
        return when (machine) {
            "aarch64" -> "arm64-v8a"
            "x86_64" -> "x86_64"
            else -> error("linux_helpers_unavailable_process_abi")
        }
    }

    internal fun processAbi(is64Bit: Boolean, machine: String): String = when {
        is64Bit && machine == "aarch64" -> "arm64-v8a"
        is64Bit && machine == "x86_64" -> "x86_64"
        !is64Bit && machine in setOf("aarch64", "arm", "armv7l", "armv8l") -> "armeabi-v7a"
        !is64Bit && machine in setOf("x86_64", "x86", "i386", "i486", "i586", "i686") -> "x86"
        else -> error("linux_helpers_unavailable_process_abi")
    }

    fun requireSupported(mode: LinuxExecutionMode = LinuxExecutionMode.PROOT): String = requireSupported(android.os.Build.VERSION.SDK_INT,
        android.os.Process.is64Bit(), android.system.Os.uname().machine, mode)

    fun unavailableReason(mode: LinuxExecutionMode = LinuxExecutionMode.PROOT): String? = runCatching { requireSupported(mode) }.exceptionOrNull()?.let {
        it.message ?: "linux_helpers_unavailable_platform"
    }
}

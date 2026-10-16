package app.daiyu.android.terminal.install

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest

/** No download, extraction, chmod, PATH lookup or app-writable fallback. */
class PackagedProotHelperProvider(private val context: Context) {
    fun resolve(): PackagedProotHelpers {
        val processAbi = requirePlatform(android.os.Build.VERSION.SDK_INT,
            android.os.Process.is64Bit(), android.system.Os.uname().machine)
        val helpers = verifyDirectory(File(checkNotNull(context.applicationInfo.nativeLibraryDir) {
            "linux_native_directory_missing"
        }))
        check(helpers.abi == processAbi) { "linux_helpers_process_abi_mismatch" }
        return helpers
    }

    companion object {
        internal fun requirePlatform(sdk: Int, is64Bit: Boolean, machine: String): String {
            check(sdk >= 34) { "linux_helpers_unavailable_api_below_34" }
            check(is64Bit) { "linux_helpers_unavailable_32_bit_process" }
            return when (machine) {
                "aarch64" -> "arm64-v8a"
                "x86_64" -> "x86_64"
                else -> error("linux_helpers_unavailable_process_abi")
            }
        }

        internal fun platformUnavailableReason(): String? = runCatching {
            requirePlatform(android.os.Build.VERSION.SDK_INT, android.os.Process.is64Bit(),
                android.system.Os.uname().machine)
        }.exceptionOrNull()?.let { it.message ?: "linux_helpers_unavailable_platform" }

        internal val hashes = mapOf(
            "arm64-v8a" to Pair("a8ebc8a4b77a634f88dd8e270423fdbc761666eb4e26b391966657550e3d0f5b",
                "9a1c8aae223f5e8de76b8d457086530ba2780261d3582cc2e1bbe4ccf19df5c9"),
            "x86_64" to Pair("1d6578c474d98d29cc3cfa25a13828a8a4be8ccdbb65055a120760290a7d628f",
                "3a28ac6af36b5fbd8142ba62dd9780bbf308736cef8672518ddca0e685e9999f"),
        )

        internal fun verifyDirectory(directory: File): PackagedProotHelpers {
            check(directory.isAbsolute && directory.canonicalFile == directory &&
                directory.isDirectory && !directory.canWrite()) { "linux_native_directory_untrusted" }
            val proot = File(directory, "libproot_exec.so")
            val loader = File(directory, "libproot_loader.so")
            fun digest(file: File): String {
                check(file.canonicalFile == file && Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) &&
                    file.canRead() && file.canExecute() && !file.canWrite() &&
                    file.length() in 1..(64L * 1024 * 1024)) { "linux_packaged_helper_unavailable" }
                val hash = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= 64L * 1024 * 1024)
                        hash.update(buffer, 0, count)
                    }
                }
                return hash.digest().joinToString("") { "%02x".format(it) }
            }
            val prootHash = digest(proot)
            val loaderHash = digest(loader)
            // Identify the actually installed pair, never the device's preferred ABI list.
            val abi = hashes.entries.singleOrNull { it.value == Pair(prootHash, loaderHash) }?.key
                ?: error("linux_packaged_helper_hash_or_abi")
            return PackagedProotHelpers(abi, proot, prootHash, loader, loaderHash)
        }
    }
}

data class PackagedProotHelpers internal constructor(
    val abi: String, val proot: File, val prootSha256: String,
    val loader: File, val loaderSha256: String,
)

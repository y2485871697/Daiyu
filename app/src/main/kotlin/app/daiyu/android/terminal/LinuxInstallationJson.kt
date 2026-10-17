package app.daiyu.android.terminal

import org.json.JSONObject
import java.io.File

/** The caller owns the version envelope; reading v1 never rewrites a file. */
internal object LinuxInstallationJson {
    fun decode(value: JSONObject, version: Int): LinuxInstallation {
        val mode = when (version) {
            1 -> LinuxExecutionMode.PROOT
            2 -> LinuxExecutionMode.fromWire(value.getString("execution_mode"))
            else -> error("linux_installation_version")
        }
        if (mode == LinuxExecutionMode.CHROOT) require(listOf("proot", "proot_sha256", "loader", "loader_sha256").none(value::has)) {
            "linux_chroot_helpers_not_supported"
        }
        val mounts = value.getJSONObject("shared_mounts")
        val proot = mode == LinuxExecutionMode.PROOT
        return LinuxInstallation(value.getString("distribution"), value.getString("environment_id"), value.getString("generation"),
            File(value.getString("rootfs")), if (proot) File(value.getString("proot")) else null,
            if (proot) value.getString("proot_sha256") else "", if (proot) File(value.getString("loader")) else null,
            if (proot) value.getString("loader_sha256") else "", value.getString("guest_cwd"),
            mounts.keys().asSequence().associateWith { mounts.getString(it) }, mode)
    }

    fun encode(value: LinuxInstallation, version: Int = 2): JSONObject {
        require(version == 1 || version == 2)
        require(version != 1 || value.executionMode == LinuxExecutionMode.PROOT)
        val result = JSONObject().put("distribution", value.distribution).put("environment_id", value.environmentId)
            .put("generation", value.generation).put("rootfs", value.rootfs.path)
        when (value.executionMode) {
            LinuxExecutionMode.PROOT -> {
                val helpers = value.requireProotFiles()
                result.put("proot", helpers.executable.path).put("proot_sha256", value.prootSha256)
                    .put("loader", helpers.loader.path).put("loader_sha256", value.loaderSha256)
            }
            LinuxExecutionMode.CHROOT -> {
                require(value.proot == null && value.loader == null && value.prootSha256.isEmpty() && value.loaderSha256.isEmpty())
            }
        }
        val mounts = JSONObject()
        value.sharedMounts.toSortedMap().forEach { (host, guest) -> mounts.put(host, guest) }
        result.put("guest_cwd", value.guestWorkingDirectory).put("shared_mounts", mounts)
        if (version == 2) result.put("execution_mode", value.executionMode.wire)
        return result
    }
}

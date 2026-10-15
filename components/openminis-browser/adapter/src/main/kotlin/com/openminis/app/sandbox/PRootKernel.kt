package com.openminis.app.sandbox

import android.content.Context
import java.io.File

/**
 * Manager-only linkage facade, NOT a PRoot implementation. There is intentionally no global
 * bind map and no local path resolution. The manager converts null to an explicit HTTP 404.
 * GuardedClient also blocks minis/file/content on entry AND page/subresource redirects.
 */
object PRootKernel {
    @Suppress("UNUSED_PARAMETER")
    fun resolveSessionHostPath(sessionId: String, linuxPath: String, context: Context): File? = null

    @Suppress("UNUSED_PARAMETER")
    fun resolveHostPath(linuxPath: String): File? = null
}

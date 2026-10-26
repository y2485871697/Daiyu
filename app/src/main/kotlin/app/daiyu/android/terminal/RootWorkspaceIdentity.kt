package app.daiyu.android.terminal

import android.system.Os
import java.io.File

/** Native bind must open the same directory that the frozen launch approved. */
data class RootWorkspaceIdentity(val device: Long, val inode: Long) {
    companion object {
        internal fun capture(directory: File): RootWorkspaceIdentity = Os.lstat(directory.path).let {
            RootWorkspaceIdentity(it.st_dev, it.st_ino)
        }
    }
}

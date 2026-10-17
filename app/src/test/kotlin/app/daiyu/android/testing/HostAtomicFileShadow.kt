package app.daiyu.android.testing

import android.util.AtomicFile
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** SDK34 .new protocol: Windows uses real fsync/close/atomic replace; Linux calls Android. */
@Implements(value = AtomicFile::class, callThroughByDefault = true)
class HostAtomicFileShadow {
    @RealObject private lateinit var atomicFile: AtomicFile
    @Implementation protected fun finishWrite(stream: FileOutputStream?) {
        if (File.separatorChar != '\\') {
            Shadow.directlyOn<Any?>(atomicFile, AtomicFile::class.java, "finishWrite",
                ClassParameter.from(FileOutputStream::class.java, stream))
            return
        }
        if (stream == null) return
        stream.use { it.fd.sync() }
        val base = atomicFile.baseFile.toPath()
        Files.move(base.resolveSibling(base.fileName.toString() + ".new"), base, ATOMIC_MOVE, REPLACE_EXISTING)
    }
}

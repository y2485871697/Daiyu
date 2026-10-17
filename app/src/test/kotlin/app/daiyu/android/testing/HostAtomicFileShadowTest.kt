package app.daiyu.android.testing

import android.app.Application
import android.util.AtomicFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], shadows = [HostAtomicFileShadow::class])
class HostAtomicFileShadowTest {
    @Test fun replaceAndFailedWriteKeepRealCommittedBytes() {
        val root = Files.createTempDirectory("host-atomic").toFile()
        try {
            val base = File(root, "index.bin")
            val atomic = AtomicFile(base)
            fun commit(bytes: ByteArray) {
                val output = atomic.startWrite()
                try { output.write(bytes); atomic.finishWrite(output) }
                catch (failure: Exception) { atomic.failWrite(output); throw failure }
                assertThrows(IOException::class.java) { output.write(0) }
            }
            fun read() = AtomicFile(base).openRead().use { it.readBytes() }
            commit(byteArrayOf(1, 2)); assertArrayEquals(byteArrayOf(1, 2), read())
            commit(byteArrayOf(3, 4, 5)); assertArrayEquals(byteArrayOf(3, 4, 5), read())
            assertFalse(File(base.path + ".new").exists())
            val failed = atomic.startWrite(); failed.write(byteArrayOf(9)); atomic.failWrite(failed)
            assertArrayEquals(byteArrayOf(3, 4, 5), read())
            assertFalse(File(base.path + ".new").exists())
        } finally { root.deleteRecursively() }
    }
}

package app.daiyu.android.runningmode

import android.content.Context
import android.util.AtomicFile
import java.io.File

internal object AndroidRunningModeStore {
    private val stores = mutableMapOf<String, RunningModeStore>()
    @Synchronized fun get(context: Context): RunningModeStore {
        val file = File(context.applicationContext.noBackupFilesDir, "running-mode-v1.txt")
        return stores.getOrPut(file.path) { RunningModeStore(Persistence(file)) }
    }

    private class Persistence(private val file: File) : RunningModePersistence {
        private val atomic = AtomicFile(file)
        override fun read(): String? {
            if (!file.exists() && !File(file.path + ".bak").exists()) return null
            return atomic.openRead().use {
                val bytes = java.io.ByteArrayOutputStream()
                while (true) {
                    val next = it.read()
                    if (next == -1) break
                    check(bytes.size() < 32)
                    bytes.write(next)
                }
                check(bytes.size() > 0)
                bytes.toString("UTF-8")
            }
        }
        override fun write(value: String) {
            val output = atomic.startWrite()
            try {
                output.write(value.toByteArray(Charsets.UTF_8))
                atomic.finishWrite(output)
                check(read() == value)
            } catch (failure: Exception) {
                atomic.failWrite(output)
                throw failure
            }
        }
    }
}

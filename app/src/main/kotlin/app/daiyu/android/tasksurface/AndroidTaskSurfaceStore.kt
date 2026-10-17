package app.daiyu.android.tasksurface

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Separate, non-secret base preference. No session, authorization, ASK answer or owner is persisted. */
internal object AndroidTaskSurfaceStore {
    private val stores = mutableMapOf<String, TaskSurfaceStore>()
    @Synchronized fun get(context: Context): TaskSurfaceStore {
        val path = File(context.applicationContext.noBackupFilesDir, "task-surface-v1.txt")
        return stores.getOrPut(path.absolutePath) { TaskSurfaceStore(AtomicPersistence(path)) }
    }

    internal class AtomicPersistence(private val base: File) : TaskSurfacePersistence {
        private val atomic = AtomicFile(base)
        override fun read(): String? {
            // openRead restores a prior committed backup, if present. Do not misclassify an
            // unreadable/interrupted record as a new installation with a usable default.
            if (!base.exists() && !File(base.path + ".bak").exists() && !File(base.path + ".new").exists()) return null
            return atomic.openRead().use { input ->
                val bytes = input.readBytesBounded()
                bytes.toString(Charsets.UTF_8)
            }
        }
        override fun write(record: String) {
            check(base.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
            val output = atomic.startWrite()
            try {
                output.write(record.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
                atomic.finishWrite(output)
                check(read() == record) { "task_surface_write_unconfirmed" }
            } catch (failure: Exception) {
                atomic.failWrite(output)
                throw failure
            }
        }
        private fun java.io.InputStream.readBytesBounded(): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(128)
            while (true) {
                val count = read(buffer)
                if (count < 0) break
                check(output.size() + count <= 128) { "task_surface_record_invalid" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}

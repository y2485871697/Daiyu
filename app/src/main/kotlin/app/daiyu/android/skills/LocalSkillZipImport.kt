package app.daiyu.android.skills

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** SAF streams are read only on the management worker and never extracted to disk. */
internal object LocalSkillZipImport {
    data class Archive(val sha256: String, val bundle: SkillBundle)

    fun read(openStream: () -> InputStream): Archive {
        val bytes = openStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= SkillZipArchive.MAX_ZIP_BYTES) { "archive_too_large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return Archive(SkillFiles.hash(bytes), SkillBundle(SkillZipArchive.readLocal(bytes)))
    }
}

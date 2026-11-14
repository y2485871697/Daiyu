package app.daiyu.android.voice.control

import java.io.File
import app.daiyu.android.storage.ConversationDatabase

internal object VoiceCallTempFiles {
    fun directory(cache: File, owner: String): File {
        require(ConversationDatabase.isTemporaryVoice(owner))
        return File(File(cache, "voice-call-temp-v1"), owner)
    }
    fun audio(cache: File, owner: String): File {
        val directory = File(directory(cache, owner), "audio")
        check(directory.mkdirs() || directory.isDirectory)
        return File.createTempFile("speech-", ".audio", directory)
    }
    fun clear(cache: File, owner: String) = remove(directory(cache, owner))
    fun recover(cache: File) {
        val root = File(cache, "voice-call-temp-v1")
        root.listFiles()?.forEach { child ->
            if (ConversationDatabase.isTemporaryVoice(child.name)) remove(child)
        }
    }
    private fun remove(file: File) {
        if (!file.exists()) return
        if (file.canonicalFile == File(file.parentFile.canonicalFile, file.name) && file.isDirectory)
            file.listFiles()?.forEach(::remove)
        check(file.delete() || !file.exists()) { "voice_call_temp_cleanup_failed" }
    }
}

package app.daiyu.android.voice.control

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceCallTempFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun owner() = "voice-call-${UUID.randomUUID()}"
    @Test fun clearRemovesOnlyExactOwnerWithNestedAudioAndContext() {
        val cache = temporary.newFolder("cache"); val first = owner(); val second = owner()
        val one = VoiceCallTempFiles.audio(cache, first).apply { writeText("audio") }
        val context = File(VoiceCallTempFiles.directory(cache, first), "context/hash").apply {
            parentFile.mkdirs(); writeText("summary")
        }
        val other = VoiceCallTempFiles.audio(cache, second)
        VoiceCallTempFiles.clear(cache, first)
        assertFalse(one.exists()); assertFalse(context.exists()); assertTrue(other.exists())
    }
    @Test fun cacheAncestorAliasStillAllowsRecursiveCleanup() {
        val real = temporary.newFolder("real")
        val alias = File(temporary.root, "alias")
        Files.createSymbolicLink(alias.toPath(), real.toPath())
        val id = owner(); VoiceCallTempFiles.audio(alias, id)
        VoiceCallTempFiles.clear(alias, id)
        assertFalse(VoiceCallTempFiles.directory(real, id).exists())
    }
    @Test fun nestedSymbolicLinkIsDeletedWithoutFollowingOutsideTheOwner() {
        val cache = temporary.newFolder("cache"); val outside = temporary.newFolder("outside")
        val keep = File(outside, "keep").apply { writeText("keep") }
        val id = owner(); val directory = VoiceCallTempFiles.directory(cache, id).apply { mkdirs() }
        Files.createSymbolicLink(File(directory, "link").toPath(), outside.toPath())
        VoiceCallTempFiles.clear(cache, id)
        assertTrue(keep.exists()); assertFalse(directory.exists())
    }
    @Test fun recoveryOnlyRemovesValidTemporaryOwners() {
        val cache = temporary.newFolder("cache"); val id = owner()
        VoiceCallTempFiles.audio(cache, id)
        val keep = File(cache, "voice-call-temp-v1/not-an-owner").apply { mkdirs() }
        VoiceCallTempFiles.recover(cache)
        assertFalse(VoiceCallTempFiles.directory(cache, id).exists()); assertTrue(keep.exists())
    }
}

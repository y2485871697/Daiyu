package app.daiyu.android.skills

import android.test.InstrumentationTestCase
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalSkillZipArchiveTest : InstrumentationTestCase() {
    private fun zip(vararg paths: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { out -> paths.forEach { path ->
            out.putNextEntry(ZipEntry(path)); if (!path.endsWith('/')) out.write("hello".toByteArray()); out.closeEntry()
        } }
    }.toByteArray()
    private fun rejects(block: () -> Unit) {
        try { block(); fail("archive accepted") } catch (_: IllegalArgumentException) { }
    }
    fun testRootAndSingleWrapperRetainOnlyExactSkillResources() {
        assertEquals(setOf("SKILL.md", "references/a.md"), SkillZipArchive.readLocal(zip("SKILL.md", "references/a.md")).keys)
        assertEquals(setOf("SKILL.md", "references/a.md"), SkillZipArchive.readLocal(zip("demo/", "demo/SKILL.md", "demo/references/a.md")).keys)
    }
    fun testZeroMultipleNestedAndAmbiguousLayoutsReject() {
        for (paths in listOf(arrayOf("readme.md"), arrayOf("a/SKILL.md", "b/SKILL.md"),
            arrayOf("SKILL.md", "nested/SKILL.md"), arrayOf("a/b/SKILL.md"), arrayOf("a/SKILL.md", "outside.txt"))) {
            rejects { SkillZipArchive.readLocal(zip(*paths)) }
        }
    }
    fun testUnsafePathsFileAncestorsAndTruncationReject() {
        for (paths in listOf(arrayOf("../SKILL.md"), arrayOf("/SKILL.md"), arrayOf("a\\SKILL.md"),
            arrayOf("SKILL.md", "a", "a/b"), arrayOf("SKILL.md", "a/b", "a"), arrayOf("SKILL.md", "a", "a/"))) {
            rejects { SkillZipArchive.readLocal(zip(*paths)) }
        }
        rejects { SkillZipArchive.readLocal(zip("SKILL.md").dropLast(1).toByteArray()) }
    }
    fun testPinnedGithubContractIsUnchanged() {
        val commit = "a".repeat(40)
        rejects { SkillZipArchive.read(zip("SKILL.md"), commit) }
        rejects { SkillZipArchive.read(zip("repo-${"b".repeat(40)}/SKILL.md"), commit) }
        assertEquals(setOf("SKILL.md"), SkillZipArchive.read(zip("repo-$commit/SKILL.md"), commit).keys)
    }
    fun testStreamFailureAndOversizeAlwaysClose() {
        var closed = false
        val broken = object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("injected")
            override fun close() { closed = true }
        }
        try { LocalSkillZipImport.read { broken }; fail("broken read accepted") } catch (_: java.io.IOException) { }
        assertTrue(closed)
        closed = false
        val oversized = object : java.io.InputStream() {
            override fun read(): Int = 0
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int { bytes.fill(0); return length }
            override fun close() { closed = true }
        }
        rejects { LocalSkillZipImport.read { oversized } }
        assertTrue(closed)
    }
}

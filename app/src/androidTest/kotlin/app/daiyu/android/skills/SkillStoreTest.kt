package app.daiyu.android.skills

import android.test.InstrumentationTestCase
import java.io.File
import java.nio.file.Files
import org.json.JSONObject

@Suppress("DEPRECATION")
class SkillStoreTest : InstrumentationTestCase() {
    private lateinit var root: File
    override fun setUp() { super.setUp(); root = File(instrumentation.targetContext.cacheDir, "skills-test-${java.util.UUID.randomUUID()}"); check(root.mkdir()) }
    override fun tearDown() { root.deleteRecursively(); super.tearDown() }
    private fun bundle(text: String = "Hello", resource: ByteArray = "reference".toByteArray()) = SkillBundle(mapOf("SKILL.md" to "---\nname: demo\ndescription: Test skill\n---\n$text".toByteArray(), "references/a.txt" to resource, "scripts/example.sh" to "echo never-executed".toByteArray()))
    private fun rejects(action: () -> Unit) { try { action(); fail("must reject") } catch (_: Exception) { } }
    fun testFrontmatterAndEnableSnapshot() {
        val store = SkillStore(root)
        val info = store.install(listOf(bundle())).single()
        assertTrue(info.enabled)
        val captured = store.snapshot()
        assertTrue(captured.skills.single().instructions.contains("Hello"))
        store.setEnabled("demo", false, info.revision)
        assertTrue(store.snapshot().skills.isEmpty())
        rejects { store.readResource("demo", "SKILL.md", info.revision) }
        assertTrue(captured.skills.single().instructions.contains("Hello"))
        assertEquals("A folded description", SkillFrontmatter.parse("---\nname: test\ndescription: >\n  A folded\n  description\n---").description)
    }
    fun testSnapshotListsCannotBeMutated() {
        val store = SkillStore(root)
        val old = store.install(listOf(bundle())).single()
        val captured = store.snapshot()
        val skills = captured.skills as MutableList<EnabledSkill>
        val original = skills.single()
        fun immutable(action: () -> Unit) {
            try { action(); fail("snapshot list must be unmodifiable") }
            catch (_: UnsupportedOperationException) { }
        }
        immutable { skills.clear() }
        immutable { skills.add(original) }
        immutable { skills[0] = original.copy(instructions = "Changed") }
        immutable { skills.removeAt(0) }
        immutable { skills.iterator().also { it.next() }.remove() }
        assertEquals(listOf(original), captured.skills)
        store.setEnabled("demo", false, old.revision)
        val empty = store.snapshot().skills as MutableList<EnabledSkill>
        immutable { empty.add(original) }
        assertTrue(empty.isEmpty())
        store.install(listOf(bundle("Changed")), mapOf("demo" to old.revision))
        assertEquals(listOf(original), captured.skills)
        assertTrue(store.snapshot().skills.single().instructions.contains("Changed"))
    }
    fun testBuiltinNamesAreDefensivelyCopied() {
        val names = mutableSetOf("demo", "other")
        val store = SkillStore(root, names)
        names.clear()
        try { store.install(listOf(bundle())); fail("clearing caller set must not remove builtin protection") }
        catch (problem: IllegalArgumentException) { assertEquals("builtin_conflict", problem.message) }
        assertTrue(store.list().isEmpty())
        assertNoTransaction()
    }
    private fun assertNoTransaction() {
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith(".txn-") || it.name.startsWith(".transaction.json") })
    }
    fun testSelectionLimitRejectsBeforeTraversingInput() {
        val store = SkillStore(root)
        val oversized = object : AbstractList<SkillBundle>() {
            override val size = SkillStore.MAX_SKILLS + 1
            override fun get(index: Int): SkillBundle = throw AssertionError("oversized selection must not be traversed")
            override fun iterator(): Iterator<SkillBundle> = throw AssertionError("oversized selection must not be iterated")
        }
        for (selection in listOf(emptyList(), oversized)) {
            try { store.install(selection); fail("selection must reject") }
            catch (problem: IllegalArgumentException) { assertEquals("invalid_selection", problem.message) }
            assertTrue(store.list().isEmpty())
            assertNoTransaction()
        }
    }
    fun testBundleLimitsRejectBeforeCopyAndLeaveNoTransaction() {
        val store = SkillStore(root)
        val old = store.install(listOf(bundle())).single()
        store.setEnabled("demo", false, old.revision)
        val metadata = File(root, "installed/demo/.skill.json").readBytes()
        val fullFile = ByteArray(SkillStore.MAX_FILE_BYTES)
        val cases = listOf(
            emptyMap<String, ByteArray>() to "skill_too_large",
            (0..SkillStore.MAX_FILES).associate { "f$it" to byteArrayOf() } to "skill_too_large",
            mapOf("large" to ByteArray(SkillStore.MAX_FILE_BYTES + 1)) to "unsafe_resource",
            (0..(SkillStore.MAX_SKILL_BYTES / SkillStore.MAX_FILE_BYTES)).associate { "f$it" to fullFile } to "skill_too_large"
        )
        for ((files, message) in cases) {
            val noCopy = object : Map<String, ByteArray> by files {
                override val values: Collection<ByteArray>
                    get() {
                        if (files.size !in 1..SkillStore.MAX_FILES) throw AssertionError("invalid file count must reject before traversing values")
                        return files.values
                    }
                override val entries: Set<Map.Entry<String, ByteArray>>
                    get() = throw AssertionError("over-limit bundle must reject before copying entries")
            }
            val incoming = SkillBundle(noCopy)
            for (preview in listOf(false, true)) {
                try {
                    if (preview) store.preview(incoming)
                    else store.install(listOf(incoming), mapOf("demo" to old.revision))
                    fail("bundle limit must reject")
                } catch (problem: IllegalArgumentException) { assertEquals(message, problem.message) }
                assertEquals(old.revision, store.list().single().revision)
                assertFalse(store.list().single().enabled)
                assertTrue(metadata.contentEquals(File(root, "installed/demo/.skill.json").readBytes()))
                assertNoTransaction()
            }
        }
        // All selected bundles must pass preflight before copying even the first bundle.
        val firstFiles = bundle().files
        val first = SkillBundle(object : Map<String, ByteArray> by firstFiles {
            override val entries: Set<Map.Entry<String, ByteArray>>
                get() = throw AssertionError("must check later bundles before copying")
        })
        try { store.install(listOf(first, SkillBundle(cases.last().first))); fail("total size must reject") }
        catch (problem: IllegalArgumentException) { assertEquals("skill_too_large", problem.message) }
        assertNoTransaction()
    }
    fun testCopiedBundlesAreStillLimitChecked() {
        val store = SkillStore(root)
        val fullFile = ByteArray(SkillStore.MAX_FILE_BYTES)
        val cases = listOf(
            (0..SkillStore.MAX_FILES).associate { "f$it" to byteArrayOf() } to "skill_too_large",
            mapOf("large" to ByteArray(SkillStore.MAX_FILE_BYTES + 1)) to "unsafe_resource",
            (0..(SkillStore.MAX_SKILL_BYTES / SkillStore.MAX_FILE_BYTES)).associate { "f$it" to fullFile } to "skill_too_large"
        )
        for ((files, message) in cases) {
            // Simulate an input whose size/values change between preflight and the copy.
            val changing = SkillBundle(object : Map<String, ByteArray> by files {
                override val size = 1
                override val values: Collection<ByteArray> = listOf(byteArrayOf())
            })
            for (preview in listOf(false, true)) {
                try {
                    if (preview) store.preview(changing) else store.install(listOf(changing))
                    fail("copied bundle limit must reject")
                } catch (problem: IllegalArgumentException) { assertEquals(message, problem.message) }
                assertTrue(store.list().isEmpty())
                assertNoTransaction()
            }
        }
    }
    fun testRootTraversalBinaryUtf8AndBounds() {
        val store = SkillStore(root); val info = store.install(listOf(bundle())).single()
        for (path in listOf("../SKILL.md", "/etc/passwd", "references/../SKILL.md", "scripts\\example.sh", ".skill.json")) rejects { store.readResource("demo", path, info.revision) }
        rejects { store.readResource("demo", "references/a.txt", info.revision, 2) }
        // Reading scripts is permitted text, not execution.
        assertEquals("echo never-executed", store.readResource("demo", "scripts/example.sh", info.revision))
        val resource = File(root, "installed/demo/references/a.txt")
        check(resource.delete()); Files.createSymbolicLink(resource.toPath(), File(root, "installed/demo/SKILL.md").toPath())
        rejects { store.readResource("demo", "references/a.txt", info.revision) }
        root.deleteRecursively(); check(root.mkdir())
        val binary = SkillStore(root).install(listOf(bundle(resource = byteArrayOf(0)))).single()
        rejects { SkillStore(root).readResource("demo", "references/a.txt", binary.revision) }
        root.deleteRecursively(); check(root.mkdir())
        val malformed = SkillStore(root).install(listOf(bundle(resource = byteArrayOf(0xc3.toByte(), 0x28)))).single()
        rejects { SkillStore(root).readResource("demo", "references/a.txt", malformed.revision) }
    }
    fun testPreciseConflictIncomingAndBuiltinProtection() {
        val store = SkillStore(root); val first = store.install(listOf(bundle())).single()
        rejects { store.install(listOf(bundle("Changed"))) }
        rejects { store.install(listOf(bundle("Changed")), mapOf("demo" to first.revision), mapOf("demo" to first.revision)) }
        assertEquals(first.revision, store.list().single().revision)
        val incoming = store.preview(bundle("Changed")).second
        val second = store.install(listOf(bundle("Changed")), mapOf("demo" to first.revision), mapOf("demo" to incoming)).single()
        assertFalse(first.revision == second.revision)
        rejects { store.install(listOf(bundle()), mapOf("demo" to first.revision)) }
        rejects { SkillStore(File(root, "protected"), setOf("demo")).install(listOf(bundle())) }
    }
    fun testTamperedBodyNeverInjected() {
        val store = SkillStore(root); store.install(listOf(bundle()))
        File(root, "installed/demo/SKILL.md").writeText("modified")
        rejects { store.snapshot() }
    }
    fun testRecoveryRollsBackPartiallyInstalledReplacement() {
        val store = SkillStore(root); val old = store.install(listOf(bundle())).single()
        val transaction = ".txn-00000000-0000-0000-0000-000000000000"
        val txn = File(root, transaction); check(File(txn, "stage").mkdirs()); check(File(txn, "backup").mkdirs())
        check(File(root, "installed/demo").renameTo(File(txn, "backup/demo")))
        check(File(root, "installed/demo").mkdir()); File(root, "installed/demo/SKILL.md").writeText("partial")
        File(root, ".transaction.json").writeText(JSONObject().put("transaction", transaction).put("committed", false).put("entries", org.json.JSONArray().put(JSONObject().put("name", "demo").put("had_previous", true))).toString())
        SkillStore(root).recover()
        assertEquals(old.revision, SkillStore(root).list().single().revision)
        assertTrue(SkillStore(root).snapshot().skills.single().instructions.contains("Hello"))
        assertFalse(txn.exists())
    }
    fun testOversizedMetadataAndSourceReplacementPreservesDisabledRevision() {
        val store = SkillStore(root)
        val old = store.install(listOf(bundle())).single()
        store.setEnabled("demo", false, old.revision)
        val metadata = File(root, "installed/demo/.skill.json").readBytes()
        val files = linkedMapOf("SKILL.md" to bundle("Changed").files.getValue("SKILL.md"))
        val parent = List(6) { "中".repeat(80) }.joinToString("/")
        repeat(255) { files["$parent/f$it"] = byteArrayOf() }
        // Paths are <=512 characters and components <=255 UTF-8 bytes; metadata still exceeds 256 KiB.
        try { store.install(listOf(SkillBundle(files)), mapOf("demo" to old.revision)); fail("metadata must reject before staging") }
        catch (problem: IllegalArgumentException) { assertEquals("metadata_too_large", problem.message) }
        try { store.install(listOf(bundle("Changed").copy(source = "中".repeat(1366))), mapOf("demo" to old.revision)); fail("source must reject") }
        catch (problem: IllegalArgumentException) { assertEquals("source_too_large", problem.message) }
        assertEquals(old.revision, store.list().single().revision)
        assertFalse(store.list().single().enabled)
        assertTrue(metadata.contentEquals(File(root, "installed/demo/.skill.json").readBytes()))
        assertTrue(store.snapshot().skills.isEmpty())
        assertFalse(File(root, ".transaction.json").exists())
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith(".txn-") })
    }
    fun testPreCommitFaultsRollBackAndPreserveEnableState() {
        for (boundary in listOf("before_journal", "after_backup", "after_publish", "before_commit")) {
            val directory = File(root, boundary)
            val store = SkillStore(directory)
            val old = store.install(listOf(bundle())).single()
            store.setEnabled("demo", false, old.revision)
            val before = File(directory, "installed/demo/.skill.json").readBytes()
            store.transactionCheckpoint = { if (it == boundary) throw java.io.IOException("injected_failure") }
            rejects { store.install(listOf(bundle("Changed")), mapOf("demo" to old.revision)) }
            store.transactionCheckpoint = {}
            val recovered = SkillStore(directory)
            assertEquals(old.revision, recovered.list().single().revision)
            assertFalse(recovered.list().single().enabled)
            assertTrue(before.contentEquals(File(directory, "installed/demo/.skill.json").readBytes()))
            assertTrue(File(directory, "installed/demo/SKILL.md").readText().contains("Hello"))
            assertFalse(File(directory, ".transaction.json").exists())
        }
    }
    fun testCommittedFaultsReportCommittedAndRecoverWithoutRollback() {
        for (boundary in listOf("after_commit", "before_cleanup")) {
            val directory = File(root, boundary)
            val store = SkillStore(directory)
            val old = store.install(listOf(bundle())).single()
            store.setEnabled("demo", false, old.revision)
            val incoming = store.preview(bundle("Changed")).second
            store.transactionCheckpoint = { if (it == boundary) throw java.io.IOException("injected_failure") }
            try {
                store.install(listOf(bundle("Changed")), mapOf("demo" to old.revision), mapOf("demo" to incoming))
                fail("must report committed cleanup failure")
            } catch (committed: SkillCleanupPending) {
                assertEquals(incoming, committed.installedSkills.single().revision)
                assertTrue(committed.installedSkills.single().enabled)
            }
            val recovered = SkillStore(directory)
            recovered.recover()
            assertEquals(incoming, recovered.list().single().revision)
            assertTrue(recovered.list().single().enabled)
            assertTrue(recovered.snapshot().skills.single().instructions.contains("Changed"))
            assertFalse(File(directory, ".transaction.json").exists())
            rejects { recovered.install(listOf(bundle()), mapOf("demo" to old.revision)) }
        }
    }
    fun testBatchFaultRestoresEveryPreviousSkill() {
        fun other(text: String) = bundle(text).let { input ->
            input.copy(files = input.files + ("SKILL.md" to String(input.files.getValue("SKILL.md")).replace("name: demo", "name: other").toByteArray()))
        }
        val store = SkillStore(root)
        val old = store.install(listOf(bundle(), other("Old other")))
        old.forEach { store.setEnabled(it.name, false, it.revision) }
        var published = 0
        store.transactionCheckpoint = { if (it == "after_publish" && ++published == 2) throw java.io.IOException("injected_failure") }
        rejects { store.install(listOf(bundle("New demo"), other("New other")), old.associate { it.name to it.revision }) }
        val recovered = SkillStore(root).list()
        assertEquals(old.map { it.revision }, recovered.map { it.revision })
        assertTrue(recovered.none { it.enabled })
    }
    fun testZipFileAncestorConflictIndependentOfOrder() {
        val commit = "a".repeat(40)
        for (paths in listOf(listOf("a", "a/b"), listOf("a/b", "a"), listOf("a", "a/"))) {
            val bytes = java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(bytes).use { out ->
                paths.forEach { path -> out.putNextEntry(java.util.zip.ZipEntry("repo-$commit/$path")); out.closeEntry() }
            }
            rejects { SkillZipArchive.read(bytes.toByteArray(), commit) }
        }
    }
    fun testZipTraversalCommitAndBounds() {
        val commit = "a".repeat(40)
        fun zip(path: String, data: ByteArray = "hello".toByteArray()): ByteArray {
            val bytes = java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(bytes).use { out -> out.putNextEntry(java.util.zip.ZipEntry(path)); out.write(data); out.closeEntry() }
            return bytes.toByteArray()
        }
        assertEquals("hello", String(SkillZipArchive.read(zip("repo-$commit/SKILL.md"), commit).getValue("SKILL.md")))
        rejects { SkillZipArchive.read(zip("repo-$commit/../escape"), commit) }
        rejects { SkillZipArchive.read(zip("repo-${"b".repeat(40)}/SKILL.md"), commit) }
        rejects { SkillZipArchive.read(zip("repo-$commit/large", ByteArray(SkillStore.MAX_FILE_BYTES + 1)), commit) }
        rejects { SkillZipArchive.read(zip("/absolute"), commit) }
    }
}

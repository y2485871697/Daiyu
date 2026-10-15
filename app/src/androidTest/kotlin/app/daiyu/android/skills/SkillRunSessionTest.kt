package app.daiyu.android.skills

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.tools.SessionCapabilities
import app.daiyu.android.domain.tools.TaskAuthorization
import org.json.JSONObject
import java.io.File
import java.util.UUID

@Suppress("DEPRECATION")
class SkillRunSessionTest : InstrumentationTestCase() {
    private fun rejected(code: String, block: () -> Unit) {
        try { block(); fail("expected $code") } catch (e: Exception) { assertEquals(code, e.message) }
    }
    private class Fixture(base: File) : AutoCloseable {
        val root = File(base, "run-skill-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val builtin = SkillStore(File(root, "builtin"))
        val user = SkillStore(File(root, "user"))
        val auth = TaskAuthorization("session", "run")
        fun install(store: SkillStore = user, body: String = "old body", revision: String? = null, resource: String = "old resource", name: String = "demo"): InstalledSkill {
            return store.install(listOf(SkillBundle(mapOf(
                "SKILL.md" to "---\nname: $name\ndescription: Demo\n---\n$body".toByteArray(),
                "references/guide.md" to resource.toByteArray()
            ))), if (revision == null) emptyMap() else mapOf(name to revision)).single()
        }
        fun session(resources: Boolean = true): SkillRunSession {
            val captured = user.captureForRun(SkillRunSession.MAX_CAPTURE_BYTES)
            return SkillRunSession.create(auth, if (resources) setOf(SkillRunSession.READ, SkillRunSession.RESOURCE) else setOf(SkillRunSession.READ),
                captured.map { SkillRunSession.Entry("user:${it.name}", it, user) })
        }
        override fun close() { root.deleteRecursively() }
    }
    private fun fixture() = Fixture(instrumentation.targetContext.cacheDir)

    fun testBodyAndIdentityFrozenAfterReplacement() { fixture().use { f ->
        val old = f.install(); val scope = f.session()
        f.install(body = "new body", revision = old.revision)
        assertTrue(scope.read(f.auth, "user:demo", old.revision).endsWith("old body"))
        rejected("skill_not_found") { scope.read(f.auth, "builtin:demo", old.revision) }
        rejected("run_mismatch") { scope.read(TaskAuthorization("session", "run"), "user:demo", old.revision) }
        rejected("revision_mismatch") { scope.read(f.auth, "user:demo", "wrong") }
    } }
    fun testResourceRevisionChangeRejectedEvenWhenResourceSame() { fixture().use { f ->
        val old = f.install(); val scope = f.session()
        f.install(body = "updated body", revision = old.revision)
        try { scope.readResource(f.auth, "user:demo", old.revision, "references/guide.md"); fail() }
        catch (_: SkillConflict) { }
    } }
    fun testFrozenHashRejectsCoordinatedMetadataAndContentTamper() { fixture().use { f ->
        val old = f.install(); val scope = f.session()
        val dir = File(f.root, "user/installed/demo")
        val bytes = "tampered".toByteArray()
        SkillFiles.atomic(File(dir, "references/guide.md"), bytes)
        val meta = File(dir, ".skill.json")
        val json = JSONObject(SkillFiles.text(SkillFiles.readAtomic(meta, SkillStore.MAX_FILE_BYTES)))
        json.getJSONObject("files").put("references/guide.md", SkillFiles.hash(bytes))
        SkillFiles.atomic(meta, json.toString().toByteArray())
        rejected("resource_changed") { scope.readResource(f.auth, "user:demo", old.revision, "references/guide.md") }
    } }
    fun testUnknownResourceAndBadPathsRejectedBeforeCurrentStore() { fixture().use { f ->
        val old = f.install(); val scope = f.session()
        File(f.root, "user").deleteRecursively()
        rejected("resource_not_found") { scope.readResource(f.auth, "user:demo", old.revision, "new.md") }
        for (path in listOf("../x", "/x", "references//x", "references\\x", ".hidden", "references/./x"))
            rejected("unsafe_path") { scope.readResource(f.auth, "user:demo", old.revision, path) }
        rejected("invalid_limit") { scope.readResource(f.auth, "user:demo", old.revision, "references/guide.md", 0) }
    } }
    fun testReadOnlyToolsAndChildCannotRegainResourcePermission() { fixture().use { f ->
        val old = f.install(); val scope = f.session(false)
        assertEquals(setOf("skills_list", "skills_read"), scope.availableTools)
        val childAuth = TaskAuthorization("session", "child")
        val child = scope.child(f.auth, childAuth, SessionCapabilities("session", setOf(SkillRunSession.READ, SkillRunSession.RESOURCE)))
        assertEquals(scope.availableTools, child.availableTools)
        rejected("skills_not_authorized") { child.readResource(childAuth, "user:demo", old.revision, "references/guide.md") }
        assertTrue(child.read(childAuth, "user:demo", old.revision).endsWith("old body"))
    } }
    fun testParentCloseRevokesDescendantsAndChildCloseDoesNotCloseParent() { fixture().use { f ->
        val old = f.install(); val scope = f.session()
        val childAuth = TaskAuthorization("session", "child")
        val child = scope.child(f.auth, childAuth, SessionCapabilities("session", setOf(SkillRunSession.READ)))
        child.close(); rejected("run_closed") { child.read(childAuth, "user:demo", old.revision) }
        assertTrue(scope.read(f.auth, "user:demo", old.revision).endsWith("old body"))
        val nextAuth = TaskAuthorization("session", "next")
        val next = scope.child(f.auth, nextAuth, SessionCapabilities("session", setOf(SkillRunSession.READ)))
        scope.close(); scope.close()
        assertTrue(scope.availableTools.isEmpty()); assertTrue(next.availableTools.isEmpty())
        rejected("run_closed") { next.index(nextAuth) }
    } }
    fun testDisabledCaptureEmptyAndImmutableCollections() { fixture().use { f ->
        val old = f.install(); f.user.setEnabled("demo", false, old.revision)
        val scope = f.session(); assertTrue(scope.index(f.auth).isEmpty())
        f.user.setEnabled("demo", true, old.revision)
        assertTrue(scope.index(f.auth).isEmpty())
        val capture = f.user.captureForRun(SkillRunSession.MAX_CAPTURE_BYTES)
        try { (capture.first().resources as MutableMap<String, String>).clear(); fail() } catch (_: UnsupportedOperationException) { }
        try { (capture as MutableList<CapturedSkill>).clear(); fail() } catch (_: UnsupportedOperationException) { }
    } }
    fun testResourceByteLimitAndDisableFailWithoutChangingFrozenBody() { fixture().use { f ->
        val old = f.install(resource = "你好"); val scope = f.session()
        rejected("resource_too_large") { scope.readResource(f.auth, "user:demo", old.revision, "references/guide.md", 5) }
        assertEquals("你好", scope.readResource(f.auth, "user:demo", old.revision, "references/guide.md", 6))
        f.user.setEnabled("demo", false, old.revision)
        rejected("skill_disabled") { scope.readResource(f.auth, "user:demo", old.revision, "references/guide.md") }
        assertTrue(scope.read(f.auth, "user:demo", old.revision).endsWith("old body"))
    } }
    fun testParentAndChildShareOutputBudget() { fixture().use { f ->
        val old = f.install(body = "x".repeat(220000)); val scope = f.session()
        val auth = TaskAuthorization("session", "child")
        val child = scope.child(f.auth, auth, SessionCapabilities("session", setOf(SkillRunSession.READ)))
        repeat(9) { if (it % 2 == 0) scope.read(f.auth, "user:demo", old.revision) else child.read(auth, "user:demo", old.revision) }
        rejected("run_read_budget_exhausted") { child.read(auth, "user:demo", old.revision) }
    } }
    fun testCaptureBudgetAccountsForAllSkills() { fixture().use { f ->
        f.install(body = "a".repeat(140000))
        f.install(body = "b".repeat(140000), name = "demo-b")
        rejected("snapshot_too_large") { f.user.captureForRun(200000) }
    } }
    fun testCaptureBudgetRejectsWithoutPublishingAndNewResourceNotAdded() { fixture().use { f ->
        val old = f.install()
        rejected("snapshot_too_large") { f.user.captureForRun(1) }
        val scope = f.session()
        val dir = File(f.root, "user/installed/demo"); val meta = File(dir, ".skill.json")
        val json = JSONObject(SkillFiles.text(SkillFiles.readAtomic(meta, SkillStore.MAX_FILE_BYTES)))
        val bytes = "extra".toByteArray(); SkillFiles.atomic(File(dir, "new.md"), bytes)
        json.getJSONObject("files").put("new.md", SkillFiles.hash(bytes)); SkillFiles.atomic(meta, json.toString().toByteArray())
        rejected("resource_not_found") { scope.readResource(f.auth, "user:demo", old.revision, "new.md") }
        assertEquals("old resource", scope.readResource(f.auth, "user:demo", old.revision, "references/guide.md"))
    } }
    fun testEncodedIndexEscapesCountTowardPerResponseLimit() { fixture().use { f ->
        val description = "\\".repeat(2048)
        f.user.install((0 until 30).map { n -> SkillBundle(mapOf("SKILL.md" to
            "---\nname: demo-$n\ndescription: $description\n---\nbody".toByteArray(Charsets.UTF_8))) })
        val run = f.session()
        rejected("index_too_large") { run.index(f.auth) }
        rejected("index_too_large") { run.indexJson(f.auth) }
    } }

    fun testEncodedIndexAndChildChargeTheSameSharedBudget() { fixture().use { f ->
        val description = "\\\"".repeat(500)
        f.user.install((0 until 8).map { n -> SkillBundle(mapOf("SKILL.md" to
            "---\nname: demo-$n\ndescription: $description\n---\nbody".toByteArray(Charsets.UTF_8))) })
        val run = f.session()
        val encoded = run.indexJson(f.auth)
        assertEquals(description, JSONObject(encoded).getJSONArray("skills").getJSONObject(0).getString("description"))
        val bytes = encoded.toByteArray(Charsets.UTF_8).size
        val auth = TaskAuthorization("session", "child")
        val child = run.child(f.auth, auth, SessionCapabilities("session", setOf(SkillRunSession.READ)))
        val allowed = SkillRunSession.MAX_CAPTURE_BYTES / bytes
        repeat(allowed - 1) { n ->
            if (n % 2 == 0) assertEquals(encoded, child.indexJson(auth)) else assertEquals(8, run.index(f.auth).size)
        }
        rejected("run_read_budget_exhausted") { child.indexJson(auth) }
        rejected("run_read_budget_exhausted") { run.index(f.auth) }
    } }

    fun testConcurrentParentAndChildReadsCannotMultiplyBudget() { fixture().use { f ->
        val skill = f.install(body = "x".repeat(220000))
        val run = f.session()
        val childAuth = TaskAuthorization("session", "child")
        val child = run.child(f.auth, childAuth, SessionCapabilities("session", setOf(SkillRunSession.READ)))
        val start = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(16)
        val successes = java.util.concurrent.atomic.AtomicInteger()
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        repeat(16) { n -> Thread {
            try {
                check(start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                if (n % 2 == 0) run.read(f.auth, "user:demo", skill.revision) else child.read(childAuth, "user:demo", skill.revision)
                successes.incrementAndGet()
            } catch (expected: IllegalArgumentException) {
                if (expected.message != "run_read_budget_exhausted") failures.add(expected)
            } catch (unexpected: Throwable) { failures.add(unexpected) }
            finally { done.countDown() }
        }.start() }
        start.countDown()
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(failures.toString(), failures.isEmpty())
        assertEquals(9, successes.get())
    } }

}

package app.daiyu.android.voice.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VoiceProjectSelectionTest {
    private fun project(name: String, allowed: Boolean = true) = VoiceProjectCatalog.Project(name, name, allowed)

    @Test fun keepsAccessibleSelectionEvenWhenDefaultExists() {
        val selected = project("selected")
        assertEquals(selected, VoiceProjectCatalog.selectProject(listOf(project("default"), selected), "selected"))
    }
    @Test fun staleOrDeniedSelectionFallsBackToAccessibleDefault() {
        val fallback = project("default")
        val projects = listOf(project("denied", false), project("first"), fallback)
        assertEquals(fallback, VoiceProjectCatalog.selectProject(projects, "missing"))
        assertEquals(fallback, VoiceProjectCatalog.selectProject(projects, "denied"))
    }
    @Test fun deniedDefaultIsNeverChosenAndFirstAccessibleProjectWins() {
        val first = project("first")
        assertEquals(first, VoiceProjectCatalog.selectProject(listOf(project("default", false), first, project("last")), "default"))
    }
    @Test fun emptyCatalogCannotInventDefault() {
        assertThrows(IllegalStateException::class.java) { VoiceProjectCatalog.selectProject(emptyList(), "default") }
    }
    @Test fun allDeniedCatalogCannotPreserveSelection() {
        assertThrows(IllegalStateException::class.java) {
            VoiceProjectCatalog.selectProject(listOf(project("default", false), project("selected", false)), "selected")
        }
    }
    @Test fun returnsServerProjectNameRatherThanDisplayName() {
        val actual = VoiceProjectCatalog.Project("server-name", "Display name", true)
        assertEquals("server-name", VoiceProjectCatalog.selectProject(listOf(actual), "").name)
    }
    @Test fun eachRefreshUsesCurrentKeysAndDoesNotReusePreviousCatalog() = runBlocking {
        val credentials = mutableListOf<String>()
        val catalog = VoiceProjectCatalog(AccountHttpTransport { request, _ ->
            credentials += request.header("Authorization")!!
            assertNull(request.header("X-Api-Key"))
            val name = if (credentials.size == 1) "previous" else "current"
            AccountResponse(200, """{"Result":{"Total":1,"Projects":[{"ProjectName":"$name","HasPermission":true}]}}""".toByteArray())
        })
        assertEquals("previous", VoiceProjectCatalog.selectProject(catalog.list("ak-one", "sk-one"), "default").name)
        assertEquals("current", VoiceProjectCatalog.selectProject(catalog.list("ak-two", "sk-two"), "previous").name)
        assertEquals(2, credentials.size)
        assertTrue(credentials[0].contains("Credential=ak-one/"))
        assertTrue(credentials[1].contains("Credential=ak-two/"))
    }
    @Test fun queryFailureNeverProducesFallbackCatalog() = runBlocking {
        val catalog = VoiceProjectCatalog(AccountHttpTransport { _, _ -> error("synthetic failure") })
        val failure = runCatching { VoiceProjectCatalog.selectProject(catalog.list("ak", "sk"), "default") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }
    @Test fun cancellationIsNotConvertedToAnEmptyOrDefaultProject() = runBlocking {
        val catalog = VoiceProjectCatalog(AccountHttpTransport { _, _ -> throw CancellationException("cancelled") })
        val failure = runCatching { VoiceProjectCatalog.selectProject(catalog.list("ak", "sk"), "default") }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }
}

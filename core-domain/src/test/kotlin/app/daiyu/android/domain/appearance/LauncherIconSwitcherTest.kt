package app.daiyu.android.domain.appearance

import org.junit.Assert.*
import org.junit.Test

class LauncherIconSwitcherTest {
    private val pkg = "app.daiyu.android"
    private val old = Task17IconCatalog.defaultEntry
    private val target = Task17IconCatalog.entries.first()
    private fun name(entry: Task17IconCatalog.Entry) = entry.componentName(pkg)

    private inner class Fake : LauncherComponentPort {
        val states = Task17IconCatalog.entries.associate { name(it) to IconComponentState.DEFAULT }.toMutableMap()
        val calls = mutableListOf<Pair<String, IconComponentState>>()
        var missing: String? = null
        var fail: (String, IconComponentState) -> Boolean = { _, _ -> false }
        var failed = false
        override fun validate(componentName: String) { check(componentName != missing) { "missing component" } }
        override fun snapshot(componentName: String): IconComponentSnapshot {
            val state = states.getValue(componentName)
            return IconComponentSnapshot(state, state == IconComponentState.ENABLED || state == IconComponentState.DEFAULT && componentName == name(old))
        }
        override fun setState(componentName: String, state: IconComponentState) {
            calls += componentName to state
            if (fail(componentName, state)) { failed = true; error("PM denied") }
            states[componentName] = state
        }
        fun enabled() = states.keys.filter { snapshot(it).enabled }
    }

    @Test fun repeatedForegroundSyncDoesNotRewriteAlreadySelectedAlias() {
        val fake = Fake()
        var notifications = 0
        val sync = LauncherIconSwitcher(pkg, fake, IconNotificationPort { notifications++ }, {})
        assertTrue(sync.apply(target).complete)
        fake.calls.clear()
        repeat(4) { assertTrue(sync.apply(target).complete) }
        assertTrue(fake.calls.isEmpty())
        assertEquals(5, notifications)
    }

    @Test fun enabledTargetStillConvergesOtherAliasesWithoutRewritingTarget() {
        val fake = Fake()
        fake.states[name(target)] = IconComponentState.ENABLED
        val result = LauncherIconSwitcher(pkg, fake, IconNotificationPort {}, {}).apply(target)
        assertTrue(result.complete)
        assertEquals(listOf(name(target)), fake.enabled())
        assertEquals(listOf(name(old) to IconComponentState.DISABLED), fake.calls)
    }

    @Test fun enablesNewBeforeDisablingOldAndRecordsBeforeRefresh() {
        val fake = Fake()
        val effects = mutableListOf<String>()
        val sync = LauncherIconSwitcher(pkg, fake, IconNotificationPort {
            assertEquals(listOf(name(target)), fake.enabled()); effects += "notification"
        }, { effects += "persist" })
        val result = sync.apply(target)
        assertTrue(result.complete)
        assertEquals(name(target) to IconComponentState.ENABLED, fake.calls.first())
        assertTrue(fake.calls.indexOf(name(old) to IconComponentState.DISABLED) > 0)
        assertEquals(listOf("persist", "notification"), effects)
    }

    @Test fun validationOfAllComponentsPrecedesMutation() {
        val fake = Fake().apply { missing = name(Task17IconCatalog.entries.last()) }
        val result = LauncherIconSwitcher(pkg, fake, IconNotificationPort { fail("must not notify") }, { fail("must not persist") }).apply(target)
        assertFalse(result.launcherApplied)
        assertEquals("validate", result.failures.single().stage)
        assertTrue(fake.calls.isEmpty())
        assertEquals(listOf(name(old)), fake.enabled())
    }

    @Test fun failureEnablingNewLeavesOldAndReportsFailure() {
        val fake = Fake().apply { fail = { component, state -> component == name(target) && state == IconComponentState.ENABLED } }
        val result = LauncherIconSwitcher(pkg, fake, IconNotificationPort {}, {}).apply(target)
        assertFalse(result.launcherApplied)
        assertEquals(listOf(name(old)), fake.enabled())
        assertEquals(IconComponentState.DEFAULT, fake.states[name(old)])
    }

    @Test fun disableFailureRestoresExplicitStatesAndDoesNotPersistOrNotify() {
        val fake = Fake().apply { fail = { component, state -> component == name(old) && state == IconComponentState.DISABLED } }
        val result = LauncherIconSwitcher(pkg, fake, IconNotificationPort { fail("must not notify") }, { fail("must not persist") }).apply(target)
        assertFalse(result.launcherApplied)
        assertEquals(listOf(name(old)), fake.enabled())
        assertTrue(fake.states.values.all { it == IconComponentState.DEFAULT })
        assertEquals("switch", result.failures.first().stage)
    }

    @Test fun recoveryFailureKeepsNewLauncherInsteadOfRemovingLastEntrance() {
        val other = Task17IconCatalog.entries[1]
        val fake = Fake()
        fake.states[name(other)] = IconComponentState.ENABLED
        // order: other is disabled, then old disable fails; restoring former entrances also fails.
        fake.fail = { component, state ->
            when {
                component == name(old) && state == IconComponentState.DISABLED -> {
                    // Simulate PM mutating before throwing, as defensive recovery must tolerate.
                    fake.states[name(old)] = IconComponentState.DISABLED; true
                }
                fake.failed && (component == name(old) || component == name(other)) -> true
                else -> false
            }
        }
        val result = LauncherIconSwitcher(pkg, fake, IconNotificationPort {}, {}).apply(target)
        assertFalse(result.launcherApplied)
        assertTrue(fake.enabled().contains(name(target)))
        assertTrue(result.retainedComponents.contains(name(target)))
        assertTrue(result.failures.any { it.stage == "restore-enable" })
    }

    @Test fun notificationFailureDoesNotRollBackSuccessfulLauncher() {
        val fake = Fake()
        val result = LauncherIconSwitcher(pkg, fake, IconNotificationPort { error("notification denied") }, {}).apply(target)
        assertTrue(result.launcherApplied)
        assertFalse(result.complete)
        assertEquals(listOf(name(target)), fake.enabled())
        assertEquals("notification", result.failures.single().stage)
    }

    @Test fun persistenceFailureStillRefreshesAndReportsDistinctStage() {
        val fake = Fake()
        var refreshed = false
        val result = LauncherIconSwitcher(pkg, fake, IconNotificationPort { refreshed = true }, { error("disk full") }).apply(target)
        assertTrue(result.launcherApplied)
        assertTrue(refreshed)
        assertEquals("persist-current", result.failures.single().stage)
    }

    @Test fun automaticApplyDoesNotReviveUserDisabledTarget() {
        val fake = Fake()
        fake.states[name(target)] = IconComponentState.DISABLED_USER
        val before = fake.states.toMap()
        val sync = LauncherIconSwitcher(pkg, fake, IconNotificationPort { fail("must not notify") }, { fail("must not persist") })
        // Default is automatic/fail-closed, including a formerly current but now disabled alias.
        repeat(2) {
            val result = sync.apply(target)
            assertFalse(result.launcherApplied)
            assertEquals("user-disabled", result.failures.single().stage)
            assertEquals(name(target), result.failures.single().component)
            assertEquals(listOf(name(old)), result.retainedComponents)
            assertEquals(before, fake.states)
            assertTrue(fake.calls.isEmpty())
        }
    }

    @Test fun explicitUserChoiceMayReviveUserDisabledTarget() {
        val fake = Fake()
        fake.states[name(target)] = IconComponentState.DISABLED_USER
        val effects = mutableListOf<String>()
        val sync = LauncherIconSwitcher(pkg, fake, IconNotificationPort { effects += "notification" }, { effects += "persist" })
        assertTrue(sync.apply(target, userInitiated = true).complete)
        assertEquals(listOf(name(target)), fake.enabled())
        assertEquals(IconComponentState.ENABLED, fake.states[name(target)])
        assertEquals(listOf("persist", "notification"), effects)
    }

    @Test fun automaticApplyLeavesOtherUserDisabledAliasesAlone() {
        val fake = Fake()
        val disabled = Task17IconCatalog.entries.last()
        fake.states[name(disabled)] = IconComponentState.DISABLED_USER
        assertTrue(LauncherIconSwitcher(pkg, fake, IconNotificationPort {}, {}).apply(target).complete)
        assertEquals(IconComponentState.DISABLED_USER, fake.states[name(disabled)])
        assertTrue(fake.calls.none { it.first == name(disabled) })
    }

    @Test fun alreadySelectedStillRefreshesNotifications() {
        val fake = Fake()
        var count = 0
        val sync = LauncherIconSwitcher(pkg, fake, IconNotificationPort { count++ }, {})
        assertTrue(sync.apply(old).complete)
        assertTrue(sync.apply(old).complete)
        assertEquals(2, count)
        assertEquals(listOf(name(old)), fake.enabled())
    }
}

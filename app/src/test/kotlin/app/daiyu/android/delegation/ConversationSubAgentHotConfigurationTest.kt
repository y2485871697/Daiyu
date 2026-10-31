package app.daiyu.android.delegation

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

class ConversationSubAgentHotConfigurationTest {
    private val owner = SubAgentConfigKey.Conversation("conversation")
    private val profile = SubAgentProfile("p", "reviewer", role = "review")
    @Test fun destructiveEdgesAreRememberedAcrossRecreationAndOrdinaryEditsKeepLifetime() {
        val disk = HotConfigurationPreferences(); val repository = ConversationSubAgentPreferences(disk)
        repository.update(owner) { it.copy(profiles = listOf(profile)) }
        val notifications = mutableListOf<ConversationSubAgentConfig?>()
        val observer = repository.observeChanges { changed, config -> if (changed == owner) notifications += config }
        val lifetime = repository.profileLifetime(owner, profile.id)
        repository.update(owner) { it.copy(enabled = false, profiles = listOf(profile.copy(name = "renamed"))) }
        assertEquals(lifetime, repository.profileLifetime(owner, profile.id))
        repository.update(owner) { it.copy(profiles = emptyList()) }
        repository.update(owner) { it.copy(profiles = listOf(profile)) }
        assertEquals(lifetime + 1, repository.profileLifetime(owner, profile.id))
        repository.update(owner) { it.copy(profiles = listOf(profile.copy(role = "implementation"))) }
        repository.update(owner) { it.copy(profiles = listOf(profile)) }
        assertEquals(lifetime + 3, ConversationSubAgentPreferences(disk).profileLifetime(owner, profile.id))
        assertEquals(5, notifications.size)
        observer.close(); repository.update(owner) { it.copy(enabled = true) }; assertEquals(5, notifications.size)
    }
    @Test fun failedCommitPublishesNeitherRevocationNorConfigurationChange() {
        val disk = HotConfigurationPreferences(); val repository = ConversationSubAgentPreferences(disk)
        repository.update(owner) { it.copy(profiles = listOf(profile)) }
        var notices = 0; val observer = repository.observeChanges { _, _ -> notices++ }
        val lifetime = repository.profileLifetime(owner, profile.id)
        disk.failNextCommit = true
        assertThrows(IllegalStateException::class.java) { repository.update(owner) { it.copy(profiles = emptyList()) } }
        assertEquals(0, notices); assertTrue(repository.recoverDurability())
        assertEquals(lifetime, repository.profileLifetime(owner, profile.id)); assertEquals(listOf(profile), repository.snapshot(owner).profiles)
        observer.close()
    }
    @Test fun legacyFallbackResizesExistingProfilePoolWithoutAffectingItsNeighbor() {
        val model = SubAgentParallelModel("provider", "api")
        val legacyProfile = profile.copy(providerId = model.providerId, modelId = "selection")
        val config = ConversationSubAgentConfig(listOf(legacyProfile), parallelLimits = mapOf(model to 2))
        val key = config.poolKey(owner, profile.id)
        val pool = app.daiyu.android.domain.delegation.ChildModelPool(4, maximum = 4)
        val neighbor = app.daiyu.android.domain.delegation.ChildModelPool(3)
        for (limit in listOf(2, 0, 1)) {
            val current = config.copy(parallelLimits = mapOf(model to limit))
            val resolved = current.parallelLimitForPool(owner, key) { model.apiModel }
            assertEquals(limit, resolved)
            pool.resize(requireNotNull(resolved))
            assertPoolCapacity(pool, if (limit == 0) 4 else limit)
            assertPoolCapacity(neighbor, 3)
        }
        assertEquals(1, config.copy(parallelLimits = emptyMap()).parallelLimitForPool(owner, key))
        assertEquals(4, config.copy(profiles = listOf(legacyProfile.copy(parallelLimit = 4)))
            .parallelLimitForPool(owner, key) { error("explicit values must not read the catalog") })
    }

    private fun assertPoolCapacity(pool: app.daiyu.android.domain.delegation.ChildModelPool, expected: Int) {
        val leases = List(expected + 1) { pool.lease() }
        try {
            leases.take(expected).forEach { assertTrue(it.tryAcquire()) }
            assertFalse(leases.last().tryAcquire())
        } finally {
            leases.forEach { it.close() }
        }
    }

    @Test fun poolLimitDecodingIsExactAndConversationScoped() {
        val configuration = ConversationSubAgentConfig(listOf(profile.copy(parallelLimit = 2)))
        val key = configuration.poolKey(owner, profile.id)
        assertEquals(2, configuration.parallelLimitForPool(owner, key))
        assertNull(configuration.parallelLimitForPool(SubAgentConfigKey.Conversation("other"), key))
        assertNull(configuration.parallelLimitForPool(owner, key + ":extra"))
        assertNull(configuration.parallelLimitForPool(owner, key + "="))
        assertNull(configuration.parallelLimitForPool(owner, "subagent:v1:c:old"))
        assertNull(configuration.copy(profiles = emptyList()).parallelLimitForPool(owner, key))
        assertEquals(0, configuration.copy(profiles = listOf(profile.copy(parallelLimit = 0))).parallelLimitForPool(owner, key))
        assertNotEquals(key, configuration.poolKey(owner, "another-profile"))
        assertNotEquals(key, configuration.poolKey(SubAgentConfigKey.Draft(owner.value), profile.id))
    }
}

private class HotConfigurationPreferences(initial: Map<String, Any?> = emptyMap()) : SharedPreferences {
    private val values = initial.toMutableMap()
    var failNextCommit = false
    fun reopen() = HotConfigurationPreferences(values.toMap())
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun contains(key: String?) = values.containsKey(key)
    override fun getString(key: String?, defValue: String?): String? = if (values.containsKey(key)) values[key] as String? else defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = Editor()
    private inner class Editor : SharedPreferences.Editor {
        private var clear = false
        private val changes = mutableMapOf<String, Any?>()
        override fun putString(key: String?, value: String?) = apply { changes[requireNotNull(key)] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { changes[requireNotNull(key)] = values?.toSet() }
        override fun putInt(key: String?, value: Int) = apply { changes[requireNotNull(key)] = value }
        override fun putLong(key: String?, value: Long) = apply { changes[requireNotNull(key)] = value }
        override fun putFloat(key: String?, value: Float) = apply { changes[requireNotNull(key)] = value }
        override fun putBoolean(key: String?, value: Boolean) = apply { changes[requireNotNull(key)] = value }
        override fun remove(key: String?) = apply { changes[requireNotNull(key)] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) values.clear()
            changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            if (failNextCommit) { failNextCommit = false; return false }
            return true
        }
        override fun apply() { commit() }
    }
}

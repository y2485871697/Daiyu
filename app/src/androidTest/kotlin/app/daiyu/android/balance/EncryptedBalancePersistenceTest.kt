package app.daiyu.android.balance

import android.test.AndroidTestCase
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Uses only a random P05 namespace; does not read or delete model/user key aliases. No network. */
@Suppress("DEPRECATION")
class EncryptedBalancePersistenceTest : AndroidTestCase() {
    private lateinit var namespace: String
    private val prefix get() = "daiyu.p05.$namespace."
    private val recordFile get() = File(context.noBackupFilesDir, "$namespace.p05")
    private fun keys(): Set<String> = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        .aliases().toList().filter { it.startsWith(prefix) }.toSet()
    override fun setUp() { super.setUp(); namespace = "p05-test-${UUID.randomUUID()}" }
    override fun tearDown() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys().forEach { keyStore.deleteEntry(it) }
        listOf("", ".bak", ".new").forEach { File(recordFile.path + it).delete() }
        super.tearDown()
    }
    private fun configuration() = BalanceConfiguration("private-account-label", "https://example.test/private-balance",
        "private-balance-secret", "/balance", "CNY")

    fun testWholeRecordIsEncryptedAndRestoresResultWithoutRequest() {
        val persistence = EncryptedBalancePersistence(context, namespace)
        val store = ProviderBalanceStore(persistence)
        assertTrue(keys().isEmpty()) // empty read cannot create a key.
        store.save(configuration())
        val ticket = store.beginManualRefresh()!!
        assertTrue(store.complete(ticket, BalanceResult.Available(BalanceMoney("123456.78".toBigDecimal(), "CNY"), 1234)))
        val onDisk = recordFile.readBytes().toString(Charsets.ISO_8859_1)
        listOf("private-account-label", "private-balance-secret", "private-balance", "123456.78", "/balance").forEach {
            assertFalse("plaintext must not be persisted", onDisk.contains(it))
        }
        assertEquals(1, keys().size)
        val restored = ProviderBalanceStore(EncryptedBalancePersistence(context, namespace))
        assertEquals("CNY 123456.78", restored.state.value.project().balanceText)
        assertFalse(restored.state.value.refreshing)
        store.clear()
        assertNull(ProviderBalanceStore(persistence).configurationForEditing())
    }

    fun testPreservedOptionsHeadersAndCachedFailureRemainEntirelyEncrypted() {
        val persistence = EncryptedBalancePersistence(context, namespace)
        val store = ProviderBalanceStore(persistence)
        val configured = configuration().copy(option = BalanceOption(true, BalanceOption.PRESET_NEW_API,
            accessToken = "private-access-override", userId = "private-user-id"),
            customHeaders = mapOf("New-Api-User" to "private-header-id"))
        store.save(configured)
        store.complete(store.beginManualRefresh()!!,
            BalanceResult.Available(BalanceMoney("987654.32".toBigDecimal(), "CNY"), 4321))
        store.complete(store.beginManualRefresh()!!, BalanceResult.Failed(BalanceFailure.AUTHORIZATION))
        val onDisk = recordFile.readBytes().toString(Charsets.ISO_8859_1)
        listOf("private-access-override", "private-user-id", "private-header-id", "987654.32", "AUTHORIZATION", "new_api").forEach {
            assertFalse("whole P05 record must remain encrypted", onDisk.contains(it))
        }
        val restored = ProviderBalanceStore(EncryptedBalancePersistence(context, namespace))
        assertEquals(configured, restored.configurationForEditing())
        assertEquals("CNY 987654.32", restored.state.value.project().balanceText)
        assertEquals(4321L, restored.state.value.project().checkedAtMillis)
        assertEquals(BalanceResult.Failed(BalanceFailure.AUTHORIZATION), restored.state.value.result)
        assertNull(restored.state.value.lastChange)
        assertEquals(1, keys().size)
    }

    fun testMissingKeyDoesNotCreateReplacementUntilExplicitSave() {
        val persistence = EncryptedBalancePersistence(context, namespace)
        ProviderBalanceStore(persistence).save(configuration())
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys().forEach { keyStore.deleteEntry(it) }
        val restored = ProviderBalanceStore(persistence)
        assertTrue(keys().isEmpty())
        assertFalse(restored.state.value.configured)
        assertEquals(BalanceResult.Failed(BalanceFailure.STORAGE), restored.state.value.result)
        restored.save(configuration())
        assertEquals(1, keys().size)
        assertTrue(ProviderBalanceStore(persistence).state.value.configured)
    }

    fun testTamperedEnvelopeCannotExposeBalanceOrEnableQuery() {
        val persistence = EncryptedBalancePersistence(context, namespace)
        ProviderBalanceStore(persistence).save(configuration())
        val bytes = recordFile.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        recordFile.writeBytes(bytes)
        val restored = ProviderBalanceStore(persistence)
        assertNull(restored.beginManualRefresh())
        assertNull(restored.state.value.project().balanceText)
        assertEquals(BalanceResult.Failed(BalanceFailure.STORAGE), restored.state.value.result)
    }
}

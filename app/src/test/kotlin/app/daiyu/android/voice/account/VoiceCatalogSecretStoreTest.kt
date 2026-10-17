package app.daiyu.android.voice.account

import android.app.Application
import android.content.Context
import android.util.Base64
import app.daiyu.android.voice.providers.DoubaoPersonalVoice
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class VoiceCatalogSecretStoreTest {
    @Test fun entireCredentialEnvelopeIsEncryptedRandomizedAndAccountBound() {
        val context = RuntimeEnvironment.getApplication()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val secrets = VoiceCatalogSecretStore(context) { key }
        val prefs = VoiceCatalogPreferences(secrets)
        val a = DoubaoPersonalVoice.accountFingerprint("key-a")
        val b = DoubaoPersonalVoice.accountFingerprint("key-b")
        prefs.clear(a); prefs.clear(b)
        prefs.save(a, "private-ak", "private-sk", "private-project")
        val raw = context.getSharedPreferences("voice_account_control_secret_v1", Context.MODE_PRIVATE)
        val first = raw.getString(a, null)!!
        assertFalse(raw.all.toString().contains("private-ak"))
        assertFalse(raw.all.toString().contains("private-sk"))
        assertFalse(raw.all.toString().contains("private-project"))
        val recreated = VoiceCatalogPreferences(VoiceCatalogSecretStore(context) { key })
        assertEquals("private-ak", recreated.load(a)!!.keyId)
        assertEquals("private-sk", recreated.load(a)!!.secret)
        assertEquals("private-project", recreated.load(a)!!.project)
        assertFalse(recreated.load(a).toString().contains("private-"))
        assertNull(recreated.load(b))
        prefs.save(a, "private-ak", "private-sk", "private-project")
        assertNotEquals(first, raw.getString(a, null))
        // Copying ciphertext to another account fails authentication instead of silently adopting it.
        raw.edit().putString(b, first).commit()
        assertThrows(IllegalStateException::class.java) { recreated.load(b) }
        prefs.clear(b); assertNull(recreated.load(b))
        raw.edit().putString(a, "broken").commit()
        assertThrows(IllegalStateException::class.java) { recreated.load(a) }
        // Explicit save is the recovery path; it does not silently happen on load.
        prefs.save(a, "replacement-ak", "replacement-sk", "  ")
        assertEquals("default", recreated.load(a)!!.project)
        prefs.clear(a); assertNull(recreated.load(a))
    }
    @Test fun changingOneAccountsCredentialsDoesNotEraseAnotherAccount() {
        val context = RuntimeEnvironment.getApplication()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val prefs = VoiceCatalogPreferences(VoiceCatalogSecretStore(context) { key })
        val a = DoubaoPersonalVoice.accountFingerprint("first")
        val b = DoubaoPersonalVoice.accountFingerprint("second")
        prefs.save(a, "a-ak", "a-sk", "a-project")
        prefs.save(b, "b-ak", "b-sk", "b-project")
        prefs.clear(a)
        assertNull(prefs.load(a)); assertEquals("b-sk", prefs.load(b)!!.secret)
        assertThrows(IllegalArgumentException::class.java) { prefs.save(b, "", "sk", "default") }
        assertEquals("b-sk", prefs.load(b)!!.secret)
        prefs.clear(b)
    }

    @Test fun plaintextEnvelopeByteBoundaryIsCheckedBeforeKeyAccessOrOverwriting() {
        val context = RuntimeEnvironment.getApplication()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        var keyAccesses = 0
        val secrets = VoiceCatalogSecretStore(context) { keyAccesses++; key }
        val account = DoubaoPersonalVoice.accountFingerprint("bounded-envelope")
        val raw = context.getSharedPreferences("voice_account_control_secret_v1", Context.MODE_PRIVATE)
        val limit = VoiceCatalogSecretStore.MAX_PLAINTEXT_BYTES
        assertEquals(4096, limit)
        fun envelope(project: String) = "{\"ak\":\"fixture-ak\",\"sk\":\"fixture-sk\",\"project\":\"$project\"}"
        val overhead = envelope("").toByteArray(Charsets.UTF_8).size
        for (size in listOf(limit - 1, limit)) {
            val token = envelope("a".repeat(size - overhead))
            secrets.save(account, token)
            assertEquals(size, token.toByteArray(Charsets.UTF_8).size)
            assertEquals(token, VoiceCatalogSecretStore(context) { key }.read(account))
        }
        // A character-count-only limit would incorrectly accept the over-budget UTF-8 case.
        val project = "é".repeat((limit - overhead) / 2) + "a".repeat((limit - overhead) % 2)
        val unicode = envelope(project)
        assertEquals(limit, unicode.toByteArray(Charsets.UTF_8).size)
        secrets.save(account, unicode)
        assertEquals(unicode, secrets.read(account))
        val before = raw.getString(account, null)
        keyAccesses = 0
        val oversizedUtf8 = envelope(project + "a")
        assertTrue(oversizedUtf8.length <= limit)
        assertEquals(limit + 1, oversizedUtf8.toByteArray(Charsets.UTF_8).size)
        for (token in listOf(envelope("a".repeat(limit - overhead + 1)), oversizedUtf8, "a".repeat(limit * 2))) {
            val error = assertThrows(IllegalArgumentException::class.java) { secrets.save(account, token) }
            assertFalse(error.message.orEmpty().contains("fixture-"))
            assertEquals(0, keyAccesses)
            assertEquals(before, raw.getString(account, null))
        }
        assertEquals(unicode, secrets.read(account))
        secrets.clear(account)
    }

    @Test fun encryptedAndBase64EnvelopeBoundariesAreCheckedBeforeDecryption() {
        val context = RuntimeEnvironment.getApplication()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        var keyAccesses = 0
        val secrets = VoiceCatalogSecretStore(context) { keyAccesses++; key }
        val account = DoubaoPersonalVoice.accountFingerprint("bounded-ciphertext")
        val raw = context.getSharedPreferences("voice_account_control_secret_v1", Context.MODE_PRIVATE)
        assertEquals(4124, VoiceCatalogSecretStore.MAX_ENCRYPTED_BYTES)
        assertEquals(5500, VoiceCatalogSecretStore.MAX_ENCODED_CHARS)
        secrets.save(account, "x")
        assertEquals(29, Base64.decode(raw.getString(account, null)!!, Base64.NO_WRAP).size)
        assertEquals("x", secrets.read(account))
        val token = "a".repeat(VoiceCatalogSecretStore.MAX_PLAINTEXT_BYTES)
        secrets.save(account, token)
        val valid = raw.getString(account, null)!!
        assertEquals(VoiceCatalogSecretStore.MAX_ENCODED_CHARS, valid.length)
        assertEquals(VoiceCatalogSecretStore.MAX_ENCRYPTED_BYTES, Base64.decode(valid, Base64.NO_WRAP).size)
        assertEquals(token, VoiceCatalogSecretStore(context) { key }.read(account))
        // One extra ciphertext byte still fits the Base64 character limit due to padding.
        val oversizedPayload = Base64.encodeToString(ByteArray(VoiceCatalogSecretStore.MAX_ENCRYPTED_BYTES + 1), Base64.NO_WRAP)
        assertEquals(VoiceCatalogSecretStore.MAX_ENCODED_CHARS, oversizedPayload.length)
        val invalid = listOf(
            valid + " ", // one character over the encoded limit, even with ignorable whitespace
            "a".repeat(VoiceCatalogSecretStore.MAX_ENCODED_CHARS + 4),
            oversizedPayload,
            Base64.encodeToString(ByteArray(28), Base64.NO_WRAP), // IV + tag, no plaintext
            Base64.encodeToString(ByteArray(12), Base64.NO_WRAP),
            ""
        )
        keyAccesses = 0
        for (encoded in invalid) {
            raw.edit().putString(account, encoded).commit()
            val error = assertThrows(IllegalStateException::class.java) { secrets.read(account) }
            assertEquals("控制面凭据不可读，请明确重新保存或清除", error.message)
            assertEquals(0, keyAccesses)
            assertEquals(encoded, raw.getString(account, null))
        }
        // Corrupt/oversized storage is never silently cleared; explicit save recovers it.
        secrets.save(account, "replacement")
        assertEquals("replacement", secrets.read(account))
        secrets.clear(account)
    }
}

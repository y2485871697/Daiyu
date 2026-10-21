package app.daiyu.android.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

class BackupPackageSpoolTest {
    @get:Rule val temp = TemporaryFolder()
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun payload(bytes: ByteArray = byteArrayOf(1, 2, 3)) = JSONObject()
        .put("text_backup", "原文😀\n\"\\\t".repeat(1100))
        .put("extra", JSONObject().put("null", JSONObject.NULL).put("boolean", true).put("number", 9L))
        .put("blobs", JSONArray().put(JSONObject().put("sha256", digest(bytes)).put("bytes", bytes.size)
            .put("data", Base64.getEncoder().encodeToString(bytes))))
    private fun envelope(payload: String, version: Long = 6) = JSONObject().put("format", "daiyu-neutral-complete")
        .put("version", version).put("sha256", digest(payload.toByteArray())).put("payload", payload).toString()
    private fun rejected(text: String) {
        BackupPackageSpool(temp.root).use { spool ->
            try { spool.read(text.byteInputStream()); fail("accepted malformed package") }
            catch (_: IllegalArgumentException) { }
            catch (_: IllegalStateException) { }
            catch (_: IOException) { }
        }
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }
    @Test fun streamedReadPreservesMetadataAndOriginalAndDoesNotCloseCallerStream() {
        var closed = false
        val bytes = ByteArray(32001) { it.toByte() }
        val source = object : ByteArrayInputStream(envelope(payload(bytes).toString()).toByteArray()) {
            override fun close() { closed = true; super.close() }
            override fun read(buffer: ByteArray, off: Int, len: Int) = super.read(buffer, off, minOf(len, 7))
        }
        BackupPackageSpool(temp.root).use { spool ->
            val parsed = spool.read(source)
            assertEquals(6L, parsed.version)
            assertEquals(payload(bytes).getString("text_backup"), parsed.payload.getString("text_backup"))
            assertFalse(parsed.payload.getJSONArray("blobs").getJSONObject(0).has("data"))
            assertArrayEquals(bytes, spool.bytes.getValue(digest(bytes)))
            assertFalse(closed)
        }
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }
    @Test fun streamedWriteUsesV6EnvelopeHashAndIsReadableByExistingJsonParser() {
        val bytes = ByteArray(32771) { (it % 137).toByte() }
        BackupPackageSpool(temp.root).use { spool ->
            spool.include(digest(bytes), bytes)
            val metadata = payload(bytes).also { it.getJSONArray("blobs").getJSONObject(0).remove("data") }
            val result = spool.write(6, metadata)
            val envelope = JSONObject(result.readText())
            val raw = envelope.getString("payload")
            assertEquals(digest(raw.toByteArray()), envelope.getString("sha256"))
            assertEquals(payload(bytes).getString("text_backup"), JSONObject(raw).getString("text_backup"))
            assertArrayEquals(bytes, Base64.getDecoder().decode(JSONObject(raw).getJSONArray("blobs").getJSONObject(0).getString("data")))
            BackupPackageSpool(temp.root).use { other ->
                result.inputStream().use { other.read(it) }
                assertArrayEquals(bytes, other.bytes.getValue(digest(bytes)))
            }
        }
    }
    @Test fun everyLegacyVersionRetainsTheActualPayloadDigest() {
        for (version in 1L..6L) BackupPackageSpool(temp.root).use { spool ->
            val raw = payload().toString(); val parsed = spool.read(envelope(raw, version).byteInputStream())
            assertEquals(version, parsed.version); assertEquals(digest(raw.toByteArray()), parsed.id)
        }
    }
    @Test fun emptyOriginalAndMissingPaddingAreAcceptedWithVerifiedSizeAndDigest() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(7), byteArrayOf(7, 9))) {
            val value = payload(bytes)
            val row = value.getJSONArray("blobs").getJSONObject(0)
            row.put("data", row.getString("data").trimEnd('='))
            BackupPackageSpool(temp.root).use { spool ->
                spool.read(envelope(value.toString()).byteInputStream())
                assertArrayEquals(bytes, spool.bytes.getValue(digest(bytes)))
            }
        }
    }
    @Test fun escapedUnicodePayloadAndReorderedBlobFieldsDoNotChangeContent() {
        val raw = payload().toString().replace("原文", "\\u539f\\u6587")
        BackupPackageSpool(temp.root).use { spool ->
            val parsed = spool.read(envelope(raw).byteInputStream())
            assertEquals(payload().getString("text_backup"), parsed.payload.getString("text_backup"))
        }
    }
    @Test fun wrongDigestSizeDuplicateHashAndUnknownBlobFieldsAreRejected() {
        rejected(envelope(payload().toString()).replaceFirst("sha256", "wrong"))
        for (field in listOf("sha256", "bytes", "decoded_sha256", "path")) {
            val value = payload(); val row = value.getJSONArray("blobs").getJSONObject(0)
            row.put(field, if (field == "bytes") 4 else "bad")
            rejected(envelope(value.toString()))
        }
        val value = payload(); value.getJSONArray("blobs").put(value.getJSONArray("blobs").getJSONObject(0))
        rejected(envelope(value.toString()))
    }
    @Test fun duplicateJsonKeysTrailingInputAndIncorrectPayloadHashAreRejected() {
        val value = payload().toString()
        rejected(envelope(value) + "{}")
        rejected(envelope(value).replaceFirst("{", "{\"version\":5,"))
        rejected(envelope(value).replace(digest(value.toByteArray()), "0".repeat(64)))
        rejected(envelope("{\"blobs\":[],\"blobs\":[]}"))
    }
    @Test fun malformedBase64AndBytesAfterPaddingNeverBecomeOriginals() {
        for (data in listOf("AQID!", "AQ==eA==", "!", "A", "AQID\\n")) {
            val value = payload(); value.getJSONArray("blobs").getJSONObject(0).put("data", data)
            rejected(envelope(value.toString()))
        }
    }
    @Test fun malformedUtf8AndUnterminatedJsonAreRejectedAndCleaned() {
        BackupPackageSpool(temp.root).use { spool ->
            try { spool.read(byteArrayOf(123, 34, -1, 34, 58, 49, 125).inputStream()); fail("UTF8 accepted") }
            catch (_: java.nio.charset.CharacterCodingException) { }
        }
        rejected(envelope(payload().toString()).dropLast(3))
    }
    @Test fun nonAsciiUnicodeEscapeDigitsAreRejectedInBothLayers() {
        rejected(envelope("{\"blobs\":[],\"text\":\"\\u００６１\"}"))
        rejected(envelope(payload().toString()).replaceFirst("daiyu", "\\u００６４aiyu"))
    }
    @Test fun lazyOriginalMapDoesNotRetainReturnedArraysAndCannotOutliveItsOwner() {
        val bytes = ByteArray(8193) { it.toByte() }; val hash = digest(bytes)
        val spool = BackupPackageSpool(temp.root)
        spool.include(hash, bytes); spool.include(hash, bytes.copyOf())
        assertNotSame(spool.bytes.getValue(hash), spool.bytes.getValue(hash))
        assertEquals(setOf(hash), spool.bytes.keys)
        spool.close()
        try { spool.bytes.getValue(hash); fail("closed owner read") } catch (_: IllegalStateException) { }
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }
    @Test fun intermittentZeroLengthReadsStillConsumeTheCompleteEnvelope() {
        val source = object : ByteArrayInputStream(envelope(payload().toString()).toByteArray()) {
            private var calls = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                if (++calls % 3 == 0 && len > 0) 0 else super.read(b, off, minOf(len, 17))
        }
        BackupPackageSpool(temp.root).use { spool ->
            val parsed = spool.read(source)
            assertEquals(payload().getString("text_backup"), parsed.payload.getString("text_backup"))
            assertArrayEquals(byteArrayOf(1, 2, 3), spool.bytes.getValue(digest(byteArrayOf(1, 2, 3))))
        }
    }
    @Test fun sourceReadFailureIsPreservedWithoutPublishingPartialOriginal() {
        val failure = IOException("bounded-test-failure")
        BackupPackageSpool(temp.root).use { spool ->
            try { spool.read(object : java.io.InputStream() { override fun read(): Int = throw failure }); fail("failure lost") }
            catch (actual: IOException) { assertSame(failure, actual) }
            assertTrue(spool.bytes.isEmpty())
        }
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }
}

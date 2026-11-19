package app.daiyu.android.platform

import org.junit.Assert.*
import org.junit.Test

class DocumentReadPolicyTest {
    @Test fun genericOrMissingMimeUsesOnlyProviderTextNames() {
        for (mime in listOf(null, "", "application/octet-stream", " Application/Octet-Stream ; charset=UTF-8 ")) {
            assertTrue(DocumentReadPolicy.needsName(mime))
            for (name in listOf("scroll-flicker（1）.log", "资料.TXT")) assertTrue(DocumentReadPolicy.supports(mime, name))
            for (name in listOf(null, "document", "file.log.exe", "file.pdf", "file.xlsx")) assertFalse(DocumentReadPolicy.supports(mime, name))
        }
    }
    @Test fun explicitNonTextMimeNeverFallsBackToExtension() {
        for (mime in listOf("application/pdf", "image/png", "application/zip", "application/unknown")) {
            assertFalse(DocumentReadPolicy.needsName(mime))
            assertFalse(DocumentReadPolicy.supports(mime, "file.log"))
        }
        for (mime in listOf("text/plain", "Text/Plain; charset=UTF-8", "application/json", "application/xml"))
            assertTrue(DocumentReadPolicy.supports(mime, null))
    }
    @Test fun percentEncodedUtf8MustBeCompleteAndIsNeverRewritten() {
        val original = "content://fixture/document/scroll-flicker%EF%BC%881%EF%BC%89.log"
        assertTrue(DocumentReadPolicy.validUriEncoding(original))
        assertTrue(DocumentReadPolicy.validUriEncoding("content://fixture/a+b%20c%2F%E4%B8%AD.txt"))
        for (suffix in listOf("%EF%89", "%", "%2", "%GG", "%FF", "%C0%AF"))
            assertFalse(DocumentReadPolicy.validUriEncoding("content://fixture/$suffix"))
    }
    @Test fun genericTextFallbackRejectsKnownBinaryHeaders() {
        for (prefix in listOf("%PDF-1.7", "PK\u0003\u0004", "GIF89a", "SQLite format 3")) {
            val bytes = prefix.toByteArray(); assertTrue(DocumentReadPolicy.binaryHeader(bytes, bytes.size))
        }
        val text = "中文日志😀\n".toByteArray()
        assertFalse(DocumentReadPolicy.binaryHeader(text, text.size))
        assertFalse(DocumentReadPolicy.binaryHeader(byteArrayOf(0x25, 0x50), 2))
    }
}

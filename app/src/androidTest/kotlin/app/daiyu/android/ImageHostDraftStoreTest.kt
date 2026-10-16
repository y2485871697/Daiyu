package app.daiyu.android

import android.test.InstrumentationTestCase
import app.daiyu.android.media.ImageAttachment
import app.daiyu.android.media.ImageAttachmentOwner
import java.util.UUID

/** Serialized identity/reference/fence contract; no image I/O, network or device permissions. */
@Suppress("DEPRECATION")
class ImageHostDraftStoreTest : InstrumentationTestCase() {
    fun testFreshStoreRestoresUnresolvedOwnerRevisionAndExactReferences() {
        val preferences = instrumentation.targetContext.getSharedPreferences("image-host-store-${UUID.randomUUID()}", 0)
        try {
            val owner = ImageAttachmentOwner("conversation", "original-draft")
            val image = ImageAttachment(owner, "image", "image/png", 42, "hash", 3, 2)
            val drafts = listOf(ImageHostDraftStore.Draft(null, owner, 17, listOf(image), true),
                ImageHostDraftStore.Draft("other", ImageAttachmentOwner("other", "other-draft"), 3, emptyList(), false))
            assertTrue(ImageHostDraftStore(preferences).write(drafts))
            val restored = ImageHostDraftStore(preferences).read()
            assertEquals(drafts, restored)
            assertTrue(restored.first().fenced)
            assertEquals("original-draft", restored.first().owner.draftId)
            assertEquals(17L, restored.first().revision)
            assertEquals(image, restored.first().images.single())
        } finally { preferences.edit().clear().commit() }
    }
}

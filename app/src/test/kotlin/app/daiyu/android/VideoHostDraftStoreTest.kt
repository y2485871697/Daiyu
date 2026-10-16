package app.daiyu.android

import android.content.Context
import app.daiyu.android.media.video.VideoAttachment
import app.daiyu.android.media.video.VideoAttachmentOwner
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class VideoHostDraftStoreTest {
    private val owner = VideoAttachmentOwner("new-conversation-id", "private-draft-id")
    private fun video() = VideoAttachment(owner, UUID.randomUUID().toString(), "video/mp4",
        123456L, "b".repeat(64), 3210, 640, 480)
    private fun prefs() = RuntimeEnvironment.getApplication().getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE)

    @Test fun exactNullSlotAndFullOriginalReferenceRoundTripWithoutSourceAuthority() {
        val preferences = prefs()
        val store = VideoHostDraftStore(preferences)
        val value = VideoHostDraftStore.Draft(null, owner, 1234567890123L, listOf(video()), true)
        assertTrue(store.write(listOf(value)))
        assertEquals(listOf(value), VideoHostDraftStore(preferences).read())
        val raw = checkNotNull(preferences.getString(VideoHostDraftStore.KEY, null))
        val saved = JSONObject(raw).getJSONArray("drafts").getJSONObject(0)
        assertTrue(saved.has("slot"))
        assertTrue(saved.isNull("slot"))
        for (forbidden in listOf("content://", "token", "permission", "preview", "password", "text")) {
            assertFalse(raw.contains(forbidden))
        }
    }

    @Test fun emptyMissingDuplicateAndMismatchedSlotsFailClosed() {
        val preferences = prefs(); val store = VideoHostDraftStore(preferences)
        val draft = VideoHostDraftStore.Draft(null, owner, 7, emptyList(), true)
        assertTrue(store.write(listOf(draft)))
        val original = checkNotNull(preferences.getString(VideoHostDraftStore.KEY, null))
        for (mutation in listOf<(JSONObject) -> Unit>(
            { it.getJSONArray("drafts").getJSONObject(0).put("slot", "") },
            { it.getJSONArray("drafts").getJSONObject(0).remove("slot") },
            { it.getJSONArray("drafts").getJSONObject(0).put("slot", "other") },
            { it.getJSONArray("drafts").put(it.getJSONArray("drafts").getJSONObject(0)) },
        )) {
            val root = JSONObject(original); mutation(root)
            preferences.edit().putString(VideoHostDraftStore.KEY, root.toString()).commit()
            try { store.read(); fail("Corrupt slots must not become a fresh owner") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun failedWritesDoNotReplaceGoodJournalAndMetadataIsValidated() {
        val preferences = prefs(); val store = VideoHostDraftStore(preferences)
        val draft = VideoHostDraftStore.Draft(null, owner, 7, listOf(video()), true)
        assertTrue(store.write(listOf(draft)))
        for (bad in listOf(
            draft.copy(revision = -1),
            draft.copy(videos = listOf(video().copy(owner = owner.copy(draftId = "different")))),
            draft.copy(videos = listOf(video().copy(bytes = Long.MAX_VALUE))),
            draft.copy(videos = listOf(video().copy(mime = "image/jpeg"))),
            draft.copy(videos = listOf(video().copy(id = "../outside"))),
            draft.copy(videos = List(5) { video() }),
        )) assertFalse(store.write(listOf(bad)))
        assertEquals(listOf(draft), store.read())
    }

    @Test fun deletionQueueIsAtomicDurableOwnerScopedAndCannotContainReadyReferences() {
        val preferences = prefs(); val store = VideoHostDraftStore(preferences)
        val original = video()
        val waiting = video()
        val draft = VideoHostDraftStore.Draft(null, owner, 9, listOf(original), true,
            pendingDeletes = listOf(waiting))
        assertTrue(store.write(listOf(draft)))
        assertEquals(listOf(draft), VideoHostDraftStore(preferences).read())
        assertFalse(store.write(listOf(draft.copy(pendingDeletes = listOf(original)))))
        assertFalse(store.write(listOf(draft.copy(pendingDeletes = listOf(waiting, waiting)))))
        val foreign = waiting.copy(owner = owner.copy(draftId = "other-owner"))
        assertFalse(store.write(listOf(draft.copy(pendingDeletes = listOf(foreign)))))
        assertEquals(listOf(draft), store.read())
        // Compatibility is conservative: absent deletion metadata does not invent acceptance.
        val root = JSONObject(checkNotNull(preferences.getString(VideoHostDraftStore.KEY, null)))
        root.getJSONArray("drafts").getJSONObject(0).remove("pendingDeletes")
        preferences.edit().putString(VideoHostDraftStore.KEY, root.toString()).commit()
        assertEquals(listOf(draft.copy(pendingDeletes = emptyList())), store.read())
    }

    @Test fun handoffAndReceiptListsAreDefensiveAndEveryIdentityFieldMatches() {
        val source = mutableListOf(video())
        val request = VideoDraftRequest(null, owner, 4, "body", source)
        val receipt = VideoDraftReceipt(null, owner, 4, source, VideoDraftOutcome.ACCEPTED, owner.conversationId)
        source.clear()
        assertEquals(1, request.videos.size)
        assertEquals(request.videos, receipt.videos)
        assertTrue(videoReceiptMatches(request, receipt))
        try { (request.videos as MutableList<*>).clear(); fail("mutable handoff") }
        catch (_: UnsupportedOperationException) { }
        val changed = request.videos.single().copy(durationMs = 3211)
        assertFalse(videoReceiptMatches(request,
            VideoDraftReceipt(null, owner, 4, listOf(changed), VideoDraftOutcome.UNKNOWN)))
        assertFalse(videoReceiptMatches(request,
            VideoDraftReceipt("", owner, 4, request.videos, VideoDraftOutcome.UNKNOWN)))
        assertFalse(videoReceiptMatches(request,
            VideoDraftReceipt(null, owner.copy(draftId = "other"), 4, request.videos, VideoDraftOutcome.UNKNOWN)))
    }
}

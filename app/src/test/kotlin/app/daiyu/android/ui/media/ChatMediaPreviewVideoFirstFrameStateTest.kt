package app.daiyu.android.ui.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMediaPreviewVideoFirstFrameStateTest {
    @Test fun renderingBeforeSingleTextureDeliversExactlyOnce() {
        val state = ChatMediaPreviewVideoFirstFrameState()
        state.reset(1)
        assertFalse(state.renderingStarted(1))
        assertTrue(state.textureUpdated(1))
        assertFalse(state.textureUpdated(1))
        assertFalse(state.renderingStarted(1))
    }

    @Test fun singleTextureBeforeRenderingDeliversExactlyOnce() {
        val state = ChatMediaPreviewVideoFirstFrameState()
        state.reset(1)
        assertFalse(state.textureUpdated(1))
        assertTrue(state.renderingStarted(1))
        assertFalse(state.textureUpdated(1))
    }

    @Test fun sourceResetRejectsOldCallbacksAndRequiresBothNewEvents() {
        val state = ChatMediaPreviewVideoFirstFrameState()
        state.reset(1)
        assertFalse(state.textureUpdated(1))
        state.reset(2)
        assertFalse(state.renderingStarted(1))
        assertFalse(state.textureUpdated(1))
        assertFalse(state.renderingStarted(2))
        assertTrue(state.textureUpdated(2))
    }
}

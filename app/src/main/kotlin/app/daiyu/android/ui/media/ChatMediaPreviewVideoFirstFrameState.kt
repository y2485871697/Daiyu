package app.daiyu.android.ui.media

/** Both native events belong to the current source; their arrival order is not prescribed. */
internal class ChatMediaPreviewVideoFirstFrameState {
    private var generation = -1L
    private var rendering = false
    private var texture = false
    private var delivered = false

    fun reset(sourceGeneration: Long) {
        generation = sourceGeneration
        rendering = false
        texture = false
        delivered = false
    }

    fun renderingStarted(sourceGeneration: Long): Boolean {
        if (sourceGeneration != generation) return false
        rendering = true
        return ready()
    }

    fun textureUpdated(sourceGeneration: Long): Boolean {
        if (sourceGeneration != generation) return false
        texture = true
        return ready()
    }

    private fun ready(): Boolean {
        if (!rendering || !texture || delivered) return false
        delivered = true
        return true
    }
}

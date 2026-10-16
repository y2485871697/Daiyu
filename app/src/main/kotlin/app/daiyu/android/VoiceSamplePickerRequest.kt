package app.daiyu.android

/** One page visit, one outstanding GetContent launch. UI-thread only; never grants upload/audio consent. */
internal class VoiceSamplePickerRequest<T : Any> {
    class Selection<T>(val configuration: VoiceConfiguration, val name: String, val value: T)
    private class Request(val configuration: VoiceConfiguration, val name: String) {
        var valid = true
    }
    private var active = true
    private var launched: Request? = null
    private var returned: Pair<Request, T>? = null
    val pending: Boolean get() = launched != null || returned != null

    fun begin(configuration: VoiceConfiguration, name: String): Boolean {
        if (!active || pending || configuration.engine != "MIMO") return false
        launched = Request(configuration, name)
        return true
    }

    /** Activity results may arrive at ON_START, before the host's voice foreground gate opens. */
    fun deliver(value: T?) {
        val request = launched ?: return
        launched = null
        if (active && request.valid && value != null) returned = request to value
    }

    fun take(resumed: Boolean, pageCurrent: Boolean, configuration: VoiceConfiguration?): Selection<T>? {
        if (!resumed) return null
        val (request, value) = returned ?: return null
        returned = null // Both acceptance and rejection consume this result exactly once.
        if (!active || !pageCurrent || !request.valid || configuration != request.configuration) return null
        return Selection(request.configuration, request.name, value)
    }

    fun invalidate() {
        // Keep the outstanding slot occupied until its callback. An old picker must not borrow a new request.
        launched?.valid = false
        returned = null
    }

    fun close() {
        active = false
        invalidate()
    }
}

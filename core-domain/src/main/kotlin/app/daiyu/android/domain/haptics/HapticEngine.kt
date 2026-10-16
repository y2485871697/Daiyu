package app.daiyu.android.domain.haptics

/**
 * Serialized policy. The Android adapter calls it on the main thread; tests need no Android clock.
 * Owners are capabilities: a disposed token can never affect its replacement or another screen.
 */
class HapticEngine(private val clock: HapticClock) {
    class Owner internal constructor()
    private data class Host(
        val conversation: String,
        val port: HapticPort,
        var resumed: Boolean,
        var visible: Boolean,
        var enabled: Boolean,
    )
    private data class Pending(val owner: Owner, val reasoning: Boolean, val background: Boolean)
    private val hosts = linkedMapOf<Owner, Host>()
    private val tools = LinkedHashSet<String>()
    private val pending = ArrayDeque<Pending>()
    private var currentConversation: String? = null
    private var lastTick = 0L
    private var lastSelection = 0L
    private var nextTick: Long? = null
    // Ports may be shared by rebound hosts; only the host that last played owns cancellation.
    private var activeHost: Host? = null
    var settings = HapticSettings()
        private set
    var foreground = true
        private set
    var lastDelivery: HapticDelivery? = null
        private set

    fun attach(conversation: String, port: HapticPort, resumed: Boolean = false,
               visible: Boolean = true, enabled: Boolean = true): Owner {
        require(conversation.isNotBlank())
        val owner = Owner()
        hosts[owner] = Host(conversation, port, resumed, visible, enabled)
        if (resumed && enabled) claim(conversation)
        return owner
    }

    fun lifecycle(owner: Owner, resumed: Boolean, visible: Boolean, enabled: Boolean = true) {
        val host = hosts[owner] ?: return
        host.resumed = resumed
        host.visible = visible
        host.enabled = enabled
        if (!enabled && activeHost === host) cancelActive()
        if (resumed && enabled) claim(host.conversation)
        prune()
    }

    fun detach(owner: Owner) {
        val host = hosts.remove(owner) ?: return
        pending.removeAll { it.owner === owner }
        if (activeHost === host) cancelActive()
        if (pending.isEmpty()) nextTick = null
    }

    private fun claim(conversation: String) {
        if (currentConversation != conversation) {
            pending.clear()
            nextTick = null
            cancelActive()
            currentConversation = conversation
        }
    }

    fun updateSettings(value: HapticSettings) {
        val previous = settings
        settings = value
        if ((previous.touch && !value.touch) || (previous.generationOn && !value.generationOn) ||
            (!foreground && previous.backgroundOn && !value.backgroundOn) ||
            (!foreground && previous.backgroundReasoningOn && !value.backgroundReasoningOn)) cancelActive()
        prune()
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) cancelActive()
        prune()
    }

    private fun allowed(host: Host, reasoning: Boolean): Boolean = host.enabled &&
        host.conversation == currentConversation && settings.generationOn &&
        (foreground || if (reasoning) settings.backgroundReasoningOn else settings.backgroundOn)

    private fun visible(host: Host) = foreground && host.resumed && host.visible && host.enabled
    private fun backgroundVisible(conversation: String) = hosts.values.any {
        it.conversation == conversation && visible(it)
    }

    fun touch(owner: Owner, kind: HapticKind): HapticDelivery? {
        val host = hosts[owner] ?: return null
        if (!foreground || !host.resumed || !host.enabled || !settings.touch ||
            host.conversation != currentConversation) return null
        return play(host, HapticRequest(kind, settings.intensity))
    }

    fun agentAction(owner: Owner, action: HapticAgentAction): HapticDelivery? {
        val host = hosts[owner] ?: return null
        if (!foreground || !host.resumed || !host.enabled || !settings.touch ||
            host.conversation != currentConversation) return null
        return play(host, HapticRequest(HapticKind.CLICK, settings.intensity, agentAction = action))
    }

    /** Preview bypasses only the app switch, never foreground, lifecycle or system settings. */
    fun preview(owner: Owner, intensity: HapticIntensity): HapticDelivery? {
        val host = hosts[owner] ?: return null
        if (!foreground || !host.resumed || !host.enabled || host.conversation != currentConversation) return null
        return play(host, HapticRequest(HapticKind.CLICK, intensity, preview = true))
    }

    fun selectionStart(owner: Owner): HapticDelivery? {
        val now = clock.nowMillis()
        if (now - lastSelection < 80L) return null
        lastSelection = now
        return touch(owner, HapticKind.LONG_PRESS)
    }

    fun visibleAdvance(owner: Owner): HapticDelivery? {
        val host = hosts[owner] ?: return null
        if (!visible(host) || !allowed(host, false)) return null
        val now = clock.nowMillis()
        if (now - lastTick < 32L) return null
        lastTick = now
        return generation(host)
    }

    /** Call once per newly arrived grapheme batch, not once per UI rebind. */
    fun backgroundOutput(owner: Owner, graphemes: Int, reasoning: Boolean = false) {
        val host = hosts[owner] ?: return
        if (graphemes <= 0 || !allowed(host, reasoning) || backgroundVisible(host.conversation)) return
        val room = 36 - pending.count { it.background }
        repeat(graphemes.coerceAtMost(room.coerceAtLeast(0))) {
            pending.addLast(Pending(owner, reasoning, true))
        }
        schedule(background = true)
    }

    /** Tools and completion markers are not lost in the 32ms text throttle. */
    fun toolAppeared(owner: Owner, id: String) {
        val host = hosts[owner] ?: return
        if (id.isBlank() || !allowed(host, false)) return
        val key = "${host.conversation.length}:${host.conversation}:$id"
        if (!tools.add(key)) return
        while (tools.size > 256) tools.remove(tools.first())
        pending.addLast(Pending(owner, false, false))
        schedule(background = false)
    }

    fun reasoningCompleted(owner: Owner, id: String) {
        if (id.isNotBlank()) toolAppeared(owner, "$id-completed")
    }

    private fun schedule(background: Boolean) {
        if (pending.isEmpty() || nextTick != null) return
        val now = clock.nowMillis()
        nextTick = if (background) now + 32L else maxOf(now, lastTick + 32L)
    }

    private fun prune() {
        pending.removeAll { item ->
            val host = hosts[item.owner]
            host == null || !allowed(host, item.reasoning) ||
                (item.background && backgroundVisible(host.conversation))
        }
        if (pending.isEmpty()) nextTick = null
    }

    /** Adapter schedules exactly one callback; every callback rechecks all gates. No late replay. */
    fun drain() {
        prune()
        val due = nextTick ?: return
        val now = clock.nowMillis()
        if (now < due) return
        nextTick = null
        if (now - lastTick < 32L) {
            nextTick = lastTick + 32L
            return
        }
        val item = pending.removeFirstOrNull() ?: return
        val host = hosts[item.owner] ?: return
        lastTick = now
        generation(host)
        if (pending.isNotEmpty()) nextTick = now + 32L
    }

    fun nextDueMillis(): Long? = nextTick
    fun pendingCount(): Int = pending.size
    private fun generation(host: Host) = play(host,
        HapticRequest(HapticKind.TICK, settings.intensity, generation = true))
    private fun play(host: Host, request: HapticRequest): HapticDelivery {
        val result = try { host.port.play(request) } catch (_: Exception) { HapticDelivery.FAILED }
        lastDelivery = result
        if (result == HapticDelivery.PLAYED) activeHost = host
        return result
    }
    private fun cancelActive() {
        val port = activeHost?.port
        activeHost = null
        try { port?.cancel() } catch (_: Exception) { lastDelivery = HapticDelivery.FAILED }
    }
    fun close() {
        pending.clear()
        nextTick = null
        cancelActive()
        hosts.clear()
    }
}

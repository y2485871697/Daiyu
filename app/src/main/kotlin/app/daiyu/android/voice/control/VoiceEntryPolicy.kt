package app.daiyu.android.voice.control

enum class VoiceEntryMode(val wireValue: String) {
    DICTATION("dictation"), UNIVERSAL("universal"), DOUBAO_DUPLEX("doubao_duplex");
    companion object {
        /** Unknown history must not accidentally opt into any voice permission. */
        fun fromWireValue(value: String): VoiceEntryMode? = entries.firstOrNull { it.wireValue == value }
    }
}

data class VoiceEntrySnapshot(
    val conversationId: String?,
    val lifecycleKey: String,
    val enabledModes: Set<VoiceEntryMode>,
    val profileReady: Boolean,
    val foreground: Boolean,
    val questionWaiting: Boolean = false,
    val microphoneGranted: Boolean = false,
)

/** Availability is not consent. Dictation and duplex remain owned by their existing hosts. */
object VoiceEntryPolicy {
    fun modes(snapshot: VoiceEntrySnapshot): List<VoiceEntryMode> =
        VoiceEntryMode.entries.filter { it in snapshot.enabledModes }
    fun directMode(snapshot: VoiceEntrySnapshot, lastSelected: String = ""): VoiceEntryMode? {
        val available = modes(snapshot)
        return available.firstOrNull { it.wireValue == lastSelected } ?: available.singleOrNull()
    }
    fun canChoose(snapshot: VoiceEntrySnapshot) = modes(snapshot).size > 1
    fun startProblem(snapshot: VoiceEntrySnapshot): VoiceControlStopReason? = when {
        VoiceEntryMode.UNIVERSAL !in snapshot.enabledModes -> VoiceControlStopReason.DISABLED
        !snapshot.profileReady -> VoiceControlStopReason.CONFIGURATION_CHANGED
        !snapshot.foreground || snapshot.lifecycleKey.isBlank() || snapshot.conversationId.isNullOrBlank() -> VoiceControlStopReason.REVOKED
        snapshot.questionWaiting -> VoiceControlStopReason.QUESTION_WAITING
        else -> null // Permission is requested separately, with zero audio resources open.
    }
}

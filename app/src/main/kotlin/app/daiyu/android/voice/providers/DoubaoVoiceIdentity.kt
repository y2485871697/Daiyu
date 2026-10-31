package app.daiyu.android.voice.providers

/** Personal identities accepted by this application; provider slot IDs remain unchanged. */
object DoubaoVoiceIdentity {
    private val slot = Regex("S_[A-Za-z0-9_-]+")
    private val custom = Regex("Daiyu[0-9a-f]{32}")
    private val customShape = Regex("[A-Za-z][A-Za-z0-9_-]*[0-9a-fA-F]{32}")

    fun isSlot(id: String): Boolean = slot.matches(id)
    fun isCustom(id: String): Boolean = custom.matches(id)
    fun isPersonal(id: String): Boolean = isSlot(id) || isCustom(id)
    fun isSupportedSpeaker(id: String): Boolean = isPersonal(id) ||
        (!id.startsWith("Daiyu") && !id.startsWith("S_") && !customShape.matches(id))
}

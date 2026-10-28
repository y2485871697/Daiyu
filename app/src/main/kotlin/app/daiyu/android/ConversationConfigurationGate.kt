package app.daiyu.android

/** Controller-monitor owned; null identifies the standalone composer, not every conversation. */
internal class ConversationConfigurationGate {
    class Operation internal constructor(val owner: String?)
    private var operation: Operation? = null
    val occupied: Boolean get() = operation != null

    fun blocks(owner: String?, taskOwners: Set<String?>): Boolean = occupied || owner in taskOwners
    fun reserve(owner: String?, taskOwners: Set<String?>): Operation? {
        if (blocks(owner, taskOwners)) return null
        return Operation(owner).also { operation = it }
    }
    fun current(candidate: Operation): Boolean = operation === candidate
    fun release(candidate: Operation): Boolean {
        if (!current(candidate)) return false
        operation = null
        return true
    }
}

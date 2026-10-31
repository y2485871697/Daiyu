package app.daiyu.android.domain

/** Buffer only the ambiguous beginning of the first item. Once decided, overlap is fixed;
 * repetitions later in the answer are ordinary body content. */
internal class ContinuationText(private val prefix: String, private val bound: Int = 2048) {
    private var firstItem: String? = null
    private var acceptedOverlap: Int? = null
    private var pending: String = ""
    private var ended = false
    fun fork(): ContinuationText = ContinuationText(prefix, bound).also {
        it.firstItem = firstItem; it.acceptedOverlap = acceptedOverlap
        it.pending = pending; it.ended = ended
    }
    fun pendingBefore(itemId: String? = null): Pair<String, String>? =
        firstItem?.takeIf { !ended && acceptedOverlap == null && it != itemId }?.let { it to pending }

    fun suffix(itemId: String, cumulative: String, final: Boolean = false): String {
        if (prefix.isEmpty()) return cumulative
        if (firstItem == null && cumulative.isNotEmpty()) firstItem = itemId
        if (itemId != firstItem) return cumulative
        pending = cumulative
        val tail = prefix.takeLast(bound)
        if (acceptedOverlap == null) {
            val stillAmbiguous = !final && (1..tail.length).any { length ->
                length > cumulative.length && tail.takeLast(length).startsWith(cumulative)
            }
            if (stillAmbiguous) return ""
            acceptedOverlap = (minOf(tail.length, cumulative.length) downTo 1)
                .firstOrNull { cumulative.startsWith(tail.takeLast(it)) } ?: 0
        }
        if (final) ended = true
        return cumulative.drop(checkNotNull(acceptedOverlap))
    }
}

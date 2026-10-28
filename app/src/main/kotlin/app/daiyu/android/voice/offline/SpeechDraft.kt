package app.daiyu.android.voice.offline

/** Replace only the captured selection; an editor revision additionally fences edit-then-undo. */
internal class SpeechDraft(text: String, start: Int, end: Int) {
    private val before = text.take(minOf(start, end).coerceIn(0, text.length))
    private val after = text.drop(maxOf(start, end).coerceIn(0, text.length))
    var expectedText: String = text
        private set
    var cursor: Int = start
        private set

    data class Preview(val text: String, val cursor: Int, val start: Int, val end: Int) {
        fun originalOffset(offset: Int): Int = when {
            offset <= start -> offset
            offset >= cursor -> end + offset - cursor
            else -> start
        }.coerceIn(0, text.length - (cursor - start) + (end - start))
    }

    private val originalEnd = text.length - after.length
    fun preview(current: String, recognized: String): Preview? =
        if (current != expectedText) null
        else Preview(before + recognized + after, before.length + recognized.length, before.length, originalEnd)

    fun accept(current: String, recognized: String): String? {
        val next = preview(current, recognized) ?: return null
        expectedText = next.text
        cursor = next.cursor
        return expectedText
    }
}

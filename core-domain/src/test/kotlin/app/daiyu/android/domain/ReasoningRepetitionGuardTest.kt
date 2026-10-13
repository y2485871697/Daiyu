package app.daiyu.android.domain

import org.junit.Assert.*
import org.junit.Test

class ReasoningRepetitionGuardTest {
    @Test fun catchesPeriodicReasoningRegardlessOfChunkBoundaries() {
        val text = "OK.\nWrite.\nNow.\nWriting.\nDONE.\n".repeat(800)
        for (chunkSize in listOf(1, 7, 511, 2048, text.length)) {
            val guard = ReasoningRepetitionGuard()
            assertTrue("chunk=$chunkSize", text.chunked(chunkSize).any { guard.append(it) })
        }
    }

    @Test fun catchesNonPeriodicMixturesOfTheSameShortLines() {
        val guard = ReasoningRepetitionGuard()
        val phrases = listOf("OK.", "Write.", "Writing.", "Now.", "DONE.")
        val random = java.util.Random(17)
        val text = (1..3000).joinToString("\n") { phrases[random.nextInt(phrases.size)] }
        assertTrue(guard.append(text))
    }

    @Test fun permitsLongDistinctReasoningAndOccasionalRepeatedHeadings() {
        val guard = ReasoningRepetitionGuard()
        repeat(5000) { index ->
            assertFalse(guard.append("Check.\nStep $index: new evidence ${index * 31} changes the result.\n"))
        }
    }

    @Test fun permitsShortRepetitionsAndWhitespaceOnly() {
        assertFalse(ReasoningRepetitionGuard().append("OK.\n".repeat(100)))
        assertFalse(ReasoningRepetitionGuard().append(" \n\t".repeat(4000)))
    }

    @Test fun resetRepresentsRealProgressAndRejectionIsStickyOtherwise() {
        val guard = ReasoningRepetitionGuard()
        repeat(10) {
            assertFalse(guard.append("OK.\n".repeat(800)))
            guard.reset()
        }
        assertTrue(guard.append("OK.\n".repeat(2000)))
        assertTrue(guard.append("new words"))
        guard.reset()
        assertFalse(guard.append("new evidence"))
    }

    @Test fun normalPrefixDoesNotTurnTinyRepetitiveSuffixIntoDegeneration() {
        val prefix = (1..1000).joinToString(" ") { "evidence-$it" }
        assertFalse(ReasoningRepetitionGuard().append(prefix + "\n" + "0\n".repeat(128)))
    }

    @Test fun giantDeltaIsCheckedBeforeItsWholeContentsAreRetained() {
        assertTrue(ReasoningRepetitionGuard().append("Write. ".repeat(150000)))
    }
}

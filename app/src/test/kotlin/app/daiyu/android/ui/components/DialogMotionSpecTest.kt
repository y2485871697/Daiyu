package app.daiyu.android.ui.components

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yukonga.miuix.kmp.anim.folmeSpring

class DialogMotionSpecTest {
    @Test fun compactEntryMatchesAssistantSpringAtEverySample() {
        val actual = TargetBasedAnimation(dialogEnterSpec(false), Float.VectorConverter, 0f, 1f)
        val expected = TargetBasedAnimation(
            spring<Float>(dampingRatio = 0.88f, stiffness = 450f, visibilityThreshold = 0.0001f),
            Float.VectorConverter, 0f, 1f,
        )
        assertEquals(expected.durationNanos, actual.durationNanos)
        for (millis in listOf(0L, 16L, 80L, 160L, 300L, 500L)) {
            val nanos = millis * 1_000_000L
            assertEquals(expected.getValueFromNanos(nanos), actual.getValueFromNanos(nanos), 0.000001f)
            assertEquals(expected.getVelocityVectorFromNanos(nanos).value,
                actual.getVelocityVectorFromNanos(nanos).value, 0.000001f)
        }
        assertTrue(actual.getValueFromNanos(80_000_000L) in 0f..1f)
        assertEquals(1f, actual.getValueFromNanos(actual.durationNanos), 0.0001f)
    }

    @Test fun largeWindowEntryMatchesAssistantFolmeSpring() {
        val actual = TargetBasedAnimation(dialogEnterSpec(true), Float.VectorConverter, 0f, 1f)
        val expected = TargetBasedAnimation(folmeSpring<Float>(damping = 0.9f, response = 0.3f),
            Float.VectorConverter, 0f, 1f)
        assertEquals(expected.durationNanos, actual.durationNanos)
        for (millis in listOf(0L, 16L, 80L, 160L, 300L, 500L)) {
            val nanos = millis * 1_000_000L
            assertEquals(expected.getValueFromNanos(nanos), actual.getValueFromNanos(nanos), 0.000001f)
        }
    }
}

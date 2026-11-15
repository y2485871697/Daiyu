package app.daiyu.android.integration.tools

import org.junit.Assert.*
import org.junit.Test

class MediaTaskAdmissionTest {
    @Test fun committedInvalidationImmediatelyNotifiesTheOriginalInvocationAndCannotRevive() {
        var valid = true
        var changed: (() -> Unit)? = null
        var detached = false
        var pauses = 0
        val admission = MediaTaskAdmission({ valid }, { callback ->
            changed = callback
            AutoCloseable { detached = true }
        })
        val observation = admission.observePause { pauses++ }
        assertTrue(admission.admitted())
        valid = false; changed!!.invoke()
        assertFalse(admission.admitted()); assertEquals(1, pauses)
        valid = true
        assertFalse(admission.admitted())
        observation.close(); assertTrue(detached)
    }

    @Test fun invalidationBeforeAttachmentPausesTheLateRuntimeWithoutStartingIt() {
        val admission = MediaTaskAdmission({ false })
        var pauses = 0
        admission.observePause { pauses++ }.close()
        assertEquals(1, pauses)
        assertFalse(admission.admitted())
    }
}

package app.daiyu.android.gui

import android.test.InstrumentationTestCase
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Exercises Android's regex engine with synthetic WM text; no service, capture or activity. */
@Suppress("DEPRECATION")
class GuiVisualWindowParserAndroidTest : InstrumentationTestCase() {
    private fun window(id: String = "a17", index: Int = 0, type: String = "BASE_APPLICATION",
        frame: String = "[0,0][100,200]", flags: String = "HARDWARE_ACCELERATED") = """
  Window #$index Window{$id u0 private-fixture-title}:
    mDisplayId=0 rootTaskId=4
    mOwnerUid=10222 showForAllUsers=false package=external.app appop=NONE
    mAttrs={(0,0)(fillxfill) ty=$type
      fl=$flags
    Requested w=100 h=200
    mFullConfiguration={winConfig={ mDisplayRotation=ROTATION_0 }}
    Frames: display=[0,0][100,200] frame=$frame last=$frame
    mHasSurface=true
    isOnScreen=true
    isVisible=true
"""
    private fun focused() = "\n  mCurrentFocus=Window{a17 u0 private-fixture-title}\n"

    fun testLegacyHeaderReproducesAndroidPatternSyntaxFailure() {
        try {
            Pattern.compile("(?m)^  Window #\\d+ Window\\{([^\\s]+) u(\\d+) [^\\n]*}:")
            fail("Android must reproduce the legacy window-header syntax failure")
        } catch (_: PatternSyntaxException) {
            // The production parser below must initialize and parse independently of this fixture.
        }
    }

    fun testProductionParserInitializesAndReadsLiteralDelimitersOnAndroid() {
        val parsed = GuiVisualWindowParser.parse(window() + focused())
        assertNotNull(parsed)
        assertEquals("a17", parsed!!.identity)
        assertEquals("external.app", parsed.packageName)
        assertEquals(GuiBounds(0, 0, 100, 200), parsed.bounds)
        assertEquals(GuiBounds(0, 0, 100, 200), parsed.displayBounds)
        assertEquals(0, parsed.rotation)
        assertFalse(parsed.toString().contains("private-fixture-title"))
    }

    fun testOccludingBoundsStillConstrainVisualGestures() {
        val parsed = GuiVisualWindowParser.parse(
            window(id = "keyboard", type = "INPUT_METHOD", frame = "[0,140][100,200]") +
                window(index = 1) + focused())!!
        assertEquals(1, parsed.above.size)
        assertTrue(parsed.admits(GuiGesture(50, 80, 50, 80, 50)))
        assertFalse(parsed.admits(GuiGesture(50, 180, 50, 180, 50)))
        assertFalse(parsed.admits(GuiGesture(50, 80, 50, 180, 500)))
    }

    fun testSecureAmbiguousAndIncompleteWitnessesStayUnavailable() {
        assertNull(GuiVisualWindowParser.parse(window(flags = "SECURE HARDWARE_ACCELERATED") + focused()))
        assertNull(GuiVisualWindowParser.parse(window() + focused() + focused()))
        assertNull(GuiVisualWindowParser.parse(window()))
        assertNull(GuiVisualWindowParser.parse(window().replace("isVisible=true", "isVisible=false") + focused()))
        assertNull(GuiVisualWindowParser.parse("Permission Denial\n" + window() + focused()))
    }

    private object FailedParser {
        val value: GuiVisualWindow? = throw IllegalStateException("fixture initializer")
    }

    fun testFailedClassInitializationIsUnavailableOnFirstAndSubsequentReads() {
        val failures = mutableListOf<Class<*>>()
        repeat(2) {
            assertNull(AndroidGuiVisualWindows.readSafely {
                try { FailedParser.value } catch (failure: LinkageError) {
                    failures += failure.javaClass
                    throw failure
                }
            })
        }
        assertEquals(listOf(ExceptionInInitializerError::class.java, NoClassDefFoundError::class.java), failures)
        assertNull(AndroidGuiVisualWindows.readSafely { throw IllegalStateException("fixture reader") })
        val parsed = AndroidGuiVisualWindows.readSafely { GuiVisualWindowParser.parse(window() + focused()) }
        assertNotNull(parsed)
        assertEquals("a17", parsed!!.identity)
    }
}

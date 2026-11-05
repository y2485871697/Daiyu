package app.daiyu.android.gui

import org.junit.Assert.*
import org.junit.Test

class GuiVisualWindowParserTest {
    private fun window(id: String = "a17", pkg: String = "external.app", display: Int = 0,
        visible: Boolean = true, type: String = "BASE_APPLICATION", flags: String = "HARDWARE_ACCELERATED",
        frame: String = "[0,0][100,200]", index: Int = 0, user: Int = 0): String = """
  Window #$index Window{$id u$user private-title-must-not-be-retained}:
    mDisplayId=$display rootTaskId=4
    mOwnerUid=10222 showForAllUsers=false package=$pkg appop=NONE
    mAttrs={(0,0)(fillxfill) ty=$type
      fl=$flags
    Requested w=100 h=200
    mFullConfiguration={winConfig={ mDisplayRotation=ROTATION_0 }}
    Frames: display=[0,0][100,200] frame=$frame last=$frame
    mHasSurface=$visible
    isOnScreen=$visible
    isVisible=$visible
"""
    private fun focused(id: String = "a17", user: Int = 0) = "\n  mCurrentFocus=Window{$id u$user private-title}\n"

    @Test fun verifiedVerboseDumpShapeAcceptsPrefixedWindowTokensAndMultiLineFlags() {
        val text = window(id = "vendor.overlay:a3", type = "ACCESSIBILITY_OVERLAY", frame = "[50,50][50,50]") +
            window(id = "abc", index = 1) + focused("abc")
        val parsed = GuiVisualWindowParser.parse(text)!!
        assertEquals("abc", parsed.identity); assertTrue(parsed.above.isEmpty())
    }
    @Test fun independentWmWitnessNeedsNoAccessibilityIdsOrTitles() {
        val parsed = GuiVisualWindowParser.parse(window() + focused())!!
        assertEquals("external.app", parsed.packageName)
        assertEquals("a17", parsed.identity)
        assertEquals(GuiBounds(0, 0, 100, 200), parsed.bounds)
        assertEquals(0, parsed.rotation)
        assertFalse(parsed.toString().contains("private-title"))
        assertTrue(parsed.admits(GuiGesture(30, 40, 30, 40, 50)))
    }
    @Test fun missingOrAmbiguousFocusAndWindowIdentityFailClosed() {
        assertNull(GuiVisualWindowParser.parse(window()))
        assertNull(GuiVisualWindowParser.parse(window() + focused() + focused()))
        assertNull(GuiVisualWindowParser.parse(window() + focused("missing")))
        assertNull(GuiVisualWindowParser.parse(window() + window(index = 1) + focused()))
    }
    @Test fun hiddenOffDisplaySystemAndOtherUserFocusedWindowsAreNotApplications() {
        assertNull(GuiVisualWindowParser.parse(window(visible = false) + focused()))
        assertNull(GuiVisualWindowParser.parse(window(display = 1) + focused()))
        assertNull(GuiVisualWindowParser.parse(window(type = "NOTIFICATION_SHADE") + focused()))
        assertNull(GuiVisualWindowParser.parse(window(user = 10) + focused(user = 10)))
    }
    @Test fun secureOrUnparsedNumericFlagsNeverEnterTheVisualBackend() {
        assertNull(GuiVisualWindowParser.parse(window(flags = "SECURE HARDWARE_ACCELERATED") + focused()))
        assertNull(GuiVisualWindowParser.parse(window(flags = "0x2000") + focused()))
        assertNull(GuiVisualWindowParser.parse(window().replace("      fl=", "      unknown=") + focused()))
    }
    @Test fun keyboardAndSystemBarsRetainTheirOccludingBounds() {
        val text = window(id = "keyboard", type = "INPUT_METHOD", frame = "[0,140][100,200]") +
            window(index = 1) + focused()
        val parsed = GuiVisualWindowParser.parse(text)!!
        assertEquals(1, parsed.above.size)
        assertTrue(parsed.admits(GuiGesture(50, 80, 50, 80, 50)))
        assertFalse(parsed.admits(GuiGesture(50, 180, 50, 180, 50)))
        assertFalse(parsed.admits(GuiGesture(50, 80, 50, 180, 500)))
        assertFalse(parsed.admits(GuiGesture(100, 80, 100, 80, 50)))
    }
    @Test fun invisibleOverlaysDoNotBecomeVisualGestureTargets() {
        val parsed = GuiVisualWindowParser.parse(window(id = "overlay", visible = false, type = "APPLICATION_OVERLAY") +
            window(index = 1) + focused())!!
        assertTrue(parsed.above.isEmpty())
    }
    @Test fun geometryRotationAndVisibleWindowIdentityArePartOfTheWitness() {
        val text = window() + focused()
        val before = GuiVisualWindowParser.parse(text)!!
        assertNotEquals(before, GuiVisualWindowParser.parse(text.replace("ROTATION_0", "ROTATION_90")))
        assertNotEquals(before, GuiVisualWindowParser.parse(window(id = "new") + focused("new")))
        assertNotEquals(before, GuiVisualWindowParser.parse(window(frame = "[0,10][100,200]") + focused()))
        assertNull(GuiVisualWindowParser.parse(text.replace("mDisplayRotation=ROTATION_0", "rotation_unknown")))
    }
    @Test fun failedTruncatedAndInvalidOwnerMetadataStayUnavailable() {
        val text = window() + focused()
        assertNull(GuiVisualWindowParser.parse("Permission Denial\n$text"))
        assertNull(GuiVisualWindowParser.parse("DUMP TIMEOUT\n$text"))
        assertNull(GuiVisualWindowParser.parse(text.replace("mOwnerUid=10222", "mOwnerUid=1000")))
        assertNull(GuiVisualWindowParser.parse(text.replace("mHasSurface=true", "mHasSurface=false")))
        assertNull(GuiVisualWindowParser.parse(text.repeat(20_000)))
    }
}

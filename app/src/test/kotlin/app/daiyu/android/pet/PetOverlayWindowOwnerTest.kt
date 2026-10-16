package app.daiyu.android.pet

import org.junit.Assert.*
import org.junit.Test

/** No Android runtime, overlay permission, real WindowManager or network needed. */
class PetOverlayWindowOwnerTest {
    private class Window(var attached: Boolean = false, var contentActive: Boolean = true)
    private class Port : PetOverlayWindowPort<Window> {
        val events = mutableListOf<String>()
        val removed = mutableListOf<Window>()
        var adds = 0
        var failAdd = false
        var attachBeforeAddFailure = true
        var removal = PetOverlayRemoval.DETACHED
        var throwRemove = false
        override fun add(window: Window) {
            adds++
            if (!failAdd || attachBeforeAddFailure) window.attached = true
            if (failAdd) throw IllegalStateException("partial add")
        }
        override fun update(window: Window) = Unit
        override fun remove(window: Window): PetOverlayRemoval {
            events += "remove"
            removed += window
            assertFalse("content must be retired before removing its root", window.contentActive)
            if (throwRemove) throw IllegalStateException("remove failed")
            if (removal == PetOverlayRemoval.DETACHED) window.attached = false
            return removal
        }
    }
    private class Fixture {
        val port = Port()
        val owner = PetOverlayWindowOwner(port) {
            port.events += "release"
            it.contentActive = false
        }
    }

    @Test fun confirmedRemovalReleasesHandleAndAllowsAnotherWindow() {
        val f = Fixture(); val first = Window()
        assertTrue(f.owner.add(first)); assertSame(first, f.owner.window)
        assertTrue(f.owner.remove()); assertNull(f.owner.window)
        assertFalse(f.owner.cleanupPending); assertFalse(first.attached)
        assertEquals(listOf("release", "remove"), f.port.events)
        assertTrue(f.owner.add(Window())); assertEquals(2, f.port.adds)
    }

    @Test fun throwingRemovalRetainsAttachedWindowAndBlocksSecondAddUntilRetry() {
        val f = Fixture(); val first = Window()
        assertTrue(f.owner.add(first)); f.port.throwRemove = true
        assertFalse(f.owner.remove())
        assertSame(first, f.owner.window); assertTrue(first.attached)
        assertFalse(first.contentActive); assertTrue(f.owner.cleanupPending)
        try { f.owner.add(Window()); fail("must not create a second root") }
        catch (_: IllegalStateException) { }
        assertEquals(1, f.port.adds)
        f.port.throwRemove = false
        assertTrue(f.owner.remove()); assertNull(f.owner.window)
        assertEquals(listOf(first, first), f.port.removed)
        assertTrue(f.owner.add(Window()))
    }

    @Test fun partialAddAndThrowingCleanupRetainTheOriginalOwner() {
        val f = Fixture(); val first = Window()
        f.port.failAdd = true; f.port.throwRemove = true
        assertFalse(f.owner.add(first))
        assertTrue(first.attached); assertFalse(first.contentActive)
        assertSame(first, f.owner.window); assertTrue(f.owner.cleanupPending)
        f.port.throwRemove = false
        assertTrue(f.owner.remove()); assertNull(f.owner.window)
        assertEquals(listOf(first, first), f.port.removed)
    }

    @Test fun notYetAttachedIsNotProofThatPartialAddReleasedItsRoot() {
        val f = Fixture(); val first = Window()
        f.port.failAdd = true; f.port.attachBeforeAddFailure = false
        f.port.removal = PetOverlayRemoval.UNKNOWN
        assertFalse(f.owner.add(first)); assertFalse(first.attached)
        assertSame(first, f.owner.window); assertTrue(f.owner.cleanupPending)
        assertFalse(f.owner.remove()); assertSame(first, f.owner.window)
        f.port.removal = PetOverlayRemoval.DETACHED
        assertTrue(f.owner.remove()); assertNull(f.owner.window)
    }

    @Test fun failedAddWithConfirmedCleanupDoesNotRetainContentOrWindow() {
        val f = Fixture(); val first = Window(); f.port.failAdd = true
        assertFalse(f.owner.add(first))
        assertFalse(first.contentActive); assertFalse(first.attached)
        assertNull(f.owner.window); assertFalse(f.owner.cleanupPending)
        f.port.failAdd = false
        assertTrue(f.owner.add(Window()))
    }

    @Test fun repeatedUnknownCleanupNeverForgetsOwnerAndEmptyCleanupIsIdempotent() {
        val f = Fixture(); val first = Window()
        assertTrue(f.owner.remove()); assertTrue(f.port.removed.isEmpty())
        f.owner.add(first); f.port.removal = PetOverlayRemoval.UNKNOWN
        repeat(3) {
            assertFalse(f.owner.remove()); assertSame(first, f.owner.window)
            assertTrue(f.owner.cleanupPending)
        }
        assertEquals(listOf(first, first, first), f.port.removed)
        f.port.removal = PetOverlayRemoval.DETACHED
        assertTrue(f.owner.remove()); val count = f.port.removed.size
        assertTrue(f.owner.remove()); assertEquals(count, f.port.removed.size)
    }

    @Test fun contentReleaseFailureDoesNotSkipRootCleanupAndCanBeRetried() {
        val port = Port(); val first = Window(contentActive = false)
        var releases = 0
        val owner = PetOverlayWindowOwner(port) {
            releases++
            if (releases == 1) throw IllegalStateException("release failed")
        }
        owner.add(first); port.removal = PetOverlayRemoval.UNKNOWN
        assertFalse(owner.remove()); assertSame(first, owner.window)
        assertEquals(1, port.removed.size)
        port.removal = PetOverlayRemoval.DETACHED
        assertTrue(owner.remove()); assertEquals(2, releases); assertNull(owner.window)
    }
}

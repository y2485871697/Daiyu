package app.daiyu.android.pet

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class PetPlacementTest {
    @Test fun bubbleGrowthAndDismissalNeverChangeSpriteAnchor() {
        for (x in listOf(0, 140, 272)) for (y in listOf(0, 280, 472)) {
            val bare = petPlacement(x, y, 400, 600, 128, 128, 128, 16)
            val bubble = petPlacement(x, y, 400, 600, 128, 220, 230, 16)
            assertEquals(bare.spriteX, bubble.x + 46)
            assertEquals(bare.spriteY, bubble.y + 102)
            assertEquals(bare, petPlacement(bubble.spriteX, bubble.spriteY, 400, 600, 128, 128, 128, 16))
        }
    }
    @Test fun defaultsAreResolvedBeforeFirstVisibleFrame() {
        val initial = petPlacement(-1, -1, 400, 600, 128, 128, 128, 16)
        assertEquals(PetPlacement(256, 372, 256, 372), initial)
        val restored = petPlacement(initial.spriteX, initial.spriteY, 400, 600, 128, 220, 230, 16)
        assertEquals(210, restored.x)
        assertEquals(270, restored.y)
    }
    @Test fun speechCanLeaveScreenWithoutPersistingNegativeSentinelCoordinates() {
        val topLeft = petPlacement(0, 0, 400, 600, 128, 220, 230, 16)
        assertTrue(topLeft.x < 0 && topLeft.y < 0)
        assertEquals(0, topLeft.spriteX)
        assertEquals(0, topLeft.spriteY)
        assertEquals(topLeft, petPlacement(topLeft.spriteX, topLeft.spriteY, 400, 600, 128, 220, 230, 16))
    }
    @Test fun viewportShrinkAndOversizedSpriteKeepRangeValid() {
        assertEquals(PetPlacement(0, 0, 0, 0), petPlacement(800, 900, 100, 100, 128, 128, 128, 16))
        assertEquals(PetPlacement(272, 472, 272, 472), petPlacement(800, 900, 400, 600, 128, 128, 128, 16))
    }
}

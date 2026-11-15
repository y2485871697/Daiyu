package app.daiyu.android.pet

/** Persist the sprite's origin; speech may grow around it without changing that anchor. */
internal data class PetPlacement(val x: Int, val y: Int, val spriteX: Int, val spriteY: Int)

internal fun petPlacement(savedX: Int, savedY: Int, screenWidth: Int, screenHeight: Int,
    petSide: Int, contentWidth: Int, contentHeight: Int, margin: Int): PetPlacement {
    val maxX = (screenWidth - petSide).coerceAtLeast(0)
    val maxY = (screenHeight - petSide).coerceAtLeast(0)
    val spriteX = (if (savedX < 0) maxX - margin else savedX).coerceIn(0, maxX)
    val spriteY = (if (savedY < 0) (screenHeight * .62f).toInt() else savedY).coerceIn(0, maxY)
    return PetPlacement(spriteX - (contentWidth - petSide).coerceAtLeast(0) / 2,
        spriteY - (contentHeight - petSide).coerceAtLeast(0), spriteX, spriteY)
}

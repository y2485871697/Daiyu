package app.daiyu.android.pet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.pet.*
import app.daiyu.android.testContext
import java.util.UUID

@Suppress("DEPRECATION")
class AndroidPetStoreTest : InstrumentationTestCase() {
    fun testVersionedStateRoundTripKeepsActionsMemoryUsageAndCompletedRuns() {
        val state = PetState(settings = PetSettings(true, true, false, true, 1.25f, 42, 84,
            PetModelSelection("pet-a", "pet-p", "pet-m")), satiety = 12_345, mood = PetMood.EATING,
            speech = "米饭真香", speechVisible = true,
            memories = listOf(PetMemory(PetMood.EATING, "米饭真香", 400, 123, PetEvent.FEED)),
            recentTasks = listOf(PetTask("任务", 100)),
            usage = listOf(PetUsage("r", "pet-a", "pet-p", "pet-m", PetEvent.FEED, PetRequestStatus.COMPLETED)),
            completedRuns = listOf(PetCompletedRun("c", "r")))
        assertEquals(state, PetStateJson.decode(PetStateJson.encode(state)))
        val context = testContext(instrumentation.targetContext, "pet-${UUID.randomUUID()}-")
        val store = AndroidPetStore(context)
        try {
            store.save(state); assertEquals(state, AndroidPetStore(context).load())
            val v1 = org.json.JSONObject(PetStateJson.encode(state)).put("version", 1).apply { remove("completedRuns") }
            assertTrue(PetStateJson.decode(v1.toString()).completedRuns.isEmpty())
        } finally { context.getSharedPreferences("daiyu_pet_v1", 0).edit().clear().commit() }
    }
    fun testCorruptStateIsNotSilentlySuccessful() {
        try { PetStateJson.decode("{bad}"); fail("corruption must fail") } catch (_: Exception) {}
        try { PetStateJson.decode("{\"version\":99}"); fail("version must fail") } catch (_: IllegalArgumentException) {}
    }
    fun testAllOriginalAssetsAndLicenseArePackagedAndEightRowsDrawActualPixels() {
        val context = instrumentation.targetContext
        val resources = WhaleMaidResources(context)
        val sheet = resources.spriteSheet()
        assertEquals(0, sheet.width % 4); assertEquals(sheet.width / 4 * 8, sheet.height)
        PetFood.entries.forEach { food ->
            assertNotNull(resources.food(food.resourceKey)); assertTrue(resources.food(food.resourceKey)!!.width > 0)
        }
        assertNotNull(resources.cupboard())
        val license = context.assets.open("whale-maid/LICENSE").bufferedReader().use { it.readText() }
        val notice = context.assets.open("whale-maid/NOTICE").bufferedReader().use { it.readText() }
        assertTrue(license.startsWith("MIT License")); assertTrue(notice.contains("https://github.com/AcidGr/dsh-web-whale-maid"))
        instrumentation.runOnMainSync {
            val view = PetSpriteView(context, resources)
            view.layout(0, 0, 128, 128)
            val target = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            try {
                PetMood.entries.forEach { mood ->
                    target.eraseColor(0); view.mood = mood; view.draw(Canvas(target))
                    val pixels = IntArray(128 * 128); target.getPixels(pixels, 0, 128, 0, 0, 128, 128)
                    assertTrue("No sprite pixels for $mood", pixels.any { it ushr 24 != 0 })
                }
            } finally { target.recycle() }
        }
    }
}

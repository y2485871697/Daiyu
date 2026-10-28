package app.daiyu.android.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class DialogDimStackTest {
    private fun composite(stack: DialogDimStack, layers: List<DialogDimLayer>): Float =
        1f - layers.fold(1f) { remainder, layer -> remainder * (1f - stack.effectiveAlpha(layer)) }

    @Test fun singleLayerKeepsItsOwnShade() {
        for (alpha in listOf(0f, .35f, .6f, 1f)) {
            val stack = DialogDimStack()
            val layer = DialogDimLayer { alpha }
            stack.add(layer)
            assertEquals(alpha, stack.effectiveAlpha(layer), .00001f)
        }
    }

    @Test fun identicalLayersDoNotAccumulate() {
        for (count in 2..5) {
            val stack = DialogDimStack()
            val layers = List(count) { DialogDimLayer { .6f }.also(stack::add) }
            assertEquals(.6f, composite(stack, layers), .00001f)
            layers.dropLast(1).forEach { assertEquals(0f, stack.effectiveAlpha(it), 0f) }
            assertEquals(.6f, stack.effectiveAlpha(layers.last()), .00001f)
        }
    }

    @Test fun mixedStrengthsComposeToTheirMaximumInEveryOrder() {
        val values = listOf(0f, .1f, .35f, .6f, .9f, .99999f, 1f)
        for (a in values) for (b in values) for (c in values) {
            val stack = DialogDimStack()
            val layers = listOf(a, b, c).map { alpha -> DialogDimLayer { alpha }.also(stack::add) }
            assertEquals(maxOf(a, b, c), composite(stack, layers), .00001f)
        }
    }

    @Test fun childEntryAndExitKeepBaseShadeWhileShadingTheParentPanel() {
        val stack = DialogDimStack()
        var childAlpha = 0f
        val parent = DialogDimLayer { .6f }.also(stack::add)
        val child = DialogDimLayer { childAlpha }.also(stack::add)
        for (step in (0..100) + (100 downTo 0)) {
            childAlpha = .6f * step / 100f
            assertEquals(.6f, composite(stack, listOf(parent, child)), .00001f)
            assertEquals(childAlpha, stack.effectiveAlpha(child), .00001f)
        }
    }

    @Test fun removingTheTopRestoresTheParentImmediately() {
        val stack = DialogDimStack()
        val parent = DialogDimLayer { .6f }.also(stack::add)
        val child = DialogDimLayer { .6f }.also(stack::add)
        stack.remove(child)
        assertEquals(.6f, stack.effectiveAlpha(parent), .00001f)
        assertEquals(0f, stack.effectiveAlpha(child), 0f)
    }

    @Test fun removingTheMiddleOrOwnerDoesNotLeaveAResidualLayer() {
        val stack = DialogDimStack()
        val layers = List(3) { DialogDimLayer { .6f }.also(stack::add) }
        stack.remove(layers[1])
        assertEquals(.6f, composite(stack, listOf(layers[0], layers[2])), .00001f)
        stack.remove(layers[0])
        assertEquals(.6f, stack.effectiveAlpha(layers[2]), .00001f)
        stack.remove(layers[2])
        layers.forEach { assertEquals(0f, stack.effectiveAlpha(it), 0f) }
    }

    @Test fun updatingTheRequestedShadeDoesNotReorderExistingWindows() {
        val stack = DialogDimStack()
        var parentAlpha = .6f
        val parent = DialogDimLayer { parentAlpha }.also(stack::add)
        val child = DialogDimLayer { .35f }.also(stack::add)
        parentAlpha = .9f
        assertEquals(.9f, composite(stack, listOf(parent, child)), .00001f)
        assertEquals(.35f, stack.effectiveAlpha(child), .00001f)
    }

    @Test fun independentHostsDoNotAffectEachOther() {
        val a = DialogDimStack()
        val b = DialogDimStack()
        val layerA = DialogDimLayer { .6f }.also(a::add)
        DialogDimLayer { 1f }.also(b::add)
        assertEquals(.6f, a.effectiveAlpha(layerA), 0f)
    }
}

package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.CompositionGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import java.util.IdentityHashMap

@OptIn(InternalComposeApi::class)
internal class MarkdownPaintSlotsProbe {
    internal data class PaintSample(
        val frame: Any,
        val candidate: TextLayoutResult?,
        val rendered: TextLayoutResult?,
        val drawn: TextLayoutResult?,
        val fits: Boolean?,
        val above: Boolean?,
    )

    private val compositions = linkedMapOf<String, CompositionData>()
    private val capturedClearances = linkedMapOf<String, StreamingTextClearance?>()

    @Composable
    fun Capture(key: String) {
        val data = currentComposer.compositionData
        val clearance = LocalStreamingTextClearance.current
        SideEffect {
            compositions[key] = data
            capturedClearances[key] = clearance
        }
    }

    fun sample(key: String, onPaint: ((PaintSample) -> Unit)? = null): String = Snapshot.withoutReadObservation {
        val paths = IdentityHashMap<Any, String>()
        val groupSlots = linkedMapOf<String, MutableList<Any>>()
        val errors = linkedSetOf<String>()
        var groups = 0
        var slots = 0
        var truncated = false
        fun id(value: Any?): String = value?.let {
            "${it.javaClass.simpleName}@${System.identityHashCode(it)}"
        } ?: "null"
        fun field(owner: Any?, name: String): Any? {
            if (owner == null) return null
            return try {
                val declared = owner.javaClass.declaredFields.firstOrNull {
                    it.name == name || it.name == name + "\$delegate"
                }
                if (declared == null) {
                    if (errors.size < 16) errors += "${id(owner)}.$name:missing"
                    null
                } else {
                    declared.isAccessible = true
                    val value = declared.get(owner)
                    if (value is State<*>) value.value else value
                }
            } catch (problem: Exception) {
                if (errors.size < 16) errors += "${id(owner)}.$name:${problem.javaClass.simpleName}"
                null
            }
        }
        data class Cursor(val iterator: Iterator<CompositionGroup>, val path: String, var index: Int = 0)
        val stack = java.util.ArrayDeque<Cursor>()
        compositions[key]?.let { stack.addLast(Cursor(it.compositionGroups.iterator(), "")) }
        while (stack.isNotEmpty() && groups < 6_000 && slots < 24_000) {
            val cursor = stack.peekLast()
            if (!cursor.iterator.hasNext()) {
                stack.removeLast()
                continue
            }
            val group = cursor.iterator.next()
            val path = "${cursor.path}/${cursor.index++}"
            groups++
            val data = group.data.iterator()
            var slot = 0
            while (data.hasNext() && slots < 24_000) {
                val value = data.next()
                slots++
                if (value != null) {
                    groupSlots.getOrPut(path) { mutableListOf() }.add(value)
                    if (!paths.containsKey(value)) paths[value] = "$path:$slot"
                }
                slot++
            }
            if (data.hasNext()) truncated = true
            stack.addLast(Cursor(group.compositionGroups.iterator(), path))
        }
        if (stack.any { it.iterator.hasNext() }) truncated = true
        val values = paths.keys.toList()
        fun location(value: Any?) = "${id(value)}@${paths[value] ?: "not-in-slots"}"
        fun layout(frame: Any, name: String) = field(frame, name) as? TextLayoutResult
        fun describe(result: TextLayoutResult?): String = result?.let {
            "${it.layoutInput.text.length}/${it.size.width}x${it.size.height}/w${it.layoutInput.constraints.maxWidth}" +
                "/style#${it.layoutInput.style.hashCode()}/wrap=${it.layoutInput.softWrap}" +
                "/density=${it.layoutInput.density.density}:${it.layoutInput.density.fontScale}" +
                "/direction=${it.layoutInput.layoutDirection}"
        } ?: "null"
        val clearances = (listOfNotNull(capturedClearances[key]) +
            values.filterIsInstance<StreamingTailViewport>().map { it.textClearance })
            .distinctBy { System.identityHashCode(it) }.take(8)
        val reveals = values.filterIsInstance<MarkdownReveal>().sortedByDescending {
            (field(it, "target") as? MarkdownTypingTarget)?.total ?: -1
        }
        val motions = values.filterIsInstance<MarkdownTypingState>()
        val paints = values.filter { it.javaClass.simpleName == "MarkdownTextFrame" }
            .sortedByDescending { layout(it, "layout")?.layoutInput?.text?.length ?: -1 }
        buildString {
            appendLine("scan key=$key captured=${compositions.containsKey(key)} groups=$groups slots=$slots truncated=$truncated values=${values.size} " +
                "reveals=${reveals.size} motions=${motions.size} paints=${paints.size} clearances=${clearances.size}")
            for (reveal in reveals.take(8)) {
                val target = field(reveal, "target") as? MarkdownTypingTarget
                val frame = field(reveal, "frame") as? MarkdownTypingFrame
                val publication = reveal.publication
                val pending = field(publication, "pending") as? Map<*, *>
                val published = field(publication, "published") as? Map<*, *>
                appendLine("REVEAL ${location(reveal)} start=${target?.start} total=${target?.total} " +
                    "visible=${frame?.visible} frameMatches=${frame?.target === target} " +
                    "completion=${field(reveal, "completion")} settled=${reveal.settled} " +
                    "PUB=${id(publication)} active=${publication.active} pending=${pending?.size} " +
                    "publicationSettled=${publication.settled(target)}")
                pending?.keys?.take(16)?.forEach { appendLine(" OWNER ${location(it)}") }
                target?.parts?.take(16)?.forEach { part ->
                    val actual = published?.get(part.source.id) as? MarkdownTypingSource
                    appendLine(" SOURCE ${part.source.id} display=${part.length} raw=${part.source.text.length} " +
                        "presented=${actual?.text?.length} exact=${actual == part.source} " +
                        "windowMatch=${actual?.window == part.source.window}")
                }
            }
            for (motion in motions.take(8)) {
                val received = field(motion, "receivedSources") as? List<*>
                appendLine("MOTION ${location(motion)} target=${id(motion.target)} total=${motion.target?.total} " +
                    "received=${received?.filterIsInstance<MarkdownTypingSource>()?.joinToString { it.id + ":" + it.text.length }} " +
                    listOf("position", "lastFrame", "receivedEnd", "arrivalFrame", "arrivalSeconds", "receiptNanos",
                        "receiptRate", "rate", "desiredRate", "resuming", "receiptPaused", "receiptReanchoring",
                        "receiptHasLiveRate").joinToString { "$it=${field(motion, it)}" })
            }
            for (paint in paints.take(16)) {
                val candidate = layout(paint, "layout")
                val drawn = layout(paint, "drawn")
                val coordinates = field(paint, "coordinates") as? LayoutCoordinates
                val attached = coordinates?.isAttached == true
                val height = maxOf(candidate?.size?.height ?: 0, if (attached) coordinates!!.size.height else 0)
                val rendered = layout(paint, "rendered")
                val clearance = clearances.firstOrNull()
                onPaint?.invoke(PaintSample(paint, candidate, rendered, drawn,
                    candidate?.let { clearance?.fits(coordinates, it.size.height) },
                    clearance?.whollyAboveViewport(coordinates, height)))
                appendLine("PAINT ${location(paint)} candidate=${describe(candidate)} " +
                    "committed=${describe(layout(paint, "committed"))} rendered=${describe(layout(paint, "rendered"))} " +
                    "drawn=${describe(drawn)} attached=$attached height=$height cachedAvailable=${field(paint, "availableHeight")} " +
                    "nextAdvance=${field(paint, "nextAdvance")} " +
                    "textEqual=${drawn != null && drawn.layoutInput.text == candidate?.layoutInput?.text} " +
                    "prefix=${drawn != null && candidate != null && drawn.canPaintPrefixOf(candidate)}")
                val group = paths[paint]?.substringBeforeLast(':')
                val peers = group?.let { groupSlots[it] }.orEmpty()
                // Only mutable text slots are read; evaluating a DerivedState could repair the defect being sampled.
                val requests = peers.filterIsInstance<MutableState<*>>().mapNotNull { state ->
                    (state.value as? AnnotatedString)?.let { state to it }
                }
                val owners = reveals.flatMap { reveal ->
                    (field(reveal.publication, "pending") as? Map<*, *>)?.keys.orEmpty()
                }.filterNotNull().filter { paths[it]?.substringBeforeLast(':') == group }
                appendLine(" REQUEST group=$group candidates=${requests.size} ownerPending=${owners.isNotEmpty()} " +
                    "ambiguous=${requests.size != 1} clearances=${clearances.size}")
                for ((state, requested) in requests.take(4)) {
                    val published = clearances.singleOrNull()?.let { clearance ->
                        requested.isEmpty() ||
                            candidate?.layoutInput?.text == requested && clearance.whollyAboveViewport(coordinates, height) ||
                            drawn != null && drawn.layoutInput.text == requested && candidate != null &&
                            drawn.canPaintPrefixOf(candidate) && clearance.fits(coordinates, drawn.size.height)
                    }
                    appendLine("  REQUEST_TEXT state=${location(state)} length=${requested.length} " +
                        "candidateEqual=${candidate?.layoutInput?.text == requested} " +
                        "committedEqual=${layout(paint, "committed")?.layoutInput?.text == requested} " +
                        "renderedEqual=${rendered?.layoutInput?.text == requested} " +
                        "drawnEqual=${drawn?.layoutInput?.text == requested} published=$published")
                }
                for (clearance in clearances) {
                    val geometry = field(clearance, "viewportGeometry")
                    val list = field(geometry, "list") as? LayoutCoordinates
                    val top = if (attached && list?.isAttached == true)
                        list.localPositionOf(coordinates!!, Offset.Zero).y else null
                    appendLine(" GEOMETRY ${id(clearance)} top=$top held=${clearance.held} " +
                        "available=${clearance.availableHeight(coordinates)} bodyEnd=${clearance.bodyEndInLeaf(coordinates)} " +
                        "lineSpace=${field(clearance, "lineSpace")} positioned=${field(clearance, "positioned")} " +
                        "protectionOn=${field(clearance, "geometry") != null} " +
                        "above=${clearance.whollyAboveViewport(coordinates, height)} " +
                        "candidateFits=${candidate?.let { clearance.fits(coordinates, it.size.height) }}")
                }
                if (drawn != null && candidate != null) {
                    val a = drawn.layoutInput
                    val b = candidate.layoutInput
                    appendLine(" PREFIX width=${a.constraints.maxWidth == b.constraints.maxWidth} style=${a.style == b.style} " +
                        "density=${a.density.density == b.density.density} fontScale=${a.density.fontScale == b.density.fontScale} " +
                        "direction=${a.layoutDirection == b.layoutDirection}")
                }
                if (rendered != null && candidate != null) {
                    val a = rendered.layoutInput
                    val b = candidate.layoutInput
                    appendLine(" RENDERED_TO_CANDIDATE text=${a.text == b.text} " +
                        "width=${a.constraints.maxWidth == b.constraints.maxWidth} style=${a.style == b.style} " +
                        "wrap=${a.softWrap == b.softWrap} density=${a.density.density == b.density.density} " +
                        "fontScale=${a.density.fontScale == b.density.fontScale} " +
                        "direction=${a.layoutDirection == b.layoutDirection} prefix=${rendered.canPaintPrefixOf(candidate)}")
                }
            }
            appendLine("schemaErrors=$errors")
        }.take(24_000)
    }
}

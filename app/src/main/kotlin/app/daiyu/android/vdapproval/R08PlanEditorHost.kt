package app.daiyu.android.vdapproval

import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.SessionCapabilities
import app.daiyu.android.integration.tools.*
import java.util.Collections

/** Finite UI vocabulary. No arbitrary JSON, display selector, shell, token or transport field. */
internal enum class R08PlanAction(val fields: List<String>, val defaults: List<String>) {
    START(emptyList(), emptyList()),
    INITIALIZE(emptyList(), emptyList()),
    LAUNCH(listOf("package"), listOf("")),
    TAP(listOf("x", "y"), listOf("0", "0")),
    SWIPE(listOf("x1", "y1", "x2", "y2", "durationMs"), listOf("0", "0", "100", "100", "300")),
    TEXT(listOf("text"), listOf("")),
    KEY(listOf("keyCode"), listOf("4")),
    RELEASE(emptyList(), emptyList()),
    STATUS(emptyList(), emptyList()),
    SNAPSHOT(listOf("include", "maxBytes"), listOf("true", "6291456"))
}
internal data class R08PlanEditorRow(val action: R08PlanAction, val values: List<String> = action.defaults)

/** Frozen host inputs for the existing accepted-send seam. Freezing is NOT approval or a run.
 * The parent passes writes/reads to prepareVirtualDisplaySend, then the existing runtime binds
 * the actual Runner-issued owner and DURABLY RESERVED generation for the final exact approval.
 * Editing after freeze cannot mutate these collections or any operation/effect in the final plan.
 */
internal class R08FrozenPlan private constructor(writes: List<R08VirtualDisplayWrite>, reads: List<R08VirtualDisplayRead>) {
    val writes: List<R08VirtualDisplayWrite> = Collections.unmodifiableList(writes.toList())
    val reads: List<R08VirtualDisplayRead> = Collections.unmodifiableList(reads.toList())

    fun prepare(owner: RunExecutionOwner, capabilities: SessionCapabilities, generation: String): R08VirtualDisplayPlan =
        R08VirtualDisplayPlan.prepare(owner, capabilities, generation, writes, reads)

    override fun toString() = "R08FrozenPlan(host-only)"

    companion object {
        /** Strict parsing, no silent clamping/coercion. Parameter limits are intentionally finite
         * UI bounds; the existing OwnerV2Contract still validates/completes the actual plan.
         */
        fun freeze(rows: List<R08PlanEditorRow>): R08FrozenPlan {
            require(rows.size in 2..64) { "需要 2–64 项（Start、Initialize 和有限动作/读取）" }
            val snapshot = rows.map { it.copy(values = it.values.toList()) }
            val writes = mutableListOf<R08VirtualDisplayWrite>()
            val reads = mutableListOf<R08VirtualDisplayRead>()
            snapshot.forEachIndexed { index, row ->
                require(row.values.size == row.action.fields.size) { "参数数量不匹配" }
                fun value(i: Int) = row.values[i]
                fun number(i: Int, range: IntRange): Int {
                    val raw = value(i)
                    require(raw.matches(Regex("0|[1-9][0-9]{0,9}"))) { "整数格式无效" }
                    return requireNotNull(raw.toIntOrNull()) { "整数超出范围" }.also {
                        require(it in range) { "整数超出范围 ${range.first}–${range.last}" }
                    }
                }
                fun coordinate(i: Int) = number(i, 0..32767)
                val id = "step_${index + 1}"
                val action: R08VirtualDisplayAction? = when (row.action) {
                    R08PlanAction.START -> R08VirtualDisplayAction.Start
                    R08PlanAction.INITIALIZE -> R08VirtualDisplayAction.Initialize
                    R08PlanAction.LAUNCH -> {
                        require(value(0).matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) &&
                            value(0).length <= 255) { "请输入完整包名" }
                        R08VirtualDisplayAction.Launch(value(0))
                    }
                    R08PlanAction.TAP -> R08VirtualDisplayAction.Tap(coordinate(0), coordinate(1))
                    R08PlanAction.SWIPE -> R08VirtualDisplayAction.Swipe(coordinate(0), coordinate(1), coordinate(2),
                        coordinate(3), number(4, 1..60000))
                    R08PlanAction.TEXT -> {
                        require(value(0).isNotEmpty() && value(0).length <= 4096 &&
                            value(0).none { it.code < 32 || it.code == 127 }) { "文本需为 1–4096 字符，不含控制字符" }
                        R08VirtualDisplayAction.Text(value(0))
                    }
                    R08PlanAction.KEY -> R08VirtualDisplayAction.Key(number(0, 0..1000))
                    R08PlanAction.RELEASE -> R08VirtualDisplayAction.Release
                    R08PlanAction.STATUS -> {
                        reads += R08VirtualDisplayRead(id)
                        null
                    }
                    R08PlanAction.SNAPSHOT -> {
                        require(value(0) == "true" || value(0) == "false") { "include 只能是 true 或 false" }
                        reads += R08VirtualDisplayRead(id, snapshot = true, include = value(0) == "true",
                            maxBytes = number(1, 1..6291456))
                        null
                    }
                }
                if (action != null) writes += R08VirtualDisplayWrite(id, action)
            }
            val actions = writes.map { it.action }
            require(actions.take(2) == listOf(R08VirtualDisplayAction.Start, R08VirtualDisplayAction.Initialize) &&
                actions.count { it == R08VirtualDisplayAction.Start } == 1 &&
                actions.count { it == R08VirtualDisplayAction.Initialize } == 1) { "写计划必须以唯一 Start、Initialize 开始" }
            require(actions.count { it == R08VirtualDisplayAction.Release } <= 1 &&
                (R08VirtualDisplayAction.Release !in actions || actions.last() == R08VirtualDisplayAction.Release)) {
                "Release 至多一次且必须是最后一项写操作"
            }
            return R08FrozenPlan(writes, reads)
        }
    }
}

/** One editor visit, one frozen submission. Old UI callbacks cannot submit later edited inputs.
 * Pure helper: no owner allocation, approval issuance, storage or executable tool registration.
 */
internal class R08PlanEditorHost {
    private var consumed = false
    @Synchronized fun freeze(rows: List<R08PlanEditorRow>): R08FrozenPlan {
        check(!consumed) { "editor_consumed" }
        val frozen = R08FrozenPlan.freeze(rows)
        consumed = true
        return frozen
    }
    @Synchronized fun cancel(): Boolean {
        if (consumed) return false
        consumed = true
        return true
    }
}

package app.daiyu.android.vdapproval

import app.daiyu.android.integration.tools.R08VirtualDisplayPlan

/** A separate, finite maintenance grant, NOT resurrection of the stopped run's tool authority.
 * Only the retaining session may accept this ORIGINAL request. There is no mutable operation,
 * target, ID, binding, transport or generation field and no auto-confirm/execute on construction.
 * The wrapped review supplies the same canonical effect rendering as ordinary plan approval.
 */
internal class R08CleanupApprovalRequest internal constructor(plan: R08VirtualDisplayPlan) {
    val review = VirtualDisplayApprovalRequest(plan)
    val deliversTasks = plan.writes.map { it.operation } == listOf("handoff", "release")
    val selectedTaskIds: List<Int> = if (deliversTasks) {
        val ids = plan.writes.first().parameters().getJSONArray("taskIds")
        java.util.Collections.unmodifiableList((0 until ids.length()).map { ids.getInt(it) })
    } else emptyList()
    init {
        require(plan.reads.isEmpty() && (deliversTasks || plan.writes.map { it.operation } == listOf("release")))
        if (deliversTasks) require(selectedTaskIds.size in 1..256 && selectedTaskIds.all { it > 0 } &&
            selectedTaskIds.distinct().size == selectedTaskIds.size)
    }
    val confirmText = if (deliversTasks) "批准所选任务的一次 Handoff → 条件 Release" else "仅批准原 owner 的一次 Release"
    val warning = if (deliversTasks)
        "原运行工具授权保持撤销。本次仅对原 owner / generation 交付明确选择的非空任务；未选择的 owned 中间任务可能被清理。" +
        "仅当 Handoff 业务回执与持久记录均确认后才发送 Release。UNKNOWN 不重放、不换 ID、不重连；物理退出仍须单独核实。"
    else "本次仅向原 owner / generation 发送一次 Release，不代表交付任务；不启动、初始化或自动交接。" +
        "UNKNOWN 保留 owner、lease 和持久回执；另行观察物理退出后才能释放 lease。"
    override fun toString() = "R08CleanupApprovalRequest(host-only)"
}

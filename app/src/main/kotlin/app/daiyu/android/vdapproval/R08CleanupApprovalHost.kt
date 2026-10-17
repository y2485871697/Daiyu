package app.daiyu.android.vdapproval

import app.daiyu.android.integration.tools.R08VirtualDisplayPlan

/** A separate, finite maintenance grant, NOT resurrection of the stopped run's tool authority.
 * Only the retaining session may accept this ORIGINAL request. There is no mutable operation,
 * target, ID, binding, transport or generation field and no auto-confirm/execute on construction.
 * The wrapped review supplies the same canonical effect rendering as ordinary plan approval.
 */
internal class R08CleanupApprovalRequest internal constructor(plan: R08VirtualDisplayPlan) {
    val review = VirtualDisplayApprovalRequest(plan)
    init {
        require(plan.writes.size == 1 && plan.writes.single().operation == "release" && plan.reads.isEmpty())
    }
    val warning = "这次运行已停止，原工具授权保持撤销。本次额外授权仅向原 owner / generation 发送一次 Release。" +
        "不会启动、初始化、换 UUID 重放、强杀或自动交接。UNKNOWN 保留 owner、lease 和持久回执；" +
        "Release 成功也必须另行观察物理退出后才能释放 lease。"
    override fun toString() = "R08CleanupApprovalRequest(host-only)"
}

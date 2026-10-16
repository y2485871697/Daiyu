package app.daiyu.android.vdapproval

import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.SessionCapabilities
import app.daiyu.android.domain.tools.TaskAuthorization
import app.daiyu.android.domain.tools.WriteEffect
import app.daiyu.android.integration.tools.*
import app.daiyu.android.vd.AndroidVirtualDisplayOwnerHost
import java.util.Collections

/** Pure view model for a NEW approval UI. Construct/show is not permission or execution.
 * UI must render EVERY row's operation/effective parameters, including start vs initialize.
 * No Activity/settings/controller hookup is installed by this artifact. */
internal class VirtualDisplayApprovalRequest(val plan: R08VirtualDisplayPlan) {
    data class Row(val stepId: String, val operation: String, val effectiveParameters: String, val exactEffect: WriteEffect)
    val rows: List<Row> = Collections.unmodifiableList(plan.writes.map {
        Row(it.id, it.operation, it.canonicalParameters, checkNotNull(it.effect))
    })
    val warning = "Root owner start, display initialization, and every GUI write are separate grants. " +
        "Reads never initialize. Closing revokes access but does not release, hand off, or kill the owner."
    private var answered = false

    /** Call ONLY from the explicit approval UI action, never on open/show/dismiss/default chat.
     * The callback joins this approval into the parent's single combined TaskAuthorization;
     * it does not create a separate authorization or execute anything here. Cancel grants nothing. */
    fun answerFromUi(accepted: Boolean, deliver: (VirtualDisplayApproval?) -> Unit) {
        val answer = synchronized(this) {
            check(!answered) { "virtual_display_approval_already_answered" }
            answered = true
            if (accepted) VirtualDisplayApproval.issue(plan) else null
        }
        deliver(answer)
    }
}

/** Capability acquired only by the explicit UI action, for one immutable run plan and one binding. */
internal class VirtualDisplayApproval private constructor(internal val plan: R08VirtualDisplayPlan) {
    @Volatile private var revoked = false
    private var boundAuthorization: TaskAuthorization? = null
    val isActive: Boolean get() = !revoked
    val requiredEffects: Set<WriteEffect> get() = plan.effects

    fun revoke() { revoked = true }

    /** Parent factory signature: pass the SAME actual owner/capabilities/combined authorization
     * used by RunCatalogTools/CatalogModelBridge, not a copy made for this leaf. No I/O here.
     * Keep both the returned leaf and host in the parent's retention record until proven drained. */
    fun bind(owner: RunExecutionOwner, capabilities: SessionCapabilities, authorization: TaskAuthorization,
             host: AndroidVirtualDisplayOwnerHost, journal: R08VirtualDisplayJournal): R08VirtualDisplayCatalogLeaf =
        bind(owner, capabilities, authorization, R08AndroidVirtualDisplayEndpoint(host), journal)

    internal fun bind(owner: RunExecutionOwner, capabilities: SessionCapabilities, authorization: TaskAuthorization,
                      endpoint: R08VirtualDisplayEndpoint, journal: R08VirtualDisplayJournal): R08VirtualDisplayCatalogLeaf {
        synchronized(this) {
            check(isActive && boundAuthorization == null) { "virtual_display_approval_unavailable" }
            require(owner === plan.owner && capabilities === plan.capabilities) { "virtual_display_owner_mismatch" }
            require(authorization.sessionId == capabilities.sessionId && authorization.taskId == owner.runId.value &&
                authorization.allowedWrites.containsAll(requiredEffects)) { "virtual_display_authorization_mismatch" }
            boundAuthorization = authorization
        }
        return R08VirtualDisplayCatalogLeaf(this, endpoint, journal).also { it.bind(owner, capabilities, authorization) }
    }

    internal fun accepts(owner: RunExecutionOwner, capabilities: SessionCapabilities, authorization: TaskAuthorization): Boolean =
        synchronized(this) {
            isActive && owner === plan.owner && capabilities === plan.capabilities && authorization === boundAuthorization
        }

    override fun toString() = "VirtualDisplayApproval(host-only)"
    companion object {
        internal fun issue(plan: R08VirtualDisplayPlan): VirtualDisplayApproval = VirtualDisplayApproval(plan)
    }
}

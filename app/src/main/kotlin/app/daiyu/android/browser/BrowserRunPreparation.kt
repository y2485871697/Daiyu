package app.daiyu.android.browser

import app.daiyu.android.browser.host.BrowserToolSession
import app.daiyu.android.browser.session.BrowserAccess
import app.daiyu.android.browser.session.BrowserGrant
import app.daiyu.android.browser.session.BrowserOwnerKind
import app.daiyu.android.browser.session.BrowserSessionRegistry
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.MessageId
import app.daiyu.android.domain.RunId
import app.daiyu.android.domain.delegation.ChildRunOwner
import app.daiyu.android.domain.delegation.ChildRunScope
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.TaskAuthorization
import app.daiyu.android.domain.tools.WriteEffect
import app.daiyu.android.integration.tools.RunBrowserTools
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Collections

/** Both the assistant profile and its durable reply identity are frozen, with no active fallback. */
internal data class BrowserRunIdentity(
    val conversationId: ConversationId,
    val runId: RunId,
    val assistantId: String,
    val assistantMessageId: MessageId,
) {
    init { require(assistantId.isNotBlank()) }
}

internal data class BrowserConfirmedWrite internal constructor(val arguments: String, val effect: WriteEffect)

/** Not a bearer grant: immutable run identity, action ceiling and optional exact effects. */
internal class BrowserRunPreview internal constructor(
    val identity: BrowserRunIdentity,
    val settings: BrowserSettings,
    val grant: BrowserGrant,
    actions: Set<String>,
    writes: List<BrowserConfirmedWrite>,
    /** Complete child lineage is visible to trusted approval UI, never a model bearer token. */
    val childOwner: ChildRunOwner? = null,
    val defaultPermission: BrowserDefaultPermission? = null,
) {
    val allowedActions: Set<String> = Collections.unmodifiableSet(actions.toSet())
    val writes: List<BrowserConfirmedWrite> = Collections.unmodifiableList(writes.toList())
}

/**
 * Pure, single-use preparation. No registry, WebView, tab, host, file or network is created.
 * The host supplies a frozen grant. Even the first WRITE preview uses only that grant and
 * the production effect algorithm; never manufacture a temporary session for approval.
 *
 * Fetch and cookie offloads are exact WRITEs, including explicit tab id. Cookie values remain private.
 * MAIN and CHILD have separate trusted preparation entries. Product default permission must
 * be supplied by the trusted host; legacy/exact callers remain strict. Even default writes
 * obtain their exact effect at invocation time, never a broad TaskAuthorization.
 */
internal class BrowserRunPreparation private constructor(
    val preview: BrowserRunPreview,
    private val existingSession: BrowserToolSession?,
    private val childScope: ChildRunScope? = null,
) {
    private var consumed = false

    /** Trusted admission only: explicit exact consent or a run-owned product default, never model content. */
    @Synchronized fun confirm(displayed: BrowserRunPreview): BrowserRunAuthorization {
        check(!consumed) { "browser_preparation_consumed" }
        require(displayed === preview) { "browser_preview_changed" }
        consumed = true
        return BrowserRunAuthorization(preview, existingSession, childScope)
    }

    @Synchronized fun cancel() { consumed = true }

    companion object {
        private val reads = BrowserSessionRegistry.readOnlyActions + "list_tabs"
        private val tabWrites = setOf("new_tab", "close_tab")
        private val fullWrites = setOf("navigate", "click", "type", "hover", "scroll", "execute_js",
            "scroll_and_collect", "set_user_agent", "set_viewport", "wait_for_selector",
            "wait_for_dom_stable", "reload", "go_back", "go_forward", "fetch")

        fun prepare(
            identity: BrowserRunIdentity,
            settings: BrowserSettings,
            grant: BrowserGrant,
            allowedActions: Set<String>,
            writeArguments: List<String> = emptyList(),
            existingSession: BrowserToolSession? = null,
            defaultPermission: BrowserDefaultPermission? = null,
        ): BrowserRunPreparation {
            // Do not remove this check: MAIN entry must never silently acquire child authority.
            require(grant.owner.kind == BrowserOwnerKind.MAIN) { "browser_child_requires_scope" }
            return prepareValidated(identity, settings, grant, allowedActions, writeArguments, existingSession, null, defaultPermission)
        }

        /** Trusted child host only, after ChildRunScope.capture has intersected parent permissions.
         * The original scope/authorization is retained, not reconstructed from matching IDs.
         * Grant owner is the child run, while the task keeps its inherited parent sessionId.
         * Pure: caller can compute requested effects with RunBrowserTools.writeEffect(grant, raw)
         * BEFORE capture; no child tab/host is needed to obtain an exact preview.
         */
        fun prepareChild(
            scope: ChildRunScope,
            assistantId: String,
            settings: BrowserSettings,
            grant: BrowserGrant,
            allowedActions: Set<String>,
            writeArguments: List<String> = emptyList(),
            existingSession: BrowserToolSession? = null,
            defaultPermission: BrowserDefaultPermission? = null,
        ): BrowserRunPreparation {
            val child = scope.owner
            require(grant.owner.kind == BrowserOwnerKind.CHILD && grant.owner.sessionId == child.childRunId) {
                "browser_child_owner_mismatch"
            }
            require(scope.authorization.taskId == child.childRunId &&
                scope.authorization.sessionId == scope.session.sessionId) { "browser_child_scope_mismatch" }
            val identity = BrowserRunIdentity(ConversationId(child.conversationId), RunId(child.childRunId),
                assistantId, MessageId(child.assistantMessageId))
            val result = prepareValidated(identity, settings, grant, allowedActions, writeArguments, existingSession, scope, defaultPermission)
            require(result.preview.writes.all { it.effect in scope.authorization.allowedWrites }) {
                "browser_child_write_not_inherited"
            }
            require((allowedActions.none { it in reads } || RunBrowserTools.READ_CAPABILITY in scope.session.enabled) &&
                (result.preview.writes.isEmpty() || RunBrowserTools.WRITE_CAPABILITY in scope.session.enabled)) {
                "browser_child_capability_not_inherited"
            }
            return result
        }

        private fun prepareValidated(
            identity: BrowserRunIdentity,
            settings: BrowserSettings,
            grant: BrowserGrant,
            allowedActions: Set<String>,
            writeArguments: List<String>,
            existingSession: BrowserToolSession?,
            childScope: ChildRunScope?,
            defaultPermission: BrowserDefaultPermission?,
        ): BrowserRunPreparation {
            require(settings.access != BrowserAccess.DISABLED && grant.access != BrowserAccess.DISABLED) {
                "BROWSER_DISABLED"
            }
            require(settings.access == BrowserAccess.FULL || grant.access == BrowserAccess.READ_ONLY) {
                "browser_preference_ceiling"
            }
            require(grant.owner.sessionId == identity.runId.value) { "browser_run_owner_mismatch" }
            require(existingSession == null || existingSession.grant == grant) { "browser_grant_mismatch" }
            val permitted = reads + tabWrites + (if (grant.access == BrowserAccess.FULL) fullWrites else emptySet()) +
                defaultPermission?.actions.orEmpty()
            require(defaultPermission == null || defaultPermission.grant == grant) { "browser_default_owner_mismatch" }
            val actions = allowedActions.toSet() + defaultPermission?.actions.orEmpty()
            require(actions.isNotEmpty() && actions.all { it in permitted }) { "browser_action_not_available" }
            require(childScope == null || defaultPermission == null || actions.all { action ->
                (if (action in reads) RunBrowserTools.READ_CAPABILITY else RunBrowserTools.WRITE_CAPABILITY) in childScope.session.enabled
            }) { "browser_child_capability_not_inherited" }
            val arguments = writeArguments.toList()
            // Reuse the exact production schema, access checks and canonical effect; no second encoder.
            val writes = arguments.map { raw ->
                val effect = RunBrowserTools.writeEffect(grant, raw)
                require(effect.operation in actions && effect.operation !in reads) { "browser_write_not_selected" }
                BrowserConfirmedWrite(raw, effect)
            }
            require((actions - reads - defaultPermission?.actions.orEmpty()).all { action ->
                writes.any { it.effect.operation == action }
            }) { "browser_exact_write_required" }
            require(writes.map { it.effect }.distinct().size == writes.size) { "browser_duplicate_effect" }
            return BrowserRunPreparation(BrowserRunPreview(identity, settings, grant, actions, writes, childScope?.owner, defaultPermission),
                existingSession, childScope)
        }
    }
}

/**
 * In-memory, revocable confirmation; never persisted or inferred from saved preferences.
 * RunBrowserTools.createConfirmed retains this token through prepare and context admission,
 * including confirmed ledger replay. TaskAuthorization alone is not a substitute for that gate.
 * The host must explicitly confirm, retain/revoke the token and own session lifetime/cleanup.
 */
internal class BrowserRunAuthorization internal constructor(
    val preview: BrowserRunPreview,
    private val existingSession: BrowserToolSession?,
    private val childScope: ChildRunScope? = null,
) {
    private val effects = Collections.unmodifiableSet(preview.writes.map { it.effect }.toSet())
    private var revoked = false
    private var boundOwner: RunExecutionOwner? = null
    private var boundSession: BrowserToolSession? = existingSession

    // Child tasks MUST retain capture's actual intersected authorization, even on first bind.
    private var boundAuthorization: TaskAuthorization? = childScope?.authorization
    val taskSessionId: String get() = childScope?.session?.sessionId ?: preview.grant.owner.sessionId

    /** Terminal for this token. Recreating a catalog or TaskAuthorization cannot restore it. */
    @Synchronized fun revoke() { revoked = true }

    /** The parent may supply its ONE mixed-catalog task, never a browser-only copy of it. */
    @Synchronized fun taskAuthorization(
        owner: RunExecutionOwner,
        assistantId: String,
        session: BrowserToolSession,
        authorization: TaskAuthorization? = null,
    ): TaskAuthorization {
        checkBinding(owner, assistantId, session)
        val task = authorization ?: boundAuthorization ?:
            TaskAuthorization(taskSessionId, preview.identity.runId.value, effects)
        require(task.sessionId == taskSessionId && task.taskId == preview.identity.runId.value &&
            task.allowedWrites.containsAll(effects)) { "browser_task_authorization_mismatch" }
        require(boundAuthorization == null || task === boundAuthorization) { "browser_task_authorization_mismatch" }
        // Any preview may precede session creation. Pin actual objects on the first binding;
        // equal IDs/grants must not authorize replacement owners, sessions or task objects.
        boundOwner = owner
        boundSession = session
        boundAuthorization = task
        return task
    }

    /** Pure admission check; this never executes or dispatches the invocation. */
    fun checkInvocation(owner: RunExecutionOwner, assistantId: String, session: BrowserToolSession,
                        toolName: String, arguments: String) {
        checkBinding(owner, assistantId, session)
        val parser = JSONTokener(arguments)
        val json = parser.nextValue()
        require(json is JSONObject && parser.nextClean() == '\u0000') { "browser_arguments_invalid" }
        val action = json.get("action")
        require(action is String && action in preview.allowedActions) { "browser_action_not_confirmed" }
        if (toolName == RunBrowserTools.READ_TOOL_NAME) {
            require(action in BrowserSessionRegistry.readOnlyActions || action == "list_tabs") { "browser_tool_mismatch" }
            // Full read schema validation remains the production catalog's responsibility.
        } else {
            require(toolName == RunBrowserTools.WRITE_TOOL_NAME) { "browser_tool_mismatch" }
            val effect = RunBrowserTools.writeEffect(session, arguments)
            require(effect in effects || action in preview.defaultPermission?.actions.orEmpty()) { "browser_effect_not_confirmed" }
        }
    }

    /** Single-effect approval, invoked only by the dispatcher after a fresh ledger reservation.
     * The original task remains immutable; child tasks retain capture's intersected task object.
     */
    @Synchronized fun defaultWriteAuthorization(owner: RunExecutionOwner, assistantId: String,
        session: BrowserToolSession, authorization: TaskAuthorization, arguments: String): TaskAuthorization? {
        checkInvocation(owner, assistantId, session, RunBrowserTools.WRITE_TOOL_NAME, arguments)
        require(authorization === boundAuthorization) { "browser_task_authorization_mismatch" }
        val effect = RunBrowserTools.writeEffect(session, arguments)
        if (effect.operation !in preview.defaultPermission?.actions.orEmpty()) return null
        return TaskAuthorization(taskSessionId, preview.identity.runId.value, setOf(effect))
    }

    fun hasDefaultWrite(effect: WriteEffect): Boolean = effect.operation in preview.defaultPermission?.actions.orEmpty()

    /** Native callbacks use the same revocable owner, not saved settings or model-supplied IDs. */
    @Synchronized fun allowsHostDefault(lease: app.daiyu.android.browser.session.BrowserTabLease,
        action: String? = null): Boolean = !revoked && preview.defaultPermission != null &&
        lease.owner == preview.grant.owner && (action == null || action in preview.defaultPermission?.actions.orEmpty())

    @Synchronized private fun checkBinding(owner: RunExecutionOwner, assistantId: String, session: BrowserToolSession) {
        require(!revoked) { "browser_authorization_revoked" }
        val identity = preview.identity
        require(owner.conversationId == identity.conversationId && owner.runId == identity.runId &&
            owner.assistantMessageId == identity.assistantMessageId && assistantId == identity.assistantId &&
            (boundOwner == null || owner === boundOwner)) {
            "browser_run_identity_mismatch"
        }
        require(session.grant == preview.grant && (boundSession == null || session === boundSession)) {
            "browser_grant_mismatch"
        }
    }
}

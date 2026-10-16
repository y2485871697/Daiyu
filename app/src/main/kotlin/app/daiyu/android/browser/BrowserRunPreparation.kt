package app.daiyu.android.browser

import app.daiyu.android.browser.host.BrowserToolSession
import app.daiyu.android.browser.session.BrowserAccess
import app.daiyu.android.browser.session.BrowserGrant
import app.daiyu.android.browser.session.BrowserOwnerKind
import app.daiyu.android.browser.session.BrowserSessionRegistry
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.MessageId
import app.daiyu.android.domain.RunId
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

/** Not a bearer grant: this immutable snapshot is what trusted UI must display and confirm. */
internal class BrowserRunPreview internal constructor(
    val identity: BrowserRunIdentity,
    val settings: BrowserSettings,
    val grant: BrowserGrant,
    actions: Set<String>,
    writes: List<BrowserConfirmedWrite>,
) {
    val allowedActions: Set<String> = Collections.unmodifiableSet(actions.toSet())
    val writes: List<BrowserConfirmedWrite> = Collections.unmodifiableList(writes.toList())
}

/**
 * Pure, single-use preparation. No registry, WebView, tab, host, file or network is created.
 * The host supplies a frozen grant. Until a grant-based production writeEffect overload exists,
 * WRITE preparation needs an ALREADY OWNED session; never manufacture a temporary session.
 *
 * This phase intentionally excludes cookies, downloads/fetch and child browser owners. Read-only
 * tab creation/closure still counts as WRITE and needs exact confirmation, as in the catalog.
 */
internal class BrowserRunPreparation private constructor(
    val preview: BrowserRunPreview,
    private val existingSession: BrowserToolSession?,
) {
    private var consumed = false

    /** Call only from explicit trusted confirmation, never from settings save or model content. */
    @Synchronized fun confirm(displayed: BrowserRunPreview): BrowserRunAuthorization {
        check(!consumed) { "browser_preparation_consumed" }
        require(displayed === preview) { "browser_preview_changed" }
        consumed = true
        return BrowserRunAuthorization(preview, existingSession)
    }

    @Synchronized fun cancel() { consumed = true }

    companion object {
        private val reads = BrowserSessionRegistry.readOnlyActions + "list_tabs"
        private val tabWrites = setOf("new_tab", "close_tab")
        private val fullWrites = setOf("navigate", "click", "type", "hover", "scroll", "execute_js",
            "scroll_and_collect", "set_user_agent", "set_viewport", "wait_for_selector",
            "wait_for_dom_stable", "reload", "go_back", "go_forward")

        fun prepare(
            identity: BrowserRunIdentity,
            settings: BrowserSettings,
            grant: BrowserGrant,
            allowedActions: Set<String>,
            writeArguments: List<String> = emptyList(),
            existingSession: BrowserToolSession? = null,
        ): BrowserRunPreparation {
            require(settings.access != BrowserAccess.DISABLED && grant.access != BrowserAccess.DISABLED) {
                "BROWSER_DISABLED"
            }
            require(settings.access == BrowserAccess.FULL || grant.access == BrowserAccess.READ_ONLY) {
                "browser_preference_ceiling"
            }
            require(grant.owner.kind == BrowserOwnerKind.MAIN) { "browser_child_not_wired" }
            require(grant.owner.sessionId == identity.runId.value) { "browser_run_owner_mismatch" }
            require(existingSession == null || existingSession.grant == grant) { "browser_grant_mismatch" }
            val permitted = reads + tabWrites + if (grant.access == BrowserAccess.FULL) fullWrites else emptySet()
            val actions = allowedActions.toSet()
            require(actions.isNotEmpty() && actions.all { it in permitted }) { "browser_action_not_available" }
            val arguments = writeArguments.toList()
            require(arguments.isEmpty() || existingSession != null) { "browser_existing_session_required" }
            // Reuse the exact production schema, access checks and canonical effect; no second encoder.
            val writes = arguments.map { raw ->
                val effect = RunBrowserTools.writeEffect(checkNotNull(existingSession), raw)
                require(effect.operation in actions && effect.operation !in reads) { "browser_write_not_selected" }
                BrowserConfirmedWrite(raw, effect)
            }
            require(writes.map { it.effect.operation }.toSet() == actions - reads) { "browser_exact_write_required" }
            require(writes.map { it.effect }.distinct().size == writes.size) { "browser_duplicate_effect" }
            return BrowserRunPreparation(BrowserRunPreview(identity, settings, grant, actions, writes), existingSession)
        }
    }
}

/**
 * In-memory confirmation only; not saved and not wired to Controller/Catalog by this file.
 * Integration MUST call checkInvocation before catalog prepare AND before confirmed ledger replay.
 * TaskAuthorization alone has no assistant/action-subset field and is not a substitute for that gate.
 * The host remains responsible for active run ownership, revocation, lifetime and cleanup.
 */
internal class BrowserRunAuthorization internal constructor(
    val preview: BrowserRunPreview,
    private val existingSession: BrowserToolSession?,
) {
    private val effects = Collections.unmodifiableSet(preview.writes.map { it.effect }.toSet())

    fun taskAuthorization(owner: RunExecutionOwner, assistantId: String, session: BrowserToolSession): TaskAuthorization {
        checkBinding(owner, assistantId, session)
        return TaskAuthorization(preview.grant.owner.sessionId, preview.identity.runId.value, effects)
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
            require(RunBrowserTools.writeEffect(session, arguments) in effects) { "browser_effect_not_confirmed" }
        }
    }

    private fun checkBinding(owner: RunExecutionOwner, assistantId: String, session: BrowserToolSession) {
        val identity = preview.identity
        require(owner.conversationId == identity.conversationId && owner.runId == identity.runId &&
            owner.assistantMessageId == identity.assistantMessageId && assistantId == identity.assistantId) {
            "browser_run_identity_mismatch"
        }
        require(session.grant == preview.grant && (existingSession == null || session === existingSession)) {
            "browser_grant_mismatch"
        }
    }
}

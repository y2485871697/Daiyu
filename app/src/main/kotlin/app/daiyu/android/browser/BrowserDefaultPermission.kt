package app.daiyu.android.browser

import app.daiyu.android.browser.session.*
import app.daiyu.android.domain.delegation.ChildRunScope
import app.daiyu.android.integration.tools.RunBrowserTools

/** Trusted product permission for ordinary browser operations, frozen to ONE resource owner.
 * Not a TaskAuthorization or a wildcard effect. Each invocation still passes the production
 * schema/URL/access checks and receives only its exact effect after ledger reservation.
 * This grants browser mechanics, not confirmation of purchases, publication or deletion.
 * Cookie operations retain the existing same-origin, name validation and private offload backend.
 * History is a host callback permission, not a newly invented tool action.
 */
internal class BrowserDefaultPermission private constructor(
    val grant: BrowserGrant,
    actions: Set<String>,
) {
    val actions: Set<String> = java.util.Collections.unmodifiableSet(actions.toSet())

    companion object {
        private val reads = BrowserSessionRegistry.readOnlyActions + "list_tabs"
        private val tabWrites = setOf("new_tab", "close_tab")
        private val fullWrites = setOf("navigate", "click", "type", "hover", "scroll", "execute_js",
            "scroll_and_collect", "set_user_agent", "set_viewport", "wait_for_selector",
            "wait_for_dom_stable", "reload", "go_back", "go_forward", "fetch", "get_cookies", "set_cookies")

        fun availableActions(access: BrowserAccess): Set<String> = when (access) {
            BrowserAccess.DISABLED -> emptySet()
            BrowserAccess.READ_ONLY -> reads + tabWrites
            BrowserAccess.FULL -> reads + tabWrites + fullWrites
        }

        fun main(grant: BrowserGrant): BrowserDefaultPermission {
            require(grant.owner.kind == BrowserOwnerKind.MAIN) { "browser_child_requires_scope" }
            return BrowserDefaultPermission(grant, availableActions(grant.access))
        }

        /** Parent/child intersection is local, never a change to shared task permissions. */
        fun child(grant: BrowserGrant, scope: ChildRunScope,
                  parent: BrowserDefaultPermission): BrowserDefaultPermission {
            require(grant.owner.kind == BrowserOwnerKind.CHILD &&
                grant.owner.sessionId == scope.owner.childRunId &&
                parent.grant.owner.kind == BrowserOwnerKind.MAIN &&
                parent.grant.owner.sessionId == scope.owner.parentRunId &&
                scope.session.sessionId == parent.grant.owner.sessionId) { "browser_child_owner_mismatch" }
            val inherited = availableActions(grant.access).intersect(parent.actions).filter { action ->
                (if (action in reads) RunBrowserTools.READ_CAPABILITY else RunBrowserTools.WRITE_CAPABILITY) in scope.session.enabled
            }.toSet()
            return BrowserDefaultPermission(grant, inherited)
        }
    }
}

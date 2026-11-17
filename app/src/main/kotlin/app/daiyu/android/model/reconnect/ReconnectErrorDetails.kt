package app.daiyu.android.model.reconnect

import app.daiyu.android.domain.ModelFailure
import app.daiyu.android.domain.ModelRequestDiagnostic

internal fun reconnectErrorDetails(reason: ModelFailure, diagnostic: ModelRequestDiagnostic? = null): String =
    app.daiyu.android.domain.modelFailureDetails(reason, diagnostic)

internal fun reconnectErrorDetailsForDisplay(details: String): String = details

package app.daiyu.android.model.catalog

/** Catalogue connectivity only; never evidence of inference or media/tool support. */
internal sealed interface ModelConnectionResult {
    data class Connected(val modelCount: Int) : ModelConnectionResult
    data object SpeechConnected : ModelConnectionResult
    data class Failed(val reason: RemoteModelFailure) : ModelConnectionResult
}

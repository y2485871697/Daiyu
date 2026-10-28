package app.daiyu.android.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue

/** One visual page's memory only: no browser lease, navigation callback or saved credentials. */
internal class BrowserSheetUiState {
    var address by mutableStateOf(TextFieldValue(""))
        private set
    private var tab: Int? = null
    private var observedUrl = ""
    private var dirty = false
    var showHistory by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
    var showDownloads by mutableStateOf(false)
    var historyQuery by mutableStateOf("")

    fun displayedAddress(selectedTab: Int?, currentUrl: String): TextFieldValue {
        // Keep the first tab projection subscribed even before synchronize runs.
        val retainedAddress = address
        return when {
            selectedTab == null -> retainedAddress
            selectedTab != tab || !dirty && currentUrl != observedUrl -> TextFieldValue(currentUrl)
            else -> retainedAddress
        }
    }

    fun synchronize(selectedTab: Int?, currentUrl: String) {
        if (selectedTab == null) return
        address = displayedAddress(selectedTab, currentUrl)
        if (selectedTab != tab) dirty = false
        tab = selectedTab
        observedUrl = currentUrl
    }

    fun addressInput(current: () -> Boolean, dismissing: () -> Boolean): (TextFieldValue) -> Unit = { value ->
        val sameText = value.text == address.text
        val blur = dismissing() || showHistory || showSettings || showDownloads
        if (current() && !(sameText && blur)) editAddress(value)
    }

    fun editAddress(value: TextFieldValue) {
        address = value
        dirty = value.text != observedUrl
    }

    fun submittedAddress(value: String) {
        address = TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))
        dirty = false
    }
}

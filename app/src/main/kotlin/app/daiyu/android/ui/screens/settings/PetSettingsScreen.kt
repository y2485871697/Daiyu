package app.daiyu.android.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.daiyu.android.pet.PetHost

/** Parent owns navigation, the single PetHost and its lifecycle; this screen owns no runtime. */
@Composable
fun PetSettingsScreen(host: PetHost, modifier: Modifier = Modifier) {
    AndroidView(modifier = modifier, factory = { host.createSettingsView(it) })
}

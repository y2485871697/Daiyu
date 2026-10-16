package app.daiyu.android.ui.screens.settings

import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import app.daiyu.android.pet.PetApplicationHost

/** Native sibling dispatch is intentional: a full-screen AndroidView above a Compose Box can
 * intercept Compose hit-testing even when its transparent pixels return false. This FrameLayout
 * dispatches to the pet first, then the underlying ComposeView for real touch-through behavior.
 * The Activity borrows only a mount lease; disposal never closes the application runtime. */
@Composable
internal fun ApplicationPetMount(pets: PetApplicationHost, owner: LifecycleOwner, modifier: Modifier = Modifier,
    content: @Composable () -> Unit) {
    val context = LocalContext.current
    val latest = rememberUpdatedState(content)
    val chat = remember(pets, owner) {
        ComposeView(context).apply {
            id = app.daiyu.android.R.id.pet_chat_compose_content
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { latest.value() }
        }
    }
    val layer = remember(pets, owner) {
        FrameLayout(context).apply {
            tag = "application-pet-layer"
            addView(chat, FrameLayout.LayoutParams(-1, -1))
        }
    }
    AndroidView(modifier = modifier, factory = { layer })
    DisposableEffect(pets, owner, layer) {
        val lease = pets.attach(layer, owner)
        onDispose { lease.cancel(); chat.disposeComposition() }
    }
}

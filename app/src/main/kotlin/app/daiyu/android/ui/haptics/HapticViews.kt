package app.daiyu.android.ui.haptics

import android.app.AlertDialog
import android.content.DialogInterface
import android.view.View

internal fun View.setHapticClickListener(listener: (View) -> Unit) {
    setOnClickListener { view ->
        if (view.isEnabled) TouchHaptics.action(view) { listener(view) }
    }
}

internal fun AlertDialog.Builder.setHapticPositiveButton(
    text: CharSequence, listener: ((DialogInterface, Int) -> Unit)? = null,
): AlertDialog.Builder = setPositiveButton(text) { dialog, which ->
    TouchHaptics.action((dialog as? AlertDialog)?.getButton(which)) { listener?.invoke(dialog, which) }
}

internal fun AlertDialog.Builder.setHapticNegativeButton(
    text: CharSequence, listener: ((DialogInterface, Int) -> Unit)? = null,
): AlertDialog.Builder = setNegativeButton(text) { dialog, which ->
    TouchHaptics.action((dialog as? AlertDialog)?.getButton(which)) { listener?.invoke(dialog, which) }
}

internal fun AlertDialog.Builder.setHapticNeutralButton(
    text: CharSequence, listener: ((DialogInterface, Int) -> Unit)? = null,
): AlertDialog.Builder = setNeutralButton(text) { dialog, which ->
    TouchHaptics.action((dialog as? AlertDialog)?.getButton(which)) { listener?.invoke(dialog, which) }
}

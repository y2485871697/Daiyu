package app.daiyu.android.ui

import app.daiyu.android.ui.haptics.setHapticNeutralButton
import app.daiyu.android.ui.haptics.setHapticNegativeButton
import app.daiyu.android.ui.haptics.setHapticClickListener
import android.app.Activity
import app.daiyu.android.ui.components.prepareDaiyuCenterMotion
import android.app.AlertDialog
import android.os.Looper
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Presentation-only navigation; business actions remain owned by the activity. */
internal class FeatureHubDialog(
    private val activity: Activity,
    private val onOpen: (FeatureRoute) -> Unit
) {
    private var window: AlertDialog? = null
    private var pageGeneration = 0L
    private var detailId: String? = null

    fun show(entryId: String? = null) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (activity.isFinishing || activity.isDestroyed) return
        dismiss()
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, padding)
        }
        val scroller = ScrollView(activity).apply { addView(body) }
        val dialog = AlertDialog.Builder(activity).setTitle("功能总览")
            .setView(scroller).setHapticNegativeButton("关闭", null)
            .setHapticNeutralButton("返回总览", null).create()
        fun live(generation: Long) = window === dialog && dialog.isShowing &&
            pageGeneration == generation && !activity.isFinishing && !activity.isDestroyed
        fun heading(id: String, title: String) = TextView(activity).apply {
            tag = id; text = title; textSize = 18f
            setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        fun render(entry: FeatureEntry?) {
            val generation = ++pageGeneration
            detailId = entry?.id
            dialog.setTitle(entry?.title ?: "功能总览")
            body.removeAllViews()
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).visibility =
                if (entry == null) android.view.View.GONE else android.view.View.VISIBLE
            if (entry == null) {
                FeatureCatalog.groups.forEach { group ->
                    body.addView(heading("feature-group:${group.id}", group.title))
                    group.entries.forEach { item ->
                        body.addView(Button(activity).apply {
                            tag = "feature:${item.id}"; text = item.title
                            setHapticClickListener { if (live(generation)) render(item) }
                        })
                    }
                }
            } else {
                body.tag = "feature-detail:${entry.id}"
                FeatureDetails.sections(entry.id).forEach { section ->
                    body.addView(heading("feature-section:${entry.id}:${section.id}", section.title))
                    section.controls.forEach { control ->
                        val row = if (control.kind == FeatureControlKind.ACTION) Button(activity) else TextView(activity)
                        row.tag = "feature-control:${entry.id}:${control.id}"
                        row.text = control.title
                        if (row !is Button) {
                            row.textSize = 16f
                            row.setPadding(0, (12 * activity.resources.displayMetrics.density).toInt(), 0, 0)
                            body.addView(row)
                            body.addView(TextView(activity).apply { text = control.kind.label })
                        } else {
                            control.route?.let { route ->
                                row.setHapticClickListener {
                                    if (live(generation)) {
                                        ++pageGeneration
                                        dialog.dismiss()
                                        onOpen(route)
                                    }
                                }
                            }
                            body.addView(row)
                        }
                    }
                }
            }
            if (entry == null) body.tag = "feature-overview"
            scroller.scrollTo(0, 0)
        }
        dialog.setOnDismissListener {
            if (window === dialog) { ++pageGeneration; window = null; detailId = null }
        }
        dialog.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_BACK && detailId != null) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) render(null)
                true
            } else false
        }
        window = dialog
        dialog.prepareDaiyuCenterMotion()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setHapticClickListener {
            if (window === dialog && dialog.isShowing && !activity.isFinishing && !activity.isDestroyed) render(null)
        }
        render(entryId?.let { id -> FeatureCatalog.groups.flatMap { it.entries }.single { it.id == id } })
    }

    fun dismiss() {
        check(Looper.myLooper() == Looper.getMainLooper())
        ++pageGeneration
        window?.dismiss()
    }
}

package app.daiyu.android.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Presentation-only host. Gemini can replace this renderer without changing business callbacks. */
internal class FeatureHubDialog(
    private val activity: Activity,
    private val onOpen: (FeatureRoute) -> Unit
) {
    private var window: AlertDialog? = null

    fun show() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (activity.isFinishing || activity.isDestroyed) return
        dismiss()
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, padding)
        }
        val dialog = AlertDialog.Builder(activity).setTitle("功能总览")
            .setView(ScrollView(activity).apply { addView(body) })
            .setNegativeButton("关闭", null).create()
        FeatureCatalog.groups.forEach { group ->
            body.addView(TextView(activity).apply {
                tag = "feature-group:${group.id}"
                text = group.title
                textSize = 18f
                setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
            })
            group.entries.forEach { entry ->
                body.addView(Button(activity).apply {
                    tag = "feature:${entry.id}"
                    text = entry.title
                    entry.route?.let { route ->
                        setOnClickListener {
                            if (window !== dialog || !dialog.isShowing || activity.isFinishing || activity.isDestroyed)
                                return@setOnClickListener
                            dialog.dismiss()
                            onOpen(route)
                        }
                    }
                })
            }
        }
        dialog.setOnDismissListener { if (window === dialog) window = null }
        window = dialog
        dialog.show()
    }

    fun dismiss() {
        check(Looper.myLooper() == Looper.getMainLooper())
        window?.dismiss()
    }
}

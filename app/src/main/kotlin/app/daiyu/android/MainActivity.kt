package app.daiyu.android

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView
import app.daiyu.android.domain.ProjectIdentity

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(TextView(context).apply {
                text = getString(R.string.bootstrap_title)
                textSize = 26f
            })
            addView(TextView(context).apply {
                text = getString(R.string.bootstrap_status, ProjectIdentity.displayName)
                textSize = 18f
            })
            addView(TextView(context).apply {
                text = getString(R.string.bootstrap_note)
                textSize = 16f
            })
        }
        content.setOnApplyWindowInsetsListener { view: View, insets: WindowInsets ->
            @Suppress("DEPRECATION")
            view.setPadding(padding + insets.systemWindowInsetLeft,
                padding + insets.systemWindowInsetTop,
                padding + insets.systemWindowInsetRight,
                padding + insets.systemWindowInsetBottom)
            insets
        }
        setContentView(content)
        content.requestApplyInsets()
    }
}

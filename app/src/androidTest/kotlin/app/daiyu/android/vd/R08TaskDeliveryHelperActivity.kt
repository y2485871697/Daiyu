package app.daiyu.android.vd

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/** Test-APK task. It must remain alive when the original owner moves it to display zero. */
class R08TaskDeliveryHelperActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val marker = intent?.data
        if (packageName != "app.daiyu.android.synthetic.test" || marker?.scheme != "daiyu-vd" ||
            marker.host != "session") {
            finishAndRemoveTask()
            return
        }
        setContentView(TextView(this).apply {
            text = "R08 synthetic task delivery helper"
            gravity = Gravity.CENTER
            textSize = 20f
        })
    }
}

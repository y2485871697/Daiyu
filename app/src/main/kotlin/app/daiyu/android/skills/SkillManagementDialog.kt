package app.daiyu.android.skills

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/** Optional settings host; no Activity/Manifest entry and no installation or script execution. */
class SkillManagementDialog(private val activity: Activity,
    private val managementFactory: () -> SkillManagement = {
        SkillManagement.create { SkillToolRegistry(activity.applicationContext) }
    }
) {
    private val main = Handler(Looper.getMainLooper())
    private var window: AlertDialog? = null

    fun show() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (activity.isFinishing || activity.isDestroyed) return
        window?.dismiss()
        val worker = Executors.newSingleThreadExecutor()
        val management by lazy(managementFactory)
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 16) }
        val status = TextView(activity)
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        body.addView(TextView(activity).apply {
            text = "启停设置用于后续运行。已发送给模型的技能正文不会撤回；更改不代表当前运行立即停止。"
        })
        body.addView(status); body.addView(rows)
        val dialog = AlertDialog.Builder(activity).setTitle("技能管理")
            .setView(ScrollView(activity).apply { addView(body) }).setNegativeButton("关闭", null)
            .setNeutralButton("刷新", null).create()
        var active = true
        var busy = false
        lateinit var load: (() -> SkillManagement.State) -> Unit
        fun render(state: SkillManagement.State) {
            rows.removeAllViews()
            rows.addView(Button(activity).apply {
                text = if (state.enabled) "技能总开关：已开启（点击关闭）" else "技能总开关：已关闭（点击开启）"
                setOnClickListener { load { management.setGlobalEnabled(!state.enabled) } }
            })
            if (state.entriesUnavailable) rows.addView(TextView(activity).apply {
                text = "技能列表暂不可用；仍可关闭总开关以停止后续运行加载技能，请刷新重读列表"
            })
            else if (state.entries.isEmpty()) rows.addView(TextView(activity).apply { text = "尚未安装技能" })
            state.entries.forEach { entry ->
                rows.addView(TextView(activity).apply {
                    text = "${entry.skill.name}${if (entry.builtin) "（内置）" else ""}\n${entry.skill.description}\n" +
                        "ID：${entry.id}\n来源：${entry.skill.source ?: "本地或内置"}\n提交：${entry.skill.commit ?: "无"}\n" +
                        "版本：${entry.skill.revision}\n${if (entry.skill.enabled) "单项已开启" else "单项已关闭"}" +
                        if (entry.skill.enabled && !state.enabled) "（总开关已关闭）" else ""
                })
                rows.addView(Button(activity).apply {
                    text = if (entry.skill.enabled) "关闭 ${entry.skill.name}" else "开启 ${entry.skill.name}"
                    setOnClickListener { load { management.setEnabled(entry.id, !entry.skill.enabled, entry.skill.revision) } }
                })
            }
        }
        load = load@{ operation ->
            if (!active || busy) return@load
            busy = true
            rows.isEnabled = false
            for (index in 0 until rows.childCount) rows.getChildAt(index).isEnabled = false
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = false
            status.text = "正在读取或保存…"
            worker.execute {
                val result = runCatching(operation)
                main.post {
                    if (!active || activity.isFinishing || activity.isDestroyed) return@post
                    busy = false
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = true
                    result.fold(onSuccess = { state ->
                        status.text = "设置已回读"
                        rows.isEnabled = true
                        render(state)
                    }, onFailure = { failure ->
                        status.text = if (failure is SkillConflict) "技能版本已变化，请刷新后操作；未覆盖新版本" else
                            "操作结果未确认，请刷新查看当前设置；没有自动重试"
                        // Old controls stay disabled until a successful read supplies fresh revisions.
                    })
                }
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { load { management.snapshot() } }
            load { management.snapshot() }
        }
        dialog.setOnDismissListener {
            active = false
            worker.shutdown() // An admitted write may finish; dismissing is not rollback.
            if (window === dialog) window = null
        }
        window = dialog
        dialog.show()
    }

    fun dismiss() {
        check(Looper.myLooper() == Looper.getMainLooper())
        window?.dismiss()
    }
}

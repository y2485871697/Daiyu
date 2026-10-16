package app.daiyu.android.appearance

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import app.daiyu.android.domain.appearance.IconAppearance
import app.daiyu.android.domain.appearance.IconSyncResult
import app.daiyu.android.domain.appearance.IconThemeMode
import app.daiyu.android.domain.appearance.Task17IconCatalog
import java.util.Locale

/** Standalone classic Android View. Host must supply the apply callback; no public activity edits. */
class IconAppearanceSettingsView(
    context: Context,
    initial: IconAppearance,
    private val applySelection: (IconAppearance) -> IconSyncResult,
) : LinearLayout(context) {
    private var settings = initial.normalized()
    private val preview = AppCatIconView(context)
    private val status = TextView(context)

    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        addView(TextView(context).apply { text = "应用图标" })
        preview.contentDescription = "当前应用图标预览"
        addView(preview, LayoutParams(dp(72), dp(72)))
        addSelector("明暗偏好", listOf("跟随系统", "浅色", "深色"), IconThemeMode.entries.indexOf(settings.themeMode)) { index ->
            settings = settings.copy(themeMode = IconThemeMode.entries[index]); updatePreview()
        }
        val colors = Task17IconCatalog.swatches.map { String.format(Locale.ROOT, "#%06X", it and 0xFFFFFF) }
        fun index(color: Int) = Task17IconCatalog.swatches.indexOf(Task17IconCatalog.nearest(color))
        addSelector("浅色图标背景", colors, index(settings.iconLightColor)) { i ->
            settings = settings.copy(iconLightColor = Task17IconCatalog.swatches[i]); updatePreview()
        }
        addSelector("深色图标背景", colors, index(settings.iconDarkColor)) { i ->
            settings = settings.copy(iconDarkColor = Task17IconCatalog.swatches[i]); updatePreview()
        }
        addSelector("猫咪颜色", colors, index(settings.iconCatColor)) { i ->
            settings = settings.copy(iconCatColor = Task17IconCatalog.swatches[i]); updatePreview()
        }
        addView(Button(context).apply {
            text = "保存并应用图标"
            setOnClickListener {
                try {
                    val result = applySelection(settings)
                    status.text = when {
                        result.complete -> "图标已应用；桌面显示可能由启动器延迟刷新"
                        result.launcherApplied -> "桌面图标已切换，但部分更新失败：" + result.failures.joinToString { it.stage + ": " + it.detail }
                        else -> "图标未应用，已尝试保留可启动入口：" + result.failures.joinToString { it.stage + ": " + it.detail }
                    }
                } catch (error: Exception) { status.text = "无法保存或应用图标：${error.message ?: error.javaClass.simpleName}" }
            }
        })
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        addView(status)
        updatePreview()
    }

    fun updatePreview() {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val dark = settings.themeMode == IconThemeMode.DARK || settings.themeMode == IconThemeMode.SYSTEM && night
        preview.backgroundColorValue = if (dark) settings.iconDarkColor else settings.iconLightColor
        preview.catColor = settings.iconCatColor
    }

    private fun addSelector(label: String, values: List<String>, selected: Int, change: (Int) -> Unit) {
        val title = TextView(context).apply { text = label; id = View.generateViewId() }
        addView(title)
        val spinner = Spinner(context)
        title.labelFor = View.generateViewId().also { spinner.id = it }
        spinner.contentDescription = label
        spinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, values)
        spinner.setSelection(selected)
        var lastSelection = selected
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != lastSelection) { lastSelection = position; change(position) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        addView(spinner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}

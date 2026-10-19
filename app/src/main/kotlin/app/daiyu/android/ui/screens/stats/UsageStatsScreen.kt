package app.daiyu.android.ui.screens.stats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.R
import app.daiyu.android.data.repository.ModelUsageModelUi
import app.daiyu.android.data.repository.ModelUsageSnapshot
import app.daiyu.android.data.repository.UsageStatsSnapshot
import app.daiyu.android.data.repository.RecordedUsageProjection
import app.daiyu.android.data.repository.formatReportedUsage
import app.daiyu.android.domain.usage.RecordedUsageSummary
import app.daiyu.android.data.repository.formatStatCount
import app.daiyu.android.data.repository.formatTokenCount
import app.daiyu.android.data.repository.heatmapAlpha
import app.daiyu.android.data.repository.heatmapQuartiles
import app.daiyu.android.ui.components.MiuixScaffoldPage
import app.daiyu.android.ui.haptics.TouchHaptics
import app.daiyu.android.ui.icons.daiyuIconColor
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle as JavaTextStyle
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.daiyu.android.ui.components.UsageSurface as Card
import app.daiyu.android.ui.components.UsageGlassDialog
import app.daiyu.android.ui.components.UsageAction
import app.daiyu.android.ui.components.UsageMetric
import app.daiyu.android.ui.components.UsageDivider
import app.daiyu.android.ui.components.usagePalette
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import app.daiyu.android.ui.components.UsageBody as Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class UsageStatsTab { Overview, Models }

@Composable
internal fun UsageStatsScreen(onBack: () -> Unit, load: suspend () -> UsageStatsSnapshot) {
    val context = LocalContext.current
    var stats by remember { mutableStateOf(UsageStatsSnapshot(isLoading = true)) }
    var selectedTab by remember { mutableStateOf(UsageStatsTab.Overview) }
    var loadFailed by remember { mutableStateOf(false) }
    var loadAttempt by remember { mutableStateOf(0) }

    LaunchedEffect(loadAttempt) {
        loadFailed = false
        stats = UsageStatsSnapshot(isLoading = true)
        val loaded = withContext(Dispatchers.IO) {
            try { Result.success(load()) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { Result.failure(failure) }
        }
        loadFailed = loaded.isFailure
        stats = loaded.getOrDefault(UsageStatsSnapshot())
    }

    UsageStatsSheet(
        title = stringResource(R.string.stats_page_title),
        onBack = onBack,
    ) {
        if (loadFailed) item(key = "load_error") {
            Column(Modifier.padding(16.dp)) {
                Text("暂时无法读取用量", modifier = Modifier.testTag("usage-error"))
                UsageAction(text = "重试", modifier = Modifier.testTag("usage-retry"), onClick = { loadAttempt++ })
            }
        }
        if (loadFailed) return@UsageStatsSheet
        item(key = "tabs") {
            UsageStatsTabs(
                selected = selectedTab,
                onSelect = { selectedTab = it },
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            )
        }
        if (stats.isLoading) {
            item(key = "loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    InfiniteProgressIndicator(modifier = Modifier.testTag("usage-loading"))
                }
            }
        } else if (selectedTab == UsageStatsTab.Models) {
            item(key = "model-usage") {
                RecordedUsagePane(
                    projection = stats.recordedProjection,
                    modelView = true,
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
                )
            }
        } else {
            if (stats.heatmapAvailable) item(key = "heatmap") {
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.stats_page_heatmap_title),
                            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                        )
                        ChatHeatmap(conversationsPerDay = stats.conversationsPerDay, available = stats.heatmapAvailable)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.stats_page_heatmap_less),
                                fontSize = 11.sp,
                                color = usagePalette().muted,
                            )
                            listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { alpha ->
                                HeatmapCell(alpha = alpha, sizeDp = 10)
                            }
                            Text(
                                text = stringResource(R.string.stats_page_heatmap_more),
                                fontSize = 11.sp,
                                color = usagePalette().muted,
                            )
                        }
                    }
                }
            }
            item(key = "grid") {
                StatsGrid(
                    stats = stats,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun UsageStatsSheet(title: String, onBack: () -> Unit, content: LazyListScope.() -> Unit) {
    UsageGlassDialog(title, onBack) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 430.dp).testTag("page:$title"),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        UsageDivider(Modifier.padding(top = 9.dp))
        UsageAction("关闭", onBack, Modifier.fillMaxWidth().padding(top = 10.dp))
    }
}

@Composable
private fun UsageStatsTabs(
    selected: UsageStatsTab,
    onSelect: (UsageStatsTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UsageTabButton(
            label = stringResource(R.string.stats_tab_overview),
            selected = selected == UsageStatsTab.Overview,
            onClick = { onSelect(UsageStatsTab.Overview) },
        )
        UsageTabButton(
            label = stringResource(R.string.stats_tab_models),
            selected = selected == UsageStatsTab.Models,
            onClick = { onSelect(UsageStatsTab.Models) },
        )
    }
}

@Composable
private fun RowScope.UsageTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    val colors = usagePalette()
    Box(
        modifier = Modifier
            .weight(1f)
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.accent else colors.bubble)
            .clickableNoRipple {
                TouchHaptics.click(view)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(fontSize = 11.sp),
            color = if (selected) androidx.compose.ui.graphics.Color(if (colors.dark) 0xff211735 else 0xffffffff) else colors.muted,
        )
    }
}


@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )

@Composable
private fun ModelUsagePane(
    usage: ModelUsageSnapshot,
    accountingAvailable: Boolean,
    modifier: Modifier = Modifier,
) {
    var expandedModels by remember { mutableStateOf(emptySet<String>()) }
    var startBound by remember { mutableStateOf(UsageTimeBound()) }
    var endBound by remember { mutableStateOf(UsageTimeBound()) }
    val filtered = remember(usage, startBound, endBound) {
        usage.filtered(startBound.toMillis(endOfBound = false), endBound.toMillis(endOfBound = true))
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ModelUsageFilterCard(
            start = startBound,
            end = endBound,
            onStartChange = { startBound = it },
            onEndChange = { endBound = it },
            onClear = {
                startBound = UsageTimeBound()
                endBound = UsageTimeBound()
            },
        )
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.stats_model_total_title),
                    style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_page_token_total),
                    value = if (accountingAvailable) formatTokenCount(filtered.totalTokens) else "—",
                )
                ModelMetricRow(
                    // The value excludes cache reads and writes. The hit rate divides by the
                    // full prompt, so it is not labelled "input".
                    label = stringResource(R.string.stats_page_fresh_input_tokens),
                    value = if (accountingAvailable) formatTokenCount(filtered.totalFreshInputTokens) else "—",
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_page_output_tokens),
                    value = if (accountingAvailable) formatTokenCount(filtered.totalOutputTokens) else "—",
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_page_cached_tokens),
                    value = if (accountingAvailable) formatTokenCount(filtered.totalCachedTokens) else "—",
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_page_cache_creation_tokens),
                    value = if (accountingAvailable) formatTokenCount(filtered.totalCacheCreationTokens) else "—",
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_model_cache_hit_rate),
                    value = formatCacheHitRate(filtered.totalCachedTokens, filtered.totalInputTokens),
                )
            }
        }
        if (filtered.providers.isEmpty()) {
            Text(
                text = stringResource(
                    if (startBound.isSet || endBound.isSet) {
                        R.string.stats_model_filter_empty
                    } else {
                        R.string.stats_model_empty
                    },
                ),
                style = TextStyle(fontSize = 10.sp, lineHeight = 16.sp),
                color = usagePalette().muted,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        } else {
            filtered.providers.forEach { provider ->
                Card {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = provider.name,
                                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        provider.models.forEach { model ->
                            val key = "${provider.id}/${model.id}"
                            val expanded = key in expandedModels
                            ModelUsageRow(
                                model = model,
                                expanded = expanded,
                                onToggle = {
                                    expandedModels = if (expanded) {
                                        expandedModels - key
                                    } else {
                                        expandedModels + key
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class UsageBoundSide { Start, End }

@Composable
private fun ModelUsageFilterCard(
    start: UsageTimeBound,
    end: UsageTimeBound,
    onStartChange: (UsageTimeBound) -> Unit,
    onEndChange: (UsageTimeBound) -> Unit,
    onClear: () -> Unit,
) {
    val view = LocalView.current
    var expanded by remember { mutableStateOf(false) }
    var pickerSide by remember { mutableStateOf<UsageBoundSide?>(null) }
    var selectedPreset by remember { mutableStateOf<UsageFilterPreset?>(null) }
    val matchedPreset = remember(start, end, selectedPreset) {
        matchingUsageFilterPreset(start, end, LocalDate.now(), preferred = selectedPreset)
    }
    val summary = filterSummaryText(start, end, matchedPreset)
        ?: stringResource(R.string.stats_model_filter_collapsed)
    Card {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickableNoRipple {
                        TouchHaptics.click(view)
                        expanded = !expanded
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.stats_model_filter_title),
                    style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                )
                Text(
                    text = summary,
                    style = TextStyle(fontSize = 10.sp, lineHeight = 16.sp),
                    color = usagePalette().muted,
                    maxLines = 1,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp, end = 4.dp),
                )
                androidx.compose.material3.Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = usagePalette().muted,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    UsageBoundRow(
                        label = stringResource(R.string.stats_model_filter_start),
                        bound = start,
                        onClick = {
                            TouchHaptics.click(view)
                            pickerSide = UsageBoundSide.Start
                        },
                    )
                    UsageBoundRow(
                        label = stringResource(R.string.stats_model_filter_end),
                        bound = end,
                        onClick = {
                            TouchHaptics.click(view)
                            pickerSide = UsageBoundSide.End
                        },
                    )
                    UsageFilterPresetRow(
                        selected = matchedPreset,
                        onSelect = { preset ->
                            TouchHaptics.click(view)
                            selectedPreset = preset
                            val (from, to) = usageFilterPresetRange(preset, LocalDate.now())
                            onStartChange(UsageTimeBound.from(from))
                            onEndChange(UsageTimeBound.from(to))
                        },
                    )
                    if (start.isSet || end.isSet) {
                        Text(
                            text = stringResource(R.string.stats_model_filter_clear),
                            style = TextStyle(fontSize = 10.sp, lineHeight = 16.sp),
                            color = usagePalette().accent,
                            modifier = Modifier
                                .clickableNoRipple {
                                    TouchHaptics.click(view)
                                    selectedPreset = null
                                    onClear()
                                }
                                .padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
    UsageDateTimePickerDialog(
        show = pickerSide != null,
        title = stringResource(R.string.stats_model_filter_datetime_title),
        current = if (pickerSide == UsageBoundSide.End) end else start,
        endOfBound = pickerSide == UsageBoundSide.End,
        onDismiss = { pickerSide = null },
        onConfirm = { picked ->
            selectedPreset = null
            if (pickerSide == UsageBoundSide.End) onEndChange(picked) else onStartChange(picked)
            pickerSide = null
        },
        onClear = {
            selectedPreset = null
            if (pickerSide == UsageBoundSide.End) onEndChange(UsageTimeBound()) else onStartChange(UsageTimeBound())
            pickerSide = null
        },
    )
}

@Composable
private fun UsageBoundRow(
    label: String,
    bound: UsageTimeBound,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = TextStyle(fontSize = 10.sp, lineHeight = 16.sp),
            color = usagePalette().muted,
        )
        UsageBoundChip(
            text = formatUsageDate(bound)
                ?: stringResource(R.string.stats_model_filter_datetime_placeholder),
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterStart,
            onClick = onClick,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsageFilterPresetRow(
    selected: UsageFilterPreset?,
    onSelect: (UsageFilterPreset) -> Unit,
) {
    val items = listOf(
        UsageFilterPreset.Today to R.string.stats_model_filter_preset_today,
        UsageFilterPreset.Last7Days to R.string.stats_model_filter_preset_7d,
        UsageFilterPreset.ThisWeek to R.string.stats_model_filter_preset_week,
        UsageFilterPreset.Last30Days to R.string.stats_model_filter_preset_30d,
        UsageFilterPreset.ThisMonth to R.string.stats_model_filter_preset_month,
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { (preset, labelRes) ->
            UsageBoundChip(
                text = stringResource(labelRes),
                selected = selected == preset,
                onClick = { onSelect(preset) },
            )
        }
    }
}

@Composable
private fun UsageBoundChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    contentAlignment: Alignment = Alignment.Center,
    onClick: () -> Unit,
) {
    val colors = usagePalette()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.accent else colors.bubble)
            .clickableNoRipple(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = contentAlignment,
    ) {
        Text(
            text = text,
            style = TextStyle(fontSize = 10.sp, lineHeight = 16.sp),
            color = if (selected) androidx.compose.ui.graphics.Color(if (colors.dark) 0xff211735 else 0xffffffff) else colors.muted,
            maxLines = 1,
        )
    }
}

private fun formatUsageDate(bound: UsageTimeBound): String? {
    val date = bound.date ?: return null
    return date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}

@Composable
private fun filterSummaryText(
    start: UsageTimeBound,
    end: UsageTimeBound,
    preset: UsageFilterPreset?,
): String? {
    val presetRes = when (preset) {
        UsageFilterPreset.Today -> R.string.stats_model_filter_preset_today
        UsageFilterPreset.Last7Days -> R.string.stats_model_filter_preset_7d
        UsageFilterPreset.ThisWeek -> R.string.stats_model_filter_preset_week
        UsageFilterPreset.Last30Days -> R.string.stats_model_filter_preset_30d
        UsageFilterPreset.ThisMonth -> R.string.stats_model_filter_preset_month
        null -> null
    }
    if (presetRes != null) return stringResource(presetRes)
    val startText = formatUsageDate(start)
    val endText = formatUsageDate(end)
    return when {
        startText != null && endText != null -> "$startText – $endText"
        startText != null -> startText
        endText != null -> endText
        else -> null
    }
}

@Composable
private fun ModelUsageRow(
    model: ModelUsageModelUi,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val view = LocalView.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickableNoRipple {
                    TouchHaptics.click(view)
                    onToggle()
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = model.displayName,
                style = TextStyle(fontSize = 11.sp),
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(18.dp),
                tint = usagePalette().muted,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ModelMetricRow(
                    label = stringResource(R.string.stats_page_token_total),
                    value = formatTokenCount(model.totalTokens),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_page_fresh_input_tokens),
                    value = formatTokenCount(model.freshInputTokens),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_page_output_tokens),
                    value = formatTokenCount(model.outputTokens),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_page_cached_tokens),
                    value = formatTokenCount(model.cachedTokens),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_page_cache_creation_tokens),
                    value = formatTokenCount(model.cacheCreationTokens),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_model_cache_hit_rate),
                    value = formatCacheHitRate(model.cachedTokens, model.inputTokens),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_model_daily_avg),
                    value = formatTokenCount(model.dailyAverageTokens),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_model_conversation_avg),
                    value = formatTokenCount(model.conversationAverageTokens),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_model_conversations),
                    value = formatStatCount(model.conversationCount.toLong()),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_model_active_days),
                    value = formatStatCount(model.activeDays.toLong()),
                )
            }
        }
    }
}

@Composable
private fun ModelMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = TextStyle(fontSize = 10.sp, lineHeight = 16.sp),
            color = usagePalette().muted,
        )
        Text(
            text = value,
            style = TextStyle(fontSize = 11.sp),
        )
    }
}

@Composable
private fun ChatHeatmap(conversationsPerDay: Map<LocalDate, Int>, available: Boolean) {
    val today = LocalDate.now()
    val startSunday = today
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        .minusWeeks(52)
    val numWeeks = 53
    val (q1, q2, q3) = heatmapQuartiles(conversationsPerDay.values)
    val cellSize = 11.dp
    val cellSpacing = 2.dp
    val monthLabelHeight = 14.dp
    val dowLabels = listOf(
        "",
        stringResource(R.string.stats_page_dow_mon),
        "",
        stringResource(R.string.stats_page_dow_wed),
        "",
        stringResource(R.string.stats_page_dow_fri),
        "",
    )
    val scrollState = rememberScrollState(initial = Int.MAX_VALUE)
    val colors = usagePalette()

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(
            modifier = Modifier.width(12.dp),
            verticalArrangement = Arrangement.spacedBy(cellSpacing),
        ) {
            Spacer(Modifier.height(monthLabelHeight + 2.dp))
            dowLabels.forEach { label ->
                Box(
                    modifier = Modifier.size(cellSize),
                    contentAlignment = Alignment.Center,
                ) {
                    if (label.isNotEmpty()) {
                        Text(
                            text = label,
                            fontSize = 8.sp,
                            color = colors.muted,
                        )
                    }
                }
            }
        }
        Column(
            modifier = Modifier.horizontalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(cellSpacing)) {
                for (weekIdx in 0 until numWeeks) {
                    val weekStart = startSunday.plusDays((weekIdx * 7).toLong())
                    val labelDate = (0..6)
                        .map { weekStart.plusDays(it.toLong()) }
                        .firstOrNull { it.dayOfMonth == 1 }
                    Box(
                        modifier = Modifier
                            .width(cellSize)
                            .height(monthLabelHeight),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        if (labelDate != null) {
                            Text(
                                text = if (labelDate.monthValue == 1) {
                                    labelDate.year.toString()
                                } else {
                                    labelDate.month.getDisplayName(JavaTextStyle.SHORT, LocalConfiguration.current.locales[0])
                                },
                                modifier = Modifier.wrapContentWidth(unbounded = true),
                                fontSize = 9.sp,
                                color = colors.muted,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(cellSpacing)) {
                for (weekIdx in 0 until numWeeks) {
                    Column(verticalArrangement = Arrangement.spacedBy(cellSpacing)) {
                        for (dow in 0..6) {
                            val date = startSunday.plusDays((weekIdx * 7 + dow).toLong())
                            val isFuture = date.isAfter(today)
                            val count = if (isFuture) 0 else (conversationsPerDay[date] ?: 0)
                            HeatmapCell(
                                alpha = heatmapAlpha(count, q1, q2, q3, isFuture),
                                sizeDp = cellSize.value.toInt(),
                                unknown = !available && !isFuture,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatmapCell(alpha: Float, sizeDp: Int, unknown: Boolean = false) {
    val colors = usagePalette()
    val color = when {
        unknown -> androidx.compose.ui.graphics.Color.Transparent
        alpha < 0f -> colors.bubble.copy(alpha = 0.3f)
        alpha == 0f -> colors.bubble
        else -> colors.accent.copy(alpha = alpha)
    }
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        if (unknown) Text("—", fontSize = (sizeDp - 2).coerceAtLeast(6).sp,
            color = colors.muted)
    }
}

@Composable
private fun StatsGrid(stats: UsageStatsSnapshot, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card {
            UsageMetric("当前保留会话", if (stats.currentDataAvailable) formatStatCount(stats.currentConversations.toLong()) else "—")
            UsageMetric("当前保留消息", if (stats.currentDataAvailable) formatStatCount(stats.currentMessages.toLong()) else "—")
        }
        RecordedUsagePane(stats.recordedProjection, modelView = false,
            modifier = Modifier.padding(bottom = 12.dp))
    }
}

@Composable
internal fun RecordedUsagePane(
    projection: RecordedUsageProjection?,
    modelView: Boolean,
    modifier: Modifier = Modifier,
) {
    var details by remember(modelView) { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (modelView) "未归属模型的用量" else "用量统计",
                    style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium))
                Text(when {
                    projection == null -> "暂时无法读取用量"
                    projection.combined.requestSnapshotCount == 0L -> "暂无已报告用量"
                    else -> "仅统计已记录用量，未报告的数据以 — 显示。"
                }, style = TextStyle(fontSize = 10.sp, lineHeight = 16.sp))
                if (projection != null && !projection.attributed.scanComplete)
                    Text("部分记录尚未读取，仅显示已读取的用量。", style = TextStyle(fontSize = 10.sp, lineHeight = 16.sp))
                if (modelView && projection != null && projection.combined.requestSnapshotCount > 0L)
                    Text("历史记录未包含模型信息", style = TextStyle(fontSize = 10.sp, lineHeight = 16.sp))
            }
        }
        if (projection != null && projection.combined.requestSnapshotCount > 0L) {
            // Unknown historical model is one explicitly unattributed bucket, not today's model.
            if (modelView) RecordedSummaryCard("未归属历史模型 · 主请求 + 子任务", projection.combined)
            else {
                RecordedSummaryCard("合计（不重复计缓存）", projection.combined)
                UsageAction(if (details) "▾ 详细用量" else "▸ 详细用量", { details = !details },
                    plain = true, modifier = Modifier.fillMaxWidth().testTag("stats-usage-details"))
                if (details) {
                    RecordedSummaryCard("主请求", projection.main)
                    RecordedSummaryCard("子任务（归父）", projection.child)
                }
            }
        }
    }
}

@Composable
private fun RecordedSummaryCard(title: String, summary: RecordedUsageSummary) {
    var cacheExpanded by remember(title) { mutableStateOf(false) }
    Card {
        Text(title, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium))
        UsageMetric("已记录请求", formatStatCount(summary.requestSnapshotCount), total = true)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Coverage is retained per field; never manufacture a total from partial reports.
            UsageMetric("输入（含缓存）", formatReportedUsage(summary.input))
            UsageMetric("输出", formatReportedUsage(summary.output))
        }
        UsageAction(if (cacheExpanded) "▾ 缓存明细" else "▸ 缓存明细", { cacheExpanded = !cacheExpanded },
            plain = true, modifier = Modifier.fillMaxWidth())
        if (cacheExpanded) {
            UsageMetric("缓存读取", formatReportedUsage(summary.cached))
            Text("缓存写入 / 独立推理 / 费用：—（未记录）", color = usagePalette().muted, fontSize = 8.sp)
        }
    }
}

// Retain the previous projection/UI algorithm for an explicitly attributed future source.
// Production must not consume its zero-default fields as historical provider observations.
@Composable
private fun LegacyStatsGrid(stats: UsageStatsSnapshot, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.Forum,
                label = stringResource(R.string.stats_page_total_conversations),
                value = formatCurrentLifetime(
                    current = if (stats.currentDataAvailable) formatStatCount(stats.currentConversations.toLong()) else "—",
                    lifetime = if (stats.lifetimeAccountingAvailable) formatStatCount(stats.lifetimeConversations.toLong()) else "—",
                ),
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.ChatBubbleOutline,
                label = stringResource(R.string.stats_page_total_messages),
                value = formatCurrentLifetime(
                    current = if (stats.currentDataAvailable) formatStatCount(stats.currentMessages.toLong()) else "—",
                    lifetime = if (stats.lifetimeAccountingAvailable) formatStatCount(stats.lifetimeMessages.toLong()) else "—",
                ),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.Keyboard,
                label = stringResource(R.string.stats_page_fresh_input_tokens),
                value = formatCurrentLifetime(
                    current = if (stats.tokenAccountingAvailable) formatTokenCount(stats.currentFreshInputTokens) else "—",
                    lifetime = if (stats.tokenAccountingAvailable) formatTokenCount(stats.lifetimeFreshInputTokens) else "—",
                ),
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.AutoMirrored.Rounded.Notes,
                label = stringResource(R.string.stats_page_output_tokens),
                value = formatCurrentLifetime(
                    current = if (stats.tokenAccountingAvailable) formatTokenCount(stats.currentOutputTokens) else "—",
                    lifetime = if (stats.tokenAccountingAvailable) formatTokenCount(stats.lifetimeOutputTokens) else "—",
                ),
            )
        }
        if (!stats.tokenAccountingAvailable || stats.lifetimeCachedTokens > 0) {
            StatCard(
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Rounded.Bolt,
                label = stringResource(R.string.stats_page_cached_tokens),
                value = formatCurrentLifetime(
                    current = if (stats.tokenAccountingAvailable) formatTokenCount(stats.currentCachedTokens) else "—",
                    lifetime = if (stats.tokenAccountingAvailable) formatTokenCount(stats.lifetimeCachedTokens) else "—",
                ),
            )
        }
        StatCard(
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Rounded.Add,
            label = stringResource(R.string.stats_page_cache_creation_tokens),
            value = formatCurrentLifetime(
                current = if (stats.tokenAccountingAvailable) formatTokenCount(stats.currentCacheCreationTokens) else "—",
                lifetime = if (stats.tokenAccountingAvailable) formatTokenCount(stats.lifetimeCacheCreationTokens) else "—",
            ),
        )
        StatCard(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            icon = Icons.Rounded.RocketLaunch,
            label = stringResource(R.string.stats_page_launch_count),
            value = if (stats.lifetimeAccountingAvailable) formatStatCount(stats.launchCount.toLong()) else "—",
        )
    }
}

private fun formatCurrentLifetime(current: String, lifetime: String): String =
    if (current == lifetime) current else "$current / $lifetime"

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = daiyuIconColor(icon),
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = value,
                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
            )
            Text(
                text = label,
                style = TextStyle(fontSize = 10.sp, lineHeight = 16.sp),
                color = usagePalette().muted,
            )
        }
    }
}

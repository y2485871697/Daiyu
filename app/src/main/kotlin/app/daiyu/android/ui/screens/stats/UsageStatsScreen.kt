package app.daiyu.android.ui.screens.stats

import app.daiyu.android.ui.navigation.PageBackHandler as BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import app.daiyu.android.ui.haptics.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import app.daiyu.android.ui.haptics.selectable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.R
import app.daiyu.android.data.repository.ModelUsageModelUi
import app.daiyu.android.data.repository.ModelUsageSnapshot
import app.daiyu.android.data.repository.UsageStatsSnapshot
import app.daiyu.android.data.repository.UsageTokenMetrics
import app.daiyu.android.data.repository.formatStatCount
import app.daiyu.android.data.repository.formatTokenCount
import app.daiyu.android.data.repository.heatmapAlpha
import app.daiyu.android.data.repository.heatmapQuartiles
import app.daiyu.android.ui.components.MiuixScaffoldPage
import app.daiyu.android.ui.haptics.TouchHaptics
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle as JavaTextStyle
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.daiyu.android.ui.components.UsageSurface as Card
import app.daiyu.android.ui.components.UsageAction
import app.daiyu.android.ui.components.UsageMetric
import app.daiyu.android.ui.components.usagePalette
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import app.daiyu.android.ui.components.UsageBody as Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class UsageStatsTab { Overview, Models }

@Composable
internal fun UsageStatsScreen(onBack: () -> Unit, load: suspend () -> UsageStatsSnapshot) {
    BackHandler(onBack = onBack)
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

    MiuixScaffoldPage(
        title = stringResource(R.string.stats_page_title),
        compactTitle = true,
        onBack = onBack,
        modifier = Modifier.testTag("usage-stats-screen"),
    ) {
        if (loadFailed) item(key = "load_error") {
            Column(Modifier.padding(16.dp)) {
                Text("暂时无法读取用量", modifier = Modifier.testTag("usage-error"))
                UsageAction(text = "重试", modifier = Modifier.testTag("usage-retry"), onClick = { loadAttempt++ })
            }
        }
        if (loadFailed) return@MiuixScaffoldPage
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
                ModelUsagePane(
                    stats = stats,
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
                        .testTag("usage-model-pane"),
                )
            }
        } else {
            item(key = "heatmap") {
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).testTag("usage-heatmap")) {
                    Column(
                        modifier = Modifier.padding(7.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.stats_page_heatmap_title),
                            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        )
                        ChatHeatmap(conversationsPerDay = stats.conversationsPerDay, available = stats.heatmapAvailable)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.stats_page_heatmap_less),
                                fontSize = 12.sp,
                                color = usagePalette().muted,
                            )
                            listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { alpha ->
                                HeatmapCell(alpha = alpha, sizeDp = 10)
                            }
                            Text(
                                text = stringResource(R.string.stats_page_heatmap_more),
                                fontSize = 12.sp,
                                color = usagePalette().muted,
                            )
                        }
                    }
                }
            }
            item(key = "grid") {
                StatsGrid(
                    stats = stats,
                    modifier = Modifier.padding(horizontal = 12.dp).testTag("usage-overview-grid"),
                )
            }
        }
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
            .heightIn(min = 44.dp)
            .testTag("usage-tab:$label")
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.accent else colors.bubble)
            .selectable(
                selected = selected,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                TouchHaptics.click(view)
                onClick()
            }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
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
    stats: UsageStatsSnapshot,
    modifier: Modifier = Modifier,
) {
    val usage = stats.modelUsage
    var expandedModels by remember { mutableStateOf(emptySet<String>()) }
    var startBound by remember { mutableStateOf(UsageTimeBound()) }
    var endBound by remember { mutableStateOf(UsageTimeBound()) }
    val filtered = remember(usage, startBound, endBound) {
        usage.filtered(startBound.toMillis(endOfBound = false), endBound.toMillis(endOfBound = true))
    }
    val tokens = stats.modelTokens(filtered)
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
        Card(Modifier.testTag("usage-model-total")) {
            Column(Modifier.padding(7.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.stats_model_total_title),
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium))
                UsageTokenRows(tokens)
                ModelMetricRow(stringResource(R.string.stats_model_cache_hit_rate),
                    if (stats.tokenAccountingAvailable && tokens.input != null && tokens.cached != null && tokens.input > 0L)
                        formatCacheHitRate(tokens.cached, tokens.input) else "0")
            }
        }
        if (stats.unattributedUsageRequests > 0 || stats.accountingFailure) Text(
            "仅含已知模型及日期的请求；${stats.unattributedUsageRequests} 笔历史用量未归属。" +
                (if (stats.accountingFailure) "用量保存曾失败，请查看错误日志。" else ""),
            style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp), color = usagePalette().muted,
            modifier = Modifier.testTag("usage-model-coverage"))
        if (filtered.providers.isEmpty()) {
            if (startBound.isSet || endBound.isSet) Text(
                text = stringResource(R.string.stats_model_filter_empty),
                style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
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
                                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
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
    Card(Modifier.testTag("usage-model-filter")) {
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
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                )
                Text(
                    text = summary,
                    style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
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
                            style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
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
            style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
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
            .heightIn(min = 44.dp)
            .clickableNoRipple(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = contentAlignment,
    ) {
        Text(
            text = text,
            style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
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
                style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
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
                    value = formatStatsTokens(model.totalTokens.takeIf { model.inputComplete && model.outputComplete }),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_page_fresh_input_tokens),
                    value = formatStatsTokens(model.freshInputTokens.takeIf { model.inputComplete && model.cachedComplete && model.creationComplete }),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_page_output_tokens),
                    value = formatStatsTokens(model.outputTokens.takeIf { model.outputComplete }),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_page_cached_tokens),
                    value = formatStatsTokens(model.cachedTokens.takeIf { model.cachedComplete }),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_page_cache_creation_tokens),
                    value = formatStatsTokens(model.cacheCreationTokens.takeIf { model.creationComplete }),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_model_cache_hit_rate),
                    value = if (model.cachedComplete && model.inputComplete && model.inputTokens > 0L) formatCacheHitRate(model.cachedTokens, model.inputTokens) else "0",
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_model_daily_avg),
                    value = formatStatsTokens(model.dailyAverageTokens.takeIf { model.inputComplete && model.cachedComplete && model.creationComplete }),
                )
                ModelMetricRow(
                    label = stringResource(R.string.stats_model_conversation_avg),
                    value = formatStatsTokens(model.conversationAverageTokens.takeIf { model.inputComplete && model.cachedComplete && model.creationComplete }),
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
        modifier = Modifier.fillMaxWidth().testTag("usage-model-metric:$label"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            color = usagePalette().muted,
        )
        Text(
            text = value,
            style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
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
    val unavailableDescription = stringResource(R.string.stats_page_heatmap_unavailable)

    Row(
        modifier = Modifier.testTag("usage-heatmap-grid").semantics {
            if (!available) stateDescription = unavailableDescription
        },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
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
                                modifier = Modifier.testTag("usage-heatmap-cell:$date"),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatmapCell(
    alpha: Float,
    sizeDp: Int,
    unknown: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = usagePalette()
    val color = when {
        unknown -> colors.bubble
        alpha < 0f -> colors.bubble.copy(alpha = 0.3f)
        alpha == 0f -> colors.bubble
        else -> colors.accent.copy(alpha = alpha)
    }
    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color)
            .then(if (unknown) Modifier.border(0.5.dp, colors.line, RoundedCornerShape(2.dp)) else Modifier),
    )
}

@Composable
private fun UsageTokenRows(tokens: UsageTokenMetrics) {
    ModelMetricRow(stringResource(R.string.stats_page_token_total), formatStatsTokens(tokens.total))
    ModelMetricRow("输入（含缓存）", formatStatsTokens(tokens.input))
    ModelMetricRow(stringResource(R.string.stats_page_fresh_input_tokens), formatStatsTokens(freshInput(tokens)))
    ModelMetricRow(stringResource(R.string.stats_page_output_tokens), formatStatsTokens(tokens.output))
    ModelMetricRow(stringResource(R.string.stats_page_cached_tokens), formatStatsTokens(tokens.cached))
    ModelMetricRow(stringResource(R.string.stats_page_cache_creation_tokens), formatStatsTokens(tokens.creation))
}

@Composable
private fun StatsGrid(stats: UsageStatsSnapshot, modifier: Modifier = Modifier) {
    val tokens = stats.overviewTokens
    val currentTokens = stats.currentAccountingTokens
    fun scoped(current: Long?, lifetime: Long?): String = if (currentTokens == null) formatStatsTokens(lifetime)
        else formatCurrentLifetime(formatStatsTokens(current), formatStatsTokens(lifetime))
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
                    current = if (stats.currentDataAvailable) formatStatCount(stats.currentConversations.toLong()) else "0",
                    lifetime = if (stats.lifetimeAccountingAvailable) formatStatCount(stats.lifetimeConversations.toLong()) else "0",
                ),
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.ChatBubbleOutline,
                label = stringResource(R.string.stats_page_total_messages),
                value = formatCurrentLifetime(
                    current = if (stats.currentDataAvailable) formatStatCount(stats.currentMessages.toLong()) else "0",
                    lifetime = if (stats.lifetimeAccountingAvailable) formatStatCount(stats.lifetimeMessages.toLong()) else "0",
                ),
            )
        }
        StatCard(Modifier.fillMaxWidth(), Icons.Rounded.Add,
            stringResource(R.string.stats_page_token_total), scoped(currentTokens?.total, tokens.total))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard(Modifier.weight(1f), Icons.Rounded.Keyboard, "输入（含缓存）", scoped(currentTokens?.input, tokens.input))
            StatCard(Modifier.weight(1f), Icons.AutoMirrored.Rounded.Notes,
                stringResource(R.string.stats_page_output_tokens), scoped(currentTokens?.output, tokens.output))
        }
        StatCard(Modifier.fillMaxWidth(), Icons.Rounded.Keyboard,
            stringResource(R.string.stats_page_fresh_input_tokens), scoped(currentTokens?.let(::freshInput), freshInput(tokens)))
        StatCard(Modifier.fillMaxWidth(), Icons.Rounded.Bolt,
            stringResource(R.string.stats_page_cached_tokens), scoped(currentTokens?.cached, tokens.cached))
        StatCard(Modifier.fillMaxWidth(), Icons.Rounded.Add,
            stringResource(R.string.stats_page_cache_creation_tokens), scoped(currentTokens?.creation, tokens.creation))
        if (stats.unknownConversationDates > 0 || stats.unattributedUsageRequests > 0 || stats.accountingFailure) {
            Text(buildString {
                if (stats.unknownConversationDates > 0) append("${stats.unknownConversationDates} 个旧会话缺少创建日期；热力图仅含已知日期。")
                if (stats.unattributedUsageRequests > 0) append("${stats.unattributedUsageRequests} 笔历史用量缺少模型或日期，已计入总览，不参与模型和日期筛选。")
                if (stats.accountingFailure) append("本次进程曾发生用量保存失败，请查看错误日志。")
            }, style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp), color = usagePalette().muted,
                modifier = Modifier.testTag("usage-accounting-coverage"))
        }
        StatCard(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            icon = Icons.Rounded.RocketLaunch,
            label = stringResource(R.string.stats_page_launch_count),
            value = if (stats.lifetimeAccountingAvailable) formatStatCount(stats.launchCount.toLong()) else "0",
        )
    }
}

private fun formatStatsTokens(value: Long?): String = formatTokenCount(value ?: 0L)

private fun freshInput(tokens: UsageTokenMetrics): Long? {
    val input = tokens.input ?: return null
    val cached = tokens.cached ?: return null
    val creation = tokens.creation ?: return null
    return (input - cached).coerceAtLeast(0L).let { (it - creation).coerceAtLeast(0L) }
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
    Card(modifier = modifier.testTag("usage-stat:$label")) {
        Column(
            modifier = Modifier.padding(7.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = usagePalette().toolbar,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = value,
                style = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
            )
            Text(
                text = label,
                style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
                color = usagePalette().muted,
            )
        }
    }
}

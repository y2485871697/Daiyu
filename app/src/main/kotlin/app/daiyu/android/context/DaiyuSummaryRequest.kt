package app.daiyu.android.context

import app.daiyu.android.diagnostics.RuntimeDiagnostics
import app.daiyu.android.diagnostics.RuntimeDiagnostics.Stage
import app.daiyu.android.domain.*
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.SummaryReasoningLevel
import java.util.concurrent.CancellationException

internal interface DaiyuSummaryOutputPolicy {
    val protocol: ProviderProtocol? get() = null
    val initialOutputLimit: Int
    fun retryLimit(current: Int, inputTokens: Long): Int?
    fun openSummary(history: List<Message>, tools: List<ToolDefinition>, systemPrompt: String?,
        deadline: SummaryRequestDeadline, reasoning: SummaryReasoningLevel,
        cancellation: ContextPreparationCancellation, outputLimit: Int, inputTokens: Long): ModelSession
}

/** Every attempt gets its own admitted usage identity. Only explicit provider decisions can retry. */
internal class DaiyuSummaryRequest(
    private val bound: BoundSummaryModel,
    private val cancellation: ContextPreparationCancellation,
    private val deadline: SummaryRequestDeadline,
    private val nextModel: (() -> BoundSummaryModel)?,
) {
    var attempts = 0
        private set

    fun complete(history: List<Message>, inputTokens: Long, tools: List<ToolDefinition> = emptyList(),
        systemPrompt: String? = null, initialReasoning: Int = 0): String {
        var reasoning = initialReasoning
        val lastReasoning = if (bound.outputPolicy == null && systemPrompt != null)
            minOf(1, bound.reasoningLevels.lastIndex) else bound.reasoningLevels.lastIndex
        var increased = false
        val policy = bound.outputPolicy
        var outputLimit = policy?.initialOutputLimit ?: 0
        fun checkLive() { cancellation.check(); deadline.check() }
        while (true) {
            checkLive()
            val model = if (attempts++ == 0) bound else bound.replacement(nextModel)
            val level = bound.reasoningLevels[reasoning]
            var session: ModelSession? = null
            var registration: AutoCloseable? = null
            try {
                val opened = RuntimeDiagnostics.measure(Stage.SUMMARY_OPEN, revision = attempts.toLong(), size = reasoning.toLong()) {
                    model.outputPolicy?.openSummary(history, tools, systemPrompt, deadline, level,
                        cancellation, outputLimit, inputTokens) ?: if (systemPrompt == null)
                        model.transport.openWithoutTools(history, deadline, level, cancellation)
                    else model.transport.openReplay(history, tools, systemPrompt, deadline, level, cancellation)
                }
                session = opened
                registration = cancellation.register { opened.cancel() }
                val text = linkedMapOf<String, StringBuilder>()
                RuntimeDiagnostics.measure(Stage.SUMMARY_SESSION, revision = attempts.toLong()) {
                    opened.next(emptyList()) { event ->
                        checkLive()
                        when (event) {
                            is ModelEvent.TextDelta -> text.getOrPut(event.itemId) { StringBuilder() }.append(event.text)
                            is ModelEvent.TextFinal -> text[event.itemId] = StringBuilder(event.text)
                            is ModelEvent.ToolReady -> error("摘要模型请求工具；未执行，原文保留")
                            else -> Unit
                        }
                    }
                }
                checkLive()
                return text.values.joinToString("\n").trim().also { require(it.isNotBlank()) { "摘要为空；原文保留" } }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException("摘要准备已取消").also { it.initCause(interrupted) }
            } catch (failure: ModelRequestException) {
                checkLive()
                if (nextModel == null) throw failure
                when {
                    failure.failure == ModelFailure.INCOMPLETE && failure.diagnostic?.confirmedOutputLimit == true && !increased -> {
                        outputLimit = policy?.retryLimit(outputLimit, inputTokens) ?: throw failure
                        increased = true
                    }
                    failure.failure == ModelFailure.REQUEST && failure.diagnostic?.reasoningLevelRejected == true &&
                        reasoning < lastReasoning -> reasoning++
                    else -> throw failure
                }
            } finally {
                registration?.close()
                session?.let { runCatching { it.cancel() } }
            }
        }
    }
}

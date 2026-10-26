package app.daiyu.android.gui.keepalive

// The system backend arms only after the exact service has a real system grant.
internal fun accessibilityKeepAliveAuthorized(savedUid: Int, appUid: Int, configured: Boolean): Boolean =
    appUid >= 0 && (savedUid == appUid || (savedUid == -1 && configured))

internal enum class AccessibilityConnectionStatus {
    CONNECTED,
    DISCONNECTED,
    UNKNOWN,
}

internal fun accessibilityConnectionStatus(
    protocolVersion: Int?,
    status: String?,
): AccessibilityConnectionStatus {
    if (protocolVersion != AccessibilityProtectionProtocol.VERSION) {
        return AccessibilityConnectionStatus.UNKNOWN
    }
    return when (status) {
        AccessibilityProtectionProtocol.HEALTH_STATUS_CONNECTED ->
            AccessibilityConnectionStatus.CONNECTED
        AccessibilityProtectionProtocol.HEALTH_STATUS_DISCONNECTED ->
            AccessibilityConnectionStatus.DISCONNECTED
        else -> AccessibilityConnectionStatus.UNKNOWN
    }
}

internal data class AccessibilityRepairAttempt(
    val number: Int,
    val disabledDurationMs: Long,
)

/**
 * 连续失败时最多尝试三轮，再冷却一分钟，避免服务持续崩溃时形成无限拉起循环。
 */
internal class AccessibilityRepairLimiter(
    disabledDurationsMs: LongArray = longArrayOf(500L, 1_000L, 2_000L),
    private val cooldownMs: Long = 60_000L,
) {
    private val disabledDurationsMs = disabledDurationsMs.copyOf()
    private var attempts = 0
    private var lastAttemptAt: Long? = null

    init {
        require(this.disabledDurationsMs.isNotEmpty())
        require(this.disabledDurationsMs.all { it >= 0L })
        require(cooldownMs > 0L)
    }

    @Synchronized
    fun nextAttempt(now: Long): AccessibilityRepairAttempt? {
        val lastAttempt = lastAttemptAt
        if (
            attempts >= disabledDurationsMs.size &&
            lastAttempt != null &&
            now - lastAttempt < cooldownMs
        ) {
            return null
        }
        if (attempts >= disabledDurationsMs.size) attempts = 0
        return AccessibilityRepairAttempt(
            number = attempts + 1,
            disabledDurationMs = disabledDurationsMs[attempts],
        ).also {
            attempts += 1
            lastAttemptAt = now
        }
    }

    @Synchronized
    fun reset() {
        attempts = 0
        lastAttemptAt = null
    }
}

/**
 * OEM 持续反删设置时逐步退避到 30 秒；稳定一分钟后恢复快速响应。
 */
internal class AccessibilityRestoreBackoff(
    delaysMs: LongArray = longArrayOf(300L, 1_000L, 5_000L, 30_000L),
    private val stableWindowMs: Long = 60_000L,
) {
    private val delaysMs = delaysMs.copyOf()
    private var level = 0
    private var lastRestoreAt: Long? = null

    init {
        require(this.delaysMs.isNotEmpty())
        require(this.delaysMs.all { it >= 0L })
        require(stableWindowMs > 0L)
    }

    @Synchronized
    fun delayFor(now: Long): Long {
        resetIfStable(now)
        return delaysMs[level]
    }

    @Synchronized
    fun recordRestore(now: Long) {
        resetIfStable(now)
        level = (level + 1).coerceAtMost(delaysMs.lastIndex)
        lastRestoreAt = now
    }

    @Synchronized
    fun reset() {
        level = 0
        lastRestoreAt = null
    }

    private fun resetIfStable(now: Long) {
        val previous = lastRestoreAt ?: return
        if (now >= previous && now - previous >= stableWindowMs) {
            level = 0
            lastRestoreAt = null
        }
    }
}

internal fun shouldScheduleAccessibilityHealthCheck(
    ownerUnlocked: Boolean,
    serviceConfigured: Boolean,
): Boolean = ownerUnlocked && serviceConfigured

internal fun appendAccessibilityServiceIfMissing(
    currentValue: String?,
    componentName: String,
): String? {
    val entries = accessibilityServiceEntries(currentValue)
    val targetIdentity = flattenedComponentIdentity(componentName)
    if (
        entries.any { entry ->
            entry == componentName ||
                (
                    targetIdentity != null &&
                        flattenedComponentIdentity(entry) == targetIdentity
                )
        }
    ) {
        return null
    }
    return (entries + componentName).joinToString(":")
}

internal fun removeAccessibilityServiceIfPresent(
    currentValue: String?,
    componentName: String,
): String? {
    val entries = accessibilityServiceEntries(currentValue)
    val targetIdentity = flattenedComponentIdentity(componentName)
    var removed = false
    val kept = entries.filter { entry ->
        val matches =
            entry == componentName ||
                (
                    targetIdentity != null &&
                        flattenedComponentIdentity(entry) == targetIdentity
                )
        if (matches) removed = true
        !matches
    }
    return if (removed) kept.joinToString(":") else null
}

internal fun containsAccessibilityService(
    currentValue: String?,
    componentName: String,
): Boolean {
    val targetIdentity = flattenedComponentIdentity(componentName)
    return accessibilityServiceEntries(currentValue).any { entry ->
        entry == componentName ||
            (
                targetIdentity != null &&
                    flattenedComponentIdentity(entry) == targetIdentity
                )
    }
}

private fun accessibilityServiceEntries(value: String?): List<String> =
    value.orEmpty()
        .split(':')
        .map(String::trim)
        .filter(String::isNotEmpty)

private fun flattenedComponentIdentity(value: String): Pair<String, String>? {
    val separator = value.indexOf('/')
    if (separator <= 0 || separator == value.lastIndex) return null
    val packageName = value.substring(0, separator)
    val rawClassName = value.substring(separator + 1)
    val className = if (rawClassName.startsWith('.')) {
        packageName + rawClassName
    } else {
        rawClassName
    }
    if (packageName.isBlank() || className.isBlank()) return null
    return packageName to className
}

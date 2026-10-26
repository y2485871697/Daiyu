package app.daiyu.android.gui.keepalive

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import java.util.concurrent.atomic.AtomicBoolean

/** System-server observer. No app-side privilege, task connection or periodic polling. */
internal class AccessibilityKeepAliveEnforcer(
    private val handler: Handler,
) {
    private val started = AtomicBoolean()
    private val workScheduled = AtomicBoolean()
    private val rerunRequested = AtomicBoolean()
    private val registrationRetryScheduled = AtomicBoolean()
    private val healthCheckScheduled = AtomicBoolean()
    private val repairInProgress = AtomicBoolean()
    private val restoreBackoff = AccessibilityRestoreBackoff()
    private val repairLimiter = AccessibilityRepairLimiter()
    private val activeSettingUris = mutableSetOf<Uri>()

    private var grantObserver: ContentObserver? = null
    private var activeSettingsObserver: ContentObserver? = null
    private var packageReceiver: BroadcastReceiver? = null
    private var lifecycleReceiver: BroadcastReceiver? = null
    private var grantObserverRegistered = false
    private var packageReceiverRegistered = false
    private var lifecycleReceiverRegistered = false
    private var registrationRetryIndex = 0
    private var lifecycleGeneration = 0L
    private var grantInvalidated = false

    @Volatile
    private var lastRestoreLogAt = 0L

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        if (!schedule(context, reason = "system_ready", delayMs = 0L)) {
            started.set(false)
        }
    }

    private fun reconcile(context: Context, reason: String) {
        ensureObserversRegistered(context)
        val enabled = isEnforcementEnabled(context)
        if (!enabled) {
            restoreBackoff.reset()
            repairLimiter.reset()
            return
        }
        if (repairInProgress.get()) return
        enforce(context, reason)
        if (
            shouldScheduleAccessibilityHealthCheck(
                ownerUnlocked = isOwnerUnlocked(context),
                serviceConfigured = isExpectedServiceConfigured(context),
            )
        ) {
            scheduleHealthCheck(
                context = context,
                reason = reason,
                delayMs = SERVICE_REBIND_GRACE_MS,
            )
        }
    }

    private fun ensureObserversRegistered(context: Context) {
        ensureGrantObserverRegistered(context)
        ensureActiveObserversRegistered(context)

        val controlReady =
            grantObserverRegistered
        val activeReady = activeSettingUris.size == ACTIVE_SETTING_URIS.size &&
            packageReceiverRegistered && lifecycleReceiverRegistered
        if (controlReady && activeReady) {
            registrationRetryIndex = 0
        } else {
            scheduleRegistrationRetry(context)
        }
    }

    private fun ensureGrantObserverRegistered(context: Context) {
        val observer = grantObserver ?: object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                schedule(context, "grant_changed", delayMs = 0L)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                schedule(context, "grant_changed", delayMs = 0L)
            }
        }.also { grantObserver = it }

        if (!grantObserverRegistered) {
            try {
                context.contentResolver.registerContentObserver(
                    GRANT_SETTING_URI,
                    false,
                    observer,
                )
                grantObserverRegistered = true
            } catch (failure: RuntimeException) {
                logFailure("无法监听无障碍授权记录", failure)
            }
        }

    }

    private fun ensureActiveObserversRegistered(context: Context) {
        val observer = activeSettingsObserver ?: object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                schedule(context, "accessibility_settings_changed")
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                schedule(context, "accessibility_settings_changed")
            }
        }.also { activeSettingsObserver = it }

        val resolver = context.contentResolver
        ACTIVE_SETTING_URIS.forEach { uri ->
            if (uri in activeSettingUris) return@forEach
            try {
                resolver.registerContentObserver(uri, false, observer)
                activeSettingUris += uri
            } catch (failure: RuntimeException) {
                logFailure("无法监听无障碍设置", failure)
            }
        }

        if (!packageReceiverRegistered) {
            val receiver = packageReceiver ?: object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    if (intent.data?.schemeSpecificPart != APP_PACKAGE ||
                        intent.getIntExtra(Intent.EXTRA_UID, -1) >= 100_000) return
                    try {
                        if ((intent.action == Intent.ACTION_PACKAGE_REMOVED &&
                                !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) ||
                            intent.action == Intent.ACTION_PACKAGE_DATA_CLEARED) {
                            lifecycleGeneration++
                            grantInvalidated = true
                            Settings.Global.putInt(context.contentResolver, AccessibilityProtectionProtocol.SETTING_NAME, -2)
                            restoreBackoff.reset()
                            repairLimiter.reset()
                        }
                        schedule(context, "package_changed")
                    } catch (failure: RuntimeException) {
                        logFailure("无法更新无障碍授权记录", failure)
                    }
                }
            }.also { packageReceiver = it }
            try {
                context.registerReceiver(
                    receiver,
                    IntentFilter().apply {
                        addAction(Intent.ACTION_PACKAGE_ADDED)
                        addAction(Intent.ACTION_PACKAGE_CHANGED)
                        addAction(Intent.ACTION_PACKAGE_REPLACED)
                        addAction(Intent.ACTION_PACKAGE_REMOVED)
                        addAction(Intent.ACTION_PACKAGE_DATA_CLEARED)
                        addDataScheme("package")
                    },
                    null,
                    handler,
                    Context.RECEIVER_NOT_EXPORTED,
                )
                packageReceiverRegistered = true
            } catch (failure: RuntimeException) {
                logFailure("无法监听 代鱼 包变化", failure)
            }
        }

        if (!lifecycleReceiverRegistered) {
            val receiver = lifecycleReceiver ?: object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    schedule(
                        context = receiverContext,
                        reason = when (intent.action) {
                            Intent.ACTION_USER_UNLOCKED -> "owner_unlocked"
                            Intent.ACTION_USER_PRESENT -> "owner_present"
                            Intent.ACTION_BOOT_COMPLETED -> "boot_completed"
                            else -> "locked_boot_completed"
                        },
                        delayMs = 0L,
                    )
                }
            }.also { lifecycleReceiver = it }
            try {
                context.registerReceiver(
                    receiver,
                    IntentFilter().apply {
                        addAction(Intent.ACTION_LOCKED_BOOT_COMPLETED)
                        addAction(Intent.ACTION_BOOT_COMPLETED)
                        addAction(Intent.ACTION_USER_UNLOCKED)
                        // 回到解锁态时确认一次真实连接，稳定期间不轮询。
                        addAction(Intent.ACTION_USER_PRESENT)
                    },
                    null,
                    handler,
                    Context.RECEIVER_NOT_EXPORTED,
                )
                lifecycleReceiverRegistered = true
            } catch (failure: RuntimeException) {
                logFailure("无法监听 owner 用户生命周期", failure)
            }
        }
    }

    private fun scheduleRegistrationRetry(context: Context) {
        if (
            registrationRetryIndex >= REGISTRATION_RETRY_DELAYS_MS.size ||
            !registrationRetryScheduled.compareAndSet(false, true)
        ) {
            return
        }
        val delayMs = REGISTRATION_RETRY_DELAYS_MS[registrationRetryIndex++]
        if (
            !post(delayMs) {
                registrationRetryScheduled.set(false)
                schedule(context, "observer_registration_retry", delayMs = 0L)
            }
        ) {
            registrationRetryScheduled.set(false)
            logFailure("无法调度无障碍监听注册重试")
        }
    }

    private fun scheduleHealthCheck(
        context: Context,
        reason: String,
        delayMs: Long,
    ) {
        if (!healthCheckScheduled.compareAndSet(false, true)) return
        if (
            !post(delayMs) {
                healthCheckScheduled.set(false)
                try {
                    verifyServiceConnection(context, reason)
                } catch (failure: RuntimeException) {
                    logFailure("无障碍连接检查失败", failure)
                }
            }
        ) {
            healthCheckScheduled.set(false)
            logFailure("无法调度无障碍连接检查")
        }
    }

    private fun verifyServiceConnection(
        context: Context,
        reason: String,
    ) {
        if (
            !isEnforcementEnabled(context) ||
            repairInProgress.get() ||
            !isOwnerUnlocked(context) ||
            !isExpectedServiceValid(context)
        ) {
            return
        }
        val currentServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        if (!containsAccessibilityService(currentServices, SERVICE_COMPONENT.flattenToString())) {
            schedule(context, "health_check_missing_service", delayMs = 0L)
            return
        }

        when (queryServiceConnection(context)) {
            AccessibilityConnectionStatus.CONNECTED -> repairLimiter.reset()
            AccessibilityConnectionStatus.DISCONNECTED -> {
                val attempt = repairLimiter.nextAttempt(SystemClock.elapsedRealtime())
                if (attempt == null) {
                    logFailure("无障碍服务连续重绑失败，已进入冷却")
                } else {
                    beginServiceRepair(context, attempt)
                }
            }
            AccessibilityConnectionStatus.UNKNOWN ->
                logFailure("无法确认无障碍服务连接状态")
        }
    }

    private fun queryServiceConnection(context: Context): AccessibilityConnectionStatus {
        val response = try {
            context.contentResolver.call(
                AccessibilityProtectionProtocol.HEALTH_URI,
                AccessibilityProtectionProtocol.HEALTH_METHOD,
                null,
                AccessibilityProtectionProtocol.request(),
            )
        } catch (failure: RuntimeException) {
            logFailure("无障碍健康检查 Provider 调用失败", failure)
            return AccessibilityConnectionStatus.UNKNOWN
        }
        return accessibilityConnectionStatus(
            protocolVersion = response?.getInt(
                AccessibilityProtectionProtocol.EXTRA_PROTOCOL_VERSION,
                -1,
            ),
            status = response?.getString(AccessibilityProtectionProtocol.HEALTH_STATUS),
        )
    }

    private fun beginServiceRepair(
        context: Context,
        attempt: AccessibilityRepairAttempt,
    ) {
        if (!repairInProgress.compareAndSet(false, true)) return
        var queued = false
        var removed = false
        try {
            val resolver = context.contentResolver
            val servicesWithoutTarget = removeLatestAccessibilitySetting(resolver) ?: return
            removed = Settings.Secure.putString(resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, servicesWithoutTarget)
            if (!removed) return
            val generation = lifecycleGeneration
            queued = post(attempt.disabledDurationMs) {
                completeServiceRepair(context, attempt, generation)
            }
        } catch (failure: RuntimeException) {
            logFailure("无法临时重绑代鱼无障碍服务", failure)
        } finally {
            if (!queued) {
                repairInProgress.set(false)
                // A confirmed removal must not be left behind when Handler rejects its continuation.
                if (removed) try { enforce(context, "repair_schedule_failed") }
                catch (failure: RuntimeException) { logFailure("无法恢复重绑设置", failure) }
            }
        }
    }

    private fun completeServiceRepair(
        context: Context,
        attempt: AccessibilityRepairAttempt,
        generation: Long,
    ) {
        try {
            if (generation != lifecycleGeneration) return
            if (isEnforcementEnabled(context) && isExpectedServiceValid(context)) {
                enforce(context, "repair_${attempt.number}")
            }
        } catch (failure: RuntimeException) {
            logFailure("无法重新启用 代鱼 无障碍服务", failure)
        } finally {
            repairInProgress.set(false)
        }
        if (isEnforcementEnabled(context)) {
            scheduleHealthCheck(
                context = context,
                reason = "repair_${attempt.number}_confirmation",
                delayMs = SERVICE_REBIND_GRACE_MS,
            )
        }
    }

    private fun schedule(
        context: Context,
        reason: String,
        delayMs: Long? = null,
    ): Boolean {
        rerunRequested.set(true)
        if (!workScheduled.compareAndSet(false, true)) return true
        val resolvedDelayMs = delayMs ?: restoreBackoff.delayFor(SystemClock.elapsedRealtime())
        val posted = post(resolvedDelayMs) {
            rerunRequested.set(false)
            try {
                reconcile(context, reason)
            } catch (failure: RuntimeException) {
                logFailure("无障碍保护校验失败", failure)
            } finally {
                workScheduled.set(false)
                if (rerunRequested.get()) schedule(context, "late_change")
            }
        }
        if (!posted) {
            workScheduled.set(false)
            logFailure("无法调度无障碍保护校验")
        }
        return posted
    }

    private fun enforce(context: Context, reason: String) {
        if (!isEnforcementEnabled(context) || !isExpectedServiceValid(context)) return
        val resolver = context.contentResolver
        val mergedServices = mergeLatestAccessibilitySetting(resolver)
        var restoredServices = false
        var restoredMasterSwitch = false
        if (mergedServices != null) {
            restoredServices = Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                mergedServices,
            )
            if (!restoredServices) {
                logFailure("无法恢复无障碍服务列表")
            }
        }
        val serviceIsEnabled = mergedServices == null || restoredServices
        if (
            serviceIsEnabled &&
            Settings.Secure.getInt(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                DISABLED,
            ) != ENABLED
        ) {
            restoredMasterSwitch = Settings.Secure.putInt(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                ENABLED,
            )
            if (!restoredMasterSwitch) {
                logFailure("无法恢复无障碍总开关")
            }
        }
        if (restoredServices || restoredMasterSwitch) {
            restoreBackoff.recordRestore(SystemClock.elapsedRealtime())
            logRestore(reason, restoredServices, restoredMasterSwitch)
        }
    }

    private fun isEnforcementEnabled(context: Context): Boolean {
        val resolver = context.contentResolver
        val configured = isExpectedServiceConfigured(context)
        val saved = Settings.Global.getInt(resolver, AccessibilityProtectionProtocol.SETTING_NAME, -1)
        // Invalidation is sticky until a disabled system state has been observed, even across boot.
        if (grantInvalidated || saved == -2) {
            if (!isExpectedServiceListed(context) && Settings.Global.putInt(resolver, AccessibilityProtectionProtocol.SETTING_NAME, -1)) {
                grantInvalidated = false
            }
            return false
        }
        val info = serviceInfo(context) ?: return false
        val uid = info.applicationInfo.uid
        if (saved >= 0 && saved != uid) {
            grantInvalidated = true
            Settings.Global.putInt(resolver, AccessibilityProtectionProtocol.SETTING_NAME, -2)
            return false
        }
        if (!accessibilityKeepAliveAuthorized(saved, uid, configured)) return false
        if (saved == uid) return true
        return Settings.Global.putInt(resolver, AccessibilityProtectionProtocol.SETTING_NAME, uid)
    }

    private fun isOwnerUnlocked(context: Context): Boolean = try {
        context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
    } catch (failure: RuntimeException) {
        logFailure("无法读取 owner 用户状态", failure)
        false
    }

    private fun isExpectedServiceConfigured(context: Context): Boolean {
        val resolver = context.contentResolver
        if (
            Settings.Secure.getInt(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                DISABLED,
            ) != ENABLED
        ) {
            return false
        }
        return isExpectedServiceListed(context)
    }

    private fun isExpectedServiceListed(context: Context): Boolean =
        containsAccessibilityService(
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ),
            SERVICE_COMPONENT.flattenToString(),
        )

    /**
     * Settings 没有公开 CAS；缺失时再读一次最新快照，尽量不覆盖同时启用的其他服务。
     */
    private fun mergeLatestAccessibilitySetting(resolver: ContentResolver): String? {
        val initialValue = Settings.Secure.getString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        if (
            appendAccessibilityServiceIfMissing(
                initialValue,
                SERVICE_COMPONENT.flattenToString(),
            ) == null
        ) {
            return null
        }
        return appendAccessibilityServiceIfMissing(
            Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ),
            SERVICE_COMPONENT.flattenToString(),
        )
    }

    private fun removeLatestAccessibilitySetting(resolver: ContentResolver): String? {
        val initialValue = Settings.Secure.getString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        if (
            removeAccessibilityServiceIfPresent(
                initialValue,
                SERVICE_COMPONENT.flattenToString(),
            ) == null
        ) {
            return null
        }
        return removeAccessibilityServiceIfPresent(
            Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ),
            SERVICE_COMPONENT.flattenToString(),
        )
    }

    private fun isExpectedServiceValid(context: Context): Boolean = serviceInfo(context) != null

    private fun serviceInfo(context: Context): ServiceInfo? {
        val info = try {
            @Suppress("DEPRECATION")
            context.packageManager.getServiceInfo(SERVICE_COMPONENT, directBootFlags())
        } catch (_: PackageManager.NameNotFoundException) { return null }
        return info.takeIf {
            it.packageName == APP_PACKAGE && it.name == SERVICE_CLASS && it.enabled &&
                it.applicationInfo?.enabled == true &&
                it.applicationInfo.uid in 0 until 100_000 &&
                it.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_STOPPED == 0 &&
                it.permission == Manifest.permission.BIND_ACCESSIBILITY_SERVICE
        }
    }

    private fun directBootFlags(): Int =
        PackageManager.MATCH_DIRECT_BOOT_AWARE or
            PackageManager.MATCH_DIRECT_BOOT_UNAWARE

    private fun post(delayMs: Long = 0L, block: () -> Unit): Boolean =
        try {
            handler.postDelayed({
                try { block() }
                catch (failure: RuntimeException) { logFailure("无障碍后台操作失败", failure) }
            }, delayMs)
        } catch (failure: RuntimeException) {
            logFailure("无障碍保护后台 Handler 拒绝任务", failure)
            false
        }

    private var lastFailureLogAt: Long? = null
    private fun logFailure(message: String, failure: Throwable? = null) {
        val now = SystemClock.elapsedRealtime()
        if (lastFailureLogAt?.let { now - it < LOG_INTERVAL_MS } == true) return
        lastFailureLogAt = now
        android.util.Log.w("DaiyuAccessibility", if (failure == null) message else "$message: ${failure.javaClass.simpleName}")
    }

    private fun logRestore(
        reason: String,
        restoredServices: Boolean,
        restoredMasterSwitch: Boolean,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (lastRestoreLogAt != 0L && now - lastRestoreLogAt < LOG_INTERVAL_MS) return
        lastRestoreLogAt = now
        android.util.Log.i("DaiyuAccessibility",
            "已恢复 代鱼 无障碍: reason=$reason " +
                "serviceList=$restoredServices masterSwitch=$restoredMasterSwitch",
        )
    }

    private companion object {
        const val APP_PACKAGE = AccessibilityProtectionProtocol.APP_PACKAGE
        const val SERVICE_CLASS = AccessibilityProtectionProtocol.SERVICE_CLASS
        const val DISABLED = 0
        const val ENABLED = 1
        const val LOG_INTERVAL_MS = 10_000L
        const val SERVICE_REBIND_GRACE_MS = 4_000L
        val REGISTRATION_RETRY_DELAYS_MS = longArrayOf(1_000L, 5_000L, 30_000L)
        val SERVICE_COMPONENT = ComponentName(APP_PACKAGE, SERVICE_CLASS)
        val GRANT_SETTING_URI =
            Settings.Global.getUriFor(AccessibilityProtectionProtocol.SETTING_NAME)
        val ACTIVE_SETTING_URIS = listOf(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
        )
    }
}

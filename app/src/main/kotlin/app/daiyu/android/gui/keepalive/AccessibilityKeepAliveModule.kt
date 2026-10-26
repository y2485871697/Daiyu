package app.daiyu.android.gui.keepalive

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/** The only injected scope is system_server; ordinary app startup never loads this class. */
class AccessibilityKeepAliveModule : XposedModule() {
    private var enforcer: AccessibilityKeepAliveEnforcer? = null
    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        try {
            val type = Class.forName("com.android.server.SystemServer", false, param.classLoader)
            val methods = type.declaredMethods.filter { it.name == "startOtherServices" }
            for (method in methods) hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("daiyu.accessibility.keepalive.${method.parameterCount}")
                .intercept { chain ->
                    val result = chain.proceed()
                    start(chain.getThisObject())
                    result
                }
        } catch (failure: Exception) {
            Log.w("DaiyuAccessibility", "System hook unavailable: ${failure.javaClass.simpleName}")
        }
    }
    @Synchronized private fun start(owner: Any?) {
        if (enforcer != null || owner == null) return
        var worker: HandlerThread? = null
        try {
            val context = systemContext(owner) ?: return
            val thread = HandlerThread("DaiyuAccessibility", Process.THREAD_PRIORITY_BACKGROUND)
            worker = thread
            thread.start()
            val handler = Handler(checkNotNull(thread.looper))
            val value = AccessibilityKeepAliveEnforcer(handler)
            if (!value.start(context)) return
            enforcer = value
        } catch (failure: Exception) {
            Log.w("DaiyuAccessibility", "System backend unavailable: ${failure.javaClass.simpleName}")
        } finally {
            if (enforcer == null) worker?.quitSafely()
        }
    }
    private fun systemContext(owner: Any): Context? {
        var type: Class<*>? = owner.javaClass
        while (type != null) {
            val current = type
            for (name in arrayOf("mSystemContext", "mContext")) {
                val context = runCatching { current.getDeclaredField(name).apply { isAccessible = true }.get(owner) as? Context }.getOrNull()
                if (context != null) return context
            }
            type = current.superclass
        }
        return null
    }
}

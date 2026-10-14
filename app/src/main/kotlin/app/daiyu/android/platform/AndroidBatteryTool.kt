package app.daiyu.android.platform

import android.app.ActivityManager
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import app.daiyu.android.domain.*
import org.json.JSONObject

class AndroidBatteryTool(private val context:Context,private val enabled:Boolean):BatteryTool {
    override fun execute(invocation:ToolInvocation):String {
        if(invocation.name!=ReadOnlyTool.BATTERY.toolName) return error("unknown_tool")
        if(!emptyArguments(invocation.arguments)) return error("invalid_arguments")
        if(!enabled) return error("capability_disabled")
        val intent=context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return error("battery_unavailable")
        val level=intent.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);val scale=intent.getIntExtra(BatteryManager.EXTRA_SCALE,-1)
        if(level<0 || scale<=0 || level>scale) return error("invalid_battery_reading")
        val status=intent.getIntExtra(BatteryManager.EXTRA_STATUS,-1)
        return JSONObject().put("ok",true).put("percent",level*100/scale).put("charging",status==BatteryManager.BATTERY_STATUS_CHARGING || status==BatteryManager.BATTERY_STATUS_FULL).put("observed_at",System.currentTimeMillis()).toString()
    }
    private fun error(code:String)=JSONObject().put("ok",false).put("error",code).toString()
}

private val emptyObject=Regex("[ \t\r\n]*\\{[ \t\r\n]*\\}[ \t\r\n]*")
private fun emptyArguments(value:String)=emptyObject.matches(value)

class AndroidReadOnlyTools(private val context:Context,allowed:Set<ReadOnlyTool>):BatteryTool {
    private val allowed=allowed.toSet()
    override fun execute(invocation:ToolInvocation):String {
        val definition=ReadOnlyTool.entries.firstOrNull {it.toolName==invocation.name} ?: return error("unknown_tool")
        if(!emptyArguments(invocation.arguments)) return error("invalid_arguments")
        if(definition !in allowed) return error("capability_disabled")
        return when(definition) {
            ReadOnlyTool.BATTERY -> AndroidBatteryTool(context,true).execute(invocation)
            ReadOnlyTool.DEVICE_STATUS -> deviceStatus(invocation)
        }
    }
    private fun deviceStatus(invocation:ToolInvocation):String {
        val result=JSONObject().put("ok",true).put("observed_at",System.currentTimeMillis())
            .put("android",JSONObject().put("api_level",Build.VERSION.SDK_INT).put("release",Build.VERSION.RELEASE))
            .put("uptime_ms",SystemClock.elapsedRealtime())
        result.put("battery",sample {
            JSONObject(AndroidBatteryTool(context,true).execute(invocation.copy(name=ReadOnlyTool.BATTERY.toolName)))
        })
        result.put("memory",sample {
            val manager=context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return@sample errorObject("unavailable")
            val info=ActivityManager.MemoryInfo();manager.getMemoryInfo(info)
            if(info.totalMem<=0 || info.availMem<0 || info.availMem>info.totalMem) return@sample errorObject("invalid_reading")
            JSONObject().put("ok",true).put("total_bytes",info.totalMem).put("available_bytes",info.availMem).put("low_memory",info.lowMemory)
        })
        result.put("storage",sample {
            val stat=StatFs(context.filesDir.absolutePath)
            if(stat.totalBytes<=0 || stat.availableBytes<0 || stat.availableBytes>stat.totalBytes) return@sample errorObject("invalid_reading")
            JSONObject().put("ok",true).put("scope","app_data_volume").put("total_bytes",stat.totalBytes).put("available_bytes",stat.availableBytes)
        })
        return result.toString()
    }
    private fun sample(read:()->JSONObject):JSONObject=try {read()} catch (_:SecurityException) {errorObject("permission_denied")} catch (_:RuntimeException) {errorObject("unavailable")}
    private fun errorObject(code:String)=JSONObject().put("ok",false).put("error",code)
    private fun error(code:String)=errorObject(code).toString()
}

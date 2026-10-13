package app.daiyu.android.platform

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import app.daiyu.android.domain.*
import org.json.JSONObject

class AndroidBatteryTool(private val context:Context,private val enabled:Boolean):BatteryTool {
    override fun execute(invocation:ToolInvocation):String {
        if(invocation.name!="get_battery_status") return error("unknown_tool")
        val args=try {JSONObject(invocation.arguments)} catch (_:Exception) {return error("invalid_arguments")}
        if(args.length()!=0) return error("invalid_arguments")
        if(!enabled) return error("capability_disabled")
        val intent=context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return error("battery_unavailable")
        val level=intent.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);val scale=intent.getIntExtra(BatteryManager.EXTRA_SCALE,-1)
        if(level<0 || scale<=0 || level>scale) return error("invalid_battery_reading")
        val status=intent.getIntExtra(BatteryManager.EXTRA_STATUS,-1)
        return JSONObject().put("ok",true).put("percent",level*100/scale).put("charging",status==BatteryManager.BATTERY_STATUS_CHARGING || status==BatteryManager.BATTERY_STATUS_FULL).put("observed_at",System.currentTimeMillis()).toString()
    }
    private fun error(code:String)=JSONObject().put("ok",false).put("error",code).toString()
}

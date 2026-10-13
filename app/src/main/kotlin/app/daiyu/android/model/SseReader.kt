package app.daiyu.android.model

import java.io.BufferedReader

object SseReader {
    fun read(reader:BufferedReader, consume:(String)->Boolean) {
        val data=StringBuilder()
        while(true) {
            val line=reader.readLine() ?: break
            require(line.length<=1_048_576) {"流式事件过大"}
            if(line.isEmpty()) {
                if(data.isNotEmpty()) {if (!consume(data.toString().removeSuffix("\n"))) return;data.setLength(0)}
            } else if(line.startsWith("data:")) {
                val value=line.substring(5).removePrefix(" ")
                require(data.length+value.length<=1_048_576) {"流式事件过大"}
                data.append(value).append('\n')
            }
        }
        // An unterminated frame is not a confirmed event.
        require(data.isEmpty()) {"流式事件未完整结束"}
    }
}

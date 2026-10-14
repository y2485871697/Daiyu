package app.daiyu.android.model

import java.io.BufferedReader
import app.daiyu.android.domain.ModelRequestException
import app.daiyu.android.domain.ModelFailure

object SseReader {
    fun read(reader:BufferedReader, consume:(String)->Boolean) {
        val data=StringBuilder()
        while(true) {
            val line=reader.readLine() ?: break
            if(line.length>1_048_576) throw ModelRequestException(ModelFailure.PROTOCOL)
            if(line.isEmpty()) {
                if(data.isNotEmpty()) {if (!consume(data.toString().removeSuffix("\n"))) return;data.setLength(0)}
            } else if(line.startsWith("data:")) {
                val value=line.substring(5).removePrefix(" ")
                if(data.length+value.length>1_048_576) throw ModelRequestException(ModelFailure.PROTOCOL)
                data.append(value).append('\n')
            }
        }
        // An unterminated frame is not a confirmed event.
        if(data.isNotEmpty()) throw ModelRequestException(ModelFailure.INCOMPLETE)
    }
}

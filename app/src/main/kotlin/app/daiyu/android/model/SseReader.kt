package app.daiyu.android.model

import java.io.BufferedReader
import app.daiyu.android.domain.ModelRequestException
import app.daiyu.android.domain.ModelFailure

object SseReader {
    fun read(reader:BufferedReader, consume:(String)->Boolean) {
        val data=StringBuilder()
        var firstCharacter = true
        var skipLf = false
        fun boundedLine(): String? {
            val line = StringBuilder()
            while (true) {
                val next = reader.read()
                if (next == -1) return if (line.isEmpty()) null else line.toString()
                if (firstCharacter) {
                    firstCharacter = false
                    if (next == 0xFEFF) continue
                }
                if (skipLf) {
                    skipLf = false
                    if (next == '\n'.code) continue
                }
                if (next == '\r'.code) { skipLf = true; return line.toString() }
                if (next == '\n'.code) return line.toString()
                if (line.length == 1_048_576) throw ModelRequestException(ModelFailure.PROTOCOL)
                line.append(next.toChar())
            }
        }
        while(true) {
            val line=boundedLine() ?: break
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

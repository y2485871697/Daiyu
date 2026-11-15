package app.daiyu.android.model.generation

import org.json.JSONObject
import java.text.Normalizer

/** Deterministic output-setting extraction shared by direct image chat and image workers. */
internal object NaturalImagePromptOptions {
    private data class Candidate(val key: String, val value: String, val range: IntRange, val requestCount: Boolean = false)
    private val flags = RegexOption.IGNORE_CASE
    private const val NUMBER = "[0-9一二两三四五六七八九十]+"
    private val ratio = Regex("(?<![\\dA-Za-z_.:/])([1-9][0-9]{0,3}(?:\\.[0-9]{1,2})?)\\s*[:：比]\\s*([1-9][0-9]{0,3}(?:\\.[0-9]{1,2})?)(?![\\d.:：/])")
    private val tier = Regex("(?<![\\dA-Za-z_.])([1-9][0-9]?(?:\\.5)?)\\s*k(?![A-Za-z0-9_.])",flags)
    private val size = Regex("(?<![\\dA-Za-z_.])([1-9][0-9]{0,4})\\s*(?:[xX×*]|乘以|乘)\\s*([1-9][0-9]{0,4})(?![\\dA-Za-z_.])")
    private val count = Regex("(?:生成|绘制|制作|出图|画|来|给我)\\s*(?:(?:一共|总共|共|出|给我)\\s*)?($NUMBER)\\s*[张幅](?!床|桌|书桌|餐桌|木桌|椅|脸|嘴|牌|票|卡|纸|钞)")
    private val namedCount = Regex("(?:(?:设置|输出|出图|图片|生成)(?:张数|数量)|出图数|图片数|(?<![\\p{L}\\p{N}_])(?:张数|数量)|\\bn)\\s*(?:设为|设置为|为|是|=|:)?\\s*($NUMBER)(?![0-9A-Za-z.])",flags)
    private val englishCount = Regex("\\b(?:generate|draw|create)\\s+(one|two|three|four|five|six|seven|eight|nine|ten|[0-9]+)\\s+(?:images?|pictures?|photos?|illustrations?)\\b",flags)
    private val concurrency = Regex("(?:并发(?:数|度)?|\\bconcurrency)\\s*(?:设为|设置为|为|是|=|:)?\\s*($NUMBER)(?![0-9A-Za-z.])",flags)
    private val label = Regex("(?:比例|宽高比|画幅|分辨率|清晰度|画质|尺寸|大小|输出|成图|目标|aspect[_ ]?ratio|resolution|size)\\s*(?:设为|设置为|为|是|用|=|:)?\\s*$",flags)
    private val negative = Regex("不要|不需要|不是|不用|别用|非|排除|避免|not\\b|don't\\b|without\\b",flags)
    private val reference = Regex("参考图|原图|输入图|原始尺寸|原尺寸|原比例|原分辨率|旧图|reference|original",flags)
    private val example = Regex("例如|比如|示例|举例|example|e\\.g\\.",flags)
    private val literal = Regex("写着|写上|写出|印着|印上|标着|标上|文字|文本|字符串|字样|字幕|文字内容|显示|write|text|caption|says",flags)
    private val timeOrScore = Regex("时钟|钟表|时间|闹钟|上午|下午|早上|晚上|凌晨|中午|开会|会议|比分|比赛|得分|配比|头身|clock|time|score|meeting|[ap]m\\b",flags)
    private val delimiters = setOf(',', '，', '、', ';', '；', '\n', '。', '!', '?', '！', '？')

    fun parse(prompt: String, overrides: ImageGenerationOptions = ImageGenerationOptions()): ImageGenerationOptions {
        val overridden = overrides.toJson().keys().asSequence().toMutableSet()
        if (overrides.size != null) overridden += setOf("aspect_ratio", "resolution")
        else if (overrides.aspectRatio != null || overrides.resolution != null) overridden += "size"
        val source = Normalizer.normalize(prompt,Normalizer.Form.NFKC)
        val searchable = maskLiterals(source)
        val candidates = mutableListOf<Candidate>()
        Regex("([一二三四五六七八九十]+)比([一二三四五六七八九十]+)").findAll(searchable).forEach {
            candidates += Candidate("aspect_ratio", "${integer(it.groupValues[1])}:${integer(it.groupValues[2])}", it.range)
        }
        Regex("宽(?:度)?\\s*(?:为|是|=|:)?\\s*([1-9][0-9]{1,4})\\s*(?:像素|px)?\\s*[,，、]?\\s*高(?:度)?\\s*(?:为|是|=|:)?\\s*([1-9][0-9]{1,4})(?![0-9])",flags)
            .findAll(searchable).forEach { candidates += Candidate("size", "${it.groupValues[1]}x${it.groupValues[2]}",it.range) }
        ratio.findAll(searchable).forEach { candidates += Candidate("aspect_ratio", "${it.groupValues[1]}:${it.groupValues[2]}",it.range) }
        tier.findAll(searchable).forEach { candidates += Candidate("resolution", ImageResolutionTier.normalize("${it.groupValues[1]}k"),it.range) }
        Regex("(超高|低|中|高)\\s*(?:分辨率|清晰度|画质)|(?:分辨率|清晰度|画质)\\s*(?:设为|设置为|为|是|=|:)?\\s*(超高|低|中|高)|\\b(low|medium|high|ultra)[ -]+resolution\\b", flags)
            .findAll(searchable).forEach {
                val value = it.groupValues.drop(1).first { part -> part.isNotBlank() }
                candidates += Candidate("resolution", ImageResolutionTier.normalize(value), it.range)
            }
        size.findAll(searchable).forEach { candidates += Candidate("size", "${it.groupValues[1]}x${it.groupValues[2]}",it.range) }
        listOf(count to "n", namedCount to "n", englishCount to "n", concurrency to "concurrency").forEach { (pattern,key) ->
            pattern.findAll(searchable).forEach { match ->
                // Interpret numbers only after context/override filtering: a quoted or
                // superseded value must not block otherwise valid output settings.
                candidates += Candidate(key,match.groupValues[1],match.range, pattern === count || pattern === englishCount)
            }
        }
        val accepted = mutableListOf<Candidate>()
        val rejected = mutableSetOf<String>()
        for (candidate in candidates.sortedBy { it.range.first }) {
            if (candidate.key in overridden) continue
            val start = candidate.range.first; val end = candidate.range.last + 1
            val left = searchable.substring(0,start).indexOfLast { it in delimiters } + 1
            val right = searchable.indexOfFirstFrom(end) { it in delimiters }.let { if (it < 0) searchable.length else it }
            val before = searchable.substring(left,start)
            val after = searchable.substring(end,right)
            val segment = searchable.substring(left,right)
            if (candidate.requestCount && !Regex("^(?:\\s|请|帮我|给我|麻烦|能否|能不能|可以|再|先|同时|然后|我想|我要|为我|不要|不需要|不是|不用|别用|please|could you|can you)*$",flags).matches(before)) continue
            if (candidate.key == "n" && Regex("^.{0,8}(?:书桌|餐桌|桌子|木桌|床|椅子|脸|嘴|卡牌|白纸)").containsMatchIn(after) &&
                !Regex("图片|照片|图像|插画|海报").containsMatchIn(after)) continue
            val explicit = label.containsMatchIn(before) || Regex("^\\s*(?:的)?(?:比例|画幅|分辨率|画质|像素|px\\b)",flags).containsMatchIn(after)
            val output = Regex("(?:输出|成图|目标|最终|生成图)(?:尺寸|大小|比例|分辨率)?\\s*(?:为|是|=|:)?\\s*$").containsMatchIn(before)
            val prefix = before.takeLast(40)
            val unwantedText = Regex("(?:不要|不带|无|without|no)\\s*(?:文字|文本|text)",flags).containsMatchIn(before)
            if ((reference.containsMatchIn(before) && !output) || (literal.containsMatchIn(before) && !output && !unwantedText)) continue
            if (candidate.key == "aspect_ratio" && !output && Regex("头身|身材|人体|配比").containsMatchIn(segment)) continue
            if (candidate.key == "aspect_ratio" && !explicit && timeOrScore.containsMatchIn(segment)) continue
            if (candidate.key == "resolution" && !explicit && Regex("预算|价格|工资|薪|收入|粉丝|播放量|金额|人民币|美元|元|budget|salary",flags).containsMatchIn(segment)) continue
            if (candidate.key == "size" && !explicit && Regex("^\\s*(?:厘米|毫米|米|英寸|cm\\b|mm\\b|m\\b)",flags).containsMatchIn(after)) continue
            if (candidate.key == "size" && !explicit && candidate.value.split('x').any { it.toInt() < 64 }) continue
            if (example.containsMatchIn(prefix)) { rejected += candidate.key; continue }
            val negation = negative.findAll(prefix).lastOrNull()
            if (negation != null) {
                val tail = prefix.substring(negation.range.last+1)
                val scope = Regex("^(?:\\s|用|采用|设为|设置成|尺寸|比例|画幅|分辨率|画质|输出|生成|的|[:=])*$")
                if (scope.matches(tail)) { rejected += candidate.key; continue }
            }
            accepted += candidate
        }
        // An example can scope comma-separated values. Require a new explicit output instruction.
        if (example.containsMatchIn(searchable) && accepted.isNotEmpty() &&
            !Regex("(?:输出|生成|画)(?:设置|参数|尺寸|比例|分辨率)?\\s*(?:为|用|设为|:)",flags).containsMatchIn(searchable))
            ImageGenerationOptions.invalid("示例中的参数不能作为本次输出设置，请明确本次比例、尺寸或使用 image_options。")
        if ((rejected.any { it in setOf("size","aspect_ratio","resolution") } &&
                accepted.none { it.key in setOf("size","aspect_ratio","resolution") }) ||
            rejected.any { key -> key in setOf("n","concurrency") && accepted.none { it.key == key } })
            ImageGenerationOptions.invalid("只识别到被否定或作为示例的生图参数，请明确本次输出设置；不会静默生成默认方图。")
        val values = JSONObject()
        for (candidate in accepted) {
            val value: Any = if (candidate.key in setOf("n","concurrency")) integer(candidate.value) else candidate.value
            if (values.has(candidate.key) && values.get(candidate.key) != value)
                ImageGenerationOptions.invalid("出现多个不同的 ${candidate.key} 设置，请只保留目标值或使用 image_options。")
            values.put(candidate.key,value)
        }
        // A labeled value which failed extraction must not silently become a default request.
        val parameterLabels = mapOf("size" to "输出尺寸|图片尺寸|尺寸|size", "aspect_ratio" to "宽高比|画幅|比例|aspect_ratio",
            "resolution" to "分辨率|画质|resolution", "n" to "(?:设置|输出|出图|图片|生成)(?:张数|数量)|出图数|图片数|(?<![\\p{L}\\p{N}_])(?:张数|数量)|\\bn", "concurrency" to "并发(?:数|度)?|concurrency")
        for (clause in separatorClauses(searchable)) {
            if (literal.containsMatchIn(clause) || reference.containsMatchIn(clause) || Regex("头身|身材|人体|配比").containsMatchIn(clause)) continue
            parameterLabels.forEach { (key,names) ->
                if (key !in overridden && !values.has(key) && Regex("(?:$names)\\s*(?:为|是|=|:)?\\s*[-0-9零]",flags).containsMatchIn(clause))
                    ImageGenerationOptions.invalid("检测到 $key 设置但无法识别，请使用明确数值；不会忽略后生成。")
            }
            val malformedConcurrency = "concurrency" !in overridden &&
                Regex("(?:并发(?:数|度)?|concurrency)\\s*(?:为|是|=|:)?\\s*(?:-[0-9]|[0-9]+\\.[0-9])",flags).containsMatchIn(clause)
            val malformedCount = "n" !in overridden && (
                Regex("(?:张数|出图数|图片数|(?<![\\p{L}\\p{N}_])数量|\\bn)\\s*(?:为|是|=|:)?\\s*(?:-[0-9]|[0-9]+\\.[0-9])",flags).containsMatchIn(clause) ||
                Regex("(?:生成|绘制|画)\\s*[0-9]+\\.[0-9]+\\s*[张幅]").containsMatchIn(clause))
            if (malformedConcurrency || malformedCount)
                ImageGenerationOptions.invalid("张数、并发数必须是支持范围内的正整数，不会忽略后生成。")
        }
        return ImageGenerationOptions.fromJson(values).also { it.validateShape() }
    }

    private fun integer(raw: String): Int {
        raw.toIntOrNull()?.let { return it }
        val words = mapOf("一" to 1,"二" to 2,"两" to 2,"三" to 3,"四" to 4,"五" to 5,"六" to 6,"七" to 7,"八" to 8,"九" to 9,"十" to 10,
            "one" to 1,"two" to 2,"three" to 3,"four" to 4,"five" to 5,"six" to 6,"seven" to 7,"eight" to 8,"nine" to 9,"ten" to 10)
        words[raw.lowercase()]?.let { return it }
        if ('十' in raw) {
            val pair=raw.split('十')
            if (pair.size == 2) {
                val tens=if (pair[0].isEmpty()) 1 else words[pair[0]]
                val units=if (pair[1].isEmpty()) 0 else words[pair[1]]
                if (tens != null && units != null) return tens * 10 + units
            }
        }
        ImageGenerationOptions.invalid("参数数值无法识别，请使用明确整数。")
    }
    private inline fun String.indexOfFirstFrom(from: Int, predicate: (Char) -> Boolean): Int {
        for (i in from until length) if (predicate(this[i])) return i
        return -1
    }
    private fun separatorClauses(text: String) = text.split(Regex("[,，、;；\\n。!?！？]"))
    private fun maskLiterals(text: String): String {
        val chars=text.toCharArray()
        // Keep offsets stable. Quoted artwork text, examples in code, and URLs are not output settings.
        val spans = listOf(
            Regex("```[\\s\\S]*?(?:```|$)"), Regex("~~~[\\s\\S]*?(?:~~~|$)"), Regex("`[^`\\n]*(?:`|$)"),
            Regex("\"[^\"\\n]*\"|“[^”\\n]*”|‘[^’\\n]*’|「[^」\\n]*」|『[^』\\n]*』|'[^'\\n]*'"),
            Regex("(?:https?://|file://|content://|/storage/|/data/|/workspace/)\\S+",flags),
        )
        spans.forEach { regex -> regex.findAll(text).forEach { match ->
            for (i in match.range) if (chars[i] != '\n') chars[i]=' '
        } }
        return String(chars)
    }
}

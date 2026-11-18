package app.daiyu.android.model

import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.media.FrozenImageAttachment
import app.daiyu.android.media.ImageAttachment
import app.daiyu.android.media.ImageAttachmentOwner
import app.daiyu.android.media.NativeFileImage
import app.daiyu.android.media.NativeScreenImage
import app.daiyu.android.model.images.ImageInputEncoder
import app.daiyu.android.storage.ModelSettings
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Wire-only pixels: a consumed tool image never becomes a new history attachment. */
internal object ScreenToolImageInput {
    private data class Call(val name: String, val arguments: String)
    private data class Receipt(val id: String, val call: Call, val output: String)
    private data class Located(val row: Int, val part: Int, val receipt: Receipt)
    private data class Metadata(val location: Located, val value: String)
    private data class Image(val location: Located, val bytes: ByteArray,
        val width: Int, val height: Int, val label: String)

    data class Projection(val request: JSONObject, val imageSizes: Map<String, Pair<Int, Int>>)

    fun project(request: JSONObject, sourceRows: JSONArray, settings: ModelSettings, capability: Boolean?,
        existingImageBytes: List<Int>, cancelled: () -> Boolean): JSONObject =
        prepare(request, sourceRows, settings, capability, existingImageBytes, cancelled).request

    fun prepare(request: JSONObject, sourceRows: JSONArray, settings: ModelSettings, capability: Boolean?,
        existingImageBytes: List<Int>, cancelled: () -> Boolean): Projection =
        prepare(request, sourceRows, settings, capability, existingImageBytes, cancelled, null, null)

    fun prepare(request: JSONObject, sourceRows: JSONArray, settings: ModelSettings, capability: Boolean?,
        existingImageBytes: List<Int>, cancelled: () -> Boolean,
        readAsset: ((String, String) -> ByteArray?)?, pendingCallIds: Set<String>?): Projection {
        val protocol = settings.protocol
        val key = when (protocol) {
            ProviderProtocol.RESPONSES -> "input"
            ProviderProtocol.MESSAGES, ProviderProtocol.CHAT_COMPLETIONS -> "messages"
            ProviderProtocol.GEMINI -> "contents"
            else -> return Projection(request, emptyMap())
        }
        val rows = request.optJSONArray(key) ?: return Projection(request, emptyMap())
        val sources = collect(sourceRows, protocol).map { it.receipt }.toSet()
        val metadata = mutableListOf<Metadata>()
        val fileMetadata = mutableListOf<Metadata>()
        var latest: Located? = null
        for (location in collect(rows, protocol)) {
            if (cancelled()) throw app.daiyu.android.media.ImageAttachmentException(
                app.daiyu.android.media.ImageAttachmentFailure.CANCELLED)
            if (location.receipt.call.name == "read_image") {
                if (location.receipt in sources) NativeFileImage.metadata("read_image",
                    location.receipt.call.arguments, location.receipt.output)?.let { fileMetadata += Metadata(location, it) }
                continue
            }
            if (location.receipt.call.name != "screen") continue
            latest = null
            if (location.receipt !in sources) continue // Custom body cannot manufacture an image authority.
            val output = location.receipt.output
            val value = if (runCatching { JSONObject(output).has("image_asset") }.getOrDefault(false))
                NativeScreenImage.metadata("screen", location.receipt.call.arguments, output)
            else NativeScreenImage.parse("screen", location.receipt.call.arguments, output)?.metadata
            if (value == null) continue
            metadata += Metadata(location, value)
            latest = location
        }
        if (metadata.isEmpty() && fileMetadata.isEmpty()) return Projection(request, emptyMap())
        val candidate = latest?.takeIf { location ->
            (pendingCallIds == null || location.receipt.id in pendingCallIds) &&
            (location.row + 1 until rows.length()).all { isResultRow(rows.getJSONObject(it), protocol) }
        }
        val selected = candidate?.let { location ->
            NativeScreenImage.parse("screen", location.receipt.call.arguments, location.receipt.output) { receipt ->
                readAsset?.invoke(location.receipt.id, receipt)
            }?.let { image -> location to image }
                ?: throw IllegalStateException("屏幕图片读取失败，请重新观察屏幕")
        }
        val fileCandidates = fileMetadata.map { it.location }.filter { location ->
            pendingCallIds != null && location.receipt.id in pendingCallIds &&
                (location.row + 1 until rows.length()).all { isResultRow(rows.getJSONObject(it), protocol) }
        }
        require(fileCandidates.map { it.receipt.id }.distinct().size == fileCandidates.size) { "重复的文件图片调用身份" }
        if (fileCandidates.isNotEmpty()) ImageInputEncoder().checkMetadataLimits(
            existingImageBytes + listOfNotNull(selected?.second?.bytes?.size) + fileCandidates.map {
                JSONObject(it.receipt.output).getJSONObject("image_asset").getInt("bytes")
            })
        val images = mutableListOf<Image>()
        selected?.let { (location, image) ->
            images += Image(location, image.bytes, image.width, image.height, image.inputLabel(location.receipt.id))
        }
        fileCandidates.forEach { location ->
            if (cancelled()) throw app.daiyu.android.media.ImageAttachmentException(
                app.daiyu.android.media.ImageAttachmentFailure.CANCELLED)
            val image = NativeFileImage.parse("read_image", location.receipt.call.arguments, location.receipt.output) { receipt ->
                readAsset?.invoke(location.receipt.id, receipt)
            } ?: throw IllegalStateException("文件图片读取失败，请重新调用 read_image")
            images += Image(location, image.bytes, image.width, image.height, image.inputLabel(location.receipt.id))
        }
        images.sortWith(compareBy<Image>({ it.location.row }, { it.location.part }))
        val imageBlocks = if (images.isEmpty()) emptyList() else {
            require(protocol in setOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES, ProviderProtocol.CHAT_COMPLETIONS)) {
                "当前协议尚不支持工具图片输入"
            }
            ImageInputEncoder.requireSupported(true, capability)
            require(request.opt("model") == settings.model) { "自定义请求体不能替换工具图片绑定的模型" }
            val encoder = ImageInputEncoder()
            encoder.checkMetadataLimits(existingImageBytes + images.map { it.bytes.size })
            val frozen = images.map { image ->
                val digest = MessageDigest.getInstance("SHA-256").digest(image.bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                val isScreen = image.location.receipt.call.name == "screen"
                val id = if (isScreen) "screen" else
                    JSONObject(image.location.receipt.output).getJSONObject("image_asset").getString("id")
                val attachment = ImageAttachment(ImageAttachmentOwner(if (isScreen) "screen-tool" else "read-image-tool", "wire"),
                    id, "image/png", image.bytes.size, digest, image.width, image.height)
                FrozenImageAttachment(attachment, image.bytes)
            }
            encoder.encode(protocol, frozen, capability, isCancelled = cancelled)
        }
        val projected = JSONObject(request.toString())
        val projectedRows = projected.getJSONArray(key)
        metadata.forEach { item ->
            val row = projectedRows.getJSONObject(item.location.row)
            val content = JSONObject(item.value).apply {
                if (item.location == selected?.first) put("image_attached", true)
                else put("image_omitted", if (candidate == null) "consumed_screen" else "superseded_screen")
            }.toString()
            when (protocol) {
                ProviderProtocol.RESPONSES -> row.put("output", content)
                ProviderProtocol.CHAT_COMPLETIONS -> row.put("content", content)
                ProviderProtocol.MESSAGES -> row.getJSONArray("content").getJSONObject(item.location.part).put("content", content)
                ProviderProtocol.GEMINI -> row.getJSONArray("parts").getJSONObject(item.location.part)
                    .getJSONObject("functionResponse").getJSONObject("response").put("output", content)
                else -> Unit
            }
        }
        fileMetadata.forEach { item ->
            val row = projectedRows.getJSONObject(item.location.row)
            val content = JSONObject(item.value).apply {
                if (item.location in fileCandidates) put("image_attached", true)
                else put("image_omitted", "consumed_read_image")
            }.toString()
            when (protocol) {
                ProviderProtocol.RESPONSES -> row.put("output", content)
                ProviderProtocol.CHAT_COMPLETIONS -> row.put("content", content)
                ProviderProtocol.MESSAGES -> row.getJSONArray("content").getJSONObject(item.location.part).put("content", content)
                ProviderProtocol.GEMINI -> row.getJSONArray("parts").getJSONObject(item.location.part)
                    .getJSONObject("functionResponse").getJSONObject("response").put("output", content)
                else -> Unit
            }
        }
        if (images.isNotEmpty()) {
            val textType = if (protocol == ProviderProtocol.RESPONSES) "input_text" else "text"
            val blocks = JSONArray()
            images.zip(imageBlocks).forEach { (image, block) ->
                blocks.put(JSONObject().put("type", textType).put("text", image.label)).put(block)
            }
            // Complete the whole adjacent tool-result batch before adding the image.
            var after = images.minOf { it.location.row }
            while (after + 1 < projectedRows.length() && isResultRow(projectedRows.getJSONObject(after + 1), protocol)) after++
            if (protocol == ProviderProtocol.MESSAGES) {
                val content = projectedRows.getJSONObject(after).getJSONArray("content")
                for (i in 0 until blocks.length()) content.put(blocks.get(i))
            } else {
                val inserted = JSONArray()
                for (i in 0 until projectedRows.length()) {
                    inserted.put(projectedRows.get(i))
                    if (i == after) inserted.put(JSONObject().put("role", "user").put("content", blocks))
                }
                projected.put(key, inserted)
            }
        }
        val sizes = images.zip(imageBlocks).associate { (image, block) ->
            requireNotNull(RequestInputEstimate.payload(block)) to (image.width to image.height)
        }
        return Projection(projected, sizes)
    }

    /** The larger stored row allowance belongs only to an exact native screen call/result. */
    fun boundedHistoryReceipt(items: List<String>, blocks: List<MessageBlock>, index: Int): Boolean = runCatching {
        val rows = JSONArray()
        items.forEach { rows.put(MessagesRequest.jsonObject(it)) }
        val row = rows.getJSONObject(index)
        val protocol = when {
            row.optString("type") == "function_call_output" -> ProviderProtocol.RESPONSES
            row.optString("role") == "tool" -> ProviderProtocol.CHAT_COMPLETIONS
            row.optString("role") == "user" && row.optJSONArray("content") != null -> ProviderProtocol.MESSAGES
            else -> return@runCatching false
        }
        val receipts = collect(rows, protocol, strict = true).filter { it.row == index }
        if (receipts.isEmpty()) return@runCatching false
        var images = 0
        for (location in receipts) {
            val receipt = location.receipt
            val block = blocks.filterIsInstance<MessageBlock.Tool>().singleOrNull {
                it.invocation.id.value == receipt.id
            } ?: return@runCatching false
            val blockArgs = MessagesRequest.jsonObject(block.invocation.arguments)
            val callArgs = MessagesRequest.jsonObject(receipt.call.arguments)
            val keys = blockArgs.keys().asSequence().toSet()
            if (block.invocation.name != receipt.call.name || block.result != receipt.output ||
                keys != callArgs.keys().asSequence().toSet() || keys.any { blockArgs.get(it) != callArgs.get(it) })
                return@runCatching false
            val image = NativeScreenImage.parse(receipt.call.name, receipt.call.arguments, receipt.output) ?: continue
            images++
            when (protocol) {
                ProviderProtocol.RESPONSES -> row.put("output", image.metadata)
                ProviderProtocol.CHAT_COMPLETIONS -> row.put("content", image.metadata)
                ProviderProtocol.MESSAGES -> row.getJSONArray("content").getJSONObject(location.part).put("content", image.metadata)
                else -> return@runCatching false
            }
        }
        images > 0 && row.toString().length <= 1_000_000
    }.getOrDefault(false)

    fun boundedNativeReceipt(rows: JSONArray, protocol: ProviderProtocol, result: JSONObject): Boolean {
        val joined = JSONArray()
        for (i in 0 until rows.length()) joined.put(rows.get(i))
        joined.put(result)
        val location = collect(joined, protocol).lastOrNull { it.row == rows.length() } ?: return false
        return NativeScreenImage.parse(location.receipt.call.name, location.receipt.call.arguments,
            location.receipt.output) != null
    }

    private fun isResultRow(row: JSONObject, protocol: ProviderProtocol): Boolean = when (protocol) {
        ProviderProtocol.RESPONSES -> row.optString("type") == "function_call_output"
        ProviderProtocol.CHAT_COMPLETIONS -> row.optString("role") == "tool"
        ProviderProtocol.MESSAGES -> row.optString("role") == "user" && row.optJSONArray("content")?.let { parts ->
            (0 until parts.length()).any { parts.optJSONObject(it)?.optString("type") == "tool_result" }
        } == true
        else -> false
    }

    private fun collect(rows: JSONArray, protocol: ProviderProtocol, strict: Boolean = false): List<Located> {
        val pending = mutableMapOf<String, Call>()
        val found = mutableListOf<Located>()
        val seen = mutableSetOf<String>()
        fun call(id: String, name: String, arguments: String) {
            if (strict) require(id.isNotBlank() && seen.add(id))
            if (id.isNotBlank()) pending[id] = Call(name, arguments)
        }
        fun result(row: Int, part: Int, id: String, output: Any?) {
            val invocation = pending.remove(id) ?: run { require(!strict); return }
            if (output is String) found += Located(row, part, Receipt(id, invocation, output))
        }
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            when (protocol) {
                ProviderProtocol.RESPONSES -> when (row.optString("type")) {
                    "function_call" -> call(row.optString("call_id"), row.optString("name"), row.optString("arguments"))
                    "function_call_output" -> result(i, -1, row.optString("call_id"), row.opt("output"))
                }
                ProviderProtocol.CHAT_COMPLETIONS -> {
                    if (row.optString("role") == "assistant") row.optJSONArray("tool_calls")?.let { calls ->
                        for (j in 0 until calls.length()) {
                            val item = calls.optJSONObject(j) ?: continue
                            val function = item.optJSONObject("function") ?: continue
                            call(item.optString("id"), function.optString("name"), function.optString("arguments"))
                        }
                    }
                    if (row.optString("role") == "tool") result(i, -1, row.optString("tool_call_id"), row.opt("content"))
                }
                ProviderProtocol.MESSAGES -> row.optJSONArray("content")?.let { parts ->
                    for (j in 0 until parts.length()) {
                        val part = parts.optJSONObject(j) ?: continue
                        if (row.optString("role") == "assistant" && part.optString("type") == "tool_use")
                            call(part.optString("id"), part.optString("name"), part.optJSONObject("input")?.toString().orEmpty())
                        if (row.optString("role") == "user" && part.optString("type") == "tool_result") {
                            if (strict || part.opt("is_error") != true)
                                result(i, j, part.optString("tool_use_id"), if (part.opt("is_error") == true) null else part.opt("content"))
                        }
                    }
                }
                ProviderProtocol.GEMINI -> row.optJSONArray("parts")?.let { parts ->
                    for (j in 0 until parts.length()) {
                        val part = parts.optJSONObject(j) ?: continue
                        if (row.optString("role") == "model") part.optJSONObject("functionCall")?.let { item ->
                            call(item.optString("id"), item.optString("name"), item.optJSONObject("args")?.toString().orEmpty())
                        }
                        if (row.optString("role") == "user") part.optJSONObject("functionResponse")?.let { item ->
                            result(i, j, item.optString("id"), item.optJSONObject("response")?.opt("output"))
                        }
                    }
                }
                else -> Unit
            }
        }
        return found
    }
}

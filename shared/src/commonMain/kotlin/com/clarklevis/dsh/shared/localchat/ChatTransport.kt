package com.clarklevis.dsh.shared.localchat

import com.clarklevis.dsh.shared.protocol.JsonValue
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** DeepSeek 兼容端点：默认官方地址与对话模型，可在设置里改。 */
data class DeepSeekChatSettings(
    val baseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-chat",
) {
    val chatCompletionsUrl: String
        get() {
            val trimmed = baseUrl.trimEnd('/')
            return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
        }

    companion object {
        val Default = DeepSeekChatSettings()
        val ChatModels = listOf("deepseek-chat", "deepseek-reasoner")
    }
}

/** 流式返回的增量事件。 */
sealed interface ChatStreamEvent {
    data class TextDelta(val text: String) : ChatStreamEvent

    data class ReasoningDelta(val text: String) : ChatStreamEvent

    data class Finished(
        val finishReason: String?,
        val promptTokens: Int?,
        val completionTokens: Int?,
    ) : ChatStreamEvent

    data class Failed(val message: String) : ChatStreamEvent
}

/** 请求体：OpenAI 兼容 `chat/completions`，默认开流式。 */
object ChatRequestBuilder {
    fun body(
        settings: DeepSeekChatSettings,
        messages: List<ChatMessage>,
        stream: Boolean = true,
    ): String {
        val payload = buildJsonObject {
            put("model", JsonPrimitive(settings.model))
            put("stream", JsonPrimitive(stream))
            put("messages", buildJsonArray {
                for (message in messages) {
                    add(
                        buildJsonObject {
                            put("role", JsonPrimitive(message.role))
                            put("content", JsonPrimitive(message.content))
                        },
                    )
                }
            })
        }
        return wireJson.encodeToString(JsonObject.serializer(), payload)
    }
}

/**
 * `text/event-stream` 解析器：平台层把收到的字节块喂给 [feed]，这里只做纯文本解析，
 * 因此两端共用同一份实现，也能在单测里用真实分片边界（半个事件、CRLF、`[DONE]`）验证。
 */
class ChatStreamParser {
    private val buffer = StringBuilder()
    private var finished = false

    val isFinished: Boolean get() = finished

    fun feed(chunk: String): List<ChatStreamEvent> {
        buffer.append(chunk)
        val events = ArrayList<ChatStreamEvent>()
        while (true) {
            val newline = buffer.indexOf("\n")
            if (newline < 0) break
            val line = buffer.substring(0, newline).trimEnd('\r')
            // 只能用 common 的 deleteRange：java.lang.StringBuilder.delete 在 Kotlin/Native 不存在
            buffer.deleteRange(0, newline + 1)
            parseLine(line, events)
        }
        return events
    }

    /** 流结束时调用，处理最后一帧没有换行符的情况。 */
    fun end(): List<ChatStreamEvent> {
        val events = ArrayList<ChatStreamEvent>()
        if (buffer.isNotEmpty()) {
            val line = buffer.toString().trimEnd('\r')
            buffer.clear()
            parseLine(line, events)
        }
        if (!finished) {
            finished = true
            events += ChatStreamEvent.Finished(null, null, null)
        }
        return events
    }

    private fun parseLine(line: String, events: MutableList<ChatStreamEvent>) {
        if (line.isEmpty()) return
        if (!line.startsWith("data:")) return
        val payload = line.removePrefix("data:").trim()
        if (payload.isEmpty()) return
        if (payload == "[DONE]") {
            if (!finished) {
                finished = true
                events += ChatStreamEvent.Finished(null, null, null)
            }
            return
        }
        val element = runCatching { wireJson.parseToJsonElement(payload) }.getOrNull() ?: return
        val value = JsonValue.fromJsonElement(element)
        value["error"]?.let { error ->
            val message = error["message"]?.stringValue ?: error.stringValue
            if (!message.isNullOrBlank()) events += ChatStreamEvent.Failed(message)
        }
        val choices = value["choices"]?.arrayValue.orEmpty()
        val choice = choices.firstOrNull()
        val delta = choice?.get("delta")
        delta?.get("reasoning_content")?.stringValue?.takeIf { it.isNotEmpty() }?.let {
            events += ChatStreamEvent.ReasoningDelta(it)
        }
        delta?.get("content")?.stringValue?.takeIf { it.isNotEmpty() }?.let {
            events += ChatStreamEvent.TextDelta(it)
        }
        val finishReason = choice?.get("finish_reason")?.stringValue
        val usage = value["usage"]
        val promptTokens = usage?.get("prompt_tokens")?.let { toIntOrNull(it) }
        val completionTokens = usage?.get("completion_tokens")?.let { toIntOrNull(it) }
        if (finishReason != null || usage != null) {
            if (!finished) {
                finished = true
                events += ChatStreamEvent.Finished(finishReason, promptTokens, completionTokens)
            }
        }
    }

    private fun toIntOrNull(value: JsonValue): Int? =
        value.doubleValue?.toInt() ?: value.stringValue?.toIntOrNull()
}

/** 非流式响应解析：`choices[0].message.content`，用于不支持流式的端点。 */
object ChatCompletionParser {
    fun text(body: String): String? {
        val element = runCatching { wireJson.parseToJsonElement(body) }.getOrNull() ?: return null
        val value = JsonValue.fromJsonElement(element)
        return value["choices"]?.arrayValue?.firstOrNull()?.get("message")?.get("content")?.stringValue
    }

    fun errorMessage(body: String): String? {
        val element = runCatching { wireJson.parseToJsonElement(body) }.getOrNull() ?: return null
        val value = JsonValue.fromJsonElement(element)
        return value["error"]?.get("message")?.stringValue ?: value["message"]?.stringValue
    }
}

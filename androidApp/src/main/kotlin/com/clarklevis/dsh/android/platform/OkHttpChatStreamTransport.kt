package com.clarklevis.dsh.android.platform

import com.clarklevis.dsh.shared.localchat.ChatCompletionParser
import com.clarklevis.dsh.shared.localchat.ChatMessage
import com.clarklevis.dsh.shared.localchat.ChatRequestBuilder
import com.clarklevis.dsh.shared.localchat.ChatStreamCancellation
import com.clarklevis.dsh.shared.localchat.ChatStreamEvent
import com.clarklevis.dsh.shared.localchat.ChatStreamParser
import com.clarklevis.dsh.shared.localchat.ChatStreamTransport
import com.clarklevis.dsh.shared.localchat.DeepSeekChatSettings
import com.clarklevis.dsh.shared.localchat.LocalChatFailure
import com.clarklevis.dsh.shared.localchat.LocalChatFailureReason
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 侧的流式执行器：OkHttp 异步请求逐行读 `text/event-stream`，
 * 喂给 shared 的 [ChatStreamParser]，因此 SSE 解析与 iOS/单测共用同一份实现。
 */
class OkHttpChatStreamTransport(
    private val client: OkHttpClient = defaultClient(),
) : ChatStreamTransport {
    override fun start(
        settings: DeepSeekChatSettings,
        apiKey: String,
        messages: List<ChatMessage>,
        onEvent: (ChatStreamEvent) -> Unit,
        onComplete: (LocalChatFailure?) -> Unit,
    ): ChatStreamCancellation {
        val body = ChatRequestBuilder.body(settings, messages, stream = true)
        val request = Request.Builder()
            .url(settings.chatCompletionsUrl)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val call = client.newCall(request)
        val finished = AtomicBoolean(false)
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (finished.compareAndSet(false, true)) {
                        onComplete(
                            LocalChatFailure(
                                reason = LocalChatFailureReason.TRANSPORT_ERROR,
                                detail = error.message,
                            ),
                        )
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val failure = response.use { readStream(it, onEvent) }
                    if (finished.compareAndSet(false, true)) onComplete(failure)
                }
            },
        )
        return ChatStreamCancellation { call.cancel() }
    }

    private fun readStream(
        response: Response,
        onEvent: (ChatStreamEvent) -> Unit,
    ): LocalChatFailure? {
        if (!response.isSuccessful) {
            val errorBody = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
            val message = ChatCompletionParser.errorMessage(errorBody) ?: errorBody.take(300)
            return LocalChatFailure(
                reason = LocalChatFailureReason.HTTP_ERROR,
                httpStatus = response.code,
                detail = message.ifBlank { null },
            )
        }
        val source = response.body?.source()
            ?: return LocalChatFailure(LocalChatFailureReason.TRANSPORT_ERROR, httpStatus = response.code)
        val parser = ChatStreamParser()
        while (!parser.isFinished) {
            val line = runCatching { source.readUtf8Line() }.getOrNull() ?: break
            if (line.isEmpty()) continue
            parser.feed(line + "\n").forEach(onEvent)
        }
        parser.end().forEach(onEvent)
        return if (parser.isFinished) {
            null
        } else {
            LocalChatFailure(LocalChatFailureReason.TRANSPORT_ERROR, httpStatus = response.code)
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 流式回复可能长时间没有新分片，读超时放宽到 5 分钟；连接仍限 20 秒。 */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

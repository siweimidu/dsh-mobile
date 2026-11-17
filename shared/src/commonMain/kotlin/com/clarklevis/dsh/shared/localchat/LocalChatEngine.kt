package com.clarklevis.dsh.shared.localchat

import com.clarklevis.dsh.shared.history.ImportedMessage
import com.clarklevis.dsh.shared.history.ImportedMessageRole
import com.clarklevis.dsh.shared.history.ImportedMessageStatus
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** 本地续聊失败原因；界面文案由平台层本地化。 */
enum class LocalChatFailureReason {
    MISSING_API_KEY,
    CONVERSATION_NOT_FOUND,
    EMPTY_MESSAGE,
    HTTP_ERROR,
    EMPTY_RESPONSE,
    TRANSPORT_ERROR,
}

data class LocalChatFailure(
    val reason: LocalChatFailureReason,
    val httpStatus: Int? = null,
    val detail: String? = null,
)

/**
 * 平台层构造失败的入口：Swift 侧构造 Kotlin 枚举字面量不可靠，
 * 统一走这里，平台只传字符串细节。
 */
object LocalChatFailures {
    fun http(status: Int, detail: String?): LocalChatFailure =
        LocalChatFailure(LocalChatFailureReason.HTTP_ERROR, status, detail)

    fun transport(detail: String?): LocalChatFailure =
        LocalChatFailure(LocalChatFailureReason.TRANSPORT_ERROR, null, detail)

    fun missingApiKey(): LocalChatFailure = LocalChatFailure(LocalChatFailureReason.MISSING_API_KEY)
}

/**
 * 平台层负责真正的 HTTP 流式请求；shared 只定义契约与解析。
 *
 * 用回调而不是 `suspend`：Swift 无法实现 Kotlin 的挂起接口，而 URLSession 天然就是回调式。
 * 实现方必须**恰好**回调一次 [onComplete]；[onEvent] 可以在完成前回调任意多次。
 */
interface ChatStreamTransport {
    fun start(
        settings: DeepSeekChatSettings,
        apiKey: String,
        messages: List<ChatMessage>,
        onEvent: (ChatStreamEvent) -> Unit,
        onComplete: (LocalChatFailure?) -> Unit,
    ): ChatStreamCancellation
}

/** 取消句柄：取消后不再回调 onEvent/onComplete。 */
fun interface ChatStreamCancellation {
    fun cancel()

    companion object {
        val None = ChatStreamCancellation {}
    }
}

data class LocalChatTurnResult(
    val failure: LocalChatFailure? = null,
    val assistantText: String = "",
    val reasoningText: String = "",
    val userMessageAppended: Boolean = false,
    val assistantMessageAppended: Boolean = false,
    val conversationTitle: String = "",
) {
    val succeeded: Boolean get() = failure == null && assistantMessageAppended
}

/**
 * 「填上 API Key 就能接着聊」的执行体：把用户输入追加进本地会话 → 用完整历史构建请求 →
 * 流式接收 → 成功后落地模型回复。失败时保留用户那条消息，方便直接重试。
 */
class LocalChatEngine(
    private val store: LocalConversationStore,
    private val transport: ChatStreamTransport,
    private val nowMillis: () -> Long,
) {
    suspend fun continueConversation(
        conversationId: String,
        userText: String,
        settings: DeepSeekChatSettings,
        apiKey: String,
        promptOptions: ChatPromptOptions = ChatPromptOptions.Default,
        onDelta: (String) -> Unit = {},
        onReasoning: (String) -> Unit = {},
    ): LocalChatTurnResult {
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) return LocalChatTurnResult(failure = LocalChatFailure(LocalChatFailureReason.EMPTY_MESSAGE))
        if (apiKey.isBlank()) return LocalChatTurnResult(failure = LocalChatFailure(LocalChatFailureReason.MISSING_API_KEY))
        val conversation = store.load(conversationId)
            ?: return LocalChatTurnResult(failure = LocalChatFailure(LocalChatFailureReason.CONVERSATION_NOT_FOUND))

        val timestamp = nowMillis()
        val userMessage = ImportedMessage(
            role = ImportedMessageRole.USER,
            text = trimmed,
            reasoning = null,
            status = ImportedMessageStatus.FINISHED,
            placeholder = false,
            createdAtMillis = timestamp,
            thinkingEnabled = false,
            searchEnabled = false,
            attachments = emptyList(),
            references = emptyList(),
            notices = emptyList(),
        )
        val withUser = store.appendMessage(conversationId, userMessage, timestamp)
            ?: return LocalChatTurnResult(failure = LocalChatFailure(LocalChatFailureReason.CONVERSATION_NOT_FOUND))

        val prompt = ChatPromptBuilder.fromConversation(withUser, promptOptions)
        val text = StringBuilder()
        val reasoning = StringBuilder()
        var streamFailure: LocalChatFailure? = null
        val failure = suspendCancellableCoroutine { continuation ->
            val handle = transport.start(
                settings = settings,
                apiKey = apiKey,
                messages = prompt,
                onEvent = { event ->
                    if (continuation.isActive) {
                        when (event) {
                            is ChatStreamEvent.TextDelta -> {
                                text.append(event.text)
                                onDelta(event.text)
                            }
                            is ChatStreamEvent.ReasoningDelta -> {
                                reasoning.append(event.text)
                                onReasoning(event.text)
                            }
                            is ChatStreamEvent.Failed -> streamFailure = LocalChatFailure(
                                reason = LocalChatFailureReason.TRANSPORT_ERROR,
                                detail = event.message,
                            )
                            is ChatStreamEvent.Finished -> Unit
                        }
                    }
                },
                onComplete = { result ->
                    if (continuation.isActive) continuation.resume(result)
                },
            )
            continuation.invokeOnCancellation { handle.cancel() }
        } ?: streamFailure
        if (failure != null) {
            return LocalChatTurnResult(
                failure = failure,
                assistantText = text.toString(),
                reasoningText = reasoning.toString(),
                userMessageAppended = true,
                conversationTitle = withUser.title,
            )
        }
        if (text.isBlank()) {
            return LocalChatTurnResult(
                failure = LocalChatFailure(LocalChatFailureReason.EMPTY_RESPONSE),
                reasoningText = reasoning.toString(),
                userMessageAppended = true,
                conversationTitle = withUser.title,
            )
        }
        val assistantMessage = ImportedMessage(
            role = ImportedMessageRole.ASSISTANT,
            text = text.toString(),
            reasoning = reasoning.toString().ifBlank { null },
            status = ImportedMessageStatus.FINISHED,
            placeholder = false,
            createdAtMillis = nowMillis(),
            thinkingEnabled = reasoning.isNotEmpty(),
            searchEnabled = false,
            attachments = emptyList(),
            references = emptyList(),
            notices = emptyList(),
        )
        val updated = store.appendMessage(conversationId, assistantMessage, assistantMessage.createdAtMillis)
            ?: return LocalChatTurnResult(
                failure = LocalChatFailure(LocalChatFailureReason.CONVERSATION_NOT_FOUND),
                userMessageAppended = true,
            )
        return LocalChatTurnResult(
            assistantText = assistantMessage.text,
            reasoningText = reasoning.toString(),
            userMessageAppended = true,
            assistantMessageAppended = true,
            conversationTitle = updated.title,
        )
    }
}

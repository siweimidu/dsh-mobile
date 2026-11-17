package com.clarklevis.dsh.shared.localchat

import com.clarklevis.dsh.shared.history.ImportedConversation
import com.clarklevis.dsh.shared.history.ImportedMessage
import com.clarklevis.dsh.shared.history.ImportedMessageRole

/** 发给模型的单条消息（OpenAI 兼容格式的 role/content）。 */
data class ChatMessage(val role: String, val content: String) {
    companion object {
        const val RoleSystem = "system"
        const val RoleUser = "user"
        const val RoleAssistant = "assistant"
    }
}

data class ChatPromptOptions(
    /** 单次请求携带的历史字符预算（中文约 1 字 ≈ 1 token 量级，留足余量）。 */
    val maxCharacters: Int = 48_000,
    val systemPrompt: String? = null,
    /** 附件（图片等）无法回放成二进制，这里用占位文字说明当时发过什么。 */
    val attachmentLabel: String = "[attachment]",
) {
    companion object {
        val Default = ChatPromptOptions()
    }
}

/**
 * 把导入的官方聊天记录还原成可继续对话的消息序列。
 *
 * 处理规则：
 * - 官方占位回复（`TEMPLATE_RESPONSE`，例如"服务器繁忙，请稍后再试。"）不算模型输出，跳过；
 * - 思考过程、联网引用、风险提示不进入续聊上下文（它们属于展示层，不是对话内容）；
 * - 连续同角色消息合并成一条，避免部分服务端拒绝 `user` 连发；
 * - 超预算时从最早的消息开始丢，保留最近若干轮，且保证第一条是用户消息。
 */
object ChatPromptBuilder {
    fun fromConversation(
        conversation: ImportedConversation,
        options: ChatPromptOptions = ChatPromptOptions.Default,
    ): List<ChatMessage> {
        val merged = mergeTurns(conversation.messages, options)
        val trimmed = trimToBudget(merged, options.maxCharacters)
        val system = options.systemPrompt?.takeIf { it.isNotBlank() }
            ?.let { listOf(ChatMessage(ChatMessage.RoleSystem, it)) }
            .orEmpty()
        return system + trimmed
    }

    private fun mergeTurns(
        messages: List<ImportedMessage>,
        options: ChatPromptOptions,
    ): List<ChatMessage> {
        val result = ArrayList<ChatMessage>(messages.size)
        var mergeable = false
        for (message in messages) {
            val payload = toPayload(message, options)
            if (payload == null) {
                // 被跳过的消息（官方占位回复等）也是轮次边界，不让前后同角色消息粘在一起
                mergeable = false
                continue
            }
            val previous = result.lastOrNull()
            if (mergeable && previous != null && previous.role == payload.role) {
                result[result.lastIndex] = previous.copy(content = previous.content + "\n\n" + payload.content)
            } else {
                result += payload
            }
            mergeable = true
        }
        return result
    }

    private fun toPayload(message: ImportedMessage, options: ChatPromptOptions): ChatMessage? =
        when (message.role) {
            ImportedMessageRole.USER -> {
                val attachments = message.attachments.joinToString("\n") { attachment ->
                    options.attachmentLabel + " " + attachment.fileName
                }
                val content = listOf(message.text, attachments)
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
                if (content.isBlank()) null else ChatMessage(ChatMessage.RoleUser, content)
            }
            ImportedMessageRole.ASSISTANT -> {
                when {
                    message.placeholder -> null
                    message.text.isBlank() -> null
                    else -> ChatMessage(ChatMessage.RoleAssistant, message.text)
                }
            }
        }

    private fun trimToBudget(messages: List<ChatMessage>, maxCharacters: Int): List<ChatMessage> {
        var total = messages.sumOf { it.content.length }
        var start = 0
        while (start < messages.size - 1 && total > maxCharacters) {
            total -= messages[start].content.length
            start++
        }
        var kept = messages.subList(start, messages.size)
        while (kept.isNotEmpty() && kept.first().role == ChatMessage.RoleAssistant) {
            kept = kept.subList(1, kept.size)
        }
        return kept.toList()
    }
}

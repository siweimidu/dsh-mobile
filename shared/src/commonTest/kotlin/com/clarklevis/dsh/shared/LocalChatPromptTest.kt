package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.history.ImportedAttachment
import com.clarklevis.dsh.shared.history.ImportedConversation
import com.clarklevis.dsh.shared.history.ImportedMessage
import com.clarklevis.dsh.shared.history.ImportedMessageRole
import com.clarklevis.dsh.shared.history.ImportedMessageStatus
import com.clarklevis.dsh.shared.localchat.ChatCompletionParser
import com.clarklevis.dsh.shared.localchat.ChatMessage
import com.clarklevis.dsh.shared.localchat.ChatPromptBuilder
import com.clarklevis.dsh.shared.localchat.ChatPromptOptions
import com.clarklevis.dsh.shared.localchat.ChatRequestBuilder
import com.clarklevis.dsh.shared.localchat.ChatStreamEvent
import com.clarklevis.dsh.shared.localchat.ChatStreamParser
import com.clarklevis.dsh.shared.localchat.DeepSeekChatSettings
import com.clarklevis.dsh.shared.protocol.JsonValue
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalChatPromptTest {
    private fun user(text: String, attachments: List<ImportedAttachment> = emptyList()): ImportedMessage =
        ImportedMessage(
            role = ImportedMessageRole.USER,
            text = text,
            reasoning = null,
            status = ImportedMessageStatus.FINISHED,
            placeholder = false,
            createdAtMillis = 0,
            thinkingEnabled = false,
            searchEnabled = false,
            attachments = attachments,
            references = emptyList(),
            notices = emptyList(),
        )

    private fun assistant(
        text: String,
        placeholder: Boolean = false,
        reasoning: String? = null,
    ): ImportedMessage = ImportedMessage(
        role = ImportedMessageRole.ASSISTANT,
        text = text,
        reasoning = reasoning,
        status = ImportedMessageStatus.FINISHED,
        placeholder = placeholder,
        createdAtMillis = 0,
        thinkingEnabled = false,
        searchEnabled = false,
        attachments = emptyList(),
        references = emptyList(),
        notices = emptyList(),
    )

    private fun conversation(messages: List<ImportedMessage>): ImportedConversation = ImportedConversation(
        id = "c1",
        title = "t",
        pinned = false,
        createdAtMillis = 0,
        updatedAtMillis = 0,
        messages = messages,
        prunedMessageCount = 0,
    )

    @Test
    fun buildsAlternatingHistoryAndSkipsPlaceholderReplies() {
        val prompt = ChatPromptBuilder.fromConversation(
            conversation(
                listOf(
                    user("第一问"),
                    assistant("服务器繁忙，请稍后再试。", placeholder = true),
                    user("第二问"),
                    assistant("思考中", reasoning = "只应留在展示层"),
                    // 连续同角色：合并
                    user("追问一"),
                    user("追问二"),
                    assistant("回答"),
                ),
            ),
        )
        // 占位回复被跳过，但它是轮次边界：两轮用户提问不会被粘成一条
        assertEquals(listOf("user", "user", "assistant", "user", "assistant"), prompt.map { it.role })
        assertEquals("第一问", prompt[0].content)
        assertEquals("第二问", prompt[1].content)
        assertEquals("思考中", prompt[2].content)
        assertEquals("追问一\n\n追问二", prompt[3].content)
        assertEquals("回答", prompt[4].content)
    }

    @Test
    fun keepsReasoningOutOfContinuationContext() {
        val prompt = ChatPromptBuilder.fromConversation(
            conversation(listOf(user("问"), assistant("答", reasoning = "内心戏"))),
        )
        assertEquals(2, prompt.size)
        assertTrue(prompt.none { it.content.contains("内心戏") })
    }

    @Test
    fun inlinesAttachmentNamesForReplay() {
        val prompt = ChatPromptBuilder.fromConversation(
            conversation(
                listOf(
                    user(
                        "看这张图",
                        listOf(
                            ImportedAttachment("f1", "a.webp", 10, true, 1, 1, "p/a.webp"),
                            ImportedAttachment("f2", "b.txt", 5, false, null, null, "p/b.txt"),
                        ),
                    ),
                ),
            ),
        )
        assertEquals("看这张图\n\n[attachment] a.webp\n[attachment] b.txt", prompt.single().content)
    }

    @Test
    fun prependsSystemPromptAndDropsLeadingAssistantWhenTrimming() {
        val longHistory = buildList {
            add(user("第一问".repeat(10)))
            add(assistant("第一答".repeat(10)))
            add(user("第二问"))
            add(assistant("第二答"))
        }
        val prompt = ChatPromptBuilder.fromConversation(
            conversation(longHistory),
            ChatPromptOptions(systemPrompt = "你是助手", maxCharacters = 20),
        )
        assertEquals(ChatMessage.RoleSystem, prompt.first().role)
        assertEquals("你是助手", prompt.first().content)
        // 预算内只保留最近消息，且第一条正文必须是用户消息
        assertEquals(ChatMessage.RoleUser, prompt[1].role)
        assertEquals("第二问", prompt[1].content)
        assertEquals("第二答", prompt[2].content)
    }

    @Test
    fun requestBodyCarriesModelAndMessages() {
        val body = ChatRequestBuilder.body(
            DeepSeekChatSettings(model = "deepseek-reasoner"),
            listOf(ChatMessage(ChatMessage.RoleUser, "你好")),
        )
        val value = JsonValue.fromJsonElement(wireJson.parseToJsonElement(body))
        assertEquals("deepseek-reasoner", value["model"]?.stringValue)
        assertEquals(true, value["stream"]?.booleanValue)
        val messages = value["messages"]?.arrayValue.orEmpty()
        assertEquals(1, messages.size)
        assertEquals("user", messages[0]["role"]?.stringValue)
        assertEquals("你好", messages[0]["content"]?.stringValue)
    }

    @Test
    fun normalizesEndpointUrl() {
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            DeepSeekChatSettings().chatCompletionsUrl,
        )
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            DeepSeekChatSettings(baseUrl = "https://api.deepseek.com/").chatCompletionsUrl,
        )
        assertEquals(
            "https://example.com/v1/chat/completions",
            DeepSeekChatSettings(baseUrl = "https://example.com/v1/chat/completions").chatCompletionsUrl,
        )
    }

    @Test
    fun parsesStreamChunksSplitAtArbitraryBoundaries() {
        val parser = ChatStreamParser()
        val first = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"想\"}}]}\n\n"
        val second = "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n"
        val third = "\ndata: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n"
        val last = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":5}}\n\ndata: [DONE]\n\n"

        val events = ArrayList<ChatStreamEvent>()
        // 故意在事件中间切开
        events += parser.feed(first.substring(0, 20))
        events += parser.feed(first.substring(20) + second)
        events += parser.feed(third + last.substring(0, 30))
        events += parser.feed(last.substring(30))
        events += parser.end()

        val reasoning = events.filterIsInstance<ChatStreamEvent.ReasoningDelta>().joinToString("") { it.text }
        val text = events.filterIsInstance<ChatStreamEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("想", reasoning)
        assertEquals("你好", text)
        val finished = events.filterIsInstance<ChatStreamEvent.Finished>().single()
        assertEquals("stop", finished.finishReason)
        assertEquals(3, finished.promptTokens)
        assertEquals(5, finished.completionTokens)
        assertTrue(parser.isFinished)
    }

    @Test
    fun parsesCrlfStreamAndReportsServerErrors() {
        val parser = ChatStreamParser()
        val error = parser.feed("data: {\"error\":{\"message\":\"invalid api key\"}}\r\n\r\n")
        assertEquals("invalid api key", (error.single() as ChatStreamEvent.Failed).message)

        val parser2 = ChatStreamParser()
        val events = parser2.feed("data: {\"choices\":[{\"delta\":{\"content\":\"翻\"}}]}\r\n")
        assertEquals("翻", (events.single() as ChatStreamEvent.TextDelta).text)
    }

    @Test
    fun endOfStreamEmitsFinishOnce() {
        val parser = ChatStreamParser()
        parser.feed("data: [DONE]\n\n")
        assertTrue(parser.isFinished)
        assertTrue(parser.end().isEmpty())
    }

    @Test
    fun parsesNonStreamingCompletion() {
        val body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"完整回答\"}}]}"
        assertEquals("完整回答", ChatCompletionParser.text(body))
        assertNull(ChatCompletionParser.text("not json"))
        assertEquals(
            "bad request",
            ChatCompletionParser.errorMessage("{\"error\":{\"message\":\"bad request\"}}"),
        )
    }
}

package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.history.ImportedConversation
import com.clarklevis.dsh.shared.history.ImportedMessage
import com.clarklevis.dsh.shared.history.ImportedMessageRole
import com.clarklevis.dsh.shared.history.ImportedMessageStatus
import com.clarklevis.dsh.shared.localchat.ChatMessage
import com.clarklevis.dsh.shared.localchat.ChatStreamCancellation
import com.clarklevis.dsh.shared.localchat.ChatStreamEvent
import com.clarklevis.dsh.shared.localchat.ChatStreamTransport
import com.clarklevis.dsh.shared.localchat.DeepSeekChatSettings
import com.clarklevis.dsh.shared.localchat.LocalChatEngine
import com.clarklevis.dsh.shared.localchat.LocalChatFailure
import com.clarklevis.dsh.shared.localchat.LocalChatFailureReason
import com.clarklevis.dsh.shared.localchat.LocalConversationStore
import com.clarklevis.dsh.shared.localchat.LocalFileStorage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryFileStorage : LocalFileStorage {
    val files = mutableMapOf<String, String>()

    override fun read(name: String): String? = files[name]

    override fun write(name: String, text: String) {
        files[name] = text
    }

    override fun delete(name: String) {
        files.remove(name)
    }

    override fun list(prefix: String): List<String> = files.keys.filter { it.startsWith(prefix) }.sorted()
}

private class FakeTransport(
    private val events: List<ChatStreamEvent> = emptyList(),
    private val failure: LocalChatFailure? = null,
) : ChatStreamTransport {
    var lastMessages: List<ChatMessage> = emptyList()
    var lastApiKey: String = ""
    var calls = 0

    override fun start(
        settings: DeepSeekChatSettings,
        apiKey: String,
        messages: List<ChatMessage>,
        onEvent: (ChatStreamEvent) -> Unit,
        onComplete: (LocalChatFailure?) -> Unit,
    ): ChatStreamCancellation {
        calls++
        lastMessages = messages
        lastApiKey = apiKey
        if (failure != null) {
            onComplete(failure)
            return ChatStreamCancellation.None
        }
        for (event in events) onEvent(event)
        onComplete(null)
        return ChatStreamCancellation.None
    }
}

class LocalChatEngineTest {
    private fun userMessage(text: String) = ImportedMessage(
        role = ImportedMessageRole.USER,
        text = text,
        reasoning = null,
        status = ImportedMessageStatus.FINISHED,
        placeholder = false,
        createdAtMillis = 0,
        thinkingEnabled = false,
        searchEnabled = false,
        attachments = emptyList(),
        references = emptyList(),
        notices = emptyList(),
    )

    private fun assistantMessage(text: String) = ImportedMessage(
        role = ImportedMessageRole.ASSISTANT,
        text = text,
        reasoning = null,
        status = ImportedMessageStatus.FINISHED,
        placeholder = false,
        createdAtMillis = 0,
        thinkingEnabled = false,
        searchEnabled = false,
        attachments = emptyList(),
        references = emptyList(),
        notices = emptyList(),
    )

    private fun conversation(id: String, title: String, messages: List<ImportedMessage>) = ImportedConversation(
        id = id,
        title = title,
        pinned = false,
        createdAtMillis = 100,
        updatedAtMillis = 200,
        messages = messages,
        prunedMessageCount = 0,
    )

    @Test
    fun importWritesFilesAndDeduplicates() {
        val storage = MemoryFileStorage()
        val store = LocalConversationStore(storage)
        val conversations = listOf(
            conversation("a", "第一个", listOf(userMessage("问"), assistantMessage("答"))),
            conversation("b", "第二个", listOf(userMessage("问二"))),
        )
        assertEquals(2, store.importConversations(conversations))
        assertEquals(setOf("a", "b"), store.importedIds())
        assertEquals(2, store.summaries().size)

        // 再导一次：全部跳过
        assertEquals(0, store.importConversations(conversations))
        val reloaded = assertNotNull(store.load("a"))
        assertEquals("第一个", reloaded.title)
        assertEquals(listOf("问", "答"), reloaded.messages.map { it.text })
        assertEquals(100L, reloaded.createdAtMillis)
    }

    @Test
    fun indexSurvivesLosingTheIndexFile() {
        val storage = MemoryFileStorage()
        val store = LocalConversationStore(storage)
        store.importConversations(listOf(conversation("a", "第一个", listOf(userMessage("问")))))
        storage.files.remove("local-conversations.json")
        assertTrue(store.summaries().isEmpty())
        store.rewriteIndex()
        assertEquals(listOf("a"), store.summaries().map { it.id })
    }

    @Test
    fun continueConversationSendsHistoryAndPersistsReply() = runTest {
        val storage = MemoryFileStorage()
        val store = LocalConversationStore(storage)
        store.importConversations(
            listOf(conversation("a", "第一个", listOf(userMessage("第一问"), assistantMessage("第一答")))),
        )
        val transport = FakeTransport(
            events = listOf(
                ChatStreamEvent.ReasoningDelta("想"),
                ChatStreamEvent.TextDelta("第二"),
                ChatStreamEvent.TextDelta("答"),
                ChatStreamEvent.Finished("stop", 10, 20),
            ),
        )
        val deltas = ArrayList<String>()
        val engine = LocalChatEngine(store, transport) { 1_000L }
        val result = engine.continueConversation(
            conversationId = "a",
            userText = "  第二问  ",
            settings = DeepSeekChatSettings(),
            apiKey = "sk-test",
            onDelta = { deltas += it },
        )

        assertTrue(result.succeeded)
        assertNull(result.failure)
        assertEquals("第二答", result.assistantText)
        assertEquals("想", result.reasoningText)
        assertEquals(listOf("第二", "答"), deltas)
        assertEquals("sk-test", transport.lastApiKey)
        // 历史（第一问/第一答）+ 本次用户输入一起发
        assertEquals(
            listOf("user:第一问", "assistant:第一答", "user:第二问"),
            transport.lastMessages.map { "${it.role}:${it.content}" },
        )
        val stored = assertNotNull(store.load("a"))
        assertEquals(listOf("第一问", "第一答", "第二问", "第二答"), stored.messages.map { it.text })
        assertEquals(1_000L, stored.updatedAtMillis)
        assertEquals(1, store.summaries().size)
    }

    @Test
    fun missingApiKeyDoesNotTouchConversation() = runTest {
        val store = LocalConversationStore(MemoryFileStorage())
        store.importConversations(listOf(conversation("a", "t", listOf(userMessage("问")))))
        val transport = FakeTransport(events = listOf(ChatStreamEvent.TextDelta("不该被调用")))
        val engine = LocalChatEngine(store, transport) { 1L }
        val result = engine.continueConversation("a", "追问", DeepSeekChatSettings(), apiKey = "   ")
        assertEquals(LocalChatFailureReason.MISSING_API_KEY, result.failure?.reason)
        assertEquals(0, transport.calls)
        assertEquals(1, assertNotNull(store.load("a")).messages.size)
    }

    @Test
    fun transportFailureKeepsUserMessageForRetry() = runTest {
        val store = LocalConversationStore(MemoryFileStorage())
        store.importConversations(listOf(conversation("a", "t", listOf(userMessage("问")))))
        val transport = FakeTransport(
            failure = LocalChatFailure(LocalChatFailureReason.HTTP_ERROR, httpStatus = 401, detail = "invalid api key"),
        )
        val engine = LocalChatEngine(store, transport) { 1L }
        val result = engine.continueConversation("a", "追问", DeepSeekChatSettings(), apiKey = "sk")
        assertTrue(!result.succeeded)
        assertEquals(401, result.failure?.httpStatus)
        assertTrue(result.userMessageAppended)
        assertTrue(!result.assistantMessageAppended)
        assertEquals(listOf("问", "追问"), assertNotNull(store.load("a")).messages.map { it.text })
    }

    @Test
    fun emptyStreamIsReportedAndNotPersisted() = runTest {
        val store = LocalConversationStore(MemoryFileStorage())
        store.importConversations(listOf(conversation("a", "t", listOf(userMessage("问")))))
        val engine = LocalChatEngine(store, FakeTransport(events = listOf(ChatStreamEvent.Finished("stop", 1, 0)))) { 1L }
        val result = engine.continueConversation("a", "追问", DeepSeekChatSettings(), apiKey = "sk")
        assertEquals(LocalChatFailureReason.EMPTY_RESPONSE, result.failure?.reason)
        assertTrue(!result.assistantMessageAppended)
        assertEquals(2, assertNotNull(store.load("a")).messages.size)
    }

    @Test
    fun unknownConversationIsReported() = runTest {
        val store = LocalConversationStore(MemoryFileStorage())
        val engine = LocalChatEngine(store, FakeTransport()) { 1L }
        val result = engine.continueConversation("missing", "问", DeepSeekChatSettings(), apiKey = "sk")
        assertEquals(LocalChatFailureReason.CONVERSATION_NOT_FOUND, result.failure?.reason)
    }
}

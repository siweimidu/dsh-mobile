package com.clarklevis.dsh.shared.localchat

import com.clarklevis.dsh.shared.history.ByteArrayHistoryArchiveSource
import com.clarklevis.dsh.shared.history.DeepSeekHistoryArchive
import com.clarklevis.dsh.shared.history.DeepSeekImportOptions
import com.clarklevis.dsh.shared.history.DeepSeekImportPreview
import com.clarklevis.dsh.shared.history.DeepSeekImportResult
import com.clarklevis.dsh.shared.history.ImportedConversation
import com.clarklevis.dsh.shared.history.HistoryImportException
import com.clarklevis.dsh.shared.history.HistoryImportFailureReason
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSUserDefaults
import platform.posix.memcpy
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned

/**
 * iOS 侧的统一入口：Swift 只跟这个类打交道。
 *
 * API Key 由 Swift 侧存 Keychain，每次续聊时按调用传入，shared 不落盘密钥；
 * baseUrl / model 走 NSUserDefaults。
 */
class IosLocalChatRuntime(
    storage: LocalFileStorage = IosLocalFileStorage(),
    private val preferences: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) {
    val store: LocalConversationStore = LocalConversationStore(storage)
    private val engine = LocalChatEngine(store, IosChatStreamTransport()) {
        (platform.Foundation.NSDate().timeIntervalSince1970 * 1000).toLong()
    }

    fun conversations(): List<LocalConversationSummary> = store.summaries()

    fun loadConversation(id: String): ImportedConversation? = store.load(id)

    fun settings(): DeepSeekChatSettings = DeepSeekChatSettings(
        baseUrl = preferences.stringForKey(KEY_BASE_URL)?.takeIf { it.isNotBlank() }
            ?: DeepSeekChatSettings.Default.baseUrl,
        model = preferences.stringForKey(KEY_MODEL)?.takeIf { it.isNotBlank() }
            ?: DeepSeekChatSettings.Default.model,
    )

    fun updateSettings(baseUrl: String, model: String) {
        preferences.setObject(baseUrl.trim(), forKey = KEY_BASE_URL)
        preferences.setObject(model.trim(), forKey = KEY_MODEL)
    }

    fun deleteConversation(id: String) {
        store.delete(id)
    }

    /** [path] 是已经复制到沙盒里的 zip 路径（Swift 侧负责从 fileImporter 复制）。 */
    @Throws(HistoryImportException::class)
    fun previewArchive(path: String): DeepSeekImportPreview =
        readArchive(path).preview(store.importedIds())

    @Throws(HistoryImportException::class)
    fun importArchive(path: String): DeepSeekImportResult {
        val archive = readArchive(path)
        val result = archive.importConversations(
            selectedIds = null,
            alreadyImportedIds = store.importedIds(),
            options = DeepSeekImportOptions.Default,
        )
        store.importConversations(result.conversations)
        return result
    }

    suspend fun sendMessage(
        conversationId: String,
        text: String,
        apiKey: String,
        onDelta: (String) -> Unit,
    ): LocalChatTurnResult = engine.continueConversation(
        conversationId = conversationId,
        userText = text,
        settings = settings(),
        apiKey = apiKey,
        promptOptions = ChatPromptOptions.Default,
        onDelta = onDelta,
    )

    @Throws(HistoryImportException::class)
    private fun readArchive(path: String): DeepSeekHistoryArchive {
        val data = NSData.dataWithContentsOfFile(path)
            ?: throw HistoryImportException(HistoryImportFailureReason.NOT_A_ZIP, "cannot read $path")
        return DeepSeekHistoryArchive.open(ByteArrayHistoryArchiveSource(data.toByteArray()))
    }

    private companion object {
        const val KEY_BASE_URL = "local_chat_base_url"
        const val KEY_MODEL = "local_chat_model"
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size <= 0) return ByteArray(0)
    val source = bytes ?: return ByteArray(0)
    val output = ByteArray(size)
    output.usePinned { pinned -> memcpy(pinned.addressOf(0), source, length) }
    return output
}

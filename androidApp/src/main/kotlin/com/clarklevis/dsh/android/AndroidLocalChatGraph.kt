package com.clarklevis.dsh.android

import android.content.Context
import android.net.Uri
import com.clarklevis.dsh.android.platform.AndroidGatewayCredentialStore
import com.clarklevis.dsh.android.platform.AndroidLocalFileStorage
import com.clarklevis.dsh.android.platform.OkHttpChatStreamTransport
import com.clarklevis.dsh.shared.history.ByteArrayHistoryArchiveSource
import com.clarklevis.dsh.shared.history.DeepSeekHistoryArchive
import com.clarklevis.dsh.shared.history.DeepSeekImportOptions
import com.clarklevis.dsh.shared.history.DeepSeekImportPreview
import com.clarklevis.dsh.shared.history.DeepSeekImportResult
import com.clarklevis.dsh.shared.history.HistoryArchiveSource
import com.clarklevis.dsh.shared.localchat.ChatPromptOptions
import com.clarklevis.dsh.shared.localchat.ChatStreamEvent
import com.clarklevis.dsh.shared.localchat.DeepSeekChatSettings
import com.clarklevis.dsh.shared.localchat.LocalChatEngine
import com.clarklevis.dsh.shared.localchat.LocalChatTurnResult
import com.clarklevis.dsh.shared.localchat.LocalConversationStore
import com.clarklevis.dsh.shared.localchat.LocalConversationSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/** 按需读取的归档源：46MB 导出包不必整体进内存。 */
private class FileHistoryArchiveSource(private val file: File) : HistoryArchiveSource {
    override val size: Long get() = file.length()

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length <= 0) return ByteArray(0)
        return RandomAccessFile(file, "r").use { handle ->
            if (offset >= handle.length()) return ByteArray(0)
            val available = minOf(length.toLong(), handle.length() - offset).toInt()
            val bytes = ByteArray(available)
            handle.seek(offset)
            handle.readFully(bytes)
            bytes
        }
    }
}

/**
 * 「导入官方聊天记录 + 填 API Key 后本地续聊」在 Android 侧的落地入口。
 * 只做数据与执行，不碰 UI；界面通过它读写，保证与 iOS 共用同一套 shared 逻辑。
 */
class AndroidLocalChatGraph(private val context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    val store: LocalConversationStore = LocalConversationStore(AndroidLocalFileStorage(appContext))
    private val credentials = AndroidGatewayCredentialStore(appContext)
    private val transport = OkHttpChatStreamTransport()
    private val engine = LocalChatEngine(store, transport) { System.currentTimeMillis() }

    fun conversations(): List<LocalConversationSummary> = store.summaries()

    fun settings(): DeepSeekChatSettings = DeepSeekChatSettings(
        baseUrl = preferences.getString(KEY_BASE_URL, null).orEmpty()
            .ifBlank { DeepSeekChatSettings.Default.baseUrl },
        model = preferences.getString(KEY_MODEL, null).orEmpty()
            .ifBlank { DeepSeekChatSettings.Default.model },
    )

    fun updateSettings(settings: DeepSeekChatSettings) {
        preferences.edit()
            .putString(KEY_BASE_URL, settings.baseUrl.trim())
            .putString(KEY_MODEL, settings.model.trim())
            .apply()
    }

    suspend fun loadApiKey(): String? = credentials.loadToken(API_KEY_ENDPOINT)

    suspend fun saveApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) {
            credentials.deleteToken(API_KEY_ENDPOINT)
        } else {
            credentials.saveToken(API_KEY_ENDPOINT, trimmed)
        }
    }

    /** 选包后先给预览：一共多少条、哪些已经导入过。 */
    suspend fun previewArchive(uri: Uri): DeepSeekImportPreview = withContext(Dispatchers.IO) {
        val archive = openArchive(uri)
        archive.preview(store.importedIds())
    }

    /** 真正导入：默认全选，已导入的自动跳过。 */
    suspend fun importArchive(
        uri: Uri,
        selectedIds: Set<String>? = null,
        options: DeepSeekImportOptions = DeepSeekImportOptions.Default,
    ): DeepSeekImportResult = withContext(Dispatchers.IO) {
        val archive = openArchive(uri)
        val result = archive.importConversations(
            selectedIds = selectedIds,
            alreadyImportedIds = store.importedIds(),
            options = options,
        )
        store.importConversations(result.conversations)
        result
    }

    suspend fun sendMessage(
        conversationId: String,
        text: String,
        promptOptions: ChatPromptOptions = ChatPromptOptions.Default,
        onEvent: (ChatStreamEvent) -> Unit = {},
        onDelta: (String) -> Unit = {},
    ): LocalChatTurnResult {
        val apiKey = loadApiKey().orEmpty()
        return engine.continueConversation(
            conversationId = conversationId,
            userText = text,
            settings = settings(),
            apiKey = apiKey,
            promptOptions = promptOptions,
            onDelta = { delta ->
                onDelta(delta)
                onEvent(ChatStreamEvent.TextDelta(delta))
            },
        )
    }

    fun loadConversation(id: String) = store.load(id)

    private fun openArchive(uri: Uri): DeepSeekHistoryArchive {
        val file = copyToCache(uri)
        return try {
            DeepSeekHistoryArchive.open(FileHistoryArchiveSource(file))
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    private fun copyToCache(uri: Uri): File {
        val target = File(appContext.cacheDir, "deepseek-export-${System.currentTimeMillis()}.zip")
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("cannot open $uri")
        input.use { source -> target.outputStream().use { sink -> source.copyTo(sink) } }
        return target
    }

    /** 供后续 UI 直接按字节解析（例如分享进来的内容）。 */
    fun openArchiveBytes(bytes: ByteArray): DeepSeekHistoryArchive =
        DeepSeekHistoryArchive.open(ByteArrayHistoryArchiveSource(bytes))

    companion object {
        private const val PREFERENCES_NAME = "local_chat_settings"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"

        /** 复用网关凭据存储（Keystore + AES-GCM），用固定 endpoint 区分 API Key。 */
        private const val API_KEY_ENDPOINT = "deepseek-api-key"
    }
}

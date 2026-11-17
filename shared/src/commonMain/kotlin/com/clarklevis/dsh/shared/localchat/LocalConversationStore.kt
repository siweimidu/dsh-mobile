package com.clarklevis.dsh.shared.localchat

import com.clarklevis.dsh.shared.history.ImportedAttachment
import com.clarklevis.dsh.shared.history.ImportedConversation
import com.clarklevis.dsh.shared.history.ImportedMessage
import com.clarklevis.dsh.shared.history.ImportedMessageRole
import com.clarklevis.dsh.shared.history.ImportedMessageStatus
import com.clarklevis.dsh.shared.history.ImportedNotice
import com.clarklevis.dsh.shared.history.ImportedNoticeLevel
import com.clarklevis.dsh.shared.history.ImportedReference
import com.clarklevis.dsh.shared.history.ImportedReferenceKind
import com.clarklevis.dsh.shared.protocol.JsonValue
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** 平台文件存储：Android 用 filesDir，iOS 用 Application Support。 */
interface LocalFileStorage {
    fun read(name: String): String?

    fun write(name: String, text: String)

    fun delete(name: String)

    /** 返回该前缀下的文件名（不含目录）。 */
    fun list(prefix: String): List<String>
}

data class LocalConversationSummary(
    val id: String,
    val title: String,
    val messageCount: Int,
    val updatedAtMillis: Long,
)

/**
 * 导入后的本地会话库：会话索引一份、每条会话一份文件（避免每次追加都重写全量）。
 * 用 JSON 手工读写，不依赖序列化插件，方便在 commonTest 里用内存存储直接验证。
 */
class LocalConversationStore(private val storage: LocalFileStorage) {
    fun summaries(): List<LocalConversationSummary> {
        val text = storage.read(INDEX_FILE) ?: return emptyList()
        val array = runCatching { wireJson.parseToJsonElement(text) }.getOrNull()
            ?.let { JsonValue.fromJsonElement(it) }?.arrayValue ?: return emptyList()
        return array.mapNotNull { item ->
            val id = item["id"]?.stringValue ?: return@mapNotNull null
            LocalConversationSummary(
                id = id,
                title = item["title"]?.stringValue.orEmpty(),
                messageCount = item["messageCount"]?.doubleValue?.toInt() ?: 0,
                updatedAtMillis = item["updatedAtMillis"]?.doubleValue?.toLong() ?: 0L,
            )
        }.sortedByDescending { it.updatedAtMillis }
    }

    fun importedIds(): Set<String> = summaries().map { it.id }.toSet()

    fun load(id: String): ImportedConversation? {
        val text = storage.read(conversationFile(id)) ?: return null
        val value = runCatching { wireJson.parseToJsonElement(text) }.getOrNull()
            ?.let { JsonValue.fromJsonElement(it) } ?: return null
        val messages = value["messages"]?.arrayValue.orEmpty().mapNotNull { readMessage(it) }
        return ImportedConversation(
            id = value["id"]?.stringValue ?: id,
            title = value["title"]?.stringValue.orEmpty(),
            pinned = value["pinned"]?.booleanValue ?: false,
            createdAtMillis = value["createdAtMillis"]?.doubleValue?.toLong() ?: 0L,
            updatedAtMillis = value["updatedAtMillis"]?.doubleValue?.toLong() ?: 0L,
            messages = messages,
            prunedMessageCount = value["prunedMessageCount"]?.doubleValue?.toInt() ?: 0,
        )
    }

    fun save(conversation: ImportedConversation) {
        storage.write(conversationFile(conversation.id), conversation.toJson().toString())
        rewriteIndex()
    }

    /** 批量导入；[existingIds] 里已有的会话跳过，返回真正写入的条数。 */
    fun importConversations(conversations: List<ImportedConversation>): Int {
        val existing = importedIds().toMutableSet()
        var imported = 0
        for (conversation in conversations) {
            if (!existing.add(conversation.id)) continue
            storage.write(conversationFile(conversation.id), conversation.toJson().toString())
            imported++
        }
        if (imported > 0) rewriteIndex()
        return imported
    }

    /** 追加一条消息（用户提问或模型回复），并刷新索引。 */
    fun appendMessage(id: String, message: ImportedMessage, updatedAtMillis: Long): ImportedConversation? {
        val conversation = load(id) ?: return null
        val updated = conversation.copy(
            messages = conversation.messages + message,
            updatedAtMillis = maxOf(conversation.updatedAtMillis, updatedAtMillis),
        )
        storage.write(conversationFile(id), updated.toJson().toString())
        rewriteIndex()
        return updated
    }

    fun delete(id: String) {
        storage.delete(conversationFile(id))
        rewriteIndex()
    }

    /** 用已存会话文件重建索引（用于索引丢失或被外部改动后自愈）。 */
    fun rewriteIndex() {
        val entries = storage.list(CONVERSATION_PREFIX).mapNotNull { name ->
            val id = name.removePrefix(CONVERSATION_PREFIX).removeSuffix(CONVERSATION_SUFFIX)
            load(id)?.let { conversation ->
                LocalConversationSummary(
                    id = conversation.id,
                    title = conversation.title,
                    messageCount = conversation.messages.size,
                    updatedAtMillis = conversation.updatedAtMillis,
                )
            }
        }.sortedByDescending { it.updatedAtMillis }
        val array = buildJsonArray {
            for (entry in entries) {
                add(
                    buildJsonObject {
                        put("id", JsonPrimitive(entry.id))
                        put("title", JsonPrimitive(entry.title))
                        put("messageCount", JsonPrimitive(entry.messageCount))
                        put("updatedAtMillis", JsonPrimitive(entry.updatedAtMillis))
                    },
                )
            }
        }
        storage.write(INDEX_FILE, array.toString())
    }

    private fun conversationFile(id: String): String = CONVERSATION_PREFIX + id + CONVERSATION_SUFFIX

    private fun ImportedConversation.toJson(): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("title", JsonPrimitive(title))
        put("pinned", JsonPrimitive(pinned))
        put("createdAtMillis", JsonPrimitive(createdAtMillis))
        put("updatedAtMillis", JsonPrimitive(updatedAtMillis))
        put("prunedMessageCount", JsonPrimitive(prunedMessageCount))
        put("messages", buildJsonArray { for (message in messages) add(message.toJson()) })
    }

    private fun ImportedMessage.toJson(): JsonObject = buildJsonObject {
        put("role", JsonPrimitive(role.name))
        put("text", JsonPrimitive(text))
        put("reasoning", reasoning?.let { JsonPrimitive(it) } ?: JsonNull)
        put("status", JsonPrimitive(status.name))
        put("placeholder", JsonPrimitive(placeholder))
        put("createdAtMillis", JsonPrimitive(createdAtMillis))
        put("thinkingEnabled", JsonPrimitive(thinkingEnabled))
        put("searchEnabled", JsonPrimitive(searchEnabled))
        put(
            "attachments",
            buildJsonArray {
                for (attachment in attachments) {
                    add(
                        buildJsonObject {
                            put("fileId", JsonPrimitive(attachment.fileId))
                            put("fileName", JsonPrimitive(attachment.fileName))
                            put("byteSize", JsonPrimitive(attachment.byteSize))
                            put("image", JsonPrimitive(attachment.image))
                            put("width", attachment.width?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("height", attachment.height?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("exportPath", attachment.exportPath?.let { JsonPrimitive(it) } ?: JsonNull)
                        },
                    )
                }
            },
        )
        put(
            "references",
            buildJsonArray {
                for (reference in references) {
                    add(
                        buildJsonObject {
                            put("kind", JsonPrimitive(reference.kind.name))
                            put("url", reference.url?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("title", reference.title?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("snippet", reference.snippet?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("siteName", reference.siteName?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("publishedAt", reference.publishedAt?.let { JsonPrimitive(it) } ?: JsonNull)
                        },
                    )
                }
            },
        )
        put(
            "notices",
            buildJsonArray {
                for (notice in notices) {
                    add(
                        buildJsonObject {
                            put("level", JsonPrimitive(notice.level.name))
                            put("text", JsonPrimitive(notice.text))
                        },
                    )
                }
            },
        )
    }

    private fun readMessage(value: JsonValue): ImportedMessage? {
        val role = when (value["role"]?.stringValue) {
            ImportedMessageRole.USER.name -> ImportedMessageRole.USER
            ImportedMessageRole.ASSISTANT.name -> ImportedMessageRole.ASSISTANT
            else -> return null
        }
        val status = value["status"]?.stringValue
            ?.let { name -> ImportedMessageStatus.entries.firstOrNull { it.name == name } }
            ?: ImportedMessageStatus.UNKNOWN
        return ImportedMessage(
            role = role,
            text = value["text"]?.stringValue.orEmpty(),
            reasoning = value["reasoning"]?.stringValue,
            status = status,
            placeholder = value["placeholder"]?.booleanValue ?: false,
            createdAtMillis = value["createdAtMillis"]?.doubleValue?.toLong() ?: 0L,
            thinkingEnabled = value["thinkingEnabled"]?.booleanValue ?: false,
            searchEnabled = value["searchEnabled"]?.booleanValue ?: false,
            attachments = value["attachments"]?.arrayValue.orEmpty().mapNotNull { item ->
                val fileId = item["fileId"]?.stringValue ?: return@mapNotNull null
                ImportedAttachment(
                    fileId = fileId,
                    fileName = item["fileName"]?.stringValue.orEmpty(),
                    byteSize = item["byteSize"]?.doubleValue?.toLong() ?: 0L,
                    image = item["image"]?.booleanValue ?: false,
                    width = item["width"]?.doubleValue?.toInt(),
                    height = item["height"]?.doubleValue?.toInt(),
                    exportPath = item["exportPath"]?.stringValue,
                )
            },
            references = value["references"]?.arrayValue.orEmpty().mapNotNull { item ->
                val kind = item["kind"]?.stringValue
                    ?.let { name -> ImportedReferenceKind.entries.firstOrNull { it.name == name } }
                    ?: ImportedReferenceKind.WEB_SEARCH
                ImportedReference(
                    kind = kind,
                    url = item["url"]?.stringValue,
                    title = item["title"]?.stringValue,
                    snippet = item["snippet"]?.stringValue,
                    siteName = item["siteName"]?.stringValue,
                    publishedAt = item["publishedAt"]?.stringValue,
                )
            },
            notices = value["notices"]?.arrayValue.orEmpty().mapNotNull { item ->
                val text = item["text"]?.stringValue ?: return@mapNotNull null
                val level = item["level"]?.stringValue
                    ?.let { name -> ImportedNoticeLevel.entries.firstOrNull { it.name == name } }
                    ?: ImportedNoticeLevel.INFO
                ImportedNotice(level, text)
            },
        )
    }

    companion object {
        private const val INDEX_FILE = "local-conversations.json"
        private const val CONVERSATION_PREFIX = "local-conversation-"
        private const val CONVERSATION_SUFFIX = ".json"
    }
}

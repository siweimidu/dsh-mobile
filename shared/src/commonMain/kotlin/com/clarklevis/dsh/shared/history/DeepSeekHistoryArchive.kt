package com.clarklevis.dsh.shared.history

import com.clarklevis.dsh.shared.protocol.JsonValue
import com.clarklevis.dsh.shared.protocol.wireJson

/**
 * 读取 DeepSeek APP 的官方导出包（`export_format_version` 3）。
 *
 * 包结构：
 * - `manifest.json`：`{"export_format_version":3}`
 * - `user.json`：账号信息（不导入）
 * - `chat_session_index.json`：会话索引（标题、置顶、消息数、时间）
 * - `chat_sessions/&lt;id&gt;.json`：单个会话，`messages[]` 是带 `parent_id` 的消息树
 * - `chat_sessions/&lt;id&gt;_files/…`：附件原文件
 *
 * 消息树说明：官方会保留编辑/重新生成出来的分支，只有从 `current_message_id` 沿
 * `parent_id` 回溯到根的那条链才是当前可见对话。导出里 522 个会话中有 48 个存在分支，
 * 因此这里必须先归一再输出，否则会把废弃版本也显示出来。
 */
class DeepSeekHistoryArchive private constructor(
    private val zip: ZipArchive,
    private val rootPrefix: String,
    val formatVersion: Int?,
    val sessionHeaders: List<DeepSeekSessionHeader>,
) {
    val attachmentCount: Int = zip.entries.count { it.name.contains("_files/") && !it.isDirectory }

    fun header(id: String): DeepSeekSessionHeader? = sessionHeaders.firstOrNull { it.id == id }

    fun preview(alreadyImportedIds: Set<String> = emptySet()): DeepSeekImportPreview {
        val items = sessionHeaders.map { header ->
            DeepSeekImportPreviewItem(
                id = header.id,
                title = header.title,
                messageCount = header.messageCount,
                updatedAtMillis = header.updatedAtMillis,
                pinned = header.pinned,
                alreadyImported = header.id in alreadyImportedIds,
            )
        }
        return DeepSeekImportPreview(
            total = items.size,
            available = items.count { !it.alreadyImported },
            imported = items.count { it.alreadyImported },
            conversations = items,
        )
    }

    fun readConversation(id: String, options: DeepSeekImportOptions = DeepSeekImportOptions.Default): ImportedConversation {
        val header = header(id) ?: throw HistoryImportException(HistoryImportFailureReason.SESSION_UNKNOWN, "id=$id")
        val text = zip.readText(header.entryName)
            ?: throw HistoryImportException(HistoryImportFailureReason.SESSION_FILE_MISSING, "entry=${header.entryName}")
        return parseConversation(text, header, options)
    }

    /**
     * 导入 [selectedIds]（为空表示全部可导入的会话）；[alreadyImportedIds] 里的会话会被跳过。
     */
    fun importConversations(
        selectedIds: Set<String>? = null,
        alreadyImportedIds: Set<String> = emptySet(),
        options: DeepSeekImportOptions = DeepSeekImportOptions.Default,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): DeepSeekImportResult {
        val targets = sessionHeaders.filter { header ->
            (selectedIds == null || header.id in selectedIds) && header.id !in alreadyImportedIds
        }
        val imported = ArrayList<ImportedConversation>(targets.size)
        val failures = ArrayList<HistoryImportFailure>()
        var skipped = 0
        targets.forEachIndexed { index, header ->
            try {
                val conversation = readConversation(header.id, options)
                if (conversation.messages.isEmpty()) {
                    skipped++
                } else {
                    imported += conversation
                }
            } catch (error: HistoryImportException) {
                failures += HistoryImportFailure(header.title, error.reason, error.detail)
            } catch (error: Exception) {
                failures += HistoryImportFailure(
                    conversationTitle = header.title,
                    reason = HistoryImportFailureReason.SESSION_JSON_INVALID,
                    detail = error.message,
                )
            }
            onProgress(index + 1, targets.size)
        }
        val skippedByDedup = sessionHeaders.count { it.id in alreadyImportedIds }
        return DeepSeekImportResult(
            imported = imported.size,
            skipped = skipped + skippedByDedup,
            failed = failures.size,
            conversations = imported,
            failures = failures,
        )
    }

    /** 读取某个附件的原始字节（图片等），不存在或读失败时返回 null。 */
    fun readAttachment(attachment: ImportedAttachment): ByteArray? {
        val path = attachment.exportPath ?: return null
        return runCatching { zip.read(path) }.getOrNull()
    }

    private fun parseConversation(
        json: String,
        header: DeepSeekSessionHeader,
        options: DeepSeekImportOptions,
    ): ImportedConversation {
        val root = parseJsonObject(json, header.id)
        val rawMessages = (root["messages"]?.arrayValue ?: emptyList()).mapNotNull { element ->
            parseRawMessage(element)
        }
        val visible = resolveVisibleChain(rawMessages, root["current_message_id"].asLong())
        val messages = visible.mapNotNull { raw -> raw.toImportedMessage(options) }
        val title = header.title.ifBlank { deriveTitle(messages) }
        return ImportedConversation(
            id = header.id,
            title = title,
            pinned = header.pinned,
            createdAtMillis = header.createdAtMillis,
            updatedAtMillis = header.updatedAtMillis,
            messages = messages,
            prunedMessageCount = rawMessages.size - visible.size,
        )
    }

    private fun parseJsonObject(json: String, id: String): JsonValue {
        val element = runCatching { wireJson.parseToJsonElement(json) }.getOrNull()
            ?: throw HistoryImportException(HistoryImportFailureReason.SESSION_JSON_INVALID, "id=$id")
        return JsonValue.fromJsonElement(element)
    }

    private fun parseRawMessage(element: JsonValue): RawMessage? {
        val object_ = element.objectValue ?: return null
        val fragments = element["fragments"]?.arrayValue ?: emptyList()
        return RawMessage(
            id = element["id"].asLong() ?: return null,
            parentId = element["parent_id"].asLong(),
            role = when (element["role"]?.stringValue?.uppercase()) {
                "USER" -> ImportedMessageRole.USER
                else -> ImportedMessageRole.ASSISTANT
            },
            status = element["status"]?.stringValue,
            createdAtMillis = IsoTimestamps.toEpochMillis(element["inserted_at"]?.stringValue),
            thinkingEnabled = object_["thinking_enabled"]?.booleanValue ?: false,
            searchEnabled = object_["search_enabled"]?.booleanValue ?: false,
            fragments = fragments,
        )
    }

    /**
     * 从 `current_message_id` 回溯到根，得到当前可见对话链；回溯过程中遇到断链（父消息已被裁剪）
     * 时就把已走到的那一段当作可见对话，不会整体失败。
     */
    private fun resolveVisibleChain(rawMessages: List<RawMessage>, currentMessageId: Long?): List<RawMessage> {
        if (rawMessages.isEmpty()) return emptyList()
        val byId = rawMessages.associateBy { it.id }
        val start: RawMessage? = currentMessageId?.takeIf { byId.containsKey(it) }?.let { byId[it] }
            ?: deepestLeaf(rawMessages, byId)
        if (start == null) return rawMessages
        val chain = ArrayList<RawMessage>()
        val visited = HashSet<Long>()
        var cursor: RawMessage? = start
        while (cursor != null && visited.add(cursor.id)) {
            chain += cursor
            cursor = cursor.parentId?.let { byId[it] }
        }
        chain.reverse()
        return chain
    }

    private fun deepestLeaf(rawMessages: List<RawMessage>, byId: Map<Long, RawMessage>): RawMessage? {
        var best: RawMessage? = null
        var bestDepth = -1
        for (message in rawMessages) {
            var depth = 0
            var cursor: RawMessage? = message
            val visited = HashSet<Long>()
            while (cursor != null && visited.add(cursor.id)) {
                depth++
                cursor = cursor.parentId?.let { byId[it] }
            }
            if (depth > bestDepth || (depth == bestDepth && best != null && message.id > best.id)) {
                best = message
                bestDepth = depth
            }
        }
        return best
    }

    private fun deriveTitle(messages: List<ImportedMessage>): String {
        val firstUserText = messages.firstOrNull { it.role == ImportedMessageRole.USER }?.text.orEmpty()
        val firstLine = firstUserText.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (firstLine.isEmpty()) return ""
        return if (firstLine.length <= TITLE_LIMIT) firstLine else firstLine.take(TITLE_LIMIT) + "…"
    }

    private class RawMessage(
        val id: Long,
        val parentId: Long?,
        val role: ImportedMessageRole,
        val status: String?,
        val createdAtMillis: Long,
        val thinkingEnabled: Boolean,
        val searchEnabled: Boolean,
        val fragments: List<JsonValue>,
    ) {
        fun toImportedMessage(options: DeepSeekImportOptions): ImportedMessage? {
            val texts = ArrayList<String>()
            var placeholderOnly = false
            var realText = false
            val reasoning = ArrayList<String>()
            val attachments = ArrayList<ImportedAttachment>()
            val references = ArrayList<ImportedReference>()
            val notices = ArrayList<ImportedNotice>()
            for (fragment in fragments) {
                val type = fragment["type"]?.stringValue?.uppercase() ?: continue
                val content = fragment["content"]?.stringValue?.trim().orEmpty()
                when (type) {
                    "REQUEST" -> if (content.isNotEmpty()) texts += content
                    "RESPONSE" -> if (content.isNotEmpty()) {
                        texts += content
                        realText = true
                    }
                    "TEMPLATE_RESPONSE" -> if (content.isNotEmpty()) texts += content
                    "THINK" -> if (options.includeReasoning && content.isNotEmpty()) reasoning += content
                    "FILE" -> if (options.includeAttachments) attachments += parseAttachments(fragment)
                    "TOOL_SEARCH" -> if (options.includeReferences) {
                        references += parseSearchReferences(fragment)
                    }
                    "TOOL_OPEN" -> if (options.includeReferences) {
                        fragment.toOpenReference()?.let { references += it }
                    }
                    "TOOL_IMAGE_SEARCH" -> if (options.includeNotices && content.isNotEmpty()) {
                        notices += ImportedNotice(ImportedNoticeLevel.INFO, content)
                    }
                    "CONTENT_RISK" -> if (options.includeNotices && content.isNotEmpty()) {
                        notices += ImportedNotice(ImportedNoticeLevel.WARNING, content)
                    }
                    "TIP" -> if (options.includeNotices && content.isNotEmpty()) {
                        notices += ImportedNotice(ImportedNoticeLevel.INFO, content)
                    }
                }
            }
            val text = texts.joinToString("\n\n")
            placeholderOnly = texts.isNotEmpty() && !realText
            if (text.isEmpty() && reasoning.isEmpty() && references.isEmpty() &&
                attachments.isEmpty() && notices.isEmpty()
            ) {
                return null
            }
            return ImportedMessage(
                role = role,
                text = text,
                reasoning = reasoning.takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
                status = status.toImportedStatus(),
                placeholder = placeholderOnly,
                createdAtMillis = createdAtMillis,
                thinkingEnabled = thinkingEnabled,
                searchEnabled = searchEnabled,
                attachments = attachments,
                references = references,
                notices = notices,
            )
        }

        private fun parseAttachments(fragment: JsonValue): List<ImportedAttachment> =
            (fragment["files"]?.arrayValue ?: emptyList()).mapNotNull { file ->
                val fileId = file["id"]?.stringValue ?: return@mapNotNull null
                ImportedAttachment(
                    fileId = fileId,
                    fileName = file["file_name"]?.stringValue.orEmpty(),
                    byteSize = file["file_size"].asLong() ?: 0L,
                    image = file["is_image"]?.booleanValue ?: false,
                    width = file["width"].asLong()?.toInt(),
                    height = file["height"].asLong()?.toInt(),
                    exportPath = file["export_path"]?.stringValue,
                )
            }

        private fun parseSearchReferences(fragment: JsonValue): List<ImportedReference> =
            (fragment["results"]?.arrayValue ?: emptyList()).mapNotNull { result ->
                val url = result["url"]?.stringValue
                val title = result["title"]?.stringValue
                if (url.isNullOrBlank() && title.isNullOrBlank()) return@mapNotNull null
                ImportedReference(
                    kind = ImportedReferenceKind.WEB_SEARCH,
                    url = url,
                    title = title,
                    snippet = result["snippet"]?.stringValue,
                    siteName = result["site_name"]?.stringValue,
                    publishedAt = result["published_at"]?.stringValue,
                )
            }

        private fun JsonValue.toOpenReference(): ImportedReference? {
            val url = this["url"]?.stringValue
            val title = this["title"]?.stringValue
            if (url.isNullOrBlank() && title.isNullOrBlank()) return null
            return ImportedReference(
                kind = ImportedReferenceKind.OPENED_PAGE,
                url = url,
                title = title,
                snippet = this["snippet"]?.stringValue,
                siteName = this["site_name"]?.stringValue,
                publishedAt = this["published_at"]?.stringValue,
            )
        }

        private fun String?.toImportedStatus(): ImportedMessageStatus = when (this?.uppercase()) {
            "FINISHED" -> ImportedMessageStatus.FINISHED
            "INCOMPLETE" -> ImportedMessageStatus.INCOMPLETE
            "TIMEOUT" -> ImportedMessageStatus.TIMEOUT
            "CONTENT_FILTER" -> ImportedMessageStatus.CONTENT_FILTER
            else -> ImportedMessageStatus.UNKNOWN
        }
    }

    companion object {
        private const val TITLE_LIMIT = 40

        fun open(source: HistoryArchiveSource): DeepSeekHistoryArchive {
            val zip = ZipArchive.open(source)
            val sessionEntries = zip.entries.filter { entry ->
                !entry.isDirectory && entry.name.contains(SESSION_DIRECTORY) && entry.name.endsWith(".json")
            }
            if (sessionEntries.isEmpty()) {
                throw HistoryImportException(HistoryImportFailureReason.NOT_A_DEEPSEEK_EXPORT, "chat_sessions missing")
            }
            val rootPrefix = sessionEntries
                .groupBy { it.name.substringBefore(SESSION_DIRECTORY) }
                .maxByOrNull { it.value.size }
                ?.key
                .orEmpty()
            val formatVersion = zip.readText(rootPrefix + "manifest.json")
                ?.let { runCatching { wireJson.parseToJsonElement(it) }.getOrNull() }
                ?.let { JsonValue.fromJsonElement(it)["export_format_version"].asLong()?.toInt() }
            val indexHeaders = parseIndex(zip.readText(rootPrefix + "chat_session_index.json"))
            val headers = mergeHeaders(indexHeaders, zip, rootPrefix, sessionEntries)
            return DeepSeekHistoryArchive(
                zip = zip,
                rootPrefix = rootPrefix,
                formatVersion = formatVersion,
                sessionHeaders = headers,
            )
        }

        fun open(bytes: ByteArray): DeepSeekHistoryArchive = open(ByteArrayHistoryArchiveSource(bytes))

        private fun parseIndex(json: String?): List<DeepSeekSessionHeader> {
            if (json.isNullOrBlank()) return emptyList()
            val element = runCatching { wireJson.parseToJsonElement(json) }.getOrNull() ?: return emptyList()
            val items = JsonValue.fromJsonElement(element).arrayValue ?: return emptyList()
            return items.mapNotNull { item ->
                val id = item["id"]?.stringValue ?: return@mapNotNull null
                DeepSeekSessionHeader(
                    id = id,
                    title = item["title"]?.stringValue.orEmpty(),
                    pinned = item["pinned"]?.booleanValue ?: false,
                    messageCount = item["message_count"].asLong()?.toInt() ?: 0,
                    createdAtMillis = IsoTimestamps.toEpochMillis(item["inserted_at"]?.stringValue),
                    updatedAtMillis = IsoTimestamps.toEpochMillis(item["updated_at"]?.stringValue),
                    entryName = "",
                )
            }
        }

        /**
         * 索引可能缺失或与会话文件不一致：以会话文件为权威，索引只补标题与置顶。
         */
        private fun mergeHeaders(
            indexHeaders: List<DeepSeekSessionHeader>,
            zip: ZipArchive,
            rootPrefix: String,
            sessionEntries: List<HistoryArchiveEntry>,
        ): List<DeepSeekSessionHeader> {
            val indexById = indexHeaders.associateBy { it.id }
            val headers = ArrayList<DeepSeekSessionHeader>(sessionEntries.size)
            for (entry in sessionEntries) {
                val id = entry.name.substringAfterLast('/').removeSuffix(".json")
                val indexed = indexById[id]
                headers += DeepSeekSessionHeader(
                    id = id,
                    title = indexed?.title.orEmpty(),
                    pinned = indexed?.pinned ?: false,
                    messageCount = indexed?.messageCount ?: 0,
                    createdAtMillis = indexed?.createdAtMillis ?: 0L,
                    updatedAtMillis = indexed?.updatedAtMillis ?: 0L,
                    entryName = entry.name,
                )
            }
            for (index in headers.indices) {
                val header = headers[index]
                if (header.updatedAtMillis != 0L && header.title.isNotEmpty()) continue
                headers[index] = readHeaderFromSession(zip, header) ?: header
            }
            return headers.sortedWith(
                compareByDescending<DeepSeekSessionHeader> { it.pinned }.thenByDescending { it.updatedAtMillis },
            )
        }

        /** 索引缺行时，直接读会话文件里的单行摘要字段。 */
        private fun readHeaderFromSession(zip: ZipArchive, header: DeepSeekSessionHeader): DeepSeekSessionHeader? {
            val text = zip.readText(header.entryName) ?: return null
            val element = runCatching { wireJson.parseToJsonElement(text) }.getOrNull() ?: return null
            val value = JsonValue.fromJsonElement(element)
            val messages = value["messages"]?.arrayValue ?: emptyList()
            return header.copy(
                title = header.title.ifEmpty { value["title"]?.stringValue.orEmpty() }
                    .ifEmpty { deriveTitleFromMessages(messages) },
                pinned = header.pinned || (value["pinned"]?.booleanValue ?: false),
                messageCount = header.messageCount.takeIf { it != 0 } ?: messages.size,
                createdAtMillis = header.createdAtMillis.takeIf { it != 0L }
                    ?: IsoTimestamps.toEpochMillis(value["inserted_at"]?.stringValue),
                updatedAtMillis = header.updatedAtMillis.takeIf { it != 0L }
                    ?: IsoTimestamps.toEpochMillis(value["updated_at"]?.stringValue),
            )
        }

        /** 会话没有标题时用第一条用户提问兜底，避免列表里出现一行空白。 */
        private fun deriveTitleFromMessages(messages: List<JsonValue>): String {
            for (message in messages) {
                for (fragment in message["fragments"]?.arrayValue ?: emptyList()) {
                    if (fragment["type"]?.stringValue?.uppercase() != "REQUEST") continue
                    val content = fragment["content"]?.stringValue.orEmpty()
                    val firstLine = content.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                    if (firstLine.isEmpty()) continue
                    return if (firstLine.length <= TITLE_LIMIT) firstLine else firstLine.take(TITLE_LIMIT) + "…"
                }
            }
            return ""
        }
    }
}

private const val SESSION_DIRECTORY = "chat_sessions/"

/** 数字或字符串形式的整数都接受：导出侧不同版本两种都出现过。 */
internal fun JsonValue?.asLong(): Long? {
    if (this == null || this is JsonValue.NullValue) return null
    return doubleValue?.toLong() ?: stringValue?.trim()?.toLongOrNull()
}

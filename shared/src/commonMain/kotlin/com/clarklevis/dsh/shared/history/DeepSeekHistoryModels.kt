package com.clarklevis.dsh.shared.history

/** 导入失败的机器可读原因；界面文案由平台层按本地化表渲染。 */
enum class HistoryImportFailureReason {
    NOT_A_ZIP,
    ZIP64_UNSUPPORTED,
    INVALID_ZIP_STRUCTURE,
    ENTRY_ENCRYPTED,
    ENTRY_TOO_LARGE,
    UNSUPPORTED_COMPRESSION,
    ENTRY_TRUNCATED,
    CRC_MISMATCH,
    INFLATE_CORRUPTED,
    NOT_A_DEEPSEEK_EXPORT,
    SESSION_FILE_MISSING,
    SESSION_JSON_INVALID,
    SESSION_UNKNOWN,
}

/** 导入异常：只带原因与诊断细节（ASCII），不带界面文案。 */
class HistoryImportException(
    val reason: HistoryImportFailureReason,
    val detail: String? = null,
    cause: Throwable? = null,
) : Exception(reason.name + (detail?.let { ": $it" } ?: ""), cause)

/** 单个会话导入失败：预览列表里可以看到是哪一条、为什么失败。 */
data class HistoryImportFailure(
    val conversationTitle: String,
    val reason: HistoryImportFailureReason,
    val detail: String? = null,
)

enum class ImportedMessageRole { USER, ASSISTANT }

enum class ImportedMessageStatus { FINISHED, INCOMPLETE, TIMEOUT, CONTENT_FILTER, UNKNOWN }

enum class ImportedReferenceKind { WEB_SEARCH, OPENED_PAGE, IMAGE_SEARCH }

enum class ImportedNoticeLevel { INFO, WARNING }

/** 官方导出里 FILE 片段描述的一个附件；[exportPath] 指向 zip 内的原始文件。 */
data class ImportedAttachment(
    val fileId: String,
    val fileName: String,
    val byteSize: Long,
    val image: Boolean,
    val width: Int?,
    val height: Int?,
    val exportPath: String?,
)

/** 联网搜索 / 打开网页 / 图片搜索留下的引用。 */
data class ImportedReference(
    val kind: ImportedReferenceKind,
    val url: String?,
    val title: String?,
    val snippet: String?,
    val siteName: String?,
    val publishedAt: String?,
)

data class ImportedNotice(
    val level: ImportedNoticeLevel,
    val text: String,
)

data class ImportedMessage(
    val role: ImportedMessageRole,
    val text: String,
    val reasoning: String?,
    val status: ImportedMessageStatus,
    val createdAtMillis: Long,
    val thinkingEnabled: Boolean,
    val searchEnabled: Boolean,
    val attachments: List<ImportedAttachment>,
    val references: List<ImportedReference>,
    val notices: List<ImportedNotice>,
)

/**
 * 一条官方导出的完整对话；[messages] 已按 `current_message_id` 归一到当前可见分支。
 * [title] 可能为空（官方导出允许空标题），由平台层显示占位文案。
 */
data class ImportedConversation(
    val id: String,
    val title: String,
    val pinned: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val messages: List<ImportedMessage>,
    val prunedMessageCount: Int,
)

/** 会话索引里的一行，用于预览与选择，不需要解析正文。 */
data class DeepSeekSessionHeader(
    val id: String,
    val title: String,
    val pinned: Boolean,
    val messageCount: Int,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val entryName: String,
)

data class DeepSeekImportPreviewItem(
    val id: String,
    val title: String,
    val messageCount: Int,
    val updatedAtMillis: Long,
    val pinned: Boolean,
    val alreadyImported: Boolean,
)

data class DeepSeekImportPreview(
    val total: Int,
    val available: Int,
    val imported: Int,
    val conversations: List<DeepSeekImportPreviewItem>,
)

data class DeepSeekImportOptions(
    val includeReasoning: Boolean = true,
    val includeReferences: Boolean = true,
    val includeNotices: Boolean = true,
    val includeAttachments: Boolean = true,
) {
    companion object {
        val Default = DeepSeekImportOptions()
    }
}

data class DeepSeekImportResult(
    val imported: Int,
    val skipped: Int,
    val failed: Int,
    val conversations: List<ImportedConversation>,
    val failures: List<HistoryImportFailure>,
)

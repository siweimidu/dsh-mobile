package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.history.DeepSeekHistoryArchive
import com.clarklevis.dsh.shared.history.DeepSeekImportOptions
import com.clarklevis.dsh.shared.history.HistoryImportException
import com.clarklevis.dsh.shared.history.HistoryImportFailureReason
import com.clarklevis.dsh.shared.history.ImportedMessageRole
import com.clarklevis.dsh.shared.history.ImportedMessageStatus
import com.clarklevis.dsh.shared.history.ImportedNoticeLevel
import com.clarklevis.dsh.shared.history.ImportedReferenceKind
import com.clarklevis.dsh.shared.history.IsoTimestamps
import com.clarklevis.dsh.shared.history.decodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeepSeekHistoryImportTest {
    private val forkedId = "11111111-1111-4111-8111-111111111111"
    private val attachmentId = "22222222-2222-4222-8222-222222222222"
    private val emptyId = "33333333-3333-4333-8333-333333333333"

    @Test
    fun readsManifestAndSessionHeaders() {
        val archive = DeepSeekHistoryArchive.open(DeepSeekHistoryFixtures.DEEPSEEK_APP_V3_FLAT)
        assertEquals(3, archive.formatVersion)
        assertEquals(3, archive.sessionHeaders.size)
        assertEquals(2, archive.attachmentCount)
        // 置顶优先，其次按更新时间倒序
        assertEquals(listOf(forkedId, attachmentId, emptyId), archive.sessionHeaders.map { it.id })
        assertEquals("分支会话：编辑与重新生成", archive.sessionHeaders.first().title)
        assertEquals(6, archive.sessionHeaders.first().messageCount)
    }

    @Test
    fun indexRowsWithoutSessionFileAreIgnored() {
        val archive = DeepSeekHistoryArchive.open(DeepSeekHistoryFixtures.DEEPSEEK_APP_V3_FLAT)
        assertNull(archive.sessionHeaders.firstOrNull { it.id.startsWith("44444444") })
        assertNull(archive.header("44444444-4444-4444-8444-444444444444"))
    }

    @Test
    fun previewMarksAlreadyImportedConversations() {
        val archive = DeepSeekHistoryArchive.open(DeepSeekHistoryFixtures.DEEPSEEK_APP_V3_FLAT)
        val emptyPreview = archive.preview()
        assertEquals(3, emptyPreview.total)
        assertEquals(3, emptyPreview.available)
        assertEquals(0, emptyPreview.imported)

        val preview = archive.preview(setOf(forkedId))
        assertEquals(3, preview.total)
        assertEquals(2, preview.available)
        assertEquals(1, preview.imported)
        assertTrue(preview.conversations.first { it.id == forkedId }.alreadyImported)
        assertTrue(!preview.conversations.first { it.id == attachmentId }.alreadyImported)
    }

    @Test
    fun visibleChainFollowsCurrentMessageIdInsteadOfAllBranches() {
        val archive = DeepSeekHistoryArchive.open(DeepSeekHistoryFixtures.DEEPSEEK_APP_V3_FLAT)
        val conversation = archive.readConversation(forkedId)
        assertEquals(4, conversation.messages.size)
        assertEquals(2, conversation.prunedMessageCount)
        assertTrue(conversation.pinned)
        assertEquals(1777709400000L, conversation.updatedAtMillis)
        assertEquals(1777477984225L, conversation.createdAtMillis)
        assertEquals(
            listOf("第一问：分支怎么处理？", "第一答：按 current_message_id 走。", "当前分支提问", "第二答：分叉已按当前分支归一。"),
            conversation.messages.map { it.text },
        )
        assertTrue(conversation.messages.none { it.text.contains("旧分支") })
    }

    @Test
    fun mapsEveryFragmentTypeOntoMessageFields() {
        val archive = DeepSeekHistoryArchive.open(DeepSeekHistoryFixtures.DEEPSEEK_APP_V3_FLAT)
        val conversation = archive.readConversation(forkedId)
        val firstAnswer = conversation.messages[1]
        assertEquals(ImportedMessageRole.ASSISTANT, firstAnswer.role)
        assertEquals("先想清楚树干", firstAnswer.reasoning)
        assertTrue(firstAnswer.thinkingEnabled)
        assertEquals(ImportedMessageStatus.FINISHED, firstAnswer.status)
        assertEquals(1777478000000L, firstAnswer.createdAtMillis)

        val secondAnswer = conversation.messages[3]
        assertEquals("联网确认一下", secondAnswer.reasoning)
        assertTrue(secondAnswer.searchEnabled)
        // 只有一个搜索结果带 url/title，另一个空结果被丢弃
        assertEquals(2, secondAnswer.references.size)
        assertEquals(ImportedReferenceKind.WEB_SEARCH, secondAnswer.references[0].kind)
        assertEquals("https://example.com/a", secondAnswer.references[0].url)
        assertEquals("示例站点", secondAnswer.references[0].siteName)
        assertEquals(ImportedReferenceKind.OPENED_PAGE, secondAnswer.references[1].kind)
        assertEquals("https://example.com/b", secondAnswer.references[1].url)
        assertEquals(1, secondAnswer.notices.size)
        assertEquals(ImportedNoticeLevel.WARNING, secondAnswer.notices.first().level)
        assertEquals("本回答由 AI 生成，内容仅供参考。", secondAnswer.notices.first().text)
    }

    @Test
    fun parsesAttachmentsAndReadsOriginalBytes() {
        val archive = DeepSeekHistoryArchive.open(DeepSeekHistoryFixtures.DEEPSEEK_APP_V3_FLAT)
        val conversation = archive.readConversation(attachmentId)
        val userMessage = conversation.messages.first()
        assertEquals("看这两张截图", userMessage.text)
        assertEquals(2, userMessage.attachments.size)
        val image = userMessage.attachments[0]
        assertTrue(image.image)
        assertEquals("Screenshot_2026-09-19.webp", image.fileName)
        assertEquals(1170, image.width)
        assertEquals(2532, image.height)
        assertEquals(24L, image.byteSize)
        val imageBytes = assertNotNull(archive.readAttachment(image))
        assertEquals(40, imageBytes.size)
        assertEquals(listOf(0x52, 0x49, 0x46, 0x46), imageBytes.take(4).map { it.toInt() and 0xFF })

        val notes = userMessage.attachments[1]
        assertTrue(!notes.image)
        assertEquals("hello", assertNotNull(archive.readAttachment(notes)).decodeToString())
    }

    @Test
    fun keepsFailedAssistantTurnsWithStatus() {
        val archive = DeepSeekHistoryArchive.open(DeepSeekHistoryFixtures.DEEPSEEK_APP_V3_FLAT)
        val conversation = archive.readConversation(attachmentId)
        val answer = conversation.messages[1]
        assertEquals(ImportedMessageStatus.TIMEOUT, answer.status)
        assertEquals("服务器繁忙，请稍后再试。", answer.text)
        assertEquals(ImportedNoticeLevel.INFO, answer.notices.first().level)
    }

    @Test
    fun skipsEmptyConversationsAndAlreadyImportedOnes() {
        val archive = DeepSeekHistoryArchive.open(DeepSeekHistoryFixtures.DEEPSEEK_APP_V3_FLAT)
        val result = archive.importConversations()
        assertEquals(2, result.imported)
        assertEquals(1, result.skipped)
        assertEquals(0, result.failed)
        assertTrue(result.failures.isEmpty())
        assertEquals(setOf(forkedId, attachmentId), result.conversations.map { it.id }.toSet())

        val deduped = archive.importConversations(alreadyImportedIds = setOf(forkedId))
        assertEquals(1, deduped.imported)
        assertEquals(attachmentId, deduped.conversations.single().id)
        assertEquals(2, deduped.skipped)

        var progressTotal = 0
        val selected = archive.importConversations(
            selectedIds = setOf(forkedId),
            onProgress = { done, total ->
                assertEquals(1, done)
                progressTotal = total
            },
        )
        assertEquals(1, selected.imported)
        assertEquals(1, progressTotal)
    }

    @Test
    fun optionsCanDropReasoningReferencesNoticesAndAttachments() {
        val archive = DeepSeekHistoryArchive.open(DeepSeekHistoryFixtures.DEEPSEEK_APP_V3_FLAT)
        val options = DeepSeekImportOptions(
            includeReasoning = false,
            includeReferences = false,
            includeNotices = false,
            includeAttachments = false,
        )
        val forked = archive.readConversation(forkedId, options)
        assertTrue(forked.messages.all { it.reasoning == null })
        assertTrue(forked.messages.all { it.references.isEmpty() })
        assertTrue(forked.messages.all { it.notices.isEmpty() })

        val attachment = archive.readConversation(attachmentId, options)
        assertTrue(attachment.messages.all { it.attachments.isEmpty() })
    }

    @Test
    fun readsArchiveWhoseEntriesSitUnderOneTopLevelFolder() {
        val archive = DeepSeekHistoryArchive.open(DeepSeekHistoryFixtures.DEEPSEEK_APP_V3_NESTED)
        assertEquals(3, archive.formatVersion)
        assertEquals(3, archive.sessionHeaders.size)
        assertEquals(4, archive.readConversation(forkedId).messages.size)
        assertEquals(2, archive.attachmentCount)
    }

    @Test
    fun fallsBackWhenSessionIndexIsMissing() {
        val archive = DeepSeekHistoryArchive.open(DeepSeekHistoryFixtures.DEEPSEEK_APP_V3_NO_INDEX)
        assertEquals(3, archive.sessionHeaders.size)
        val attachment = archive.sessionHeaders.first { it.id == attachmentId }
        // 会话文件里 title 为空，回退用第一条用户提问
        assertEquals("看这两张截图", attachment.title)
        assertEquals(2, attachment.messageCount)
        assertEquals(1789801780000L, attachment.updatedAtMillis)
    }

    @Test
    fun readsStoredEntriesWithoutDeflate() {
        val archive = DeepSeekHistoryArchive.open(DeepSeekHistoryFixtures.DEEPSEEK_APP_V3_STORED)
        assertEquals(3, archive.sessionHeaders.size)
        assertEquals(4, archive.readConversation(forkedId).messages.size)
    }

    @Test
    fun rejectsInputThatIsNotAnExportArchive() {
        assertEquals(
            HistoryImportFailureReason.NOT_A_ZIP,
            assertFailsWith<HistoryImportException> { DeepSeekHistoryArchive.open(ByteArray(0)) }.reason,
        )
        assertEquals(
            HistoryImportFailureReason.INVALID_ZIP_STRUCTURE,
            assertFailsWith<HistoryImportException> { DeepSeekHistoryArchive.open(ByteArray(64)) }.reason,
        )
        val truncated = DeepSeekHistoryFixtures.DEEPSEEK_APP_V3_FLAT.copyOfRange(0, 200)
        assertFailsWith<HistoryImportException> { DeepSeekHistoryArchive.open(truncated) }
    }

    @Test
    fun brokenSessionFileOnlyFailsThatConversation() {
        val archive = DeepSeekHistoryArchive.open(DeepSeekHistoryFixtures.DEEPSEEK_APP_V3_BROKEN_SESSION)
        val result = archive.importConversations()
        assertEquals(1, result.imported)
        assertEquals(forkedId, result.conversations.single().id)
        assertEquals(1, result.failed)
        val failure = result.failures.single()
        assertEquals(HistoryImportFailureReason.SESSION_JSON_INVALID, failure.reason)
        assertEquals("带截图的会话", failure.conversationTitle)
    }

    @Test
    fun parsesOfficialIsoTimestamps() {
        assertEquals(1777477984225L, IsoTimestamps.toEpochMillis("2026-04-29T15:53:04.225Z"))
        assertEquals(1777477984000L, IsoTimestamps.toEpochMillis("2026-04-29T15:53:04Z"))
        assertEquals(1777477984000L, IsoTimestamps.toEpochMillis("2026-04-29T15:53:04.000Z"))
        assertEquals(1777477984123L, IsoTimestamps.toEpochMillis("2026-04-29T15:53:04.123456Z"))
        assertEquals(1777477984000L, IsoTimestamps.toEpochMillis("2026-04-29T23:53:04+08:00"))
        assertEquals(1767225600000L, IsoTimestamps.toEpochMillis("2026-01-01T00:00:00.000Z"))
        assertEquals(0L, IsoTimestamps.toEpochMillis(null))
        assertEquals(0L, IsoTimestamps.toEpochMillis(""))
        assertEquals(0L, IsoTimestamps.toEpochMillis("not-a-date"))
    }

    @Test
    fun decodesUtf8WithReplacementInsteadOfFailing() {
        assertEquals(
            "中文",
            decodeUtf8(
                byteArrayOf(
                    0xE4.toByte(), 0xB8.toByte(), 0xAD.toByte(),
                    0xE6.toByte(), 0x96.toByte(), 0x87.toByte(),
                ),
            ),
        )
        // 4 字节字符（emoji）→ 代理对
        assertEquals("\uD83D\uDE00", decodeUtf8(byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte())))
        // 非法续字节 → 替换字符而不是抛异常
        assertEquals("\uFFFD", decodeUtf8(byteArrayOf(0xE4.toByte())))
        assertEquals("a\uFFFDb", decodeUtf8(byteArrayOf(0x61.toByte(), 0xE4.toByte(), 0x62.toByte())))
    }
}

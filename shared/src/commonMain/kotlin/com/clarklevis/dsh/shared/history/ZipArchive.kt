package com.clarklevis.dsh.shared.history

/** 随机访问的归档字节源；手机上按条目读取，避免把整包（官方导出可达数十 MB）常驻内存。 */
interface HistoryArchiveSource {
    val size: Long

    /** 读取 [offset] 起最多 [length] 字节；越界时按实际可用长度返回。 */
    fun read(offset: Long, length: Int): ByteArray
}

class ByteArrayHistoryArchiveSource(private val bytes: ByteArray) : HistoryArchiveSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset >= bytes.size.toLong()) return ByteArray(0)
        val start = offset.toInt()
        val end = minOf(bytes.size, start + maxOf(0, length))
        return bytes.copyOfRange(start, end)
    }
}

/** zip 中央目录里的一条记录。 */
class HistoryArchiveEntry(
    val name: String,
    val compressionMethod: Int,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val crc32: Long,
    val localHeaderOffset: Long,
    val flags: Int,
) {
    val isDirectory: Boolean get() = name.endsWith("/")
    val isEncrypted: Boolean get() = flags and 0x1 != 0
}

/**
 * 只读 zip 解析器：读中央目录拿条目表，读本地头部定位数据区。
 *
 * 兼容点：导出包的条目可能全部位于一个顶层目录下（例如 `deepseek_data-2026-11-17/chat_sessions/...`），
 * 这里只按条目名匹配，由上层决定前缀。
 */
class ZipArchive private constructor(
    private val source: HistoryArchiveSource,
    val entries: List<HistoryArchiveEntry>,
) {
    private val entryByName: Map<String, HistoryArchiveEntry> = entries.associateBy { it.name }

    fun entry(name: String): HistoryArchiveEntry? = entryByName[name]

    fun entriesUnder(prefix: String): List<HistoryArchiveEntry> =
        entries.filter { it.name.startsWith(prefix) && !it.isDirectory }

    fun read(entry: HistoryArchiveEntry): ByteArray {
        if (entry.isEncrypted) throw HistoryImportException(HistoryImportFailureReason.ENTRY_ENCRYPTED, "entry=${entry.name}")
        if (entry.uncompressedSize > MAX_ENTRY_BYTES) {
            throw HistoryImportException(HistoryImportFailureReason.ENTRY_TOO_LARGE, "entry=${entry.name}")
        }
        val header = source.read(entry.localHeaderOffset, LOCAL_HEADER_BYTES)
        if (header.size < LOCAL_HEADER_BYTES) throw HistoryImportException(HistoryImportFailureReason.INVALID_ZIP_STRUCTURE, "local header truncated entry=${entry.name}")
        if (littleEndianInt(header, 0) != LOCAL_HEADER_SIGNATURE) {
            throw HistoryImportException(HistoryImportFailureReason.INVALID_ZIP_STRUCTURE, "local header signature entry=${entry.name}")
        }
        val nameLength = littleEndianShort(header, 26)
        val extraLength = littleEndianShort(header, 28)
        val dataOffset = entry.localHeaderOffset + LOCAL_HEADER_BYTES + nameLength + extraLength
        val compressed = source.read(dataOffset, entry.compressedSize.toInt())
        if (compressed.size.toLong() != entry.compressedSize) {
            throw HistoryImportException(HistoryImportFailureReason.ENTRY_TRUNCATED, "entry=${entry.name}")
        }
        val raw = when (entry.compressionMethod) {
            METHOD_STORED -> compressed
            METHOD_DEFLATED -> ZipInflate.inflate(
                compressed,
                0,
                compressed.size,
                entry.uncompressedSize.toInt(),
            )
            else -> throw HistoryImportException(HistoryImportFailureReason.UNSUPPORTED_COMPRESSION, "method=${entry.compressionMethod} entry=${entry.name}")
        }
        if (entry.crc32 != 0L && entry.crc32 != 0xFFFFFFFFL) {
            val actual = Crc32.checksum(raw)
            if (actual != entry.crc32) throw HistoryImportException(HistoryImportFailureReason.CRC_MISMATCH, "entry=${entry.name}")
        }
        return raw
    }

    fun read(name: String): ByteArray? = entry(name)?.let(::read)

    fun readText(name: String): String? = read(name)?.let(::decodeUtf8)

    companion object {
        private const val LOCAL_HEADER_SIGNATURE = 0x04034B50
        private const val CENTRAL_HEADER_SIGNATURE = 0x02014B50
        private const val END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054B50
        private const val LOCAL_HEADER_BYTES = 30
        private const val CENTRAL_HEADER_BYTES = 46
        private const val END_OF_CENTRAL_DIRECTORY_BYTES = 22
        private const val MAX_COMMENT_BYTES = 0xFFFF
        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATED = 8
        private const val MAX_ENTRY_BYTES = 512L * 1024 * 1024

        fun open(source: HistoryArchiveSource): ZipArchive {
            if (source.size < END_OF_CENTRAL_DIRECTORY_BYTES) throw HistoryImportException(HistoryImportFailureReason.NOT_A_ZIP, null)
            val scanBytes = minOf(source.size, (END_OF_CENTRAL_DIRECTORY_BYTES + MAX_COMMENT_BYTES).toLong()).toInt()
            val tail = source.read(source.size - scanBytes, scanBytes)
            var directoryOffsetInTail = -1
            var index = tail.size - END_OF_CENTRAL_DIRECTORY_BYTES
            while (index >= 0) {
                if (littleEndianInt(tail, index) == END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
                    val commentLength = littleEndianShort(tail, index + 20)
                    if (index + END_OF_CENTRAL_DIRECTORY_BYTES + commentLength == tail.size) {
                        directoryOffsetInTail = index
                        break
                    }
                }
                index--
            }
            if (directoryOffsetInTail < 0) throw HistoryImportException(HistoryImportFailureReason.INVALID_ZIP_STRUCTURE, "end of central directory")
            val entryCount = littleEndianShort(tail, directoryOffsetInTail + 10)
            val directorySize = littleEndianInt(tail, directoryOffsetInTail + 12).toLong() and 0xFFFFFFFFL
            val directoryOffset = littleEndianInt(tail, directoryOffsetInTail + 16).toLong() and 0xFFFFFFFFL
            if (entryCount == 0xFFFF || directoryOffset == 0xFFFFFFFFL || directorySize == 0xFFFFFFFFL) {
                throw HistoryImportException(HistoryImportFailureReason.ZIP64_UNSUPPORTED, null)
            }
            if (directoryOffset + directorySize > source.size) throw HistoryImportException(HistoryImportFailureReason.INVALID_ZIP_STRUCTURE, "central directory offset")
            val directory = source.read(directoryOffset, directorySize.toInt())
            if (directory.size.toLong() != directorySize) throw HistoryImportException(HistoryImportFailureReason.ENTRY_TRUNCATED, "central directory")
            val entries = ArrayList<HistoryArchiveEntry>(entryCount)
            var cursor = 0
            while (cursor + CENTRAL_HEADER_BYTES <= directory.size) {
                if (littleEndianInt(directory, cursor) != CENTRAL_HEADER_SIGNATURE) break
                val flags = littleEndianShort(directory, cursor + 8)
                val method = littleEndianShort(directory, cursor + 10)
                val crc = littleEndianInt(directory, cursor + 16).toLong() and 0xFFFFFFFFL
                val compressedSize = littleEndianInt(directory, cursor + 20).toLong() and 0xFFFFFFFFL
                val uncompressedSize = littleEndianInt(directory, cursor + 24).toLong() and 0xFFFFFFFFL
                val nameLength = littleEndianShort(directory, cursor + 28)
                val extraLength = littleEndianShort(directory, cursor + 30)
                val commentLength = littleEndianShort(directory, cursor + 32)
                val localOffset = littleEndianInt(directory, cursor + 42).toLong() and 0xFFFFFFFFL
                val nameStart = cursor + CENTRAL_HEADER_BYTES
                if (nameStart + nameLength > directory.size) break
                val name = decodeUtf8(directory.copyOfRange(nameStart, nameStart + nameLength))
                entries += HistoryArchiveEntry(
                    name = name,
                    compressionMethod = method,
                    compressedSize = compressedSize,
                    uncompressedSize = uncompressedSize,
                    crc32 = crc,
                    localHeaderOffset = localOffset,
                    flags = flags,
                )
                cursor = nameStart + nameLength + extraLength + commentLength
            }
            if (entries.isEmpty()) throw HistoryImportException(HistoryImportFailureReason.INVALID_ZIP_STRUCTURE, "no entries")
            return ZipArchive(source, entries)
        }

        internal fun littleEndianInt(bytes: ByteArray, offset: Int): Int {
            if (offset + 4 > bytes.size) return 0
            return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
        }

        internal fun littleEndianShort(bytes: ByteArray, offset: Int): Int {
            if (offset + 2 > bytes.size) return 0
            return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        }
    }
}

/**
 * 条目名与正文都按 UTF-8 解码；官方导出里出现过非法序列，换掉而不是整体失败。
 */
internal fun decodeUtf8(bytes: ByteArray): String {
    val builder = StringBuilder(bytes.size)
    var index = 0
    while (index < bytes.size) {
        val first = bytes[index].toInt() and 0xFF
        when {
            first < 0x80 -> {
                builder.append(first.toChar())
                index++
            }
            first in 0xC2..0xDF && index + 1 < bytes.size -> {
                val second = bytes[index + 1].toInt() and 0xFF
                if (second and 0xC0 == 0x80) {
                    builder.append((((first and 0x1F) shl 6) or (second and 0x3F)).toChar())
                    index += 2
                } else {
                    builder.append('\uFFFD')
                    index++
                }
            }
            first in 0xE0..0xEF && index + 2 < bytes.size -> {
                val second = bytes[index + 1].toInt() and 0xFF
                val third = bytes[index + 2].toInt() and 0xFF
                if (second and 0xC0 == 0x80 && third and 0xC0 == 0x80) {
                    val codePoint = ((first and 0x0F) shl 12) or ((second and 0x3F) shl 6) or (third and 0x3F)
                    builder.append(codePoint.toChar())
                    index += 3
                } else {
                    builder.append('\uFFFD')
                    index++
                }
            }
            first in 0xF0..0xF4 && index + 3 < bytes.size -> {
                val second = bytes[index + 1].toInt() and 0xFF
                val third = bytes[index + 2].toInt() and 0xFF
                val fourth = bytes[index + 3].toInt() and 0xFF
                if (second and 0xC0 == 0x80 && third and 0xC0 == 0x80 && fourth and 0xC0 == 0x80) {
                    val codePoint = ((first and 0x07) shl 18) or ((second and 0x3F) shl 12) or
                        ((third and 0x3F) shl 6) or (fourth and 0x3F)
                    if (codePoint in 0x10000..0x10FFFF) {
                        val offset = codePoint - 0x10000
                        builder.append((0xD800 + (offset shr 10)).toChar())
                        builder.append((0xDC00 + (offset and 0x3FF)).toChar())
                    } else {
                        builder.append('\uFFFD')
                    }
                    index += 4
                } else {
                    builder.append('\uFFFD')
                    index++
                }
            }
            else -> {
                builder.append('\uFFFD')
                index++
            }
        }
    }
    return builder.toString()
}

internal object Crc32 {
    private val table: IntArray = IntArray(256) { index ->
        var value = index
        repeat(8) { value = if (value and 1 != 0) (value ushr 1) xor 0xEDB88320.toInt() else value ushr 1 }
        value
    }

    fun checksum(bytes: ByteArray): Long {
        var crc = -1
        for (byte in bytes) {
            crc = (crc ushr 8) xor table[(crc xor byte.toInt()) and 0xFF]
        }
        return (crc.inv().toLong() and 0xFFFFFFFFL)
    }
}

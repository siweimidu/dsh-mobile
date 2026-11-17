package com.clarklevis.dsh.shared.history

/**
 * RFC 1951 raw DEFLATE 解码器。
 *
 * 导入的官方导出包是 zip，而 iOS 没有公开的 zip API、Android 的 java.util.zip 又不能在
 * commonMain 使用。为了两端共用同一份实现（并且能在 commonTest 里被真实数据验证），
 * 这里用纯 Kotlin 解码 raw DEFLATE，不依赖任何平台库。
 */
internal object ZipInflate {
    private val lengthBase = intArrayOf(
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
        35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
    )
    private val lengthExtra = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
        3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
    )
    private val distanceBase = intArrayOf(
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
        257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577,
    )
    private val distanceExtra = intArrayOf(
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
        7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
    )
    private val codeLengthOrder = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

    private val fixedLiterals: HuffmanTable by lazy {
        val lengths = IntArray(288)
        for (symbol in 0..143) lengths[symbol] = 8
        for (symbol in 144..255) lengths[symbol] = 9
        for (symbol in 256..279) lengths[symbol] = 7
        for (symbol in 280..287) lengths[symbol] = 8
        HuffmanTable.build(lengths)
    }
    private val fixedDistances: HuffmanTable by lazy {
        HuffmanTable.build(IntArray(30) { 5 })
    }

    /**
     * 解码 [sourceOffset] 起、长度为 [sourceLength] 的 raw DEFLATE 数据，输出长度由 zip 目录给出。
     */
    fun inflate(source: ByteArray, sourceOffset: Int, sourceLength: Int, expectedSize: Int): ByteArray {
        require(sourceOffset >= 0 && sourceLength >= 0 && sourceOffset + sourceLength <= source.size)
        val reader = BitReader(source, sourceOffset, sourceOffset + sourceLength)
        val out = ByteArray(expectedSize)
        var produced = 0
        var finalBlock = false
        while (!finalBlock) {
            finalBlock = reader.bits(1) == 1
            when (reader.bits(2)) {
                0 -> produced = reader.readStoredBlock(out, produced)
                1 -> produced = reader.readHuffmanBlock(out, produced, fixedLiterals, fixedDistances)
                2 -> {
                    val (literals, distances) = reader.readDynamicTables()
                    produced = reader.readHuffmanBlock(out, produced, literals, distances)
                }
                else -> throw HistoryImportException(HistoryImportFailureReason.INFLATE_CORRUPTED, "block type")
            }
        }
        if (produced != expectedSize) {
            throw HistoryImportException(HistoryImportFailureReason.INFLATE_CORRUPTED, "length expected=$expectedSize actual=$produced")
        }
        return out
    }

    private fun BitReader.readStoredBlock(out: ByteArray, produced: Int): Int {
        alignToByte()
        val rawLength = readByte() or (readByte() shl 8)
        val rawCheck = readByte() or (readByte() shl 8)
        if (rawLength xor rawCheck != 0xFFFF) throw HistoryImportException(HistoryImportFailureReason.INFLATE_CORRUPTED, "stored block length")
        if (produced + rawLength > out.size) throw HistoryImportException(HistoryImportFailureReason.INFLATE_CORRUPTED, "stored block overflow")
        for (index in 0 until rawLength) out[produced + index] = readByte().toByte()
        return produced + rawLength
    }

    private fun BitReader.readDynamicTables(): Pair<HuffmanTable, HuffmanTable> {
        val literalCount = bits(5) + 257
        val distanceCount = bits(5) + 1
        val codeLengthCount = bits(4) + 4
        if (literalCount > 286 || distanceCount > 30) throw HistoryImportException(HistoryImportFailureReason.INFLATE_CORRUPTED, "dynamic table size")
        val codeLengths = IntArray(19)
        for (index in 0 until codeLengthCount) codeLengths[codeLengthOrder[index]] = bits(3)
        val codeLengthTable = HuffmanTable.build(codeLengths)
        val lengths = IntArray(literalCount + distanceCount)
        var index = 0
        while (index < lengths.size) {
            when (val symbol = codeLengthTable.decode(this)) {
                in 0..15 -> lengths[index++] = symbol
                16 -> {
                    if (index == 0) throw HistoryImportException(HistoryImportFailureReason.INFLATE_CORRUPTED, "repeat without previous")
                    val previous = lengths[index - 1]
                    repeat(bits(2) + 3) {
                        if (index >= lengths.size) throw HistoryImportException(HistoryImportFailureReason.INFLATE_CORRUPTED, "repeat overflow")
                        lengths[index++] = previous
                    }
                }
                17 -> repeat(bits(3) + 3) {
                    if (index >= lengths.size) throw HistoryImportException(HistoryImportFailureReason.INFLATE_CORRUPTED, "zero run overflow")
                    lengths[index++] = 0
                }
                18 -> repeat(bits(7) + 11) {
                    if (index >= lengths.size) throw HistoryImportException(HistoryImportFailureReason.INFLATE_CORRUPTED, "long zero run overflow")
                    lengths[index++] = 0
                }
                else -> throw HistoryImportException(HistoryImportFailureReason.INFLATE_CORRUPTED, "code length symbol")
            }
        }
        if (lengths[256] == 0) throw HistoryImportException(HistoryImportFailureReason.INFLATE_CORRUPTED, "missing end code")
        val literals = HuffmanTable.build(lengths.copyOfRange(0, literalCount))
        val distances = HuffmanTable.build(lengths.copyOfRange(literalCount, lengths.size))
        return literals to distances
    }

    private fun BitReader.readHuffmanBlock(
        out: ByteArray,
        startProduced: Int,
        literals: HuffmanTable,
        distances: HuffmanTable,
    ): Int {
        var produced = startProduced
        while (true) {
            val symbol = literals.decode(this)
            when {
                symbol < 256 -> {
                    if (produced >= out.size) throw HistoryImportException(HistoryImportFailureReason.INFLATE_CORRUPTED, "output overflow")
                    out[produced++] = symbol.toByte()
                }
                symbol == 256 -> return produced
                symbol > 285 -> throw HistoryImportException(HistoryImportFailureReason.INFLATE_CORRUPTED, "length symbol")
                else -> {
                    val lengthIndex = symbol - 257
                    val length = lengthBase[lengthIndex] + bits(lengthExtra[lengthIndex])
                    val distanceSymbol = distances.decode(this)
                    if (distanceSymbol > 29) throw HistoryImportException(HistoryImportFailureReason.INFLATE_CORRUPTED, "distance symbol")
                    val distance = distanceBase[distanceSymbol] + bits(distanceExtra[distanceSymbol])
                    if (distance > produced - startProduced && distance > produced) {
                        throw HistoryImportException(HistoryImportFailureReason.INFLATE_CORRUPTED, "distance overflow")
                    }
                    if (produced + length > out.size) throw HistoryImportException(HistoryImportFailureReason.INFLATE_CORRUPTED, "output overflow")
                    var copyIndex = produced - distance
                    repeat(length) { out[produced++] = out[copyIndex++] }
                }
            }
        }
    }

    private class HuffmanTable(private val counts: IntArray, private val symbols: IntArray) {
        fun decode(reader: BitReader): Int {
            var code = 0
            var first = 0
            var index = 0
            for (length in 1..15) {
                code = code or reader.bits(1)
                val count = counts[length]
                if (code - count < first) return symbols[index + (code - first)]
                index += count
                first = (first + count) shl 1
                code = code shl 1
            }
            throw HistoryImportException(HistoryImportFailureReason.INFLATE_CORRUPTED, "huffman code")
        }

        companion object {
            fun build(lengths: IntArray): HuffmanTable {
                val counts = IntArray(16)
                for (length in lengths) {
                    if (length < 0 || length > 15) throw HistoryImportException(HistoryImportFailureReason.INFLATE_CORRUPTED, "code length")
                    counts[length]++
                }
                counts[0] = 0
                var total = 0
                val offsets = IntArray(16)
                for (length in 1..15) {
                    offsets[length] = total
                    total += counts[length]
                }
                val symbols = IntArray(total)
                lengths.forEachIndexed { symbol, length ->
                    if (length != 0) symbols[offsets[length]++] = symbol
                }
                return HuffmanTable(counts, symbols)
            }
        }
    }

    /** 位序为 LSB-first；对齐时丢弃缓冲区里不足一字节的尾部比特。 */
    private class BitReader(private val data: ByteArray, start: Int, private val end: Int) {
        private var nextByte = start
        private var bitBuffer = 0
        private var bitCount = 0

        fun bits(count: Int): Int {
            while (bitCount < count) {
                if (nextByte >= end) throw HistoryImportException(HistoryImportFailureReason.INFLATE_CORRUPTED, "truncated stream")
                bitBuffer = bitBuffer or ((data[nextByte].toInt() and 0xFF) shl bitCount)
                nextByte++
                bitCount += 8
            }
            val value = bitBuffer and ((1 shl count) - 1)
            bitBuffer = bitBuffer ushr count
            bitCount -= count
            return value
        }

        fun alignToByte() {
            val discard = bitCount and 7
            bitBuffer = bitBuffer ushr discard
            bitCount -= discard
        }

        fun readByte(): Int {
            if (bitCount >= 8) {
                val value = bitBuffer and 0xFF
                bitBuffer = bitBuffer ushr 8
                bitCount -= 8
                return value
            }
            if (nextByte >= end) throw HistoryImportException(HistoryImportFailureReason.INFLATE_CORRUPTED, "truncated stream")
            return data[nextByte++].toInt() and 0xFF
        }
    }
}

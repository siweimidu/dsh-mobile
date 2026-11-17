package com.clarklevis.dsh.shared.history

/**
 * 官方导出里的时间是 ISO-8601 UTC（例如 `2026-04-29T15:53:04.225Z`），
 * commonMain 没有日期库，这里按 RFC 3339 自己换算成 epoch 毫秒。
 */
internal object IsoTimestamps {
    fun toEpochMillis(raw: String?): Long {
        val text = raw?.trim().orEmpty()
        if (text.length < 10) return 0L
        val year = digits(text, 0, 4) ?: return 0L
        if (text[4] != '-') return 0L
        val month = digits(text, 5, 2) ?: return 0L
        if (text[7] != '-') return 0L
        val day = digits(text, 8, 2) ?: return 0L
        var index = 10
        var hour = 0
        var minute = 0
        var second = 0
        var millis = 0
        var offsetMinutes = 0
        if (index < text.length && (text[index] == 'T' || text[index] == ' ')) {
            index++
            hour = digits(text, index, 2) ?: return 0L
            index += 2
            if (index < text.length && text[index] == ':') {
                minute = digits(text, index + 1, 2) ?: return 0L
                index += 3
                if (index < text.length && text[index] == ':') {
                    second = digits(text, index + 1, 2) ?: return 0L
                    index += 3
                    if (index < text.length && (text[index] == '.' || text[index] == ',')) {
                        index++
                        var fraction = 0
                        var digits = 0
                        while (index < text.length && text[index].isDigit() && digits < 3) {
                            fraction = fraction * 10 + (text[index] - '0')
                            digits++
                            index++
                        }
                        while (index < text.length && text[index].isDigit()) index++
                        millis = when (digits) {
                            0 -> 0
                            1 -> fraction * 100
                            2 -> fraction * 10
                            else -> fraction
                        }
                    }
                }
            }
        }
        if (index < text.length) {
            when (text[index]) {
                'Z', 'z' -> Unit
                '+', '-' -> {
                    val sign = if (text[index] == '-') -1 else 1
                    val offsetHour = digits(text, index + 1, 2) ?: 0
                    var offsetMinute = 0
                    var cursor = index + 3
                    if (cursor < text.length && text[cursor] == ':') cursor++
                    if (cursor + 2 <= text.length) offsetMinute = digits(text, cursor, 2) ?: 0
                    offsetMinutes = sign * (offsetHour * 60 + offsetMinute)
                }
            }
        }
        val days = daysFromCivil(year, month, day)
        return days * 86_400_000L +
            (hour * 3_600L + minute * 60L + second) * 1_000L +
            millis -
            offsetMinutes * 60_000L
    }

    private fun digits(text: String, start: Int, count: Int): Int? {
        if (start < 0 || start + count > text.length) return null
        var value = 0
        for (index in start until start + count) {
            val char = text[index]
            if (!char.isDigit()) return null
            value = value * 10 + (char - '0')
        }
        return value
    }

    /** Howard Hinnant 的 days_from_civil。 */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val adjustedYear = if (month <= 2) year - 1 else year
        val era = (if (adjustedYear >= 0) adjustedYear else adjustedYear - 399) / 400
        val yearOfEra = adjustedYear - era * 400
        val adjustedMonth = (month + 9) % 12
        val dayOfYear = (153 * adjustedMonth + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era.toLong() * 146_097L + dayOfEra.toLong() - 719_468L
    }
}

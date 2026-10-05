package com.uniomo.wafce.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * 统一的时间格式化。
 *
 * 为什么要单独抽一个工具：雷池不同接口、不同版本返回的时间字段类型完全不同，
 * 之前各页面各写一份 `if (raw > 10_000_000_000L) raw else raw * 1000`，
 * 结果「频率控制」「人机验证」这两个页签显示的是 `created_at` 的原始字符串
 * （官方 OpenAPI 里 `acl.Log.created_at`、`api.ChallengeLog.created_at`
 * 都声明为 **string**），人根本看不懂。
 *
 * 这里按官方 OpenAPI 的实际类型做穷举兼容：
 * - 整数时间戳：秒 / 毫秒 / 微秒 / 纳秒（按位数判定）
 * - 数字字符串："1759680380"
 * - ISO-8601 / RFC3339："2026-10-05T15:46:20Z"、"...+08:00"、带小数秒
 * - 普通日期串："2026-10-05 15:46:20"、"2026-10-05"
 *
 * 时区处理：带偏移的输入（Z / +08:00）会被正确换算为设备本地时间；
 * 不带偏移的裸时间串按设备本地时区解释（雷池服务端的裸时间即本地时间）。
 *
 * 全部解析失败时返回空串；调用方若需保留原文请自行回退。
 */
object TimeFmt {

    private const val PAT_FULL = "yyyy-MM-dd HH:mm:ss"
    private const val PAT_MINUTE = "yyyy-MM-dd HH:mm"
    private const val PAT_DATE = "yyyy-MM-dd"
    private const val PAT_CLOCK = "HH:mm"

    /** 「2026-10-05 15:46」：日志列表与实时事件用，精确到分钟 */
    const val PAT_MINUTE_STAMP = PAT_MINUTE

    /** 「2026-10-05 15:46:20」：详情类使用，精确到秒 */
    const val PAT_FULL_STAMP = PAT_FULL

    // DateTimeFormatter 线程安全且不可变，可直接做常量，避免每次渲染都新建 SimpleDateFormat。
    // 固定 Locale.CHINA：避免 th_TH 佛历、ar_SA 希吉来历法下输出 2569 这类错误年份。
    private val FMT_FULL = pat(PAT_FULL)
    private val FMT_MINUTE = pat(PAT_MINUTE)
    private val FMT_DATE = pat(PAT_DATE)
    private val FMT_CLOCK = pat(PAT_CLOCK)

    private fun pat(p: String) = DateTimeFormatter.ofPattern(p, Locale.CHINA)

    /** 「2026-10-05 15:46」：日志列表与实时事件用，精确到分钟 */
    fun stamp(ms: Long): String = format(ms, FMT_MINUTE)

    /** 「2026-10-05 15:46:20」：详情类使用，精确到秒 */
    fun full(ms: Long): String = format(ms, FMT_FULL)

    /** 「2026-10-05」：证书有效期等只需日期的场景 */
    fun date(ms: Long): String = format(ms, FMT_DATE)

    /** 「15:46」：趋势图坐标轴 */
    fun clock(ms: Long): String = format(ms, FMT_CLOCK)

    /** 距今多久，用于「3 分钟前」这类提示；异常输入回落到完整时间 */
    fun relative(ms: Long, now: Long = System.currentTimeMillis()): String {
        if (ms <= 0L) return ""
        val diff = now - ms
        return when {
            diff < 0 -> full(ms)
            diff < 60_000L -> "刚刚"
            diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
            diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
            diff < 7 * 86_400_000L -> "${diff / 86_400_000L} 天前"
            else -> full(ms)
        }
    }

    private fun format(ms: Long, f: DateTimeFormatter): String {
        if (ms <= 0L) return ""
        return try {
            f.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
        } catch (e: Exception) {
            // 绝不把裸epoch 毫秒直接上屏，用户看不懂
            ""
        }
    }

    /**
     * 把秒/毫秒/微秒/纳秒统一换算成毫秒。
     *
     * 判据用位数而不是「离 1.8e9 有多远」：时间戳的位数是稳定的，
     * 而 1e11 这种边界既可能是 5138 年的时间，也可能是毫秒，语义本身模糊。
     * 当前时间戳位数：秒 10 位 / 毫秒 13 位 / 微秒 16 位 / 纳秒 19 位。
     */
    fun toMillis(raw: Long): Long {
        if (raw <= 0L) return 0L
        val digits = digitCount(raw)
        return when {
            digits <= 10 -> raw * 1000L          // 秒
            digits <= 11 -> raw * 100_000L       // 毫秒（1973 年前少见，宽松接受）
            digits <= 14 -> raw                 // 毫秒
            digits <= 17 -> raw / 1_000L        // 微秒
            else -> raw / 1_000_000L           // 纳秒
        }
    }

    private fun digitCount(v: Long): Int {
        var n = v
        var c = 0
        while (n > 0) {
            c++
            n /= 10
        }
        return c
    }

    /**
     * 从对象里按字段名顺序取第一个能解析成时间的时间值，返回毫秒。
     * 找不到返回 0。数字与字符串两种类型都会尝试。
     */
    fun millisOf(o: JsonObject?, vararg keys: String): Long {
        val obj = o ?: return 0L
        for (k in keys) {
            val ms = millisOfElement(obj.get(k))
            if (ms > 0L) return ms
        }
        return 0L
    }

    private fun millisOfElement(el: JsonElement?): Long {
        if (el == null || el.isJsonNull || !el.isJsonPrimitive) return 0L
        val p = el.asJsonPrimitive
        return try {
            if (p.isNumber) toMillis(p.asLong) else parseText(p.asString)
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * 从对象里取时间并格式化成 [pattern]。
     * 解析不出来时返回空串，调用方决定是否回退到原始文本。
     */
    fun formatOf(o: JsonObject?, pattern: String, vararg keys: String): String {
        val ms = millisOf(o, *keys)
        return if (ms > 0L) format(ms, pat(pattern)) else ""
    }

    /**
     * 日志条目的时间字段名，覆盖官方 OpenAPI 中各日志模型。
     * 数字与字符串都会尝试，因此不必按字段类型分组。
     */
    private val LOG_TIME_KEYS = arrayOf(
        "timestamp", "time_stamp", "start_at", "started_at", "ended_at",
        "created_at", "create_time", "create_at", "updated_at",
        "ts", "time", "date", "datetime", "event_time"
    )

    /** 取「日志条目时间」：优先结构化时间戳，取不到再退回字符串字段 */
    fun logTimeOf(o: JsonObject?): String {
        val ms = millisOf(o, *LOG_TIME_KEYS)
        return if (ms > 0L) stamp(ms) else ""
    }

    /** 同 [logTimeOf]，但返回完整到秒，用于详情页 */
    fun logTimeFullOf(o: JsonObject?): String {
        val ms = millisOf(o, *LOG_TIME_KEYS)
        return if (ms > 0L) full(ms) else ""
    }

    /**
     * 宽松解析时间文本，成功返回毫秒，失败返回 0。
     * 支持纯数字、ISO-8601（Z / ±HH:MM / 小数秒）、"yyyy-MM-dd HH:mm:ss" 等。
     */
    fun parseText(raw: String): Long {
        val text = raw.trim()
        if (text.isEmpty()) return 0L

        // 纯数字按时间戳处理
        if (text.all { it.isDigit() }) {
            val n = text.toLongOrNull() ?: return 0L
            return toMillis(n)
        }

        // 带时区偏移 / Z 的 ISO-8601：交给 parser 自己换算，不要手工截断偏移
        parseIsoWithZone(text)?.let { return it }

        // 裸时间串（无偏移）：先补齐缺失部分再按本地时区解释。
        // 雷池服务端的裸时间本身就是本地时间，这样处理与控制台一致。
        parseLocalText(text)?.let { return it }
        return 0L
    }

    /** "2026-10-05T23:57:04.123Z" / "...+08:00" / "...-05:00" / 带方括号时区名 */
    private fun parseIsoWithZone(text: String): Long? {
        if (!(text.endsWith("Z") || text.endsWith("z") ||
                Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(text) ||
                text.endsWith("]"))
        ) return null
        return try {
            OffsetDateTime.parse(text)?.toInstant()?.toEpochMilli()
        } catch (e: DateTimeParseException) {
            try {
                // 方括号时区名，如 "2026-10-05T23:57:04+08:00[Asia/Shanghai]"
                val cleaned = text.substringBefore('[')
                if (cleaned == text) throw e
                ZonedDateTime.parse(text).toInstant().toEpochMilli()
            } catch (e2: DateTimeParseException) {
                try {
                    Instant.parse(text).toEpochMilli()
                } catch (e3: DateTimeParseException) {
                    null
                }
            }
        }
    }

    /**
     * 裸时间串：把 'T' 或空格统一成分隔符，按从完整到宽松的顺序尝试。
     * DateTimeFormatter 默认严格，非法日期（如 13 月45 日）会直接失败而不是被补齐。
     */
    private fun parseLocalText(text: String): Long? {
        val normalized = normalizeLocal(text)
        if (normalized.isEmpty()) return null
        for (f in arrayOf(FMT_FULL, FMT_MINUTE, FMT_DATE)) {
            try {
                return LocalDateTime.parse(normalized, f).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (e: DateTimeParseException) {
                // 继续尝试下一个更宽松的 pattern
            }
        }
        try {
            return LocalDate.parse(normalized, FMT_DATE).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            return null
        }
    }

    /**
     * 归一化裸时间串：把 'T' 或空格统一成分隔符，补齐缺失的时分秒。
     *
     * 返回值可能是**只有日期**的 "yyyy-MM-dd"（无分隔符、或时间部分为空），
     * 由 [parseLocalText] 的 FMT_DATE 分支接手 —— 不要在这里就把纯日期丢掉，
     * 证书有效期（not_after）等字段就是这种格式。
     */
    private fun normalizeLocal(text: String): String {
        val sep = text.indexOfFirst { it == 'T' || it == ' ' }
        // 纯日期 "2026-10-05"：没有时间部分，交给纯日期解析分支
        if (sep < 0) return text.trim()
        val datePart = text.substring(0, sep)
        var i = sep + 1
        while (i < text.length && text[i] != '.' && text[i] != 'Z' && text[i] != 'z' && text[i] != '+' && text[i] != '-') i++
        var timePart = text.substring(sep + 1, i)
        // 有分隔符但时间为空（如 "2026-10-05T" / "2026-10-05 "）：同样按纯日期处理
        if (timePart.isEmpty()) return datePart
        if (timePart.length == 2) return "$datePart $timePart:00:00"          // HH
        if (timePart.length == 4 && !timePart.contains(':')) {
            // HHmm 紧凑写法必须补中间的冒号，否则 "1546:00" 匹配不上 "HH:mm:ss"
            return "$datePart ${timePart.substring(0, 2)}:${timePart.substring(2)}:00"
        }
        return "$datePart $timePart"
    }

    /** 便捷方法：任意 JsonElement 转可读时间 */
    fun ofElement(el: JsonElement?): String {
        val ms = millisOfElement(el)
        return if (ms > 0L) stamp(ms) else ""
    }
}
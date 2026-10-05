package com.uniomo.wafce.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * 雷池返回体统一结构：
 * { "err": null | string, "data": <object|array>, "msg": "..." }
 *
 * 各版本、各接口返回值字段差异较大，因此统一使用 JsonObject 承载，
 * 由上层按需取值，避免实体类反序列化失败导致整页不可用。
 */
object Resp {

    fun unwrap(body: JsonObject?): JsonElement {
        val obj = body ?: throw ApiException("服务端未返回有效数据")
        val err = obj.get("err")
        if (err != null && err.isJsonPrimitive && (err as JsonPrimitive).isString) {
            val msg = err.asString
            if (msg.isNotBlank()) throw ApiException(msg)
        }
        if (!obj.has("data")) throw ApiException("返回数据缺少 data 字段")
        return obj.get("data")
    }

    fun obj(body: JsonObject?): JsonObject {
        val d = unwrap(body)
        if (d.isJsonNull) return JsonObject()
        if (!d.isJsonObject) throw ApiException("返回数据格式非对象")
        return d.asJsonObject
    }

    fun arr(body: JsonObject?): List<JsonObject> {
        val d = unwrap(body)
        if (d.isJsonNull) return emptyList()
        if (d.isJsonArray) {
            return d.asJsonArray.mapNotNull {
                if (it.isJsonObject) it.asJsonObject else null
            }
        }
        // 分页结构：{ total, nodes/data: [] }
        if (d.isJsonObject) {
            val o = d.asJsonObject
            return nodes(o)
        }
        return emptyList()
    }

    /** 分页对象中提取列表，兼容 nodes / data / records 等多种键名 */
    fun nodes(parent: JsonObject): List<JsonObject> {
        for (key in arrayOf("nodes", "data", "records", "items", "list")) {
            val el = parent.get(key)
            if (el != null && el.isJsonArray) {
                return el.asJsonArray.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
            }
        }
        return emptyList()
    }

    fun total(parent: JsonObject?): Int {
        if (parent == null) return 0
        return first(parent, "total", "count") ?: 0
    }

    fun str(parent: JsonObject?, vararg keys: String): String {
        val el = pick(parent, *keys) ?: return ""
        if (el.isJsonNull) return ""
        return try {
            el.asString
        } catch (e: Exception) {
            el.toString()
        }
    }

    fun l(parent: JsonObject?, vararg keys: String): Long {
        val el = pick(parent, *keys) ?: return 0L
        if (el.isJsonNull) return 0L
        return try {
            if (el.isJsonPrimitive && (el as JsonPrimitive).isNumber) el.asLong else el.asString.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    fun i(parent: JsonObject?, vararg keys: String): Int = l(parent, *keys).toInt()

    fun b(parent: JsonObject?, vararg keys: String): Boolean {
        val el = pick(parent, *keys) ?: return false
        if (el.isJsonNull) return false
        return try {
            if (el.isJsonPrimitive && (el as JsonPrimitive).isBoolean) el.asBoolean
            else l(parent, *keys) != 0L
        } catch (e: Exception) {
            false
        }
    }

    fun strList(parent: JsonObject?, vararg keys: String): List<String> {
        val el = pick(parent, *keys) ?: return emptyList()
        if (!el.isJsonArray) return emptyList()
        return el.asJsonArray.map {
            if (it.isJsonPrimitive) it.asString else it.toString()
        }
    }

    private fun first(parent: JsonObject, vararg keys: String): Int? {
        for (k in keys) {
            val el = parent.get(k)
            if (el != null && el.isJsonPrimitive && (el as JsonPrimitive).isNumber) return el.asInt
        }
        return null
    }

    // ------------------------------------------------------------------
    // 版本无关取值：雷池各版本统计接口字段命名差异较大，
    // 这里用「广度优先找第一个数组 / 对象」的方式兼容不同结构。
    // ------------------------------------------------------------------

    /** 在任意层级中找到第一个数组 */
    fun firstArray(root: JsonElement?): JsonArray? {
        if (root == null || root.isJsonNull) return null
        if (root.isJsonArray) return root.asJsonArray
        if (!root.isJsonObject) return null
        for ((_, v) in root.asJsonObject.entrySet()) {
            firstArray(v)?.let { return it }
        }
        return null
    }

    /** 在任意层级中找到第一个对象 */
    fun firstObj(root: JsonElement?): JsonObject? {
        if (root == null || root.isJsonNull) return null
        if (root.isJsonObject) return root.asJsonObject
        if (!root.isJsonArray) return null
        for (v in root.asJsonArray) {
            firstObj(v)?.let { return it }
        }
        return null
    }

    private fun isNumber(el: JsonElement): Boolean =
        el.isJsonPrimitive && (el as JsonPrimitive).isNumber

    /** 收集结构中出现的全部数值，顺序遍历，用于画趋势图 */
    fun flattenNumbers(el: JsonElement?, limit: Int = 720): MutableList<Float> {
        val out = mutableListOf<Float>()
        collect(el, out, limit)
        return out
    }

    private fun collect(el: JsonElement?, out: MutableList<Float>, limit: Int) {
        when {
            el == null || el.isJsonNull -> Unit
            isNumber(el) -> if (out.size < limit) out.add(el.asFloat)
            el.isJsonArray -> for (v in el.asJsonArray) collect(v, out, limit)
            el.isJsonObject -> {
                // 优先沿 nodes/data 这类列表键下钻，其次普通字段
                val obj = el.asJsonObject
                val ordered = obj.entrySet().sortedBy { (k, _) ->
                    when (k) {
                        "nodes", "data", "records", "items", "list" -> 0
                        else -> 1
                    }
                }
                for ((_, v) in ordered) collect(v, out, limit)
            }
        }
    }

    /**
     * 通用 Top-N 解析：对数组中每个对象，取「第一个字符串字段」作为名称，
     * 取「第一个数值字段」作为指标值，兼容 {ip,count}、{name,value} 等多种命名。
     */
    fun pairsOf(body: JsonObject?, limit: Int = 5): List<Pair<String, Long>> {
        val data = runCatching { unwrap(body) }.getOrNull() ?: return emptyList()
        val arr = firstArray(data) ?: return emptyList()
        val out = mutableListOf<Pair<String, Long>>()
        for (el in arr) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            var label = ""
            var value = 0L
            for ((k, v) in obj.entrySet()) {
                if (v.isJsonPrimitive) {
                    val p = v as JsonPrimitive
                    if (label.isEmpty() && p.isString && p.asString.isNotBlank() && k !in IGNORE_LABEL_KEYS) {
                        label = p.asString
                    } else if (value == 0L && p.isNumber) {
                        value = p.asLong
                    }
                }
                if (label.isNotEmpty() && value != 0L) break
            }
            if (label.isEmpty()) continue
            out.add(label to value)
            if (out.size >= limit) break
        }
        return out
    }

    private val IGNORE_LABEL_KEYS = setOf("id", "uuid", "hash", "key")

    // ------------------------------------------------------------------
    // 时序数据解析（趋势图数据源）
    //
    // 雷池各版本的统计接口（stat/qps、dashboard/requests、dashboard/intercepts…）
    // 返回结构差异很大：有的是裸数组，有的是 { nodes: [...] }，
    // 数组元素还可能是纯数字而非对象。这里用「就地和 + 忽略时间字段」的方式兼容。
    // ------------------------------------------------------------------

    /** 时序点：数值 + 时间标签（标签可能为空） */
    data class SeriesPoint(val value: Float, val label: String)

    private val SERIES_LABEL_KEYS = arrayOf(
        "time", "time_stamp", "timestamp", "ts", "date", "datetime",
        "hour", "t", "label", "name", "day"
    )

    private val SERIES_LABEL_SET = SERIES_LABEL_KEYS.toSet()

    /** 取 data 层，失败返回 null（统计接口经常整块缺失，不应抛异常） */
    fun dataOrNull(body: JsonObject?): JsonElement? = runCatching { unwrap(body) }.getOrNull()

    /**
     * 宽松解析时序数组。元素为对象时，把所有「非时间字段的数值」求和作为该点数值；
     * 元素为裸数字时直接作为数值点。
     */
    fun seriesOf(body: JsonObject?): List<SeriesPoint> {
        val arr = firstArray(dataOrNull(body)) ?: return emptyList()
        val out = mutableListOf<SeriesPoint>()
        for (el in arr) {
            if (el.isJsonPrimitive && (el as JsonPrimitive).isNumber) {
                out.add(SeriesPoint(el.asFloat, ""))
                continue
            }
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            var value = 0f
            for ((k, v) in o.entrySet()) {
                if (k.lowercase() in SERIES_LABEL_SET) continue
                if (v.isJsonPrimitive && (v as JsonPrimitive).isNumber) value += v.asFloat
            }
            out.add(SeriesPoint(value, labelOf(o)))
        }
        return out
    }

    /** 从时序元素里取时间标签，能转成时间戳就格式化成 HH:mm */
    private fun labelOf(o: JsonObject): String {
        for (k in SERIES_LABEL_KEYS) {
            val el = o.get(k) ?: continue
            if (el.isJsonNull || !el.isJsonPrimitive) continue
            val p = el.asJsonPrimitive
            if (p.isNumber) return clockLabel(p.asLong)
            if (p.isString && p.asString.isNotBlank()) return p.asString.take(16)
        }
        return ""
    }

    private fun clockLabel(raw: Long): String = try {
        val ms = if (raw > 10_000_000_000L) raw else raw * 1000L
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(ms))
    } catch (e: Exception) {
        ""
    }

    /**
     * 统计对象里的「总数」：优先 total/count，其次统计所有数字字段求和。
     * security_posture 这类接口没有 total，需要按字段汇总。
     */
    fun sumNumbers(o: JsonObject?, vararg skip: String): Long {
        val obj = o ?: return 0L
        val skipSet = skip.toSet()
        var sum = 0L
        for ((k, v) in obj.entrySet()) {
            if (k.lowercase() in skipSet) continue
            if (v.isJsonPrimitive && (v as JsonPrimitive).isNumber) sum += v.asLong
        }
        return sum
    }

    private fun pick(parent: JsonObject?, vararg keys: String): JsonElement? {
        if (parent == null) return null
        for (k in keys) {
            val el = parent.get(k)
            if (el != null && !el.isJsonNull) return el
        }
        return null
    }
}

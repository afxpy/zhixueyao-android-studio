package com.zhixueyao.util

/**
 * 极简 JSON 实现。
 *
 * 为什么不直接用 Gson / kotlinx-serialization：
 * IDE 插件的类加载器与平台库、其他插件共享，引入第三方 JSON 库极易与平台自带版本冲突，
 * 轻则 NoSuchMethodError，重则整个 IDE 启动失败。这里手写一份自足实现，彻底规避该风险。
 *
 * 数字统一以原始文本保存，避免 Double 转换导致的大整数精度丢失（MCP 的请求 id 会用到）。
 */
sealed class Json {

    object Null : Json()

    data class Bool(val value: Boolean) : Json()

    data class Num(val raw: String) : Json() {
        val isIntegral: Boolean
            get() = raw.none { it == '.' || it == 'e' || it == 'E' }
    }

    data class Str(val value: String) : Json()

    data class Arr(val items: MutableList<Json> = mutableListOf()) : Json() {
        val size: Int get() = items.size
        operator fun get(i: Int): Json = items[i]
        fun add(v: Json) = apply { items.add(v) }
    }

    data class Obj(val fields: LinkedHashMap<String, Json> = LinkedHashMap()) : Json() {
        operator fun get(key: String): Json? = fields[key]
        operator fun set(key: String, value: Json) {
            fields[key] = value
        }

        fun has(key: String): Boolean = fields.containsKey(key)
    }

    // ---------- 便捷读取 ----------

    val asStringOrNull: String?
        get() = (this as? Str)?.value ?: (this as? Num)?.raw

    val asBoolOrNull: Boolean?
        get() = (this as? Bool)?.value

    val asIntOrNull: Int?
        get() = when (this) {
            is Num -> raw.toIntOrNull()
            is Str -> value.toIntOrNull()
            is Bool -> if (value) 1 else 0
            else -> null
        }

    val asLongOrNull: Long?
        get() = when (this) {
            is Num -> raw.toLongOrNull()
            is Str -> value.toLongOrNull()
            else -> null
        }

    val asDoubleOrNull: Double?
        get() = when (this) {
            is Num -> raw.toDoubleOrNull()
            is Str -> value.toDoubleOrNull()
            else -> null
        }

    val asObjOrNull: Obj? get() = this as? Obj
    val asArrOrNull: Arr? get() = this as? Arr

    fun str(key: String): String? = (this as? Obj)?.get(key)?.asStringOrNull
    fun int(key: String): Int? = (this as? Obj)?.get(key)?.asIntOrNull
    fun long(key: String): Long? = (this as? Obj)?.get(key)?.asLongOrNull
    fun double(key: String): Double? = (this as? Obj)?.get(key)?.asDoubleOrNull
    fun bool(key: String): Boolean? = (this as? Obj)?.get(key)?.asBoolOrNull
    fun obj(key: String): Obj? = (this as? Obj)?.get(key)?.asObjOrNull
    fun arr(key: String): Arr? = (this as? Obj)?.get(key)?.asArrOrNull

    /** 非空字符串，读不到时返回默认值 */
    fun strOr(key: String, fallback: String): String = str(key) ?: fallback

    // ---------- 序列化 ----------

    fun stringify(): String = StringBuilder().also { write(it) }.toString()

    fun stringifyPretty(): String = StringBuilder().also { write(it, 0, true) }.toString()

    private fun write(sb: StringBuilder, indent: Int = 0, pretty: Boolean = false) {
        when (this) {
            is Null -> sb.append("null")
            is Bool -> sb.append(if (value) "true" else "false")
            is Num -> sb.append(if (raw.isEmpty()) "0" else raw)
            is Str -> escapeTo(sb, value)
            is Arr -> {
                if (items.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append('[')
                items.forEachIndexed { i, v ->
                    if (i > 0) sb.append(',')
                    newline(sb, indent + 1, pretty)
                    v.write(sb, indent + 1, pretty)
                }
                newline(sb, indent, pretty)
                sb.append(']')
            }
            is Obj -> {
                if (fields.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append('{')
                var first = true
                for ((k, v) in fields) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, indent + 1, pretty)
                    escapeTo(sb, k)
                    sb.append(':')
                    if (pretty) sb.append(' ')
                    v.write(sb, indent + 1, pretty)
                }
                newline(sb, indent, pretty)
                sb.append('}')
            }
        }
    }

    private fun newline(sb: StringBuilder, indent: Int, pretty: Boolean) {
        if (!pretty) return
        sb.append('\n')
        repeat(indent) { sb.append("  ") }
    }

    companion object {
        val EMPTY_OBJ: Obj = Obj()

        private fun escapeTo(sb: StringBuilder, s: String) {
            sb.append('"')
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    else ->
                        if (c < ' ') sb.append("\\u").append(String.format("%04x", c.code))
                        else sb.append(c)
                }
            }
            sb.append('"')
        }

        /**
         * 解析 JSON 文本。遇到非法输入抛 [JsonException]。
         */
        fun parse(text: String): Json = Parser(text).parseDocument()
    }

    class JsonException(message: String) : RuntimeException(message)
}

// ---------- 构造便捷函数 ----------

/**
 * 构造 JSON 对象。值可以是任意类型，会自动转换 —— 这样写工具的参数 schema 时
 * 不必到处调用 .toJson()，可读性显著更好。
 */
fun jsonObj(vararg pairs: Pair<String, Any?>): Json.Obj {
    val o = Json.Obj()
    for ((k, v) in pairs) {
        if (v != null) o[k] = v.toJsonValue()
    }
    return o
}

fun jsonObjOf(map: Map<String, Json>): Json.Obj = Json.Obj(LinkedHashMap(map))

/**
 * 构造 JSON 数组。
 *
 * 小心这里的「隐式展开」行为：`jsonArr` 的参数类型是 `Any?`，如果把一个 List
 * 直接传进来（例如 `jsonArr(list.map { ... })`），编译器不会报错，而语义上你
 * 想要的多半是「展开成多个元素」而不是「塞进一个嵌套数组」。因此这里对
 * 集合/数组类型的单个参数做展开处理，避免静默产生 `[[...]]` 这种错误结构。
 * 确实需要嵌套数组时，请显式传 `Json.Arr`。
 */
fun jsonArr(vararg items: Any?): Json.Arr {
    val out = mutableListOf<Json>()
    for (item in items) {
        when (item) {
            is Json.Arr -> out.add(item)
            is Array<*> -> item.forEach { out.add(it.toJsonValue()) }
            is Iterable<*> -> item.forEach { out.add(it.toJsonValue()) }
            else -> out.add(item.toJsonValue())
        }
    }
    return Json.Arr(out)
}

fun jsonArrOf(items: Iterable<*>): Json.Arr =
    Json.Arr(items.map { it.toJsonValue() }.toMutableList())

fun String.toJson(): Json = Json.Str(this)
fun Int.toJson(): Json = Json.Num(this.toString())
fun Long.toJson(): Json = Json.Num(this.toString())
fun Double.toJson(): Json = Json.Num(this.toString())
fun Boolean.toJson(): Json = if (this) Json.Bool(true) else Json.Bool(false)

/** 把任意 Kotlin 结构转换为 Json，用于 MCP 工具参数的宽松输入。 */
fun Any?.toJsonValue(): Json = when (this) {
    null -> Json.Null
    is Json -> this
    is String -> Json.Str(this)
    is Boolean -> Json.Bool(this)
    is Int -> Json.Num(this.toString())
    is Long -> Json.Num(this.toString())
    is Double -> Json.Num(this.toString())
    is Float -> Json.Num(this.toString())
    is Number -> Json.Num(this.toString())
    is Map<*, *> -> {
        val o = Json.Obj()
        for ((k, v) in this) o[k.toString()] = v.toJsonValue()
        o
    }
    is Iterable<*> -> Json.Arr(this.map { it.toJsonValue() }.toMutableList())
    is Array<*> -> Json.Arr(this.map { it.toJsonValue() }.toMutableList())
    else -> Json.Str(this.toString())
}

// ---------- 递归下降解析器 ----------

private class Parser(private val src: String) {

    private var pos = 0

    fun parseDocument(): Json {
        skipWs()
        if (pos >= src.length) throw Json.JsonException("JSON 内容为空")
        val v = parseValue()
        skipWs()
        if (pos < src.length) {
            throw Json.JsonException("JSON 结尾存在多余内容，位置 $pos")
        }
        return v
    }

    private fun parseValue(): Json {
        skipWs()
        if (pos >= src.length) throw Json.JsonException("JSON 意外结束")
        return when (val c = src[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> Json.Str(parseString())
            't' -> {
                expect("true"); Json.Bool(true)
            }
            'f' -> {
                expect("false"); Json.Bool(false)
            }
            'n' -> {
                expect("null"); Json.Null
            }
            else ->
                if (c == '-' || c in '0'..'9') parseNumber()
                else throw Json.JsonException("位置 $pos 出现非法字符 '$c'")
        }
    }

    private fun parseObject(): Json.Obj {
        val o = Json.Obj()
        pos++ // {
        skipWs()
        if (pos < src.length && src[pos] == '}') {
            pos++; return o
        }
        while (true) {
            skipWs()
            if (pos >= src.length) throw Json.JsonException("对象未闭合")
            if (src[pos] != '"') throw Json.JsonException("位置 $pos 期望对象键的引号")
            val key = parseString()
            skipWs()
            if (pos >= src.length || src[pos] != ':') throw Json.JsonException("位置 $pos 期望冒号")
            pos++
            o[key] = parseValue()
            skipWs()
            if (pos >= src.length) throw Json.JsonException("对象未闭合")
            when (src[pos]) {
                ',' -> pos++
                '}' -> {
                    pos++; return o
                }
                else -> throw Json.JsonException("位置 $pos 期望逗号或右花括号")
            }
        }
    }

    private fun parseArray(): Json.Arr {
        val a = Json.Arr()
        pos++ // [
        skipWs()
        if (pos < src.length && src[pos] == ']') {
            pos++; return a
        }
        while (true) {
            a.items.add(parseValue())
            skipWs()
            if (pos >= src.length) throw Json.JsonException("数组未闭合")
            when (src[pos]) {
                ',' -> pos++
                ']' -> {
                    pos++; return a
                }
                else -> throw Json.JsonException("位置 $pos 期望逗号或右方括号")
            }
        }
    }

    private fun parseString(): String {
        pos++ // 开引号
        val sb = StringBuilder()
        while (true) {
            if (pos >= src.length) throw Json.JsonException("字符串未闭合")
            when (val c = src[pos]) {
                '"' -> {
                    pos++; return sb.toString()
                }
                '\\' -> {
                    pos++
                    if (pos >= src.length) throw Json.JsonException("转义符后意外结束")
                    when (val e = src[pos]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 >= src.length) throw Json.JsonException("Unicode 转义不完整")
                            val hex = src.substring(pos + 1, pos + 5)
                            val code = hex.toIntOrNull(16)
                                ?: throw Json.JsonException("非法 Unicode 转义 \\u$hex")
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> throw Json.JsonException("未知转义符 '\\$e'")
                    }
                    pos++
                }
                else -> {
                    sb.append(c); pos++
                }
            }
        }
    }

    private fun parseNumber(): Json.Num {
        val start = pos
        if (pos < src.length && src[pos] == '-') pos++
        while (pos < src.length && src[pos] in '0'..'9') pos++
        if (pos < src.length && src[pos] == '.') {
            pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
        }
        if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
            pos++
            if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
        }
        val raw = src.substring(start, pos)
        if (raw.isEmpty() || raw == "-") throw Json.JsonException("位置 $start 出现非法数字")
        return Json.Num(raw)
    }

    private fun expect(literal: String) {
        if (!src.startsWith(literal, pos)) {
            throw Json.JsonException("位置 $pos 期望 '$literal'")
        }
        pos += literal.length
    }

    private fun skipWs() {
        while (pos < src.length && src[pos].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) pos++
    }
}

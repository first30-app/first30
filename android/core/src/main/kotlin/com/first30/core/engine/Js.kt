package com.first30.core.engine

import com.first30.core.JsFunction
import com.first30.core.JsUndefined
import com.first30.core.seal.Canonical
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * JS 값 모델(`null`/[JsUndefined]/Boolean/Double/String/List/Map) 위의 작은 도구들 — 판정 모델 응답처럼 바깥에서 온
 * 모양이 정해지지 않은 값(Jev answers, 판정 결과)을 JS 와 똑같이 읽는 데 쓴다.
 */
internal object Js {
    /** JS `o[k]` — 객체(Map)가 아니면 undefined. 키가 없으면 undefined. */
    fun get(o: Any?, k: String): Any? = if (o is Map<*, *> && o.containsKey(k)) o[k] else JsUndefined

    /** JS 참거짓 (`!!v`). */
    fun truthy(v: Any?): Boolean = when (v) {
        null, JsUndefined -> false
        is Boolean -> v
        is Number -> v.toDouble().let { !(it == 0.0 || it.isNaN()) }
        is String -> v.isNotEmpty()
        else -> true
    }

    /** JS `v == null` (null 또는 undefined). */
    fun isNullish(v: Any?): Boolean = v == null || v === JsUndefined

    /** JS `typeof v === 'object' && v !== null` (배열 포함). */
    fun isObject(v: Any?): Boolean = v is Map<*, *> || v is List<*> || v is ByteArray

    /** JS `Object.prototype.hasOwnProperty.call(o, k)` (o 가 null/undefined 면 false — jev-client.js `own`). */
    fun own(o: Any?, k: String): Boolean = when (o) {
        is Map<*, *> -> o.containsKey(k)
        is List<*> -> k == "length" || (isArrayIndex(k) && k.toLong() < o.size)
        is String -> k == "length" || (isArrayIndex(k) && k.toLong() < o.length)
        else -> false
    }

    private fun isArrayIndex(k: String): Boolean =
        k.isNotEmpty() && k.length <= 10 && k.all { it in '0'..'9' } && (k == "0" || k[0] != '0') && k.toLong() < 4294967295L

    /**
     * JS `Object.entries(o)` 의 키 순서: 배열 인덱스 꼴 키를 숫자 순으로 먼저, 나머지는 넣은 순서.
     * 배열·문자열은 인덱스 키. 그 밖의 원시값은 빈 목록.
     */
    fun entries(o: Any?): List<Pair<String, Any?>> = when (o) {
        is Map<*, *> -> {
            val keys = o.keys.map { it as String }
            val idx = keys.filter { isArrayIndex(it) }.sortedBy { it.toLong() }
            (idx + keys.filter { !isArrayIndex(it) }).map { it to o[it] }
        }
        is List<*> -> o.mapIndexed { i, v -> i.toString() to v }
        is String -> o.mapIndexed { i, c -> i.toString() to c.toString() }
        else -> emptyList()
    }

    /** JS `ToNumber` (비교·산술용 근사: 객체는 NaN). */
    fun toNumber(v: Any?): Double = when (v) {
        null -> 0.0
        JsUndefined -> Double.NaN
        is Boolean -> if (v) 1.0 else 0.0
        is Number -> v.toDouble()
        is String -> jsTrim(v).let { s -> if (s.isEmpty()) 0.0 else s.toDoubleOrNull() ?: Double.NaN }
        else -> Double.NaN
    }

    /** JS 템플릿 문자열·`String(v)` 의 값 표기. */
    fun str(v: Any?): String = when (v) {
        null -> "null"
        JsUndefined -> "undefined"
        is String -> v
        is Boolean -> if (v) "true" else "false"
        is Double -> Canonical.jsNumberToString(v)
        is Number -> Canonical.jsNumberToString(v.toDouble())
        is List<*> -> v.joinToString(",") { if (isNullish(it)) "" else str(it) }
        is JsFunction -> "function ${v.name}() { [native code] }"
        is Map<*, *> -> "[object Object]"
        else -> v.toString()
    }

    /** JS `JSON.stringify(v)` (넣은 키 순서; undefined·함수 값 키는 뺀다). 중계 서버로 보내는 요청 본문용. */
    fun jsonStringify(v: Any?): String = StringBuilder().also { writeJson(it, v) }.toString()

    private fun writeJson(sb: StringBuilder, v: Any?) {
        when (v) {
            null, JsUndefined, is JsFunction -> sb.append("null")
            is String -> Canonical.quote(sb, v)
            is Boolean -> sb.append(v)
            is Number -> sb.append(Canonical.jsonNumber(v.toDouble()))
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, e -> if (i > 0) sb.append(','); writeJson(sb, e) }
                sb.append(']')
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in entries(v)) {
                    if (x === JsUndefined || x is JsFunction) continue
                    if (!first) sb.append(',')
                    first = false
                    Canonical.quote(sb, k); sb.append(':'); writeJson(sb, x)
                }
                sb.append('}')
            }
            else -> error("jsonStringify: unsupported ${v::class.java.name}")
        }
    }
}

/**
 * JS 정규식 원문(`re.source`)을 java.util.regex 로 옮긴다 (플래그 없음 / g 만 쓰는 원문 전제, u·i·m·s·y 없음).
 * - `\s` → ECMAScript WhiteSpace+LineTerminator 집합 (Java `\s` 는 ASCII 만)
 * - `\d` → `[0-9]` (JS 와 같은 ASCII)
 * - `.` (문자 집합 밖) → `[^\n\r  ]` (Java `.` 는 U+0085 도 빼고 \r\n 을 한 덩어리로 본다)
 * - `$` (문자 집합 밖) → `\z` (Java `$` 는 끝의 줄바꿈 앞에서도 맞는다)
 * - 문자 집합 안의 `[`·`&` 는 Java 에서만 특별하므로 이스케이프한다.
 * JS(u 플래그 없음)는 UTF-16 코드 단위로 맞추고 Java 는 코드 포인트로 맞춘다 — 서로게이트가 섞일 수 있는 입력은
 * [bmpOnly] 로 바꿔서 맞출 것.
 */
internal fun jsRegex(src: String): Pattern {
    val ws = "\\t\\n\\x0B\\f\\r\\x20\\xA0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    val dot = "[^\\n\\r\\u2028\\u2029]"
    val sb = StringBuilder()
    var inClass = false
    var i = 0
    while (i < src.length) {
        val c = src[i]
        if (c == '\\') {
            val n = src[i + 1]
            when (n) {
                's' -> sb.append(if (inClass) ws else "[$ws]")
                'd' -> sb.append(if (inClass) "0-9" else "[0-9]")
                else -> sb.append(c).append(n)
            }
            i += 2
            continue
        }
        if (inClass) {
            when (c) {
                ']' -> { inClass = false; sb.append(c) }
                '[' -> sb.append("\\[")
                '&' -> sb.append("\\&")
                else -> sb.append(c)
            }
        } else {
            when (c) {
                '[' -> {
                    inClass = true
                    sb.append(c)
                    if (i + 1 < src.length && src[i + 1] == '^') { sb.append('^'); i++ }
                }
                '.' -> sb.append(dot)
                '$' -> sb.append("\\z")
                else -> sb.append(c)
            }
        }
        i++
    }
    return Pattern.compile(sb.toString())
}

/** 서로게이트 코드 단위를 사설 영역 문자로 바꿔, Java 정규식이 JS 처럼 코드 단위 하나씩 보게 한다(길이·위치 그대로). */
internal fun bmpOnly(s: String): String {
    if (s.none { Character.isSurrogate(it) }) return s
    val a = s.toCharArray()
    for (i in a.indices) if (Character.isHighSurrogate(a[i])) a[i] = '' else if (Character.isLowSurrogate(a[i])) a[i] = ''
    return String(a)
}

/** JS `re.test(s)` (g 플래그 없는 정규식). */
internal fun Pattern.test(s: String): Boolean = matcher(s).find()

/** 한 번의 매치 (JS exec 결과의 index / [0] / [n]). */
internal class JsMatch(val index: Int, val text: String, private val groups: List<String?>) {
    val end: Int get() = index + text.length
    fun group(n: Int): String? = groups.getOrNull(n)
}

/** local-judge.js `all(re, s)` — 모든 매치 (빈 매치는 한 칸 넘긴다; Java find 도 같다). */
internal fun Pattern.all(s: String): List<JsMatch> {
    val m: Matcher = matcher(s)
    val out = ArrayList<JsMatch>()
    while (m.find()) out.add(JsMatch(m.start(), m.group(), (0..m.groupCount()).map { m.group(it) }))
    return out
}

/** JS `s.replace(/re/g, lit)` (치환 문자열에 `$` 패턴이 없는 경우). */
internal fun Pattern.replaceAllLiteral(s: String, replacement: String): String = matcher(s).replaceAll(Matcher.quoteReplacement(replacement))

/** JS `s.slice(a, b)` (음수 없음, 범위 넘으면 자름). */
internal fun String.jsSlice(a: Int, b: Int = length): String {
    val from = a.coerceIn(0, length)
    val to = b.coerceIn(0, length)
    return if (from >= to) "" else substring(from, to)
}

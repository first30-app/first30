package com.first30.core.seal

import com.first30.core.JsFunction
import com.first30.core.JsUndefined
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.security.MessageDigest

/**
 * 증거 봉인 직렬화와 지문 — engine/engine.js `stableStringify`(1624행), `sha256Hex`(1668행) 이식.
 *
 * 값 모델(JS 값과 1:1): `null`, [JsUndefined], Boolean, Number(Double/Int/Long…, 모두 double 로 본다), String,
 * `List<*>`(배열), `Map<String, *>`(객체, 키 순서 무관), `ByteArray`(Uint8Array), [JsFunction](함수).
 * 결과 문자열은 JS 와 바이트 단위로 같아야 한다(golden 봉인 384개 + 숫자 말뭉치로 검증).
 */
object Canonical {

    /**
     * JS `stableStringify`:
     * - 원시값: `JSON.stringify(v === undefined ? null : v)` → undefined 는 "null", NaN/±Infinity 는 "null", -0 은 "0".
     * - 배열: 원소마다 재귀, undefined 원소는 "null". 함수 원소는 JS 에서 `JSON.stringify(fn)` = undefined 이고
     *   `join` 이 그것을 빈 문자열로 만들므로 ""(예: `[1, fn]` → `[1,]`).
     * - 객체: 값이 undefined·함수인 키는 빼고, 키를 UTF-16 코드 단위 순서로 정렬(`Array.prototype.sort` 기본 = String.compareTo).
     * - 최상위 함수: JS 는 문자열이 아닌 undefined 를 돌려준다. 여기서는 ""(봉인 본문에는 나오지 않는다).
     */
    fun stableStringify(value: Any?): String {
        val sb = StringBuilder()
        write(sb, value)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null, JsUndefined -> sb.append("null")
            is JsFunction -> Unit
            is String -> quote(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Number -> sb.append(jsonNumber(v.toDouble()))
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, e -> if (i > 0) sb.append(','); write(sb, e) }
                sb.append(']')
            }
            is Array<*> -> write(sb, v.asList())
            is ByteArray -> {
                // Uint8Array 는 typeof 'object' 이고 배열이 아니므로 키 "0","1",… 를 문자열 순으로 정렬한 객체가 된다.
                write(sb, v.indices.associate { it.toString() to (v[it].toInt() and 0xff) })
            }
            is Map<*, *> -> {
                val keys = v.entries
                    .filter { (_, x) -> x !== JsUndefined && x !is JsFunction }
                    .map { it.key as? String ?: error("object key must be String: ${it.key}") }
                    .sorted()
                sb.append('{')
                keys.forEachIndexed { i, k ->
                    if (i > 0) sb.append(',')
                    quote(sb, k); sb.append(':'); write(sb, v[k])
                }
                sb.append('}')
            }
            else -> error("stableStringify: unsupported value type ${v::class.java.name}")
        }
    }

    /** `JSON.stringify(x)` for a number: non-finite → "null", otherwise Number::toString (-0 → "0"). */
    fun jsonNumber(d: Double): String = if (d.isNaN() || d.isInfinite()) "null" else jsNumberToString(d)

    /**
     * `JSON.stringify(string)` (QuoteJSONString, ES2019 well-formed): `"` `\` → 이스케이프, \b \f \n \r \t,
     * 그 밖의 U+0000–001F 와 짝 없는 서로게이트는 `\u` + 소문자 16진 4자리, 나머지(비 ASCII, U+2028/2029, DEL)는 그대로.
     */
    fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> hex4(sb, c)
                c.isHighSurrogate() -> {
                    if (i + 1 < n && s[i + 1].isLowSurrogate()) { sb.append(c).append(s[i + 1]); i++ } else hex4(sb, c)
                }
                c.isLowSurrogate() -> hex4(sb, c)
                else -> sb.append(c)
            }
            i++
        }
        sb.append('"')
    }

    private fun hex4(sb: StringBuilder, c: Char) {
        val h = c.code.toString(16)
        sb.append("\\u")
        repeat(4 - h.length) { sb.append('0') }
        sb.append(h)
    }

    /**
     * ECMAScript Number::toString(x) (ES2023 6.1.6.1.20), radix 10.
     *
     * 최단 자릿수는 플랫폼 Double.toString / parseDouble 에 기대지 않고 BigDecimal 정수 산술로만 구한다
     * (JDK 17 의 Double.toString 은 최단이 아닌 경우가 있고, Android 런타임 구현도 다르다):
     * 자릿수 p = 1..17 에 대해 정확한 값 x 를 p 자리로 내림·올림한 두 후보 중 x 의 반올림 구간
     * (이웃 double 과의 중점; 경계는 가수가 짝수일 때만 포함 = parse 의 round-half-even)에 드는 것을 찾고,
     * 둘 다 들면 x 에 더 가까운 것(같으면 끝자리 짝수)을 고른다 — 명세의 "k 최소, 가장 가까운 s" 규칙 그대로.
     */
    fun jsNumberToString(d: Double): String {
        if (d.isNaN()) return "NaN"
        if (d == 0.0) return "0" // +0, -0
        if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
        if (d < 0) return "-" + jsNumberToString(-d)

        val (digits, n) = shortestDigits(d)
        val k = digits.length
        return when {
            n in k..21 -> digits + "0".repeat(n - k)
            n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val e = n - 1
                val exp = (if (e >= 0) "e+" else "e-") + kotlin.math.abs(e)
                if (k == 1) digits + exp else digits[0] + "." + digits.substring(1) + exp
            }
        }
    }

    /** For finite d > 0: (s, n) with s the shortest decimal digit string (no trailing zeros) and value = 0.s × 10^n. */
    internal fun shortestDigits(d: Double): Pair<String, Int> {
        val x = BigDecimal(d)
        val two = BigDecimal(2)
        val below = Math.nextAfter(d, 0.0)
        val lo = x.add(BigDecimal(below)).divide(two)              // exact (binary fractions divide by 2 exactly)
        val hi = x.add(BigDecimal(Math.ulp(d)).divide(two))        // ulp(d) = gap to the next larger double (MAX_VALUE too)
        val evenMantissa = (java.lang.Double.doubleToRawLongBits(d) and 1L) == 0L
        fun inside(c: BigDecimal): Boolean {
            val a = c.compareTo(lo)
            val b = c.compareTo(hi)
            return if (evenMantissa) a >= 0 && b <= 0 else a > 0 && b < 0
        }
        for (p in 1..17) {
            val dn = x.round(MathContext(p, RoundingMode.DOWN))
            val up = x.round(MathContext(p, RoundingMode.UP))
            val okDn = inside(dn)
            val okUp = inside(up)
            val pick = when {
                okDn && okUp -> {
                    val c = x.subtract(dn).compareTo(up.subtract(x))
                    when {
                        c < 0 -> dn
                        c > 0 -> up
                        else -> if (dn.unscaledValue().testBit(0)) up else dn
                    }
                }
                okDn -> dn
                okUp -> up
                else -> continue
            }
            return digitsOf(pick)
        }
        error("no shortest representation within 17 digits for $d") // unreachable for finite doubles
    }

    private fun digitsOf(c: BigDecimal): Pair<String, Int> {
        val raw = c.unscaledValue().toString()
        val digits = raw.trimEnd('0')
        // c = raw × 10^-scale; with k = raw.length, c = 0.raw × 10^(k - scale)
        return digits to (raw.length - c.scale())
    }

    /** JS `sha256Hex`: SHA-256 over the UTF-8 bytes of [s], lowercase hex. */
    fun sha256Hex(s: String): String = sha256Hex(s.toByteArray(Charsets.UTF_8))

    /** SHA-256 of raw bytes (JS `sha256Fallback(bytes)`), lowercase hex. */
    fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(64)
        for (b in md) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0xf])
        }
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"
}

/** Top-level aliases mirroring the JS names (engine.js 1624 / 1668). */
fun stableStringify(value: Any?): String = Canonical.stableStringify(value)

fun sha256Hex(s: String): String = Canonical.sha256Hex(s)

fun jsNumberToString(d: Double): String = Canonical.jsNumberToString(d)

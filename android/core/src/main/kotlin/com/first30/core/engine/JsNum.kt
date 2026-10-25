package com.first30.core.engine

import com.first30.core.model.JsMath

/**
 * engine.js 수학 유틸(78~110행)의 비트 단위 이식.
 *
 * 규칙: 모든 산술은 JS 와 같은 순서로(괄호 그대로), 모든 비교는 `Double` 원시값으로 한다
 * (`Double?` 끼리 `==` 는 equals 라서 -0.0 != 0.0, NaN == NaN 이 돼 JS `===` 와 다르다 — 쓰지 말 것).
 * `Math.max/min` 은 java.lang.Math 와 JS 가 같다(NaN 전파, +0 > -0). `x ** 2` 는 V8(fdlibm pow 의 y==2 특례)에서 `x * x`.
 */

/** engine.js 78행 `rad`. */
internal fun rad(d: Double): Double = (d * Math.PI) / 180.0

/** engine.js 79행 `deg`. */
internal fun deg(r: Double): Double = (r * 180.0) / Math.PI

/** engine.js 80행 `angDiff`: a - b 를 -180~180 으로. */
internal fun angDiff(a: Double, b: Double): Double {
    var d = ((a - b) % 360.0 + 540.0) % 360.0 - 180.0
    if (d == -180.0) d = 180.0
    return d
}

private val POW10 = DoubleArray(6) { StrictMath.pow(10.0, it.toDouble()) }

/** engine.js 90행 `round(x, n)` = `Math.round(x * 10**n) / 10**n` (JS Math.round: 동점은 +∞ 쪽, -0 유지). */
internal fun round(x: Double, n: Int = 0): Double {
    val p = POW10[n]
    return JsMath.round(x * p) / p
}

/** engine.js 91행 `sec(ms)`. */
internal fun sec(ms: Double): Double = round(Math.abs(ms) / 1000.0, 1)

/** engine.js 93행 `mean`: 왼쪽부터 0 + x0 + x1 ... / 길이. 빈 배열은 0. */
internal fun mean(a: List<Double>): Double {
    if (a.isEmpty()) return 0.0
    var s = 0.0
    for (x in a) s += x
    return s / a.size
}

/** JS `(x, y) => x - y` 비교자 (안정 정렬과 함께 쓰면 V8 TimSort 와 같은 순서가 된다). */
internal val NUMERIC_ASC: Comparator<Double> = Comparator { x, y ->
    val d = x - y
    if (d < 0.0) -1 else if (d > 0.0) 1 else 0
}

/** engine.js 94행 `median`. 빈 배열은 0. */
internal fun median(a: List<Double>): Double {
    if (a.isEmpty()) return 0.0
    val s = a.sortedWith(NUMERIC_ASC)
    val k = s.size shr 1
    return if (s.size % 2 != 0) s[k] else (s[k - 1] + s[k]) / 2.0
}

/** engine.js 97행 `robustCenter(values, half = null)`. */
internal fun robustCenter(values: List<Double>, halfIn: Double? = null): Double {
    if (values.isEmpty()) return 0.0
    var c = median(values)
    var half = halfIn
    if (half == null) {
        val c0 = c
        val sigma = 1.4826 * median(values.map { Math.abs(it - c0) })
        half = Math.max(0.3, Math.min(Thresh.REF_TRIM_DEG, 2 * sigma))
    }
    for (it in 0 until 3) {
        val cc = c
        val h = half
        val near = values.filter { Math.abs(it - cc) <= h }
        if (near.isEmpty()) break
        c = mean(near)
    }
    return c
}

/** JS `Math.max(...a)` (빈 배열은 -Infinity). */
internal fun jsMax(a: List<Double>): Double {
    var m = Double.NEGATIVE_INFINITY
    for (x in a) m = Math.max(m, x)
    return m
}

/** JS `Math.min(...a)` (빈 배열은 +Infinity). */
internal fun jsMin(a: List<Double>): Double {
    var m = Double.POSITIVE_INFINITY
    for (x in a) m = Math.min(m, x)
    return m
}

/** JS `Math.sign` (−0 → −0, NaN → NaN). 비교는 원시 `==` 로 할 것. */
internal fun sign(x: Double): Double = Math.signum(x)

/** JS 의 참거짓 판정으로 숫자 `x || fallback` (0, -0, NaN, null 은 거짓). */
internal fun orElse(x: Double?, fallback: Double): Double = if (x == null || x == 0.0 || x.isNaN()) fallback else x

/** JS `String.prototype.trim` (ECMAScript WhiteSpace + LineTerminator; Kotlin trim() 과 집합이 다르다). */
internal fun jsTrim(s: String): String {
    fun ws(c: Char): Boolean = when (c) {
        '\u0009', '\u000B', '\u000C', ' ', ' ', '﻿', '\n', '\r', ' ', ' ' -> true
        else -> Character.getType(c) == Character.SPACE_SEPARATOR.toInt()
    }
    var a = 0
    var b = s.length
    while (a < b && ws(s[a])) a++
    while (b > a && ws(s[b - 1])) b--
    return s.substring(a, b)
}

package com.first30.core.golden

import com.first30.core.JsUndefined
import com.first30.core.model.Ctx
import com.first30.core.model.Frame

/**
 * Deep comparison of two values in the JS value model with a path-level diff
 * (e.g. `laneChange.startMs: js=-2100 kt=-2000`).
 *
 * Numbers are compared exactly (`==` on the double values) and, separately, by the sign of zero:
 * a `-0` vs `0` difference is reported as a diff unless [ignoreZeroSign] is set, in which case it is only counted in [zeroSignDiffs].
 * Key order of objects is ignored; key presence is not (a key present with value `undefined` ≠ an absent key).
 */
class JsDiff(private val ignoreZeroSign: Boolean = false) {
    val diffs = ArrayList<String>()
    val zeroSignDiffs = ArrayList<String>()

    fun compare(js: Any?, kt: Any?, path: String = "\$"): JsDiff { walk(js, kt, path); return this }

    private fun isNegZero(d: Double) = d == 0.0 && 1.0 / d < 0

    private fun show(v: Any?): String = when (v) {
        null -> "null"
        is Double -> if (isNegZero(v)) "-0" else com.first30.core.seal.Canonical.jsNumberToString(v).let { if (v.isNaN()) "NaN" else it }
        is Number -> show(v.toDouble())
        is String -> "\"$v\""
        is Map<*, *> -> "{" + v.keys.joinToString(",") + "}"
        is List<*> -> "[${v.size} items]"
        else -> v.toString()
    }

    private fun walk(js: Any?, kt: Any?, path: String) {
        when {
            js is Number && kt is Number -> {
                val a = js.toDouble()
                val b = kt.toDouble()
                if (a.isNaN() && b.isNaN()) return
                if (a != b) diffs.add("$path: js=${show(a)} kt=${show(b)}")
                else if (isNegZero(a) != isNegZero(b)) {
                    val msg = "$path: js=${show(a)} kt=${show(b)} (zero sign)"
                    if (ignoreZeroSign) zeroSignDiffs.add(msg) else diffs.add(msg)
                }
            }
            js is Map<*, *> && kt is Map<*, *> -> {
                for (k in js.keys) if (!kt.containsKey(k)) diffs.add("$path.$k: js=${show(js[k])} kt=<absent>")
                for (k in kt.keys) if (!js.containsKey(k)) diffs.add("$path.$k: js=<absent> kt=${show(kt[k])}")
                for (k in js.keys) if (kt.containsKey(k)) walk(js[k], kt[k], "$path.$k")
            }
            js is List<*> && kt is List<*> -> {
                if (js.size != kt.size) diffs.add("$path: length js=${js.size} kt=${kt.size}")
                for (i in 0 until minOf(js.size, kt.size)) walk(js[i], kt[i], "$path[$i]")
            }
            else -> if (js != kt) diffs.add("$path: js=${show(js)} kt=${show(kt)}")
        }
    }

    val ok: Boolean get() = diffs.isEmpty()

    companion object {
        /** A golden frame array (decoded) → engine input frames. Non-object entries become null (JS `f && ...` drops them). */
        fun framesOf(v: Any?): List<Frame?>? = (v as? List<*>)?.map { f ->
            @Suppress("UNCHECKED_CAST")
            (f as? Map<String, Any?>)?.let { Frame.fromJsMap(it) }
        }

        /** A golden ctx (decoded) → [Ctx]; `null`/`undefined` → null. Unknown keys are rejected so nothing is silently dropped. */
        fun ctxOf(v: Any?): Ctx? {
            if (v == null || v === JsUndefined) return null
            @Suppress("UNCHECKED_CAST")
            val m = v as Map<String, Any?>
            val known = setOf("trigger", "roadType", "navCautions", "driverStatement", "laneChangeConfirmed", "occupants", "injuryReported", "now")
            require(known.containsAll(m.keys)) { "unknown ctx keys ${m.keys - known}" }
            fun <T> g(k: String): T? {
                val x = m[k]
                @Suppress("UNCHECKED_CAST")
                return if (x == null || x === JsUndefined) null else x as T
            }
            return Ctx(
                trigger = g("trigger"),
                roadType = g("roadType"),
                navCautions = g<List<*>>("navCautions")?.map { it as String },
                driverStatement = g("driverStatement"),
                laneChangeConfirmed = g("laneChangeConfirmed"),
                occupants = g<Double>("occupants")?.let { d -> d.toInt().also { require(it.toDouble() == d) { "non-integer occupants $d" } } },
                injuryReported = g("injuryReported"),
                now = g("now"),
            )
        }
    }
}

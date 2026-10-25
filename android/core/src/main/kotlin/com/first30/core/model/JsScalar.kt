package com.first30.core.model

import com.first30.core.JsUndefined

/**
 * One raw value of a vehicle-signal frame field, kept exactly as the JS engine would see it.
 *
 * The engine (engine/engine.js `prepare`, 166행; `sealEvidence`, 1678행) reads frame fields through
 * type tests, so the Kotlin port has to keep the JS *type* of every value, not just a "missing" flag:
 *
 * | JS value in the frame                  | JsScalar               | engine.js meaning |
 * |----------------------------------------|------------------------|-------------------|
 * | key absent, or `undefined`             | [Undefined]            | `num()`→null, `normSignal()`→null, `typeof brake !== 'boolean'`→null; `stableStringify` omits the key |
 * | `null`                                 | [Null]                 | same as above for the engine; `stableStringify` writes `null` (only matters for the duplicate-`t` tie-break sort) |
 * | number (incl. `NaN`, `±Infinity`, `-0`) | [Num] (value kept bit-exact) | `num()` keeps only finite numbers → `NaN`/`Infinity` act like "no record"; seal `prim()` keeps finite numbers as-is (numeric turn-signal enums are sealed as numbers) |
 * | string                                 | [Str]                  | turnSignal: `normSignal` (trim+upper: LEFT/L, RIGHT/R, NONE/OFF, BOTH/HAZARD/EMERGENCY, else no record) |
 * | boolean                                | [Bool]                 | brake/airbag record; anything non-boolean in brake = "no record", airbag counts only `=== true` |
 *
 * "Absent" and "present but undefined" are deliberately the same value: nothing in the engine distinguishes
 * them (`stableStringify` drops undefined-valued keys exactly like missing ones, engine.js 1627행).
 */
sealed class JsScalar {
    /** JS `undefined` or an absent key. */
    data object Undefined : JsScalar()

    /** JS `null`. */
    data object Null : JsScalar()

    /** JS number. `NaN`, `±Infinity` and `-0.0` are preserved as given. */
    data class Num(val value: Double) : JsScalar() {
        // data-class equals on Double uses Double.equals (bitwise: -0.0 != 0.0, NaN == NaN), which is what we want here.
    }

    /** JS string. */
    data class Str(val value: String) : JsScalar()

    /** JS boolean. */
    data class Bool(val value: Boolean) : JsScalar()

    /** engine.js `num(x)` (92행): a finite number, else null. */
    fun finiteOrNull(): Double? = (this as? Num)?.value?.takeIf { it.isFinite() }

    /** The string if this is a JS string, else null. */
    fun stringOrNull(): String? = (this as? Str)?.value

    /** The boolean if this is a JS boolean, else null (engine.js `typeof x === 'boolean' ? x : null`). */
    fun booleanOrNull(): Boolean? = (this as? Bool)?.value

    val isUndefined: Boolean get() = this === Undefined

    /**
     * The value in the shared JS value model used by `Canonical.stableStringify`
     * (`null` / [JsUndefined] / Double / String / Boolean).
     */
    fun toAny(): Any? = when (this) {
        Undefined -> JsUndefined
        Null -> null
        is Num -> value
        is Str -> value
        is Bool -> value
    }

    companion object {
        /** Kotlin null → JS null; otherwise a [Num]. */
        fun of(value: Double?): JsScalar = if (value == null) Null else Num(value)

        /** Kotlin null → JS null; otherwise a [Str]. */
        fun of(value: String?): JsScalar = if (value == null) Null else Str(value)

        /** Kotlin null → JS null; otherwise a [Bool]. */
        fun of(value: Boolean?): JsScalar = if (value == null) Null else Bool(value)

        /** Inverse of [toAny] for scalar values; anything else (arrays, objects) is rejected. */
        fun fromAny(value: Any?): JsScalar = when (value) {
            null -> Null
            JsUndefined -> Undefined
            is Boolean -> Bool(value)
            is String -> Str(value)
            is Number -> Num(value.toDouble())
            else -> throw IllegalArgumentException("not a JS scalar: $value")
        }
    }
}

package com.first30.core.model

/**
 * One 10Hz vehicle-signal sample — the engine's input frame (engine/engine.js 상단 주석 17~24행, `prepare` 166행).
 *
 * Every field is a raw [JsScalar] so the port sees exactly what the JS engine sees (see the mapping table on
 * [JsScalar]). Summary of how the engine reads each field:
 *
 * - [t] ms relative to the trigger (t=0 = impact for 'airbag'/'impact' triggers, ≤ 0 before it).
 *   Frames whose `t` is not a finite number are dropped (`num(f.t) != null`).
 * - [speedKph] km/h. Finite number → `max(0, v)`; anything else = missing (interpolated, or 0 if the whole channel is missing).
 * - [headingDeg] degrees clockwise from true north. Finite → normalised to 0..360; `NaN`, `null`, `undefined` = missing.
 * - [steeringDeg] degrees, right(clockwise) = +. Finite number or missing. `-0.0` is a valid value (the synthesizer produces it).
 * - [turnSignal] a JS *string* 'LEFT'/'RIGHT'/'NONE'/'OFF'/'HAZARD' (case-insensitive, trimmed; also L/R/BOTH/EMERGENCY).
 *   A number (e.g. a raw SDK enum like `2`), `null` or `undefined` = **no record** (channel unavailable) — never "off".
 *   The seal keeps the raw value (a numeric enum is sealed as that number).
 * - [brake] JS boolean; anything else = no record. The seal keeps strings/booleans/finite numbers raw, else null.
 * - [airbag] deployed only when `=== true`; the seal keeps the raw value like [brake].
 *
 * The app's signal collector should use [Frame.of], which maps Kotlin `null` ("no record") to JS `null`.
 */
data class Frame(
    val t: JsScalar,
    val speedKph: JsScalar = JsScalar.Undefined,
    val headingDeg: JsScalar = JsScalar.Undefined,
    val steeringDeg: JsScalar = JsScalar.Undefined,
    val turnSignal: JsScalar = JsScalar.Undefined,
    val brake: JsScalar = JsScalar.Undefined,
    val airbag: JsScalar = JsScalar.Undefined,
) {
    /**
     * The frame as a JS object in the shared value model (`Map<String, Any?>`, see `Canonical`):
     * [JsScalar.Undefined] fields become `JsUndefined` entries (stableStringify drops them like absent keys).
     */
    fun toJsMap(): Map<String, Any?> = linkedMapOf(
        "t" to t.toAny(),
        "speedKph" to speedKph.toAny(),
        "headingDeg" to headingDeg.toAny(),
        "steeringDeg" to steeringDeg.toAny(),
        "turnSignal" to turnSignal.toAny(),
        "brake" to brake.toAny(),
        "airbag" to airbag.toAny(),
    )

    companion object {
        /** Field names in JS order (scenarios.js 35~43행). */
        val KEYS: List<String> = listOf("t", "speedKph", "headingDeg", "steeringDeg", "turnSignal", "brake", "airbag")

        /** Turn-signal strings the engine accepts (`normSignal`, engine.js 114행). */
        const val LEFT = "LEFT"
        const val RIGHT = "RIGHT"
        const val NONE = "NONE"
        const val OFF = "OFF"
        const val HAZARD = "HAZARD"

        /**
         * Collector-friendly constructor: Kotlin `null` = no record (JS `null`). Pass `turnSignal` already converted
         * to one of [LEFT]/[RIGHT]/[NONE]/[HAZARD] — a raw numeric SDK enum would be treated as "no record".
         */
        fun of(
            t: Long,
            speedKph: Double?,
            headingDeg: Double?,
            steeringDeg: Double?,
            turnSignal: String?,
            brake: Boolean?,
            airbag: Boolean?,
        ): Frame = Frame(
            t = JsScalar.Num(t.toDouble()),
            speedKph = JsScalar.of(speedKph),
            headingDeg = JsScalar.of(headingDeg),
            steeringDeg = JsScalar.of(steeringDeg),
            turnSignal = JsScalar.of(turnSignal),
            brake = JsScalar.of(brake),
            airbag = JsScalar.of(airbag),
        )

        /** Inverse of [toJsMap]: missing keys → [JsScalar.Undefined]. Unknown keys are ignored (the engine ignores them too, except in the duplicate-t sort tie-break). */
        fun fromJsMap(m: Map<String, Any?>): Frame {
            fun f(k: String) = if (m.containsKey(k)) JsScalar.fromAny(m[k]) else JsScalar.Undefined
            return Frame(f("t"), f("speedKph"), f("headingDeg"), f("steeringDeg"), f("turnSignal"), f("brake"), f("airbag"))
        }
    }
}

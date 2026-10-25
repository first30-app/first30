package com.first30.core.model

/**
 * Engine context `ctx` (CLAUDE.md "신호 수집기"; engine.js `impactOf` 213행, `roadTypeOf` 736행, `run` 1704행).
 * A JS `ctx` of `null`/`undefined` is the same as `Ctx()` (`ctx = ctx || {}`).
 *
 * Kotlin `null` on every field = the key is absent **or** `undefined` in JS. The engine never distinguishes those,
 * and the JS tests never pass JS `null` for a ctx field. Mapping per field:
 *
 * - [trigger] 'airbag' | 'impact' | 'decel' | 'voice' | 'button' | anything. The engine trims and lower-cases it
 *   (`' Impact '` → 'impact'); only 'airbag'/'impact' mean t=0 is the impact. null (or a non-string in JS) → source 'unknown'.
 * - [roadType] 'urban' | 'highway'; any other value or null → 'unknown' (exact, case-sensitive match).
 * - [navCautions] NaviHelper caution-point types (e.g. 'LANE_CHANGE_CAMERA_ALERT'). null behaves like an empty list
 *   (`ctx.navCautions || []`, also in the seal's `place`).
 * - [driverStatement] STT transcript; null behaves like '' (`String(ctx.driverStatement || '')`).
 * - [laneChangeConfirmed] answer to the confirm question: true = "예", false = "아니요", **null = not asked yet**
 *   (absent ≠ false: the engine tests `=== true` / `=== false`).
 * - [occupants] occupied seats other than the driver; the engine checks `Number(ctx.occupants) > 0`, null → no passengers.
 * - [injuryReported] truthy → emergency mode; null = false.
 * - [now] ISO-8601 seal time (fixed once per incident). null or '' → the engine falls back to the current clock.
 */
data class Ctx(
    val trigger: String? = null,
    val roadType: String? = null,
    val navCautions: List<String>? = null,
    val driverStatement: String? = null,
    val laneChangeConfirmed: Boolean? = null,
    val occupants: Int? = null,
    val injuryReported: Boolean? = null,
    val now: String? = null,
) {
    /** The ctx as a JS object (shared value model): null fields are omitted, i.e. absent keys. Key order = JS literal order. */
    fun toJsMap(): Map<String, Any?> = buildMap {
        trigger?.let { put("trigger", it) }
        roadType?.let { put("roadType", it) }
        navCautions?.let { put("navCautions", it) }
        driverStatement?.let { put("driverStatement", it) }
        laneChangeConfirmed?.let { put("laneChangeConfirmed", it) }
        occupants?.let { put("occupants", it.toDouble()) }
        injuryReported?.let { put("injuryReported", it) }
        now?.let { put("now", it) }
    }

    companion object {
        const val TRIGGER_AIRBAG = "airbag"
        const val TRIGGER_IMPACT = "impact"
        const val TRIGGER_DECEL = "decel"
        const val TRIGGER_VOICE = "voice"
        const val TRIGGER_BUTTON = "button"
        const val ROAD_URBAN = "urban"
        const val ROAD_HIGHWAY = "highway"
    }
}

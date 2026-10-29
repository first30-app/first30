package com.first30.app.signals

/**
 * Unit/encoding conversions from Pleos Connect vehicle values to what [SignalUpdate] (and the engine) expects.
 * Pure functions, no SDK dependency, so they are unit-tested here and the SDK source only calls them.
 *
 * Source: Pleos developer docs (document.pleos.ai, read 2026-10-29) — see docs/CONTEST.md "Pleos SDK 확인 사항".
 */
object PleosUnits {
    /**
     * Steering ratio (steering-wheel degrees per front-wheel degree) assumed when the SDK reports the front-wheel angle.
     * The engine thresholds (DESIGN.md 3장: 2°, 1°, 0.5° …) are steering-wheel degrees, and the engine tests derive the
     * wheel angle with the same ×15 (test/engine.test.js `steerOf`). Vehicle-dependent; confirm on the AVD / real car.
     */
    const val DEFAULT_STEERING_RATIO = 15.0

    /**
     * Vehicle SDK steering angle → engine steering-wheel angle (right = +).
     * The SDK doc says the value is the **front-wheel** angle in degrees, left negative, and "does not represent the
     * rotation angle of the steering wheel", so it is multiplied by [ratio]. If the AVD shows the SDK actually returns the
     * wheel angle (the Virtual Console slider spans −540…540°), pass `ratio = 1.0`.
     * Without this the engine sees ~1/15 of every steering input and holds back on almost every lane change.
     */
    fun steeringWheelDeg(sdkDeg: Float?, ratio: Double = DEFAULT_STEERING_RATIO): Double? {
        require(ratio > 0) { "ratio must be > 0" }
        if (sdkDeg == null || !sdkDeg.isFinite()) return null
        return sdkDeg.toDouble() * ratio
    }

    /** AAOS `PERF_VEHICLE_SPEED` (m/s, negative when reversing) → km/h. */
    fun speedKph(mps: Float?): Double? {
        if (mps == null || !mps.isFinite()) return null
        return mps.toDouble() * 3.6
    }

    /**
     * Vehicle SDK `TurnSignalValue` (OFF / LEFT / RIGHT) → engine string 'NONE' / 'LEFT' / 'RIGHT'.
     * Takes the enum `name` so this file stays SDK-free. Anything else → null ("no record"), never 'NONE'.
     */
    fun turnSignal(sdkName: String?): String? = when (sdkName?.trim()?.uppercase()) {
        "OFF" -> "NONE"
        "LEFT" -> "LEFT"
        "RIGHT" -> "RIGHT"
        else -> null
    }
}

package com.first30.core.scenarios

import com.first30.core.model.Frame
import com.first30.core.model.JsMath
import com.first30.core.model.JsScalar

/**
 * Port of scenarios/scenarios.js `synth` (= `F.synthFrames`, 26~46행): a physically plausible 10Hz buffer
 * from `fromS` seconds before the trigger up to t=0. Formulas, operation order and rounding follow the JS line by line.
 */

/** Speed profile `spec.speed`: a number or a function of t (s). JS 32행 `typeof spec.speed === 'function' ? spec.speed(t) : spec.speed`. */
fun interface SpeedProfile {
    fun at(t: Double): Double

    companion object {
        fun constant(kph: Double): SpeedProfile = Constant(kph)
    }

    /** Constant speed (kept as a class so it prints / compares nicely). */
    data class Constant(val kph: Double) : SpeedProfile {
        override fun at(t: Double): Double = kph
    }
}

/** `spec.laneChange` (JS 15행): smooth lane change starting at [startS] lasting [durS] s toward [dir] ('RIGHT' = +, anything else = LEFT). */
data class LaneChangeSpec(val startS: Double, val durS: Double, val dir: String)

/** `spec.signal` (JS 34행): turn signal [dir] on for fromS ≤ t ≤ (toS ?: 0). */
data class SignalSpec(val fromS: Double, val dir: String, val toS: Double? = null)

/**
 * Options of `synthFrames(spec)`. JS `null`/`undefined` options are Kotlin `null` (the JS code tests them with `== null`
 * or truthiness, so both behave the same).
 */
data class SynthSpec(
    val speed: SpeedProfile,
    val laneChange: LaneChangeSpec? = null,
    val signal: SignalSpec? = null,
    val brakeFromS: Double? = null,
    /** JS `!!(spec.airbag && t >= -0.05)` — only truthiness matters. */
    val airbag: Boolean = false,
    /** default 72 (JS 28행) */
    val baseHeading: Double? = null,
    /** default -10 (JS 29행) */
    val fromS: Double? = null,
) {
    constructor(
        speedKph: Double,
        laneChange: LaneChangeSpec? = null,
        signal: SignalSpec? = null,
        brakeFromS: Double? = null,
        airbag: Boolean = false,
        baseHeading: Double? = null,
        fromS: Double? = null,
    ) : this(SpeedProfile.constant(speedKph), laneChange, signal, brakeFromS, airbag, baseHeading, fromS)
}

object Synth {
    const val HZ = 10 // JS 11행
    const val LANE_W = 3.5 // m, JS 12행

    private data class Lc(val headingDev: Double, val steer: Double)

    private val ZERO = Lc(0.0, 0.0)

    // JS 14~24행: y(τ) = W(τ/D − sin(2πτ/D)/2π)
    private fun laneChangeAt(t: Double, lc: LaneChangeSpec, speedKph: Double): Lc {
        val sign = if (lc.dir == "RIGHT") 1.0 else -1.0
        val tau = t - lc.startS
        if (tau <= 0 || tau >= lc.durS) return ZERO
        val v = Math.max(1.0, speedKph / 3.6)
        val dy = (LANE_W / lc.durS) * (1 - JsMath.cos((2 * Math.PI * tau) / lc.durS))
        val headingDev = (JsMath.atan(dy / v) * 180) / Math.PI
        val steer = 6 * JsMath.sin((2 * Math.PI * tau) / lc.durS)
        return Lc(sign * headingDev, sign * steer)
    }

    /** JS `synth(spec)` (26~46행). */
    fun synthFrames(spec: SynthSpec): List<Frame> {
        val frames = ArrayList<Frame>()
        val baseHeading = spec.baseHeading ?: 72.0
        val fromS = spec.fromS ?: -10.0
        var i = JsMath.round(fromS * HZ) // JS 30행: loop counter is a JS number
        while (i <= 0) {
            val t = i / HZ
            val speed = spec.speed.at(t)
            val lc = if (spec.laneChange != null) laneChangeAt(t, spec.laneChange, speed) else ZERO
            val sig = if (spec.signal != null && t >= spec.signal.fromS && t <= (spec.signal.toS ?: 0.0)) spec.signal.dir else "NONE"
            frames.add(
                Frame(
                    t = JsScalar.Num(JsMath.round(t * 1000)),
                    speedKph = JsScalar.Num(JsMath.round(speed * 10) / 10),
                    headingDeg = JsScalar.Num((baseHeading + lc.headingDev + 360) % 360),
                    turnSignal = JsScalar.Str(sig),
                    steeringDeg = JsScalar.Num(JsMath.round(lc.steer * 10) / 10),
                    brake = JsScalar.Bool(spec.brakeFromS != null && t >= spec.brakeFromS),
                    airbag = JsScalar.Bool(spec.airbag && t >= -0.05),
                ),
            )
            i += 1.0
        }
        return frames
    }
}

/** JS 48행 `brakingSpeed(cruise, fromS, decelKphPerS)`: cruise until fromS, then linear deceleration down to 0. */
fun brakingSpeed(cruise: Double, fromS: Double, decelKphPerS: Double): SpeedProfile =
    SpeedProfile { t -> if (t < fromS) cruise else Math.max(0.0, cruise - (t - fromS) * decelKphPerS) }

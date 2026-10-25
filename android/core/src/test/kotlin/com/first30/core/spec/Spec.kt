package com.first30.core.spec

import com.first30.core.engine.Card
import com.first30.core.engine.JevHttpResponse
import com.first30.core.engine.JevJudge
import com.first30.core.engine.JevTransport
import com.first30.core.engine.Js
import com.first30.core.engine.LocalJudge
import com.first30.core.engine.RunOutput
import com.first30.core.engine.run
import com.first30.core.model.Ctx
import com.first30.core.model.Frame
import com.first30.core.model.JsMath
import com.first30.core.model.JsScalar
import com.first30.core.scenarios.LaneChangeSpec
import com.first30.core.scenarios.Scenario
import com.first30.core.scenarios.Scenarios
import com.first30.core.scenarios.SignalSpec
import com.first30.core.scenarios.SpeedProfile
import com.first30.core.scenarios.Synth
import com.first30.core.scenarios.SynthSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail

/**
 * Helpers of test/engine.test.js, ported with the same arithmetic (operation order, JS Math semantics) so that every
 * synthesized frame is bit-identical to the JS one. The spec tests in this package do NOT read the golden fixtures:
 * they are the standalone Kotlin specification that remains once the JS engine is retired.
 */
internal object Spec {
    const val FIXED_NOW = "2026-10-23T15:00:00.000Z"
    val PI = Math.PI

    // ---------- run / ctx helpers (engine.test.js 7~14행) ----------
    fun runLocal(frames: List<Frame>, ctx: Ctx): RunOutput = run(frames, ctx.copy(now = FIXED_NOW), LocalJudge)

    /** t=0 이 충돌 순간인 기록(충격 감지 트리거). */
    fun urban(driverStatement: String = "") = Ctx(trigger = "impact", roadType = "urban", navCautions = emptyList(), driverStatement = driverStatement)
    fun highway(driverStatement: String = "") = Ctx(trigger = "impact", roadType = "highway", navCautions = emptyList(), driverStatement = driverStatement)
    /** "차로를 바꾸던 중이었나요?"에 "예" */
    fun confirmed(ctx: Ctx) = ctx.copy(laneChangeConfirmed = true)
    /** "아니요" */
    fun keptLane(ctx: Ctx) = ctx.copy(laneChangeConfirmed = false)

    fun sc(id: String): Scenario = Scenarios.byId(id)
    val SCENARIOS: List<Scenario> get() = Scenarios.ALL

    // ---------- frame helpers ----------
    fun num(x: Double): JsScalar = JsScalar.Num(x)
    fun str(s: String): JsScalar = JsScalar.Str(s)
    fun bool(b: Boolean): JsScalar = JsScalar.Bool(b)

    val Frame.tMs: Double get() = (t as JsScalar.Num).value
    val Frame.hd: Double get() = (headingDeg as JsScalar.Num).value
    val Frame.sd: Double get() = (steeringDeg as JsScalar.Num).value
    val Frame.ts: String? get() = turnSignal.stringOrNull()

    fun synth(
        speed: Double,
        fromS: Double? = null,
        laneChange: LaneChangeSpec? = null,
        signal: SignalSpec? = null,
        brakeFromS: Double? = null,
    ): List<Frame> = Synth.synthFrames(SynthSpec(speed, laneChange = laneChange, signal = signal, brakeFromS = brakeFromS, fromS = fromS))

    fun synth(
        speed: SpeedProfile,
        fromS: Double? = null,
        laneChange: LaneChangeSpec? = null,
        signal: SignalSpec? = null,
        brakeFromS: Double? = null,
    ): List<Frame> = Synth.synthFrames(SynthSpec(speed, laneChange = laneChange, signal = signal, brakeFromS = brakeFromS, fromS = fromS))

    fun lc(startS: Double, durS: Double, dir: String) = LaneChangeSpec(startS, durS, dir)
    fun sig(fromS: Double, dir: String, toS: Double? = null) = SignalSpec(fromS, dir, toS)

    /** 곡선 도로: 반경 R(m)를 일정 속도로 달림. 핸들은 곡선만큼 일정하게 꺾여 있다. (engine.test.js 17행) */
    fun curveFrames(R: Double, kph: Double, dir: Double = 1.0, fromS: Double = -10.0): List<Frame> {
        val v = kph / 3.6
        return synth(kph, fromS = fromS).map { f ->
            val t = (f.tMs - fromS * 1000) / 1000
            f.copy(headingDeg = num((72 + dir * ((v * t) / R) * (180 / PI) + 360) % 360), steeringDeg = num(dir * 2.5))
        }
    }

    /** 결정적 의사 난수 (engine.test.js 25행): 32비트 LCG, x / 2^32. */
    fun rng(seed: Long): () -> Double {
        var x = seed and 0xFFFFFFFFL
        return { x = (x * 1664525L + 1013904223L) and 0xFFFFFFFFL; x.toDouble() / 4294967296.0 }
    }

    /** 도로 곡률 k(t)[1/m]를 적분해 방향을 만든다. 핸들은 곡률에 비례 (R800 에서 2.5°). (engine.test.js 520행) */
    fun roadFrames(kph: Double, kOf: (Double) -> Double, steer: Boolean = true): List<Frame> {
        val v = kph / 3.6
        var h = 72.0
        return synth(kph).mapIndexed { i, f ->
            val t = f.tMs / 1000
            if (i > 0) h += (v * kOf(t) * 0.1) * 180 / PI
            f.copy(headingDeg = num((h + 360) % 360), steeringDeg = if (steer) num(JsMath.round(kOf(t) * 2000 * 10) / 10) else JsScalar.Null)
        }
    }

    fun ramp(t0: Double, t1: Double, k: Double): (Double) -> Double = { t -> if (t <= t0) 0.0 else if (t >= t1) k else (k * (t - t0)) / (t1 - t0) }
    fun rampDown(t0: Double, t1: Double, k: Double): (Double) -> Double = { t -> if (t <= t0) k else if (t >= t1) 0.0 else k * (1 - (t - t0) / (t1 - t0)) }
    fun bump(t0: Double, t1: Double, k: Double): (Double) -> Double = { t -> if (t <= t0 || t >= t1) 0.0 else k * JsMath.sin(PI * (t - t0) / (t1 - t0)) }

    private fun steerOf(v: Double): (Double) -> Double = { k -> (2.7 * k + (2 * PI / 180 / 9.81) * v * v * k) * 15 * 180 / PI }

    /** 물리 모형 (engine.test.js 754행): 핸들 = 조향비 15 × 축거 2.7m × 곡률 + 언더스티어. */
    fun physFrames(kph: Double, fromS: Double = -10.0, roadK: (Double) -> Double = { 0.0 }, signal: SignalSpec? = null, lc: LaneChangeSpec? = null): List<Frame> {
        val v = kph / 3.6
        val steer = steerOf(v)
        var h = 72.0
        val arr = synth(kph, fromS = fromS, signal = signal, laneChange = lc)
        return arr.mapIndexed { i, f ->
            val t = f.tMs / 1000
            if (i > 0) h += v * roadK(t) * ((f.tMs - arr[i - 1].tMs) / 1000) * 180 / PI
            val lcDev = f.hd - 72
            f.copy(headingDeg = num((h + lcDev + 360) % 360), steeringDeg = num(JsMath.round((f.sd + steer(roadK(t))) * 10) / 10))
        }
    }

    /** physDrive 의 진로변경 (W 기본 3.5m). */
    data class DriveLc(val startS: Double, val durS: Double, val dir: String, val W: Double? = null)
    data class DriveSignal(val fromS: Double, val dir: String)
    data class Weave(val A: Double, val P: Double, val ph: Double)

    /** 도로 곡률 + 진로변경 곡률 물리 모형 (100Hz 적분 → 10Hz 프레임). (engine.test.js 937행) */
    fun physDrive(
        kph: Double,
        fromS: Double = -20.0,
        roadK: (Double) -> Double = { 0.0 },
        lc: DriveLc? = null,
        signal: DriveSignal? = null,
        weave: Weave? = null,
    ): List<Frame> {
        val v = kph / 3.6
        val steer = steerOf(v)
        fun wv(t: Double): DoubleArray {
            if (weave == null) return doubleArrayOf(0.0, 0.0)
            val w = (2 * PI) / weave.P
            return doubleArrayOf(
                (JsMath.atan((weave.A * w * JsMath.cos(w * t + weave.ph)) / v) * 180) / PI,
                (-weave.A * w * w * JsMath.sin(w * t + weave.ph)) / (v * v),
            )
        }
        fun lcAt(t: Double): DoubleArray {
            if (lc == null) return doubleArrayOf(0.0, 0.0)
            val tau = t - lc.startS
            val D = lc.durS
            val W = lc.W ?: 3.5
            val s = if (lc.dir == "RIGHT") 1.0 else -1.0
            if (tau <= 0 || tau >= D) return doubleArrayOf(0.0, 0.0)
            val dy = (W / D) * (1 - JsMath.cos((2 * PI * tau) / D))
            val ay = ((2 * PI * W) / (D * D)) * JsMath.sin((2 * PI * tau) / D)
            return doubleArrayOf((s * JsMath.atan(dy / v) * 180) / PI, (s * ay) / (v * v))
        }
        val frames = ArrayList<Frame>()
        var road = 0.0
        val i0 = JsMath.round(fromS * 100).toInt()
        for (i in i0..0) {
            val t = i / 100.0
            if (i > i0) road += (v * roadK(t) * 0.01 * 180) / PI
            if (i % 10 != 0) continue
            val (dev, k) = lcAt(t).let { it[0] to it[1] }
            val (wd, wk) = wv(t).let { it[0] to it[1] }
            frames.add(
                Frame(
                    t = num(JsMath.round(t * 1000)), speedKph = num(kph), headingDeg = num((72 + road + dev + wd + 360) % 360),
                    turnSignal = str(if (signal != null && t >= signal.fromS) signal.dir else "NONE"),
                    steeringDeg = num(JsMath.round(steer(roadK(t) + k + wk) * 10) / 10),
                    brake = bool(false), airbag = bool(false),
                ),
            )
        }
        return frames
    }

    // ---------- Jev fakes ----------
    /** `makeJevJudge({ apiKey, fetchImpl: async () => ({ ok: false, status }) })` */
    fun failingJev(apiKey: String, status: Int) =
        JevJudge(apiKey = apiKey, transport = JevTransport { _, _, _, _ -> JevHttpResponse(false, status) { error("no body") } })

    // ---------- JS-ish assertions ----------
    fun rx(pattern: String) = Regex(pattern)
    fun assertMatch(actual: String?, pattern: String, msg: String? = null) {
        if (actual == null || !Regex(pattern).containsMatchIn(actual)) fail<Unit>("${msg ?: ""} expected /$pattern/ to match: $actual")
    }
    fun assertNoMatch(actual: String, pattern: String, msg: String? = null) {
        val m = Regex(pattern).find(actual)
        if (m != null) fail<Unit>("${msg ?: ""} expected /$pattern/ not to match, found '${m.value}' in: $actual")
    }
    fun ok(cond: Boolean, msg: String? = null) = assertTrue(cond, msg ?: "assert.ok")
    fun List<String>.anyMatch(pattern: String) = Regex(pattern).let { r -> any { r.containsMatchIn(it) } }

    /** assert.equal for engine numbers (JS number vs Kotlin Double?). */
    fun eqNum(expected: Number?, actual: Double?, msg: String? = null) = assertEquals(expected?.toDouble(), actual, msg)
    fun nums(vararg xs: Number?): List<Double?> = xs.map { it?.toDouble() }

    fun json(v: Any?): String = Js.jsonStringify(v)

    fun Card.textJson(): String = json(toJs())

    /** Nested JS-model access: `o.a.b.c`. */
    fun path(o: Any?, vararg keys: String): Any? {
        var cur = o
        for (k in keys) cur = (cur as? Map<*, *>)?.get(k) ?: return null
        return cur
    }

    /** JS-model object literal with Double numbers. */
    fun obj(vararg kv: Pair<String, Any?>): Map<String, Any?> = linkedMapOf(*kv).mapValuesTo(LinkedHashMap()) { (_, v) -> if (v is Int) v.toDouble() else v }
    fun noul(x: Double) = obj("noul" to x)
}

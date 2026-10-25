package com.first30.core.engine

import com.first30.core.model.Ctx
import com.first30.core.model.Frame

/**
 * engine.js `impactOf`(213), `speedContext`(219), `roadTypeOf`(736), `brakeEmpty`(809), `analyzeWindow`(848).
 */

/** `impact` 결과 `{ source, known, reason?, anchoredMs? }` (null = 키 없음). */
data class Impact(
    val source: String,
    var known: Boolean,
    var reason: String? = null,
    var anchoredMs: Double? = null,
) {
    fun toJs(): Map<String, Any?> = linkedMapOf<String, Any?>("source" to source, "known" to known).also { m ->
        reason?.let { m["reason"] = it }
        anchoredMs?.let { m["anchoredMs"] = it }
    }
}

/** engine.js 213행 `impactOf(ctx)`. */
fun impactOf(ctx: Ctx?): Impact {
    val trig = ctx?.trigger
    val src = if (trig != null) jsTrim(trig).lowercase() else ""
    return Impact(source = src.ifEmpty { "unknown" }, known = Thresh.IMPACT_TRIGGERS.contains(src))
}

/** engine.js 736행 `roadTypeOf(ctx)`. */
fun roadTypeOf(ctx: Ctx?): String {
    val r = ctx?.roadType
    return if (r == "highway" || r == "urban") r else "unknown"
}

internal class Speeds(val recentSpeedKph: Double?, val peakSpeedKph: Double?, val endSpeedKph: Double?)

/** engine.js 219행 `speedContext(pre, quality)`. */
internal fun speedContext(pre: List<PFrame>, quality: Quality?): Speeds {
    if (pre.isEmpty() || (quality != null && !quality.hasSpeed)) return Speeds(null, null, null)
    val last = pre[pre.size - 1]
    val recent = mean(pre.filter { it.t >= last.t - 4000 }.map { it.spd })
    var peak = 0.0
    var a = 0
    for (i in pre.indices) {
        while (pre[i].t - pre[a].t > 1000) a++
        peak = Math.max(peak, mean(pre.subList(a, i + 1).map { it.spd }))
    }
    return Speeds(round(recent, 0), round(peak, 0), round(last.spd, 0))
}

/** `brake` 결과 (engine.js 809행 `brakeEmpty`, 916~923행). */
data class BrakeFacts(
    val applied: Boolean?,
    val onsetSecBefore: Double? = null,
    val releasedSecBefore: Double? = null,
    val onAtBufferStart: Boolean = false,
) {
    fun toJs(): Map<String, Any?> = linkedMapOf(
        "applied" to applied, "onsetSecBefore" to onsetSecBefore,
        "releasedSecBefore" to releasedSecBefore, "onAtBufferStart" to onAtBufferStart,
    )
}

internal fun brakeEmpty(available: Boolean) = BrakeFacts(applied = if (available) false else null)

/** `road` 결과 (938행). */
data class RoadFacts(val yawRateDegS: Double, val curveCompensated: Boolean, val headingNoiseDeg: Double, val refEndSecBefore: Double) {
    fun toJs(): Map<String, Any?> = linkedMapOf(
        "yawRateDegS" to yawRateDegS, "curveCompensated" to curveCompensated,
        "headingNoiseDeg" to headingNoiseDeg, "refEndSecBefore" to refEndSecBefore,
    )
}

/**
 * `analyzeWindow` 결과 (= run() 의 `metrics`). [early] = 충돌 시각을 모르거나 프레임이 3개 미만일 때의 조기 반환(877행) 모양:
 * `road` 키가 없고 `stoppedAtEnd` 키가 있다(값은 null 일 수 있음). 그 밖엔 `stoppedAtEnd` 키가 없다.
 */
data class WindowMetrics(
    val quality: Quality,
    val roadType: String,
    val impact: Impact,
    val road: RoadFacts?,
    val impactSpeedKph: Double?,
    val recentSpeedKph: Double?,
    val peakSpeedKph: Double?,
    val endSpeedKph: Double?,
    val laneChange: LaneChange,
    val signal: SignalFacts,
    val brake: BrakeFacts,
    val maxDecelKphPerS: Double?,
    val airbagDeployed: Boolean,
    val stoppedAtImpact: Boolean?,
    val stoppedAtEnd: Boolean?,
    val hazardOn: Boolean,
    val bufferSec: Double,
    val early: Boolean,
) {
    fun toJs(): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("quality" to quality.toJs(), "roadType" to roadType, "impact" to impact.toJs())
        if (!early) m["road"] = road!!.toJs()
        m["impactSpeedKph"] = impactSpeedKph
        m["recentSpeedKph"] = recentSpeedKph
        m["peakSpeedKph"] = peakSpeedKph
        m["endSpeedKph"] = endSpeedKph
        m["laneChange"] = laneChange.toJs()
        m["signal"] = signal.toJs()
        m["brake"] = brake.toJs()
        m["maxDecelKphPerS"] = maxDecelKphPerS
        m["airbagDeployed"] = airbagDeployed
        m["stoppedAtImpact"] = stoppedAtImpact
        if (early) m["stoppedAtEnd"] = stoppedAtEnd
        m["hazardOn"] = hazardOn
        m["bufferSec"] = bufferSec
        return m
    }
}

/** engine.js 848행 `analyzeWindow(frames, ctx)`. [frames] null = JS 에서 배열이 아닌 값. */
fun analyzeWindow(frames: List<Frame?>?, ctxIn: Ctx?): WindowMetrics {
    val ctx = ctxIn ?: Ctx()
    val prepared = prepare(frames)
    var all = prepared.pre
    val quality = prepared.quality
    val roadType = roadTypeOf(ctx)
    val impact = impactOf(ctx)
    // 856행 에어백 시각 맞춤
    if (impact.known && impact.source == "airbag" && all.isNotEmpty()) {
        var i0 = -1
        for (i in all.indices) if (all[i].t <= Thresh.AIRBAG_SYNC_MS) i0 = i
        if (i0 >= 0 && all[i0].airbag) {
            var s0 = i0
            while (s0 > 0 && all[s0 - 1].airbag) s0--
            val startT = all[s0].t
            if (s0 == 0 && startT < -Thresh.AIRBAG_SYNC_MS) {
                impact.known = false; impact.reason = "airbag_before_buffer"
            } else if (startT < -Thresh.AIRBAG_SYNC_MS) {
                if (-startT > Thresh.AIRBAG_MAX_SHIFT_MS) { impact.known = false; impact.reason = "airbag_too_early" }
                else { impact.anchoredMs = startT; all = all.map { it.withT(it.t - startT) } }
            }
        }
    }
    val pre = all.filter { it.t <= 0 }
    val airbagDeployed = all.any { it.airbag }
    val speeds = speedContext(pre, quality)
    val bufferSec = if (pre.isNotEmpty()) sec(pre[pre.size - 1].t - pre[0].t) else 0.0
    val hazardOn = quality.signalAvailable && pre.any { it.turnSignal == "HAZARD" }
    if (!impact.known || pre.size < 3) {
        return WindowMetrics(
            quality = quality, roadType = roadType, impact = impact, road = null, impactSpeedKph = null,
            recentSpeedKph = speeds.recentSpeedKph, peakSpeedKph = speeds.peakSpeedKph, endSpeedKph = speeds.endSpeedKph,
            laneChange = emptyLaneChange("unmeasurable", if (impact.known) "too_few_frames" else "impact_unknown"),
            signal = SignalFacts(
                status = "not_applicable", requiredM = if (roadType == "highway") 100.0 else 30.0,
                leadM = null, leadSec = null, stateAtImpact = null, recentOn = null, full = false,
            ),
            brake = brakeEmpty(quality.brakeAvailable && impact.known), maxDecelKphPerS = null, airbagDeployed = airbagDeployed,
            stoppedAtImpact = null,
            stoppedAtEnd = if (pre.isNotEmpty()) pre.takeLast(5).all { it.spd < 2 } else null,
            hazardOn = hazardOn, bufferSec = bufferSec, early = true,
        )
    }
    val last = pre[pre.size - 1]
    if (quality.steeringAvailable) {
        quality.steeringSign = steeringSignCheck(pre)
        if (quality.steeringSign == "reversed") quality.steeringAvailable = false
    }
    val guard = guardFor(pre)
    if (quality.steeringAvailable) quality.headingLagSec = estimateHeadingLag(pre)
    val base = roadBaseline(pre, guard.guardMs)
    val series = lateralSeries(pre, base)
    val allBrakeRuns = if (quality.brakeAvailable) runsOf(pre) { it.brake == true } else emptyList()
    val opts = LcOpts(fast = guard.fast)
    var laneChange = classifyLaneChange(pre, series, base, quality, allBrakeRuns, opts)
    // 900행 판정 민감도 확인
    if (base.quiet && (laneChange.status == "detected" || laneChange.status == "none")) {
        val base2 = if (base.curved) {
            baselineFrom(fitWindow(pre, pre[0].t).copy(ws = pre[0].t, we = last.t, quiet = true), pre[0].t, true).copy(wholeBuffer = true)
        } else wholeBufferBaseline(pre, base)
        val lc2 = classifyLaneChange(pre, lateralSeries(pre, base2), base2, quality, allBrakeRuns, opts)
        if (laneChange.status == "detected" && lc2.status != "detected") {
            laneChange = laneChange.copy(status = "uncertain", detected = false, reason = "baseline_sensitive")
        } else if (laneChange.status == "none" && lc2.status != "none" && lc2.status != "too_slow" &&
            (lc2.status in listOf("detected", "completed", "starting") || orElse(lc2.lateralM, 0.0) >= Thresh.LANE_LAT_M)
        ) {
            laneChange = laneChange.copy(status = "uncertain", reason = "baseline_sensitive")
        }
    }
    val signal = analyzeSignal(pre, laneChange, roadType, quality)

    // 915행 브레이크
    val brake = if (!quality.brakeAvailable) brakeEmpty(false) else {
        val bruns = allBrakeRuns.filter { it.end >= last.t - 1500 }
        val br = bruns.lastOrNull()
        if (br != null) BrakeFacts(
            applied = true, onsetSecBefore = sec(last.t - br.start),
            releasedSecBefore = if (br.end < last.t) sec(last.t - br.end) else null, onAtBufferStart = br.start == pre[0].t,
        ) else brakeEmpty(true)
    }

    // 925행
    var maxDecel = 0.0
    val last2s = pre.filter { it.t >= last.t - 2000 }
    for (i in last2s.indices) {
        for (j in i + 1 until last2s.size) {
            val dt = (last2s[j].t - last2s[i].t) / 1000
            if (dt >= 0.5 && dt <= 1.0) maxDecel = Math.max(maxDecel, (last2s[i].spd - last2s[j].spd) / dt)
        }
    }

    return WindowMetrics(
        quality = quality, roadType = roadType, impact = impact,
        road = RoadFacts(base.yawRateDegS, base.curved, base.noiseDeg, sec(last.t - base.refEndT)),
        impactSpeedKph = round(last.spd, 0),
        recentSpeedKph = speeds.recentSpeedKph, peakSpeedKph = speeds.peakSpeedKph, endSpeedKph = speeds.endSpeedKph,
        laneChange = laneChange, signal = signal, brake = brake,
        maxDecelKphPerS = round(maxDecel, 1), airbagDeployed = airbagDeployed,
        stoppedAtImpact = pre.takeLast(5).all { it.spd < 2 }, stoppedAtEnd = null,
        hazardOn = hazardOn, bufferSec = bufferSec, early = false,
    )
}

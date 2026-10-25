package com.first30.core.engine

/**
 * engine.js 차로변경 움직임 분류: `emptyLaneChange`(452), `classifyLaneChange`(482), `classifyLaneChangeCore`(509).
 */

/**
 * `laneChange` 결과. JS 처럼 제자리에서 고치고(`lc.x = ...`), 펼침 복사(`{ ...lc, ... }`)는 [copy].
 *
 * 늘 있는 키(값이 JS null 일 수 있음): status, detected, direction, lateralM, maxHeadingDeg, startT, endT,
 * completedSecBefore, steeringCorroborated, steeringReturned, maneuverSpeedKph, reason.
 * 있을 때만 있는 키(Kotlin null = 키 없음; JS 는 이 키들에 null 을 넣지 않는다): windowSec, roadCurveCompensated,
 * steerActivityDeg, steerActivityThrDeg, steerRefSigmaDeg, basis, riseSec, steerThresholdDeg, headingHeldSec,
 * keptLaneAfter, oppositeMoveM.
 */
data class LaneChange(
    var status: String,
    var detected: Boolean = false,
    var direction: String? = null,
    var lateralM: Double? = null,
    var maxHeadingDeg: Double? = null,
    var windowSec: Double? = null,
    var startT: Double? = null,
    var endT: Double? = null,
    var completedSecBefore: Double? = null,
    var steeringCorroborated: Boolean? = null,
    var steeringReturned: Boolean? = null,
    var maneuverSpeedKph: Double? = null,
    var reason: String? = null,
    var roadCurveCompensated: Boolean? = null,
    var steerActivityDeg: Double? = null,
    var steerActivityThrDeg: Double? = null,
    var steerRefSigmaDeg: Double? = null,
    var basis: String? = null,
    var riseSec: Double? = null,
    var steerThresholdDeg: Double? = null,
    var headingHeldSec: Double? = null,
    var keptLaneAfter: Boolean? = null,
    var oppositeMoveM: Double? = null,
    /** engine.js 941행: detected 일 때만 키가 생긴다(값은 null 일 수 있음). [hasDirectionCaveat] = 키가 있는가. */
    var hasDirectionCaveat: Boolean = false,
    var directionCaveat: String? = null,
) {
    fun toJs(): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "status" to status, "detected" to detected, "direction" to direction,
            "lateralM" to lateralM, "maxHeadingDeg" to maxHeadingDeg,
        )
        windowSec?.let { m["windowSec"] = it }
        m["startT"] = startT
        m["endT"] = endT
        m["completedSecBefore"] = completedSecBefore
        m["steeringCorroborated"] = steeringCorroborated
        m["steeringReturned"] = steeringReturned
        m["maneuverSpeedKph"] = maneuverSpeedKph
        m["reason"] = reason
        roadCurveCompensated?.let { m["roadCurveCompensated"] = it }
        steerActivityDeg?.let { m["steerActivityDeg"] = it }
        steerActivityThrDeg?.let { m["steerActivityThrDeg"] = it }
        steerRefSigmaDeg?.let { m["steerRefSigmaDeg"] = it }
        basis?.let { m["basis"] = it }
        riseSec?.let { m["riseSec"] = it }
        steerThresholdDeg?.let { m["steerThresholdDeg"] = it }
        headingHeldSec?.let { m["headingHeldSec"] = it }
        keptLaneAfter?.let { m["keptLaneAfter"] = it }
        oppositeMoveM?.let { m["oppositeMoveM"] = it }
        if (hasDirectionCaveat) m["directionCaveat"] = directionCaveat
        return m
    }
}

/** engine.js 452행 `emptyLaneChange(status, extra)` — extra 는 호출하는 쪽에서 copy/대입으로. */
internal fun emptyLaneChange(status: String, reason: String? = null): LaneChange = LaneChange(status = status, reason = reason)

internal class LcOpts(val fast: Boolean = false)

private fun dirName(sgn: Double) = if (sgn > 0) "RIGHT" else "LEFT"

/** engine.js 482행 `classifyLaneChange`. */
internal fun classifyLaneChange(
    pre: List<PFrame>, series: List<SPoint>, base: Baseline, quality: Quality, brakeRuns: List<Run>, opts: LcOpts = LcOpts(),
): LaneChange {
    val lc = classifyLaneChangeCore(pre, series, base, quality, brakeRuns, opts)
    if (lc.status == "starting") {
        if (!quality.steeringAvailable) return lc.copy(status = "uncertain", reason = "no_steering")
        val st = lc.startT
        if (st != null && brakeRuns.any { it.end >= st - 1500 }) lc.reason = "evasive"
    }
    val st = lc.startT
    val dir = lc.direction
    if (lc.status == "starting" && dir != null && st != null && series.isNotEmpty()) {
        val last = pre[pre.size - 1]
        val sgn = if (dir == "RIGHT") 1.0 else -1.0
        val local = series.filter { it.t >= last.t - Thresh.LOCAL_SEC * 1000 }
        val atStart = local.filter { it.t <= st }
        val latS = if (atStart.isNotEmpty()) atStart[atStart.size - 1].lat else local[0].lat
        val oppBefore = Math.max(0.0, jsMax(atStart.map { (it.lat - latS) * sgn }))
        val oppAfter = -(series[series.size - 1].lat - latS) * sgn
        val oppSteer = quality.steeringAvailable && steeringRun(series, -sgn, st - 5000, last.t).ok
        if (oppBefore >= Thresh.LANE_LAT_M || oppAfter >= 0.3 || oppSteer) {
            return lc.copy(status = "uncertain", reason = "opposite_pair", oppositeMoveM = round(Math.max(oppBefore, oppAfter), 1))
        }
    }
    return lc
}

/** engine.js 509행 `classifyLaneChangeCore`. */
internal fun classifyLaneChangeCore(
    pre: List<PFrame>, series: List<SPoint>, base: Baseline, quality: Quality, brakeRuns: List<Run>, opts: LcOpts,
): LaneChange {
    val last = pre[pre.size - 1]
    val bufferMs = last.t - pre[0].t
    val recent = pre.filter { it.t >= last.t - 4000 }
    val recentSpeed = mean(recent.map { it.spd })
    if (!quality.hasHeading || quality.headingMissing > Thresh.MISSING_MAX || quality.speedMissing > Thresh.MISSING_MAX) {
        return emptyLaneChange("unmeasurable", "missing")
    }
    if (bufferMs < Thresh.MIN_BUFFER_SEC * 1000) return emptyLaneChange("unmeasurable", "short_buffer")
    val localMs = Math.min(Thresh.LOCAL_SEC * 1000, bufferMs)
    val local = series.filter { it.t >= last.t - localMs }
    val latEnd = series[series.size - 1].lat
    val latSpan = jsMax(local.map { Math.abs(latEnd - it.lat) })
    val maxAbsDh = jsMax(local.map { Math.abs(it.dhs) })
    val lc = LaneChange(
        status = "none", detected = false, direction = null,
        lateralM = round(latSpan, 1), maxHeadingDeg = round(maxAbsDh, 1), windowSec = sec(localMs),
        startT = null, endT = null, completedSecBefore = null,
        steeringCorroborated = if (quality.steeringAvailable) false else null, steeringReturned = null,
        maneuverSpeedKph = round(recentSpeed, 0), reason = null,
        roadCurveCompensated = base.curved,
    )

    if (recentSpeed < Thresh.MIN_SPEED_KPH && latSpan < Thresh.LANE_LAT_M) { lc.status = "too_slow"; return lc }
    // 535행
    if (base.roadChange) {
        return emptyLaneChange("uncertain", "road_change").also {
            it.maneuverSpeedKph = round(recentSpeed, 0); it.windowSec = sec(localMs)
        }
    }

    // 538행
    val lateSteer: Int = if (quality.steeringAvailable) {
        val a = steeringRun(series, 1.0, last.t - 1500, last.t)
        val b = steeringRun(series, -1.0, last.t - 1500, last.t)
        if (a.ok) 1 else if (b.ok) -1 else 0
    } else 0
    // 542행
    val tail2 = series.filter { it.t >= last.t - 2000 }
    val endDh = tail2[tail2.size - 1].dhs
    val driftDh = endDh - tail2[0].dhs
    val steerTail = series.filter { it.t >= last.t - 1000 && it.sdev != null }.map { it.sdev!! }
    val steerMean = if (steerTail.isNotEmpty()) mean(steerTail) else 0.0
    val drifting = Math.abs(endDh) >= 0.5 && Math.abs(driftDh) >= 0.4 && sign(driftDh) == sign(endDh) &&
        (Math.abs(steerMean) < 0.3 || sign(steerMean) == sign(endDh))
    // 549행 뚜렷한 차로 유지
    if (latSpan < Thresh.LANE_KEEP_LAT_M && maxAbsDh < Thresh.LANE_KEEP_DEG && lateSteer == 0 && !drifting) {
        val lag = quality.headingLagSec
        if (opts.fast && bufferMs < Thresh.MIN_BUFFER_FAST_SEC * 1000) { lc.status = "uncertain"; lc.reason = "short_for_speed" }
        else if (base.lateRef) { lc.status = "uncertain"; lc.reason = "late_reference" }
        else if (!quality.steeringAvailable) { lc.status = "uncertain"; lc.reason = "no_steering" }
        else if (lag != null && lag >= Thresh.LAG_MAX_SEC) {
            val tailFrom = last.t - (lag + 2) * 1000
            val quietTail = series.filter { it.t >= tailFrom && it.sdevs != null }.all { Math.abs(it.sdevs!!) < 1.5 }
            if (!quietTail) { lc.status = "uncertain"; lc.reason = "heading_lag" }
        }
        if (lc.status == "none") {
            val act = steerActivity(pre, base, last.t - localMs)
            if (act != null) { lc.steerActivityDeg = act.dev; lc.steerActivityThrDeg = act.thr; lc.steerRefSigmaDeg = act.sigma }
            if (act != null && act.dev >= act.thr) { lc.status = "uncertain"; lc.reason = "steer_activity" }
        }
        return lc
    }

    // 573행
    fun startingOrBorderline(): LaneChange {
        val recentDh = series.filter { it.t >= last.t - 2000 }
        var peak = recentDh[0]
        for (k in 1 until recentDh.size) { val s = recentDh[k]; if (Math.abs(s.dhs) > Math.abs(peak.dhs)) peak = s }
        if (lateSteer != 0 || drifting || Math.abs(peak.dhs) >= Thresh.LANE_KEEP_DEG) {
            val sgn: Double = if (lateSteer != 0) lateSteer.toDouble() else orElse(sign(peak.dhs), 1.0)
            var i = series.size - 1
            fun active(s: SPoint): Boolean = s.dhs * sgn >= 0.5 || (s.sdev != null && s.sdev * sgn >= Thresh.STEER_DEV_DEG)
            while (i > 0 && !active(series[i])) i--
            while (i > 0 && active(series[i - 1])) i--
            lc.status = "starting"
            lc.direction = dirName(sgn)
            lc.startT = series[i].t
            lc.steeringCorroborated = if (quality.steeringAvailable) lateSteer != 0 else null
            lc.basis = if (lateSteer != 0) "steering" else "heading"
            return lc
        }
        lc.status = "uncertain"; lc.reason = "borderline"
        return lc
    }

    // 591행
    var e = series.size - 1
    while (e > 0 && Math.abs(series[e].dhs) < 0.5) e--
    if (Math.abs(series[e].dhs) < 0.5) return startingOrBorderline()
    val dirSign = sign(series[e].dhs)
    var s0 = e
    while (s0 > 0 && series[s0 - 1].dhs * dirSign >= 0.5) s0--
    var maxDirDh = 0.0
    var peakK = s0
    for (k in s0..e) if (series[k].dhs * dirSign > maxDirDh) { maxDirDh = series[k].dhs * dirSign; peakK = k }
    val startTh = Math.max(0.5, 0.15 * maxDirDh)
    s0 = peakK
    while (s0 > 0 && series[s0 - 1].dhs * dirSign >= startTh) s0--
    // 607행 무릎
    run {
        val W = Math.max(1000.0, 1.5 * orElse(quality.headingUpdateMs, 0.0))
        fun rateAt(k: Int): Double {
            var j = k
            while (j > 0 && series[k].t - series[j - 1].t <= W) j--
            val dt = (series[k].t - series[j].t) / 1000
            return if (dt > 0) ((series[k].dhs - series[j].dhs) * dirSign) / dt else 0.0
        }
        var maxRate = 0.0
        for (k in s0..peakK) maxRate = Math.max(maxRate, rateAt(k))
        if (maxRate > 0) {
            var kk = peakK
            while (kk > s0 && rateAt(kk - 1) >= 0.15 * maxRate) kk--
            val kneeT = series[kk].t - W / 2
            var k2 = s0
            while (k2 < peakK && series[k2].t < kneeT) k2++
            if (k2 > s0) s0 = k2
        }
    }
    // 627행
    val relLat = (series[e].lat - series[s0].lat) * dirSign
    if (!(relLat >= Thresh.LANE_LAT_M && maxDirDh >= Thresh.LANE_DEG)) return startingOrBorderline()
    val oppTh = Math.max(0.5, 0.5 * maxDirDh)
    val s0t = series[s0].t
    if (series.any { it.t >= s0t - 1500 && it.t < s0t && it.dhs * -dirSign >= oppTh }) {
        lc.status = "uncertain"; lc.reason = "opposite_pair"; lc.direction = dirName(dirSign)
        lc.lateralM = round(relLat, 1); lc.maxHeadingDeg = round(maxDirDh, 1)
        return lc
    }

    // 639행
    var peakSteerSeg = 0.0
    for (k in s0..e) { val sv = series[k].sdevs; if (sv != null) peakSteerSeg = Math.max(peakSteerSeg, sv * dirSign) }
    val steerStartTh = Math.max(Thresh.STEER_START_DEG, 0.15 * peakSteerSeg)
    val headingStart = series[s0].t
    fun steerAt(k: Int): Double { val sv = series[k].sdevs; return if (sv == null) 0.0 else sv * dirSign }
    while (s0 > 0 && steerAt(s0 - 1) >= steerStartTh && headingStart - series[s0 - 1].t <= 1500) s0--
    lc.direction = dirName(dirSign)
    val startT = series[s0].t
    lc.startT = startT
    lc.riseSec = sec(Math.max(0.0, series[peakK].t - series[s0].t))
    val endT: Double? = if (e == series.size - 1) null else series[e].t
    lc.endT = endT
    lc.lateralM = round(relLat, 1)
    lc.maxHeadingDeg = round(maxDirDh, 1)
    val manEnd = endT ?: last.t
    val inMan = pre.filter { it.t >= startT && it.t <= manEnd }
    val manSpeed = round(mean(inMan.map { it.spd }), 0)
    lc.maneuverSpeedKph = manSpeed

    if (manSpeed < Thresh.MIN_SPEED_KPH) { lc.status = "too_slow"; return lc }
    if (relLat > Thresh.LANE_LAT_MAX_M) { lc.status = "uncertain"; lc.reason = "too_large"; return lc }
    if (!base.quiet) { lc.status = "uncertain"; lc.reason = "noisy_heading"; return lc }
    if (!quality.steeringAvailable) { lc.status = "uncertain"; lc.reason = "no_steering"; return lc }
    // 662행
    val outside = series.filter { (it.t < startT - 1000 || it.t > manEnd + 500) && it.sdev != null }.map { it.sdev!! }
    var steerDev = Thresh.STEER_DEV_DEG
    if (outside.size >= 20) {
        val md = median(outside)
        val sigma = 1.4826 * median(outside.map { Math.abs(it - md) })
        steerDev = Math.max(steerDev, 2.5 * sigma)
    }
    lc.steerThresholdDeg = round(steerDev, 1)
    val run = steeringRun(series, dirSign, startT - 1000, manEnd, steerDev)
    lc.steeringCorroborated = run.ok
    if (!run.ok) { lc.status = "uncertain"; lc.reason = "steering_mismatch"; return lc }
    // 674행
    val tail = series.filter { it.t > manEnd - 300 && it.t <= manEnd && it.sdevs != null }
    val endDev = if (tail.isNotEmpty()) mean(tail.map { it.sdevs!! * dirSign }) else 0.0
    val returned = endDev <= Thresh.STEER_RETURN_RATIO * run.peakDeg
    lc.steeringReturned = returned
    if (!returned) { lc.status = "uncertain"; lc.reason = "curve_or_lc"; return lc }
    // 680행 굽은 길
    if (endT == null) {
        val neutral = Math.max(0.6, Thresh.HOLD_RATIO * run.peakDeg)
        var k = series.size - 1
        var holdFrom: Double? = null
        while (k > s0) {
            val sv = series[k].sdevs ?: break
            if (!(Math.abs(sv) <= neutral && series[k].dhs * dirSign >= 0.6 * maxDirDh)) break
            holdFrom = series[k].t
            k--
        }
        val held = if (holdFrom == null) 0.0 else last.t - holdFrom
        lc.headingHeldSec = sec(held)
        val rise = Math.max(0.0, series[peakK].t - startT)
        if (held >= Math.max(Thresh.HOLD_MIN_MS, Thresh.HOLD_RATIO * rise)) { lc.status = "uncertain"; lc.reason = "bend_hold"; return lc }
    }

    // 690행
    if (endT != null && last.t - endT > Thresh.ACTIVE_SEC * 1000) {
        lc.status = "completed"
        lc.completedSecBefore = sec(last.t - endT)
        lc.keptLaneAfter = Math.abs(latEnd - series[e].lat) < Thresh.LANE_KEEP_LAT_M
        return lc
    }
    if (last.t - startT < Thresh.STARTING_SEC * 1000) { lc.status = "starting"; lc.basis = "heading"; return lc }
    val evasive = last.t - startT < Thresh.EVASIVE_SEC * 1000 && brakeRuns.any { it.end >= startT - 1500 }
    if (evasive) { lc.status = "starting"; lc.reason = "evasive"; lc.basis = "heading"; return lc }
    lc.status = "detected"
    lc.detected = true
    return lc
}

/**
 * engine.js 747행 `directionCaveat(pre, series, base, lc, quality)` — detected 움직임의 방향을 믿기 어려운 기록인가.
 * 'opposite_signal' | 'opposite_move' | 'road_curve' | 'late_reference' | null.
 */
internal fun directionCaveat(pre: List<PFrame>, series: List<SPoint>, base: Baseline, lc: LaneChange, quality: Quality): String? {
    val startT = lc.startT
    val dir = lc.direction
    if (lc.status != "detected" || dir == null || startT == null) return null
    val last = pre[pre.size - 1]
    val sgn = if (dir == "RIGHT") 1.0 else -1.0
    val opp = if (sgn > 0) "LEFT" else "RIGHT"
    if (quality.signalAvailable && pre.any { it.t >= startT - Thresh.RECENT_SIGNAL_SEC * 1000 && it.turnSignal == opp }) return "opposite_signal"
    val atStart = series.filter { it.t >= last.t - Thresh.LOCAL_SEC * 1000 && it.t <= startT }
    if (atStart.isNotEmpty()) {
        val latS = atStart[atStart.size - 1].lat
        // 757행 Math.max(0, ...) — 빈 펼침은 없다(atStart 가 비지 않음)
        val oppLat = Math.max(0.0, jsMax(atStart.map { (it.lat - latS) * sgn }))
        if (oppLat >= Thresh.LANE_LAT_M &&
            steeringRun(series, -sgn, startT - 5000, startT, orElse(lc.steerThresholdDeg, Thresh.STEER_DEV_DEG)).ok
        ) return "opposite_move"
    }
    if (base.curved) return "road_curve"
    if (base.lateRef) return "late_reference"
    return null
}

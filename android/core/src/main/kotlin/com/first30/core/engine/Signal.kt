package com.first30.core.engine

/** engine.js 740행 `analyzeSignal` — 방향지시등 선행 거리와 여유. */

/**
 * `signal` 결과. [full] = false 는 analyzeWindow 의 조기 반환(880행) 모양:
 * `{ status, requiredM, leadM, leadSec, stateAtImpact, recentOn }` 만 있다.
 */
data class SignalFacts(
    var status: String,
    val requiredM: Double,
    var leadM: Double? = null,
    var leadSec: Double? = null,
    var marginM: Double? = null,
    var onAtBufferStart: Boolean = false,
    val stateAtImpact: String? = null,
    val recentOn: String? = null,
    val bridged: Boolean = false,
    var note: String? = null,
    val full: Boolean = true,
) {
    fun toJs(): Map<String, Any?> =
        if (!full) linkedMapOf(
            "status" to status, "requiredM" to requiredM, "leadM" to leadM, "leadSec" to leadSec,
            "stateAtImpact" to stateAtImpact, "recentOn" to recentOn,
        ) else linkedMapOf(
            "status" to status, "requiredM" to requiredM, "leadM" to leadM, "leadSec" to leadSec, "marginM" to marginM,
            "onAtBufferStart" to onAtBufferStart, "stateAtImpact" to stateAtImpact, "recentOn" to recentOn,
            "bridged" to bridged, "note" to note,
        )
}

/** engine.js 740행 `analyzeSignal(pre, lc, roadType, quality)`. */
internal fun analyzeSignal(pre: List<PFrame>, lc: LaneChange, roadType: String, quality: Quality): SignalFacts {
    val last = pre[pre.size - 1]
    val requiredM = if (roadType == "highway") 100.0 else 30.0
    val recent = if (quality.signalAvailable) pre.filter { it.t >= last.t - Thresh.RECENT_SIGNAL_SEC * 1000 } else emptyList()
    val recentLR = recent.filter { it.turnSignal == "LEFT" || it.turnSignal == "RIGHT" }
    val signal = SignalFacts(
        status = "not_applicable", requiredM = requiredM, leadM = null, leadSec = null, marginM = null,
        onAtBufferStart = false, stateAtImpact = if (quality.signalAvailable) last.turnSignal else null,
        recentOn = if (recentLR.isNotEmpty()) recentLR[recentLR.size - 1].turnSignal else null,
        bridged = pre.any { it.bridged }, note = null,
    )
    val dir = lc.direction
    val s = lc.startT
    if (dir == null || s == null) return signal
    if (!quality.signalAvailable) { signal.status = "unavailable"; return signal }
    val runs = runsOf(pre) { it.turnSignal == dir }
    if (runs.isEmpty()) { signal.status = "none"; return signal }
    // 760행
    val lagSec = Math.min(2.0, orElse(quality.headingLagSec, 0.0))
    val errSec = Math.max(
        Thresh.SIGNAL_MARGIN_SEC,
        Math.min(1.5, Math.max(orElse(quality.headingUpdateMs, 0.0) / 1000, 0.3 * orElse(lc.riseSec, 0.0))),
    ) + lagSec
    val vNear = mean(pre.filter { it.t >= s - errSec * 1000 && it.t <= s + errSec * 1000 }.map { it.spd }) / 3.6
    val covering = runs.firstOrNull { it.start <= s && it.end >= s }
    val after = runs.firstOrNull { it.start > s }
    if (covering == null && after == null) {
        signal.status = "none"
        signal.note = "움직이기 전에 신호를 껐음"
        return signal
    }
    if (covering == null) {
        val afterSec = (after!!.start - s) / 1000
        signal.leadM = 0.0
        signal.leadSec = -sec(after.start - s)
        if (afterSec <= errSec) {
            signal.status = "borderline"
            signal.marginM = Math.max(Thresh.SIGNAL_MARGIN_MIN_M, round(vNear * errSec, 0))
            signal.note = "움직이기 시작한 무렵에 켰음 — 시작 시점의 측정 오차 안이라 기준 충족 여부를 단정할 수 없음"
        } else signal.status = "after"
        return signal
    }
    val run = covering
    var dist = 0.0
    for (i in 1 until pre.size) {
        val p = pre[i - 1]
        val f = pre[i]
        if (p.t >= run.start && f.t <= s) dist += ((p.spd + f.spd) / 2 / 3.6) * ((f.t - p.t) / 1000)
    }
    val vAvg = mean(pre.filter { it.t >= run.start && it.t <= s }.map { it.spd }) / 3.6
    val marginM = Math.max(Thresh.SIGNAL_MARGIN_MIN_M, round(Math.max(vAvg, orElse(vNear, 0.0)) * errSec, 0))
    val leadM = round(dist, 0)
    signal.leadM = leadM
    signal.leadSec = sec(s - run.start)
    signal.marginM = marginM
    signal.onAtBufferStart = run.start == pre[0].t
    if (leadM < 1 && !signal.onAtBufferStart) {
        signal.status = "borderline"
        signal.note = "움직이기 시작하면서 거의 동시에 켰음 — 시작 시점의 측정 오차 안이라 기준 충족 여부를 단정할 수 없음"
        return signal
    }
    if (roadType == "unknown" && leadM >= 30 - marginM && leadM < 100 + marginM) {
        signal.status = if (signal.onAtBufferStart) "indeterminate" else "borderline"
        signal.note = "도로 유형을 몰라 일반도로(30m)·고속도로(100m) 기준 중 어느 쪽인지 정할 수 없음"
        return signal
    }
    signal.status = if (leadM >= requiredM + marginM) "ok"
    else if (signal.onAtBufferStart) "indeterminate"
    else if (leadM > requiredM - marginM) "borderline"
    else "late"
    return signal
}

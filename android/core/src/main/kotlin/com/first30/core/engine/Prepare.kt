package com.first30.core.engine

import com.first30.core.model.Frame
import com.first30.core.seal.Canonical

/**
 * engine.js 0단계 "입력 정리" (112~209행): `normSignal`, `interpolate`, `holdLast`, `bridgeSignal`, `prepare`.
 */

/**
 * 정리된 프레임 (`prepare` 171~179행의 객체). 필드는 JS 처럼 제자리에서 바뀐다(보간·holdLast·bridgeSignal).
 * - [speedKph]: prepare 뒤에는 늘 값이 있다(채널 전체가 없으면 0).
 * - [headingDeg]: 채널 전체가 없으면 null 로 남는다. JS 산술에서 null 은 0 이므로 분석 단계는 [hd] 를 쓴다.
 * - [bridged]: bridgeSignal 이 이은 프레임에만 `bridged: true` 키가 생긴다.
 */
class PFrame(
    var t: Double,
    var speedKph: Double?,
    var headingDeg: Double?,
    var steeringDeg: Double?,
    var turnSignal: String?,
    var brake: Boolean?,
    val airbag: Boolean,
    var bridged: Boolean = false,
) {
    /** 분석 단계의 `f.speedKph` (prepare 뒤 늘 숫자). */
    val spd: Double get() = speedKph ?: 0.0

    /** 분석 단계의 `f.headingDeg` — JS 산술의 null→0 강제 변환을 그대로 따른다. */
    val hd: Double get() = headingDeg ?: 0.0

    /** JS `{ ...f, t }` (analyzeWindow 867행). */
    fun withT(newT: Double) = PFrame(newT, speedKph, headingDeg, steeringDeg, turnSignal, brake, airbag, bridged)

    fun toJs(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "t" to t,
        "speedKph" to speedKph,
        "headingDeg" to headingDeg,
        "steeringDeg" to steeringDeg,
        "turnSignal" to turnSignal,
        "brake" to brake,
        "airbag" to airbag,
    ).also { if (bridged) it["bridged"] = true }
}

/**
 * `prepare` 의 `quality` (189~206행) + analyzeWindow 가 덧붙이는 [steeringSign]·[headingLagSec] (887~891행).
 * JS 처럼 하나의 객체가 공유되고 analyzeWindow 가 고친다([steeringAvailable] 이 false 로 바뀔 수 있다).
 */
class Quality(
    val frames: Int,
    val headingUpdateMs: Double?,
    val speedMissing: Double,
    val headingMissing: Double,
    var steeringAvailable: Boolean,
    val signalAvailable: Boolean,
    val brakeAvailable: Boolean,
    val maxGapMs: Double,
) {
    var hasSpeed: Boolean = false
    var hasHeading: Boolean = false

    /** 'ok' | 'reversed' | 'unknown'; null = 키 없음. */
    var steeringSign: String? = null

    /** headingLagSec 키가 있는가 (값은 null 일 수 있다). */
    var headingLagSet: Boolean = false
    var headingLagSec: Double? = null
        set(v) { field = v; headingLagSet = true }

    fun toJs(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "frames" to frames.toDouble(),
        "headingUpdateMs" to headingUpdateMs,
        "speedMissing" to speedMissing,
        "headingMissing" to headingMissing,
        "steeringAvailable" to steeringAvailable,
        "signalAvailable" to signalAvailable,
        "brakeAvailable" to brakeAvailable,
        "maxGapMs" to maxGapMs,
        "hasSpeed" to hasSpeed,
        "hasHeading" to hasHeading,
    ).also { m ->
        steeringSign?.let { m["steeringSign"] = it }
        if (headingLagSet) m["headingLagSec"] = headingLagSec
    }
}

/** `prepare` 의 반환값 `{ pre, quality }`. */
class Prepared(val pre: List<PFrame>, val quality: Quality) {
    fun toJs(): Map<String, Any?> = linkedMapOf("pre" to pre.map { it.toJs() }, "quality" to quality.toJs())
}

/** engine.js 114행 `normSignal`. */
internal fun normSignal(v: String?): String? {
    if (v == null) return null
    val s = jsTrim(v).uppercase()
    if (s == "LEFT" || s == "L") return "LEFT"
    if (s == "RIGHT" || s == "R") return "RIGHT"
    if (s == "NONE" || s == "OFF") return "NONE"
    if (s == "BOTH" || s == "HAZARD" || s == "EMERGENCY") return "HAZARD"
    return null
}

/** engine.js 125행 `interpolate(arr, key, angular)` — [get]/[set] 가 `f[key]` 역할. */
private inline fun interpolate(
    arr: List<PFrame>,
    angular: Boolean,
    get: (PFrame) -> Double?,
    set: (PFrame, Double) -> Unit,
): Boolean {
    val valid = ArrayList<Int>()
    arr.forEachIndexed { i, f -> if (get(f) != null) valid.add(i) }
    if (valid.isEmpty()) return false
    // valid.filter(j < i).pop() / valid.find(j > i) — valid 는 오름차순이라 포인터로 같은 값을 얻는다.
    var vp = 0 // valid[vp] 는 i 이상인 첫 유효 인덱스
    for (i in arr.indices) {
        while (vp < valid.size && valid[vp] < i) vp++
        if (get(arr[i]) != null) continue
        val prevI = if (vp > 0) valid[vp - 1] else null
        val nextI = if (vp < valid.size) valid[vp] else null // arr[i] 는 결측이라 valid[vp] > i
        if (prevI == null) set(arr[i], get(arr[nextI!!])!!)
        else if (nextI == null) set(arr[i], get(arr[prevI])!!)
        else {
            val a = arr[prevI]
            val b = arr[nextI]
            val w = (arr[i].t - a.t) / (b.t - a.t)
            val av = get(a)!!
            val bv = get(b)!!
            set(arr[i], if (angular) (av + w * angDiff(bv, av) + 360.0) % 360.0 else av + w * (bv - av))
        }
    }
    return true
}

/** engine.js 144행 `holdLast`. */
private inline fun <T : Any> holdLast(arr: List<PFrame>, fallback: T, get: (PFrame) -> T?, set: (PFrame, T) -> Unit) {
    var lastV: T? = null
    for (f in arr) {
        val v = get(f)
        if (v == null) set(f, lastV ?: fallback) else lastV = v
    }
}

/** engine.js 150행 `bridgeSignal`. */
private fun bridgeSignal(pre: List<PFrame>) {
    for (dir in listOf("LEFT", "RIGHT")) {
        var lastOn = -1
        for (i in pre.indices) {
            if (pre[i].turnSignal != dir) continue
            if (lastOn >= 0 && i - lastOn > 1 && pre[i].t - pre[lastOn].t <= Thresh.SIGNAL_GAP_MS + 100) {
                val between = pre.subList(lastOn + 1, i)
                if (between.all { it.turnSignal == "NONE" }) between.forEach { it.turnSignal = dir; it.bridged = true }
            }
            lastOn = i
        }
    }
}

/** engine.js 165행 `byTimeThenContent`: `(a.t - b.t) || (stableStringify 비교)`. */
private val BY_TIME_THEN_CONTENT: Comparator<Pair<Frame, Double>> = Comparator { a, b ->
    val d = a.second - b.second
    if (d < 0.0) -1
    else if (d > 0.0) 1
    else {
        val sa = Canonical.stableStringify(a.first.toJsMap())
        val sb = Canonical.stableStringify(b.first.toJsMap())
        if (sa < sb) -1 else if (sa > sb) 1 else 0
    }
}

/**
 * engine.js 166행 `prepare(frames)`. [frames] 가 null 이면 JS 의 "배열이 아님"(= 빈 배열)과 같다.
 * 원소가 null 인 프레임도 JS 처럼 버린다(`f && ...`).
 */
fun prepare(frames: List<Frame?>?): Prepared {
    val raw = (frames ?: emptyList()).mapNotNull { f -> f?.t?.finiteOrNull()?.let { f to it } }
        .sortedWith(BY_TIME_THEN_CONTENT) // 안정 정렬 (V8 Array.prototype.sort 와 같다)
    val dedup = ArrayList<Pair<Frame, Double>>()
    for (f in raw) {
        if (dedup.isNotEmpty() && dedup[dedup.size - 1].second == f.second) dedup.removeAt(dedup.size - 1)
        dedup.add(f)
    }
    val all = dedup.map { (f, t) ->
        PFrame(
            t = t,
            speedKph = f.speedKph.finiteOrNull()?.let { Math.max(0.0, it) },
            headingDeg = f.headingDeg.finiteOrNull()?.let { ((it % 360.0) + 360.0) % 360.0 },
            steeringDeg = f.steeringDeg.finiteOrNull(),
            turnSignal = normSignal(f.turnSignal.stringOrNull()),
            brake = f.brake.booleanOrNull(),
            airbag = f.airbag.booleanOrNull() == true,
        )
    }
    val n = if (all.isEmpty()) 1 else all.size
    fun ratio(has: (PFrame) -> Boolean): Double = all.count(has).toDouble() / n
    var maxGapMs = 0.0
    for (i in 1 until all.size) maxGapMs = Math.max(maxGapMs, all[i].t - all[i - 1].t)
    val changeTs = ArrayList<Double>()
    for (i in 1 until all.size) {
        val h = all[i].headingDeg
        val hp = all[i - 1].headingDeg
        if (h != null && hp != null && h.toDouble() != hp.toDouble()) changeTs.add(all[i].t) // JS !== (원시 비교)
    }
    val gaps = (1 until changeTs.size).map { changeTs[it] - changeTs[it - 1] }.filter { it <= 3000.0 }
    val quality = Quality(
        frames = all.size,
        headingUpdateMs = if (gaps.isNotEmpty()) com.first30.core.model.JsMath.round(median(gaps)) else null,
        speedMissing = round(1 - ratio { it.speedKph != null }, 3),
        headingMissing = round(1 - ratio { it.headingDeg != null }, 3),
        steeringAvailable = ratio { it.steeringDeg != null } >= 1 - Thresh.MISSING_MAX,
        signalAvailable = ratio { it.turnSignal != null } >= 1 - Thresh.MISSING_MAX,
        brakeAvailable = ratio { it.brake != null } >= 1 - Thresh.MISSING_MAX,
        maxGapMs = maxGapMs,
    )
    val hasSpeed = interpolate(all, false, { it.speedKph }, { f, v -> f.speedKph = v })
    val hasHeading = interpolate(all, true, { it.headingDeg }, { f, v -> f.headingDeg = v })
    if (quality.steeringAvailable) interpolate(all, false, { it.steeringDeg }, { f, v -> f.steeringDeg = v })
    quality.hasSpeed = hasSpeed
    if (!hasSpeed) all.forEach { it.speedKph = 0.0 }
    holdLast(all, "NONE", { it.turnSignal }, { f, v -> f.turnSignal = v })
    holdLast(all, false, { it.brake }, { f, v -> f.brake = v })
    quality.hasHeading = hasHeading
    bridgeSignal(all)
    return Prepared(all, quality)
}

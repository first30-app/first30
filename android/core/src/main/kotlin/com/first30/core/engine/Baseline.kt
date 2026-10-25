package com.first30.core.engine

/**
 * engine.js 1단계 측정 중 도로 방향 기준: `fitWindow`(232), `roadBaseline`(256), `baselineFrom`(366),
 * `wholeBufferBaseline`(382), `guardFor`(422).
 */

/** `fitWindow` 반환값 + 구간 표시(`{ ...w, ws, we, quiet }`). */
internal data class FitWin(
    val h0: Double,
    val mx: Double,
    val my: Double,
    val sxx: Double,
    val slope: Double,
    val sd: Double,
    val maxResid: Double,
    val steers: List<Double>,
    val steerSpread: Double,
    val lenMs: Double,
    val ws: Double = Double.NaN,
    val we: Double = Double.NaN,
    val quiet: Boolean = false,
)

/**
 * 도로 방향 기준 (`baselineFrom` / `flatFrom` / `wholeBufferBaseline` 반환 객체).
 * JS 에서 키가 없을 수 있는 값: [guardMs](null=없음), [lateRef]·[afterCurve]·[roadChange]·[wholeBuffer](false=없음 — 엔진은 참거짓으로만 읽는다).
 */
internal data class Baseline(
    val at: (Double) -> Double,
    val yawRateDegS: Double,
    val curved: Boolean,
    val noiseDeg: Double,
    val quiet: Boolean,
    val refStartT: Double,
    val refEndT: Double,
    val steerBase: Double,
    val guardMs: Double? = null,
    val lateRef: Boolean = false,
    val afterCurve: Boolean = false,
    val roadChange: Boolean = false,
    val wholeBuffer: Boolean = false,
)

/** engine.js 232행 `fitWindow(ref, t0)`. */
internal fun fitWindow(ref: List<PFrame>, t0: Double): FitWin {
    val h0 = ref[0].hd
    val xs = ref.map { (it.t - t0) / 1000.0 }
    val ys = ref.map { angDiff(it.hd, h0) }
    val mx = mean(xs)
    val my = mean(ys)
    var sxx = 0.0
    var sxy = 0.0
    for (i in xs.indices) {
        val x = xs[i]
        sxx += (x - mx) * (x - mx)
        sxy += (x - mx) * (ys[i] - my)
    }
    val slope = if (sxx > 0) sxy / sxx else 0.0
    val resid = ys.indices.map { i -> ys[i] - (my + slope * (xs[i] - mx)) }
    var rr = 0.0
    for (r in resid) rr += r * r
    val sd = Math.sqrt(rr / Math.max(1, ys.size - 2))
    val maxResid = jsMax(resid.map { Math.abs(it) })
    val steers = ref.mapNotNull { it.steeringDeg }
    val steerSpread = if (steers.isNotEmpty()) jsMax(steers) - jsMin(steers) else 0.0
    return FitWin(h0, mx, my, sxx, slope, sd, maxResid, steers, steerSpread, ref[ref.size - 1].t - ref[0].t)
}

private class CurveTry(val grown: FitWin, val curved: Boolean, val reached: Boolean)

/** engine.js 256행 `roadBaseline(pre, guardMs = 0)`. */
internal fun roadBaseline(pre: List<PFrame>, guardMs: Double = 0.0): Baseline {
    val t0 = pre[0].t
    val tEnd = pre[pre.size - 1].t
    val len = Thresh.REF_SEC * 1000
    val guard = Math.max(0.0, Math.min(guardMs, tEnd - t0 - len))
    val refEnd = tEnd - guard
    val curveEnd = tEnd - Math.min(guard, 2000.0)

    // 263행
    fun isTurning(w: FitWin): Boolean {
        val m = if (w.steers.isNotEmpty()) median(w.steers) else 0.0
        return Math.abs(w.slope) >= Thresh.YAW_MIN_DEG_S && Math.abs(w.slope) * (w.lenMs / 1000) >= 1 &&
            Math.abs(m) >= 0.3 && sign(m) == sign(w.slope)
    }

    // 267행
    fun findQuiet(from: Double, to: Double, flatOnly: Boolean = false): FitWin? {
        var ws = from
        while (ws + len <= to + 1) {
            val wsc = ws
            val ref = pre.filter { it.t >= wsc && it.t <= wsc + len }
            if (ref.size >= 3) {
                val w = fitWindow(ref, t0)
                if (w.sd <= Thresh.REF_NOISE_DEG && w.maxResid <= 2 * Thresh.REF_NOISE_DEG &&
                    w.steerSpread <= Thresh.REF_STEER_SPREAD_DEG && (!flatOnly || !isTurning(w))
                ) {
                    return w.copy(ws = ws, we = ws + len, quiet = true)
                }
            }
            ws += 500
        }
        return null
    }

    // 279행
    fun tryCurve(pick: FitWin): CurveTry {
        var grown = pick
        var we = pick.we + 500
        while (we <= curveEnd) {
            val wec = we
            val ref = pre.filter { it.t >= pick.ws && it.t <= wec }
            val w = fitWindow(ref, t0)
            if (w.maxResid > 0.6 || w.steerSpread > 3) break
            grown = w.copy(ws = pick.ws, we = we, quiet = true)
            we += 500
        }
        val g = grown
        val se = if (g.sxx > 0) g.sd / Math.sqrt(g.sxx) else Double.POSITIVE_INFINITY
        val steerMed: Double? = if (g.steers.isNotEmpty()) median(g.steers) else null
        // 291행 steady
        val steady = run {
            if (g.lenMs < Thresh.CURVE_MIN_SEC * 1000 - 1) return@run false
            val mid = g.ws + (g.we - g.ws) / 2
            val h1 = pre.filter { it.t >= g.ws && it.t <= mid }
            val h2 = pre.filter { it.t > mid && it.t <= g.we }
            if (h1.size < 3 || h2.size < 3) return@run false
            val s1 = fitWindow(h1, t0).slope
            val s2 = fitWindow(h2, t0).slope
            val m1 = median(h1.mapNotNull { it.steeringDeg })
            val m2 = median(h2.mapNotNull { it.steeringDeg })
            Math.abs(s1 - s2) <= Math.max(0.15, 0.35 * Math.abs(g.slope)) &&
                Math.abs(m1 - m2) <= Math.max(0.5, 0.4 * Math.abs(orElse(steerMed, 0.0)))
        }
        val curved = steady &&
            Math.abs(g.slope) >= Thresh.YAW_MIN_DEG_S && Math.abs(g.slope) >= 3 * se &&
            steerMed != null && sign(steerMed) == sign(g.slope) && Math.abs(steerMed) >= 0.3
        return CurveTry(g, curved, g.we >= curveEnd - 500)
    }

    // 309행 flatFrom
    fun flatFrom(pick: FitWin, endT: Double, lateRef: Boolean, afterCurve: Boolean = false): Baseline {
        val hi = Math.max(endT, pick.we)
        val ref = pre.filter { it.t >= pick.ws && it.t <= hi }
        val devs = ref.map { angDiff(it.hd, pick.h0) }
        fun sm(vals: List<Double?>, i: Int): Double? {
            var a = i
            var b = i
            while (a > 0 && ref[i].t - ref[a - 1].t <= 500) a--
            while (b < ref.size - 1 && ref[b + 1].t - ref[i].t <= 500) b++
            val xs = vals.subList(a, b + 1).filterNotNull()
            return if (xs.isNotEmpty()) mean(xs) else null
        }
        val steerVals = ref.map { it.steeringDeg }
        val devS = devs.indices.map { sm(devs, it) }
        val steerS = steerVals.indices.map { sm(steerVals, it) }
        var n = ref.count { it.t <= pick.we }
        var c = robustCenter(devs.subList(0, n))
        while (n < ref.size) {
            val steerMed = median(steerVals.subList(0, n).filterNotNull())
            val k = n
            val sk = steerS[k]
            if (Math.abs(devS[k]!! - c) > Thresh.REF_TRIM_DEG || (sk != null && Math.abs(sk - steerMed) > 3)) break
            n++
            if (n % 5 == 0 || n == ref.size) c = robustCenter(devs.subList(0, n))
        }
        c = robustCenter(devs.subList(0, n))
        val steers = steerVals.subList(0, n).filterNotNull()
        val h = (pick.h0 + c + 360) % 360
        return Baseline(
            at = { h }, yawRateDegS = 0.0, curved = false, noiseDeg = round(pick.sd, 2), quiet = true,
            refStartT = pick.ws, refEndT = ref[n - 1].t, guardMs = guard,
            steerBase = if (steers.isNotEmpty()) median(steers) else 0.0,
            lateRef = lateRef, afterCurve = afterCurve,
        )
    }

    var pick = findQuiet(t0, refEnd)
    var lateRef = false
    if (pick == null) {
        pick = findQuiet(refEnd - len + 500, tEnd)
        if (pick != null) lateRef = true
    }
    if (pick == null) {
        val ref = pre.filter { it.t <= refEnd }
        return baselineFrom(fitWindow(if (ref.size >= 3) ref else pre, t0).copy(ws = t0, we = refEnd, quiet = false), t0, false)
            .copy(guardMs = guard)
    }
    fun flatEnd(p: FitWin) = if (lateRef) p.we else refEnd
    val cv = tryCurve(pick)
    if (cv.curved && cv.reached) return baselineFrom(cv.grown, t0, true).copy(guardMs = guard, lateRef = lateRef)
    if (cv.curved || isTurning(pick)) {
        val after = if (cv.curved) cv.grown.we else pick.we
        val pick2 = findQuiet(after, if (lateRef) tEnd else refEnd, true)
        if (pick2 != null) return flatFrom(pick2, flatEnd(pick2), lateRef, afterCurve = true)
        return flatFrom(pick, flatEnd(pick), lateRef).copy(roadChange = true)
    }
    return flatFrom(pick, flatEnd(pick), lateRef)
}

/** engine.js 366행 `baselineFrom(w, t0, curved)`. */
internal fun baselineFrom(w: FitWin, t0: Double, curved: Boolean): Baseline {
    val h0 = w.h0
    val mx = w.mx
    val my = w.my
    val slope = if (curved) w.slope else 0.0
    return Baseline(
        at = { t -> (h0 + my + slope * ((t - t0) / 1000 - mx) + 360) % 360 },
        yawRateDegS = round(slope, 2),
        curved = curved,
        noiseDeg = round(w.sd, 2),
        quiet = w.quiet,
        refStartT = w.ws,
        refEndT = w.we,
        steerBase = if (w.steers.isNotEmpty()) median(w.steers) else 0.0,
    )
}

/** engine.js 382행 `wholeBufferBaseline(pre, base)`. */
internal fun wholeBufferBaseline(pre: List<PFrame>, base: Baseline): Baseline {
    val h0 = pre[0].hd
    val c = (h0 + robustCenter(pre.map { angDiff(it.hd, h0) }) + 360) % 360
    return base.copy(at = { c }, curved = false, refStartT = pre[0].t, refEndT = pre[pre.size - 1].t, wholeBuffer = true)
}

internal class Guard(val fast: Boolean, val guardMs: Double)

/** engine.js 422행 `guardFor(pre)`. */
internal fun guardFor(pre: List<PFrame>): Guard {
    val last = pre[pre.size - 1]
    val v10 = mean(pre.filter { it.t >= last.t - 10000 }.map { it.spd })
    val fast = v10 >= Thresh.FAST_KPH
    return Guard(fast, (if (fast) Thresh.GUARD_SEC_FAST else Thresh.GUARD_SEC) * 1000)
}

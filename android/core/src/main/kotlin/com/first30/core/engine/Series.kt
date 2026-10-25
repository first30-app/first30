package com.first30.core.engine

/**
 * engine.js 측면 이동 시계열과 핸들 관련 측정: `lateralSeries`(389), `steeringRun`(439), `steerActivity`(460),
 * `steeringSignCheck`(706), `runsOf`(725), `estimateHeadingLag`(813).
 */

/** `lateralSeries` 의 한 점 `{ t, lat, dh, sdev, dhs, sdevs }`. */
internal class SPoint(val t: Double, val lat: Double, val dh: Double, val sdev: Double?) {
    var dhs: Double = 0.0
    var sdevs: Double? = null
}

/** engine.js 389행 `lateralSeries(pre, base)`. */
internal fun lateralSeries(pre: List<PFrame>, baseIn: Baseline): List<SPoint> {
    if (pre.isEmpty()) return emptyList()
    val allSteer = pre.mapNotNull { it.steeringDeg }
    val steerBase = if (allSteer.isNotEmpty()) median(allSteer) else baseIn.steerBase
    var lat = 0.0
    val dh0 = angDiff(pre[0].hd, baseIn.at(pre[0].t))
    val series = ArrayList<SPoint>(pre.size)
    series.add(SPoint(pre[0].t, 0.0, dh0, pre[0].steeringDeg?.let { it - steerBase }))
    for (i in 1 until pre.size) {
        val p = pre[i - 1]
        val f = pre[i]
        val dt = (f.t - p.t) / 1000
        val v = ((p.spd + f.spd) / 2) / 3.6
        val dh = angDiff(f.hd, baseIn.at(f.t))
        lat += v * Math.sin(rad((series[i - 1].dh + dh) / 2)) * dt
        series.add(SPoint(f.t, lat, dh, f.steeringDeg?.let { it - steerBase }))
    }
    for (i in series.indices) {
        var a = i
        var b = i
        while (a > 0 && series[i].t - series[a - 1].t <= 500) a--
        while (b < series.size - 1 && series[b + 1].t - series[i].t <= 500) b++
        series[i].dhs = mean(series.subList(a, b + 1).map { it.dh })
        if (series[i].sdev == null) { series[i].sdevs = null; continue }
        var c = i
        var d = i
        while (c > 0 && series[i].t - series[c - 1].t <= 250 && series[c - 1].sdev != null) c--
        while (d < series.size - 1 && series[d + 1].t - series[i].t <= 250 && series[d + 1].sdev != null) d++
        series[i].sdevs = mean(series.subList(c, d + 1).map { it.sdev!! })
    }
    return series
}

internal class SteerRun(val ok: Boolean, val durationMs: Double, val peakDeg: Double)

/** engine.js 439행 `steeringRun(series, dirSign, fromT, toT, minDev = STEER_DEV_DEG)`. */
internal fun steeringRun(series: List<SPoint>, dirSign: Double, fromT: Double, toT: Double, minDev: Double = Thresh.STEER_DEV_DEG): SteerRun {
    var runStart: Double? = null
    var best = 0.0
    var peak = 0.0
    for (s in series) {
        val sv = s.sdevs
        if (s.t < fromT || s.t > toT || sv == null) { runStart = null; continue }
        peak = Math.max(peak, sv * dirSign)
        if (sv * dirSign >= minDev) {
            if (runStart == null) runStart = s.t
            best = Math.max(best, s.t - runStart + 100)
        } else runStart = null
    }
    return SteerRun(best >= Thresh.STEER_RUN_MS, best, round(peak, 1))
}

internal class SteerAct(val dev: Double, val thr: Double, val sigma: Double)

/** engine.js 460행 `steerActivity(pre, base, fromT)`. */
internal fun steerActivity(pre: List<PFrame>, base: Baseline, fromT: Double): SteerAct? {
    val ref = pre.filter { it.t >= base.refStartT && it.t <= base.refEndT && it.steeringDeg != null }.map { it.steeringDeg!! }
    if (ref.size < 10) return null
    val c = median(ref)
    val sigma = 1.4826 * median(ref.map { Math.abs(it - c) })
    val loc = pre.filter { it.t >= fromT }
    val sm = loc.map { f ->
        val w = loc.filter { g -> Math.abs(g.t - f.t) <= 250 && g.steeringDeg != null }.map { it.steeringDeg!! }
        if (w.isNotEmpty()) mean(w) - c else null
    }
    var dev = 0.0
    for (i in loc.indices) {
        var mn = Double.POSITIVE_INFINITY
        var j = i
        while (j < loc.size && loc[j].t - loc[i].t <= Thresh.STEER_ACT_MS) {
            val sj = sm[j]
            mn = Math.min(mn, if (sj == null) 0.0 else Math.abs(sj))
            j++
        }
        if (loc[j - 1].t - loc[i].t >= Thresh.STEER_ACT_MS - 100) dev = Math.max(dev, mn)
    }
    val thr = Math.max(if (base.curved) Thresh.STEER_ACT_CURVE_DEG else Thresh.STEER_ACT_DEG, 2.5 * sigma)
    return SteerAct(round(dev, 2), round(thr, 2), round(sigma, 2))
}

/** engine.js 706행 `steeringSignCheck(pre)`: 'ok' | 'reversed' | 'unknown'. */
internal fun steeringSignCheck(pre: List<PFrame>): String {
    val xs = ArrayList<Double>()
    val ys = ArrayList<Double>()
    for (i in 1 until pre.size) {
        val p = pre[i - 1]
        val f = pre[i]
        val sd = f.steeringDeg
        if (sd == null || f.spd < 10) continue
        val dt = (f.t - p.t) / 1000
        if (dt <= 0) continue
        xs.add(sd); ys.add(angDiff(f.hd, p.hd) / dt)
    }
    if (xs.size < 20) return "unknown"
    val mxv = mean(xs)
    val myv = mean(ys)
    var sxy = 0.0
    var sxx = 0.0
    var syy = 0.0
    for (i in xs.indices) {
        val x = xs[i]
        sxy += (x - mxv) * (ys[i] - myv)
        sxx += (x - mxv) * (x - mxv)
        syy += (ys[i] - myv) * (ys[i] - myv)
    }
    val n = xs.size
    if (Math.sqrt(sxx / n) < 1 || Math.sqrt(syy / n) < 0.3) return "unknown"
    val r = sxy / Math.sqrt(sxx * syy)
    return if (r < -0.5) "reversed" else if (r > 0.3) "ok" else "unknown"
}

/** `runsOf` 의 한 구간 `{ start, end }`. */
internal class Run(val start: Double, var end: Double)

/** engine.js 725행 `runsOf(pre, pred)`. */
internal inline fun runsOf(pre: List<PFrame>, pred: (PFrame) -> Boolean): List<Run> {
    val runs = ArrayList<Run>()
    var cur: Run? = null
    for (f in pre) {
        if (pred(f)) { if (cur == null) cur = Run(f.t, f.t) else cur.end = f.t }
        else if (cur != null) { runs.add(cur); cur = null }
    }
    if (cur != null) runs.add(cur)
    return runs
}

/** engine.js 813행 `estimateHeadingLag(pre)`: 초 단위(0.1초) 또는 null. */
internal fun estimateHeadingLag(pre: List<PFrame>): Double? {
    val n = pre.size
    if (n < 40 || pre.any { it.steeringDeg == null }) return null
    fun win(i: Int, half: Double): IntArray {
        var a = i
        var b = i
        while (a > 0 && pre[i].t - pre[a - 1].t <= half) a--
        while (b < n - 1 && pre[b + 1].t - pre[i].t <= half) b++
        return intArrayOf(a, b)
    }
    val rate = DoubleArray(n) { i ->
        val (a, b) = win(i, 500.0)
        val dt = (pre[b].t - pre[a].t) / 1000
        if (dt > 0) angDiff(pre[b].hd, pre[a].hd) / dt else 0.0
    }
    val x = DoubleArray(n) { i ->
        val (a, b) = win(i, 500.0)
        mean(pre.subList(a, b + 1).map { it.steeringDeg!! }) * Math.max(1.0, pre[i].spd / 3.6)
    }
    val xl = x.asList()
    val mx0 = mean(xl)
    val sdx = Math.sqrt(mean(xl.map { (it - mx0) * (it - mx0) }))
    val vAvg = mean(pre.map { Math.max(1.0, it.spd / 3.6) })
    if (sdx / vAvg < 0.5) return null
    var bestLag: Double? = null
    var bestR = -1.0
    var L = 0
    while (L <= 3000) {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        var j = 0
        for (i in 0 until n) {
            val target = pre[i].t + L
            while (j < n && pre[j].t < target) j++
            if (j >= n) break
            if (pre[j].t - target > 150) continue
            xs.add(x[i]); ys.add(rate[j])
        }
        if (xs.size < 30) break
        val mxs = mean(xs)
        val mys = mean(ys)
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (k in xs.indices) {
            val v = xs[k]
            sxy += (v - mxs) * (ys[k] - mys)
            sxx += (v - mxs) * (v - mxs)
            syy += (ys[k] - mys) * (ys[k] - mys)
        }
        val r = if (sxx > 0 && syy > 0) sxy / Math.sqrt(sxx * syy) else 0.0
        if (r > bestR) { bestLag = L.toDouble(); bestR = r }
        L += 100
    }
    return if (bestR >= 0.5) round(bestLag!! / 1000, 1) else null
}

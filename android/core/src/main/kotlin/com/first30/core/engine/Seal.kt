package com.first30.core.engine

import com.first30.core.model.Ctx
import com.first30.core.model.Frame
import com.first30.core.model.JsScalar
import com.first30.core.seal.Canonical
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * engine.js 6단계 "증거 봉인" (1669~1745행) `sealEvidence(frames, m, ctx)`.
 * 엔진은 지문(hash)과 직렬화 본문(canonical)을 돌려줄 뿐이고, 앱이 둘을 함께 기기 내부 저장소에 보관한다.
 * 위치 좌표는 넣지 않는다. 형식 버전 4 — 기록이 없던 값은 null 로 남긴다.
 */

/** 엔진이 쓰는 시계 (봉인 시각 기본값 `new Date().toISOString()` 과 run() 의 latencyMs). 테스트에서 고정할 수 있게 주입한다. */
interface EngineClock {
    fun nowMs(): Long

    /** JS `new Date().toISOString()` 꼴: `YYYY-MM-DDTHH:mm:ss.sssZ` (UTC). */
    fun isoNow(): String = ISO.format(Instant.ofEpochMilli(nowMs()))

    companion object {
        private val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        val SYSTEM: EngineClock = object : EngineClock {
            override fun nowMs(): Long = System.currentTimeMillis()
        }

        /** 늘 같은 시각을 돌려주는 시계 (golden 은 2026-01-01T00:00:00.000Z 로 고정해 만들었다). */
        fun fixed(epochMs: Long): EngineClock = object : EngineClock {
            override fun nowMs(): Long = epochMs
        }
    }
}

/** 봉인 결과 `{ hash, canonical, payload }`. [payload] 는 JS 값 모델(stableStringify(payload) == canonical). */
class Seal(val hash: String, val canonical: String, val payload: Map<String, Any?>) {
    fun toJs(): Map<String, Any?> = linkedMapOf("hash" to hash, "canonical" to canonical, "payload" to payload)
}

/** engine.js 1730행 `prim(x)`: 문자열·참거짓·유한한 숫자는 그대로, 그 밖은 null. */
private fun prim(x: JsScalar): Any? = when (x) {
    is JsScalar.Str -> x.value
    is JsScalar.Bool -> x.value
    is JsScalar.Num -> if (x.value.isFinite()) x.value else null
    else -> null
}

/** engine.js 1721행 `r1(x)`. */
private fun r1(x: JsScalar): Double? = x.finiteOrNull()?.let { round(it, 1) }

/** engine.js 165행 `byTimeThenContent` (prepare 와 같은 정렬). */
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

/** engine.js 1724행 `sealEvidence(frames, m, ctx)`. [frames] null = JS 의 "배열이 아님". [m] null 이면 trigger 'unknown', metrics null. */
fun sealEvidence(frames: List<Frame?>?, m: WindowMetrics?, ctxIn: Ctx?, clock: EngineClock = EngineClock.SYSTEM): Seal {
    val ctx = ctxIn ?: Ctx()
    val rows = (frames ?: emptyList()).mapNotNull { f -> f?.t?.finiteOrNull()?.takeIf { it <= 0 }?.let { f to it } }
        .sortedWith(BY_TIME_THEN_CONTENT)
    val payload = linkedMapOf<String, Any?>(
        "app" to "first30",
        "version" to 4.0,
        "sealedAt" to (ctx.now?.takeIf { it.isNotEmpty() } ?: clock.isoNow()),
        "trigger" to (m?.impact?.source ?: "unknown"),
        "frames" to rows.map { (f, t) ->
            linkedMapOf<String, Any?>(
                "t" to t, "v" to r1(f.speedKph), "h" to r1(f.headingDeg),
                "ts" to prim(f.turnSignal), "st" to r1(f.steeringDeg),
                "b" to prim(f.brake), "ab" to prim(f.airbag),
            )
        },
        "metrics" to m?.toJs(),
        "place" to linkedMapOf<String, Any?>("roadType" to roadTypeOf(ctx), "navCautions" to (ctx.navCautions ?: emptyList<String>())),
    )
    val canonical = Canonical.stableStringify(payload)
    return Seal(Canonical.sha256Hex(canonical), canonical, payload)
}

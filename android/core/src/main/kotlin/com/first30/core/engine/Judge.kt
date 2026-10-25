package com.first30.core.engine

import com.first30.core.JsUndefined
import com.first30.core.model.Ctx
import com.first30.core.model.JsMath

/**
 * 판정기 경계: engine/local-judge.js(`localJudge`, 규칙 기반 대체 판정기)와 engine/jev-client.js(`validateJevAnswers`,
 * `makeJevJudge`). 네트워크 호출 자체는 앱이 [JevTransport] 로 넣는다(API 키는 앱에 두지 않고 중계 서버를 부른다).
 */

/** 판정기에 함께 넘기는 값 (JS 의 두 번째 인자 `{ metrics, ctx }`). */
class JudgeInput(val metrics: WindowMetrics?, val ctx: Ctx?)

/**
 * 판정 응답. Jev 응답 본문을 그대로(JS 값 모델) 들고 있다: `{ answers: { chart: {...}, evidence_sufficient: {noul}, ... }, model, provider, ... }`.
 * run() 출력의 `judged` 는 [raw] 그대로다.
 */
class Judged(val raw: Map<String, Any?>) {
    val answers: Any? get() = Js.get(raw, "answers")
    val model: Any? get() = Js.get(raw, "model")
    val provider: Any? get() = Js.get(raw, "provider")
}

/**
 * 판정기 (JS `judge(request, { metrics, ctx })`). 블로킹 함수다 — 앱은 run() 을 작업 스레드에서 부르고,
 * 3초 시간 제한은 구현(예: [JevJudge] 의 transport)이 지킨다. 예외를 던지거나 null/answers 없는 응답을 돌려주면
 * run() 이 [LocalJudge] 로 내려간다.
 */
fun interface Judge {
    fun judge(request: JevRequest, input: JudgeInput): Judged?
}

/** local-judge.js `NOUL_CAP` (`F.LOCAL_NOUL_CAP`): 진술에서 뽑은 가감 요소 Noul 상한. */
const val LOCAL_NOUL_CAP = 0.6

private val P_REAR_END = linkedMapOf("none" to 0.9, "c252_me_A" to 0.06, "c252_me_B" to 0.02, "c252_2" to 0.02)
private val P_OTHER_TYPE = linkedMapOf("none" to 0.85, "c252_me_A" to 0.05, "c252_me_B" to 0.05, "c252_2" to 0.05)

private fun p(vararg kv: Pair<String, Double>): LinkedHashMap<String, Double> = linkedMapOf(*kv)

/** local-judge.js `normalize(probs)`. */
private fun normalize(probs: Map<String, Double>): LinkedHashMap<String, Double> {
    var s = 0.0
    for (v in probs.values) s += v
    val out = LinkedHashMap<String, Double>()
    for ((k, v) in probs) out[k] = JsMath.round((v / s) * 1000) / 1000
    return out
}

/** local-judge.js `localJudge(request, { metrics, ctx })` — Jev 와 같은 응답 모양의 규칙 기반 대체 판정. 요청은 쓰지 않는다. */
fun localJudge(request: Any?, metrics: WindowMetrics?, ctxIn: Ctx?): Judged {
    val ctx = ctxIn ?: Ctx()
    val c = parseStatement(ctx.driverStatement)
    val lc = metrics?.laneChange?.status ?: "unmeasurable"
    val cautions = ctx.navCautions ?: emptyList()
    val otherClaim = c.otherLC || c.otherNoSignal || c.otherLateSignal
    val confirmed = ctx.laneChangeConfirmed

    val probs0: Map<String, Double>
    val evidence: Double
    if (c.otherType) { probs0 = P_OTHER_TYPE; evidence = 0.6 }
    else if (lc == "detected") {
        if (c.simultaneous) { probs0 = p("c252_2" to 0.74, "c252_me_B" to 0.18, "none" to 0.06, "c252_me_A" to 0.02); evidence = 0.8 }
        else if (c.otherLC && !c.selfLC && confirmed != true) { probs0 = p("c252_me_B" to 0.62, "c252_2" to 0.28, "none" to 0.06, "c252_me_A" to 0.04); evidence = 0.6 }
        else { probs0 = p("c252_me_B" to 0.88, "c252_2" to 0.07, "none" to 0.04, "c252_me_A" to 0.01); evidence = 0.85 }
    } else if (lc == "none") {
        if (c.rearEnd && !otherClaim) { probs0 = P_REAR_END; evidence = 0.7 }
        else if (c.selfLC || confirmed == true) { probs0 = p("none" to 0.45, "c252_me_B" to 0.3, "c252_2" to 0.15, "c252_me_A" to 0.1); evidence = 0.3 }
        else if (otherClaim) { probs0 = p("c252_me_A" to 0.86, "none" to 0.08, "c252_2" to 0.04, "c252_me_B" to 0.02); evidence = 0.8 }
        else { probs0 = p("none" to 0.7, "c252_me_A" to 0.15, "c252_2" to 0.08, "c252_me_B" to 0.07); evidence = 0.3 }
    } else if (lc == "completed") {
        probs0 = p("none" to 0.62, "c252_me_B" to 0.3, "c252_2" to 0.04, "c252_me_A" to 0.04); evidence = 0.45
    } else if (lc == "starting") {
        probs0 = if (confirmed == true && c.simultaneous) p("c252_2" to 0.7, "c252_me_B" to 0.2, "none" to 0.06, "c252_me_A" to 0.04)
        else if (confirmed == true) p("c252_me_B" to 0.8, "c252_2" to 0.1, "none" to 0.06, "c252_me_A" to 0.04)
        else if (c.otherLC && !c.selfLC) p("c252_me_A" to 0.5, "c252_me_B" to 0.25, "c252_2" to 0.15, "none" to 0.1)
        else if (c.selfLC) p("c252_me_B" to 0.55, "c252_2" to 0.15, "none" to 0.2, "c252_me_A" to 0.1)
        else p("none" to 0.6, "c252_me_B" to 0.2, "c252_me_A" to 0.15, "c252_2" to 0.05)
        evidence = if (confirmed == true) 0.65 else 0.45
    } else {
        probs0 = if (c.rearEnd && !otherClaim) P_REAR_END
        else if (otherClaim && !c.selfLC) p("c252_me_A" to 0.55, "none" to 0.35, "c252_2" to 0.05, "c252_me_B" to 0.05)
        else if (c.selfLC) p("c252_me_B" to 0.55, "none" to 0.35, "c252_2" to 0.05, "c252_me_A" to 0.05)
        else p("none" to 0.75, "c252_me_A" to 0.1, "c252_me_B" to 0.1, "c252_2" to 0.05)
        evidence = 0.3
    }
    val probs = normalize(probs0)
    val ranked = probs.entries.map { it.key to it.value }.sortedWith { a, b -> NUMERIC_ASC.compare(b.second, a.second) }
    val top = Charts.CHART_OPTIONS[ranked[0].first]
    val laneChanger: String? = if (top == null || top.chart == null) null else if (top.me == "B" || top.chart == "252-2") "self" else "other"
    fun byChanger(by: String?) = by == null || laneChanger == null || by == laneChanger

    fun cap(x: Double) = Math.min(LOCAL_NOUL_CAP, x)
    val otherSignal = if (c.otherNoSignal || c.otherLateSignal) cap(0.6) else 0.15
    val camera = cautions.contains("LANE_CHANGE_CAMERA_ALERT") || cautions.contains("CUT_IN_VIOLATION_ENFORCEMENT")
    val forbidden = if (c.solidLine && byChanger(c.solidLineBy)) cap(0.6) else if (camera) 0.45 else 0.05
    val busNotice = cautions.contains("BUS_LANE_AVAILABLE_NOTICE") || cautions.contains("BUS_LANE_VIOLATION_ENFORCEMENT")
    val bus = if (c.busLane && byChanger(c.busLaneBy)) cap(0.6) else if (busNotice) 0.45 else 0.05
    val selfLaneChange = if (confirmed == true) 0.95 else if (c.selfLC && !c.selfDeny) 0.9 else if (confirmed == false || c.selfDeny) 0.02 else 0.1

    fun noul(v: Double) = linkedMapOf<String, Any?>("type" to "noul", "noul" to v)
    return Judged(
        linkedMapOf(
            "model" to "local-standin",
            "provider" to "offline-rules",
            "cues" to c.toJs(),
            "answers" to linkedMapOf<String, Any?>(
                "chart" to linkedMapOf<String, Any?>(
                    "type" to "choice", "choice" to ranked[0].first, "probabilities" to LinkedHashMap<String, Any?>(probs),
                    "confidence" to JsMath.round((ranked[0].second - ranked[1].second) * 100) / 100,
                ),
                "evidence_sufficient" to noul(evidence),
                "self_lane_change" to noul(selfLaneChange),
                "other_signal_fail" to noul(otherSignal),
                "forbidden_zone" to noul(forbidden),
                "bus_lane" to noul(bus),
            ),
        ),
    )
}

/** [localJudge] 를 [Judge] 로. run() 의 기본 판정기이자 판정 모델 실패 시 대체 판정기. */
object LocalJudge : Judge {
    override fun judge(request: JevRequest, input: JudgeInput): Judged = localJudge(request, input.metrics, input.ctx)
}

// ---------------- jev-client.js ----------------

/** jev-client.js `DEFAULT_ENDPOINT` (`F.JEV_ENDPOINT`). 실제 배포에서는 키를 가진 중계 서버 주소를 쓴다. */
const val JEV_ENDPOINT = "https://api.typesafe.ai/v1/systemone"

/** 판정 응답 검증 실패 (jev-client.js 가 던지는 Error 와 같은 메시지). */
class JevValidationException(message: String) : RuntimeException(message)

private fun prob(x: Any?): Boolean = x is Number && x.toDouble().let { it.isFinite() && it >= 0 && it <= 1 }

/**
 * jev-client.js `validateAnswers(request, body)` (`F.validateJevAnswers`). [request] 는 요청의 JS 값(`JevRequest.toJs()`),
 * [body] 는 응답 본문(JS 값 모델). 맞지 않으면 [JevValidationException], 맞으면 body 를 그대로 돌려준다.
 */
fun validateJevAnswers(request: Map<String, Any?>, body: Any?): Any? {
    if (!Js.truthy(body) || !Js.isObject(body) || !Js.truthy(Js.get(body, "answers")) || !Js.isObject(Js.get(body, "answers"))) {
        throw JevValidationException("Jev 응답에 answers 가 없습니다")
    }
    val answers = Js.get(body, "answers")
    for ((name, q) in Js.entries(Js.get(request, "questions"))) {
        val a = if (Js.own(answers, name)) jsIndex(answers, name) else null
        if (!Js.truthy(a) || !Js.isObject(a)) throw JevValidationException("Jev 응답에 $name 답이 없습니다")
        val type = Js.get(q, "type")
        val criteria = Js.get(q, "criteria")
        if (type == "choice") {
            val choice = Js.get(a, "choice")
            if (choice !is String || !Js.own(criteria, choice)) throw JevValidationException("$name: 선택지 밖의 답 ${Js.str(choice)}")
            val probs = Js.get(a, "probabilities")
            if (!Js.truthy(probs) || !Js.isObject(probs)) throw JevValidationException("$name: probabilities 없음")
            for ((k, pv) in Js.entries(probs)) {
                if (!Js.own(criteria, k)) throw JevValidationException("$name: 선택지 밖의 확률 키 $k")
                if (!prob(pv)) throw JevValidationException("$name: 확률 값 오류 $k")
            }
            if (!Js.own(probs, choice)) throw JevValidationException("$name: 고른 답의 확률이 없습니다")
        } else if (type == "noul") {
            if (!prob(Js.get(a, "noul"))) throw JevValidationException("$name: noul 값 오류")
        }
    }
    return body
}

/** [validateJevAnswers] for a typed request. */
fun validateJevAnswers(request: JevRequest, body: Any?): Any? = validateJevAnswers(request.toJs(), body)

/** JS `o[k]` for objects and arrays (배열 인덱스 키). */
private fun jsIndex(o: Any?, k: String): Any? = when (o) {
    is Map<*, *> -> o[k]
    is List<*> -> k.toIntOrNull()?.let { o.getOrNull(it) } ?: JsUndefined
    else -> JsUndefined
}

/** 중계 서버 HTTP 응답. [json] 은 본문을 JS 값 모델(Map/List/String/Double/Boolean/null)로 푼 값을 돌려준다(실패하면 예외). */
class JevHttpResponse(val ok: Boolean, val status: Int, val json: () -> Any?)

/** 네트워크 전송 (앱이 구현: OkHttp 등). [timeoutMs] 안에 끝나지 않으면 예외를 던질 것. */
fun interface JevTransport {
    fun post(endpoint: String, headers: Map<String, String>, body: String, timeoutMs: Long): JevHttpResponse
}

/**
 * jev-client.js `makeJevJudge({ apiKey, endpoint, timeoutMs, fetchImpl })` 의 `jevJudge`. 요청 본문은 JSON.stringify(request),
 * 응답은 검증 뒤 `{ ...body, model: body.model || request.model, provider: body.provider || 'TypeSafe' }`.
 * 앱에는 API 키를 넣지 말고(apiKey = null) 중계 서버 [endpoint] 를 쓸 것.
 */
class JevJudge(
    private val apiKey: String? = null,
    private val endpoint: String = JEV_ENDPOINT,
    private val timeoutMs: Long = 3000,
    private val transport: JevTransport?,
) : Judge {
    override fun judge(request: JevRequest, input: JudgeInput): Judged {
        val t = transport ?: throw IllegalStateException("fetch 를 쓸 수 없는 환경입니다")
        val headers = linkedMapOf("Content-Type" to "application/json")
        if (!apiKey.isNullOrEmpty()) headers["Authorization"] = "Bearer $apiKey"
        val req = request.toJs()
        val res = t.post(endpoint, headers, Js.jsonStringify(req), timeoutMs)
        if (!res.ok) throw RuntimeException("Jev HTTP ${res.status}")
        val body = res.json()
        validateJevAnswers(req, body)
        @Suppress("UNCHECKED_CAST")
        val ok = LinkedHashMap(body as Map<String, Any?>)
        val model = Js.get(ok, "model")
        ok["model"] = if (Js.truthy(model)) model else request.model
        val provider = Js.get(ok, "provider")
        ok["provider"] = if (Js.truthy(provider)) provider else "TypeSafe"
        return Judged(ok)
    }
}

package com.first30.core.engine

import com.first30.core.model.Ctx
import com.first30.core.model.Frame

/**
 * engine.js 7단계 "전체 파이프라인" (1747~1790행) `run(frames, ctx, judge, opts)`.
 * 안전 카드와 봉인은 판정이 실패해도 반드시 나온다.
 * opts.onJudgeFailure: 'label'(기본, 대체 판정기 결과를 표시와 함께 사용) | 'abstain'(판정 모델 실패 시 판단 보류).
 */

/** run() 결과. [latencyMs] 는 판정에 걸린 시간(시계 값이라 비교에서 뺀다). */
class RunOutput(
    val metrics: WindowMetrics,
    val facts: Facts?,
    val request: JevRequest?,
    val judged: Judged?,
    val judgeError: String?,
    val analysisError: String?,
    val sealError: String?,
    val latencyMs: Long,
    val result: FaultResult,
    val seal: Seal?,
    val flow: Flow,
) {
    fun toJs(): Map<String, Any?> = linkedMapOf(
        "metrics" to metrics.toJs(),
        "facts" to facts?.toJs(),
        "request" to request?.toJs(),
        "judged" to judged?.raw,
        "judgeError" to judgeError,
        "analysisError" to analysisError,
        "sealError" to sealError,
        "latencyMs" to latencyMs.toDouble(),
        "result" to result.toJs(),
        "seal" to seal?.toJs(),
        "flow" to flow.toJs(),
    )
}

/** JS `String((e && e.message) || e)`. */
private fun errText(e: Throwable): String = e.message?.takeIf { it.isNotEmpty() } ?: e.toString()

/**
 * engine.js 1750행 `run(frames, ctx, judge, opts)`. [judge] null = [LocalJudge]. 블로킹 함수 — 판정기가 네트워크를 쓰면
 * 작업 스레드에서 부를 것.
 */
fun run(
    frames: List<Frame?>?,
    ctxIn: Ctx?,
    judge: Judge? = null,
    optsIn: RunOpts? = null,
    clock: EngineClock = EngineClock.SYSTEM,
): RunOutput {
    val ctx = ctxIn ?: Ctx()
    val opts = optsIn ?: RunOpts()
    var analysisError: String? = null
    val metrics: WindowMetrics = try {
        analyzeWindow(frames, ctx)
    } catch (e: Exception) {
        analysisError = errText(e)
        analyzeWindow(emptyList(), ctx)
    }

    var seal: Seal? = null
    var sealError: String? = null
    try { seal = sealEvidence(frames, metrics, ctx, clock) } catch (e: Exception) { sealError = errText(e) }

    val started = clock.nowMs()
    var facts: Facts? = null
    var request: JevRequest? = null
    var judged: Judged? = null
    var judgeError: String? = null
    var analysisFailed = false
    var result: FaultResult
    try {
        facts = renderFacts(metrics, ctx)
        request = buildJevRequest(facts, opts)
        try {
            judged = (judge ?: LocalJudge).judge(request, JudgeInput(metrics, ctx))
            if (judged == null || !Js.truthy(judged.answers)) throw IllegalStateException("판정 응답이 비어 있습니다")
        } catch (e: Exception) {
            judgeError = errText(e)
            judged = LocalJudge.judge(request, JudgeInput(metrics, ctx))
        }
        result = computeFault(judged!!.answers, metrics, ctx)
        if (judgeError != null && opts.onJudgeFailure == "abstain") {
            result = FaultResult(
                status = "abstain", option = null, reasons = listOf("판정 모델에 연결하지 못해 과실 범위를 계산하지 않았음"),
                confidence = 0.0, evidenceChecklist = evidenceChecklist(metrics, ctx), confirm = null,
            )
        }
    } catch (e: Exception) {
        result = FaultResult(
            status = "abstain", option = null, reasons = listOf("기록 분석 중 문제가 생겨 과실 범위를 계산하지 않았음"),
            confidence = 0.0, evidenceChecklist = evidenceChecklist(metrics, ctx), confirm = null,
        )
        analysisError = analysisError ?: errText(e)
        analysisFailed = true
    }
    val latencyMs = clock.nowMs() - started
    val j = judged
    val standIn = j == null || j.model == "local-standin"
    result.judge = JudgeInfo(
        model = if (j != null && Js.truthy(j.model)) j.model else null,
        provider = if (j != null && Js.truthy(j.provider)) j.provider else null,
        standIn = standIn,
        fallback = judgeError != null,
        usedStandIn = standIn && !(judgeError != null && opts.onJudgeFailure == "abstain") && !analysisFailed,
    )
    return RunOutput(metrics, facts, request, judged, judgeError, analysisError, sealError, latencyMs, result, seal, companion(result, metrics, ctx, seal))
}

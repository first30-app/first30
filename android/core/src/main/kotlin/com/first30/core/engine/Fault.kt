package com.first30.core.engine

import com.first30.core.model.Ctx

/**
 * engine.js 4단계 "과실 계산" (1178~1489행): `decide`, `recordConflict`, `driverCheck`, `confirmHint`, `computeFault`,
 * `evidenceChecklist`. 숫자는 차량 기록이 유형을 뒷받침하고 확인 질문의 답이 그 기록과 맞을 때만 낸다.
 */

/** 확인 질문 `{ question, direction, hint }` (direction·hint 는 null 일 수 있다). */
data class Confirm(val question: String, val direction: String?, val hint: String?) {
    fun toJs(): Map<String, Any?> = linkedMapOf("question" to question, "direction" to direction, "hint" to hint)
}

/** 가감 요소 평가 하나 (`modifiers[i]`). [delta] null = 동시 진로변경 항목. */
data class ModifierEval(
    val id: String,
    val label: String,
    val chartLabel: String,
    val party: String,
    val delta: Double?,
    val p: Double?,
    val source: String?,
    val basis: List<String>,
    var decision: String,
    val why: String,
    val effectOnMe: Double,
) {
    fun toJs(): Map<String, Any?> = linkedMapOf(
        "id" to id, "label" to label, "chartLabel" to chartLabel, "party" to party, "delta" to delta, "p" to p,
        "source" to source, "basis" to basis, "decision" to decision, "why" to why, "effectOnMe" to effectOnMe,
    )
}

/** 2순위 해석 `{ option, p, title }`. */
data class Alternative(val option: String, val p: Double, val title: String) {
    fun toJs(): Map<String, Any?> = linkedMapOf("option" to option, "p" to p, "title" to title)
}

/** run() 이 덧붙이는 `result.judge`. [model]·[provider] 는 판정 응답의 값 그대로(JS 값, 없으면 null). */
data class JudgeInfo(val model: Any?, val provider: Any?, val standIn: Boolean, val fallback: Boolean, val usedStandIn: Boolean) {
    fun toJs(): Map<String, Any?> = linkedMapOf(
        "model" to model, "provider" to provider, "standIn" to standIn, "fallback" to fallback, "usedStandIn" to usedStandIn,
    )
}

/**
 * computeFault 결과. [status] 'estimate' 이면 예상 과실 범위, 'abstain' 이면 판단 보류.
 *  - abstain 키: status, option, reasons, confidence, evidenceChecklist, confirm
 *  - estimate 키: status, option, judgedOption, chartId, chartTitle, optionLabel, me, other, base, likely, low, high,
 *    modifiers, confidence, alternative, verified, source, caveats
 *  - run() 을 거치면 [judge] 가 더해진다.
 */
data class FaultResult(
    val status: String,
    val option: String?,
    val confidence: Double,
    // abstain
    val reasons: List<String> = emptyList(),
    val evidenceChecklist: List<String> = emptyList(),
    val confirm: Confirm? = null,
    // estimate
    val judgedOption: String? = null,
    val chartId: String? = null,
    val chartTitle: String? = null,
    val optionLabel: String? = null,
    val me: String? = null,
    val other: String? = null,
    val base: Double? = null,
    val likely: Double? = null,
    val low: Double? = null,
    val high: Double? = null,
    val modifiers: List<ModifierEval> = emptyList(),
    val alternative: Alternative? = null,
    val verified: Boolean? = null,
    val source: ChartSource? = null,
    val caveats: List<String> = emptyList(),
    var judge: JudgeInfo? = null,
) {
    val isEstimate: Boolean get() = status == "estimate"

    fun toJs(): Map<String, Any?> {
        val m: LinkedHashMap<String, Any?> = if (status == "estimate") linkedMapOf(
            "status" to status, "option" to option, "judgedOption" to judgedOption, "chartId" to chartId,
            "chartTitle" to chartTitle, "optionLabel" to optionLabel, "me" to me, "other" to other,
            "base" to base, "likely" to likely, "low" to low, "high" to high,
            "modifiers" to modifiers.map { it.toJs() }, "confidence" to confidence,
            "alternative" to alternative?.toJs(), "verified" to verified, "source" to source?.toJs(), "caveats" to caveats,
        ) else linkedMapOf(
            "status" to status, "option" to option, "reasons" to reasons, "confidence" to confidence,
            "evidenceChecklist" to evidenceChecklist, "confirm" to confirm?.toJs(),
        )
        judge?.let { m["judge"] = it.toJs() }
        return m
    }
}

/** engine.js 1179행 `decide(p)`. */
private fun decide(p: Double?): String = when {
    p == null -> "not_assessed"
    p >= Thresh.YES -> "applied"
    p >= Thresh.MAYBE -> "possible"
    else -> "not_applied"
}

private fun clamp(x: Double): Double = Math.max(0.0, Math.min(100.0, x))

/** engine.js 1188행 `recordConflict(choice, m, ctx)`: 차량 기록이 고른 유형을 뒷받침하지 못하면 보류 사유. */
fun recordConflict(choice: String, m: WindowMetrics, ctxIn: Ctx?): String? {
    val ctx = ctxIn ?: Ctx()
    if (choice == "none") return null
    val opt = Charts.CHART_OPTIONS[choice]!!
    val lc = m.laneChange
    val needsMyLC = opt.me == "B" || opt.chart == "252-2"
    if (lc.status == "starting" && lc.reason != "evasive" && needsMyLC && ctx.laneChangeConfirmed == true) return null
    return when (lc.status) {
        "detected" -> if (needsMyLC) null else "차량 기록에는 내 차가 차로를 바꾸는 움직임이 있는데, 판정은 상대 차만 진로를 바꾼 사고로 나와 서로 맞지 않음"
        "none" -> if (needsMyLC) "판정은 내 차의 진로변경으로 나왔지만, 차량 기록에는 내 차가 차로를 유지한 것으로 나와 서로 맞지 않음" else null
        "completed" -> "내 차는 충돌 ${Js.str(lc.completedSecBefore)}초 전에 옆으로 움직이기를 마쳤음 — 진로변경 사고인지 추돌 사고인지 기록만으로 가리기 어려움"
        "starting" -> if (lc.reason == "evasive") "충돌 직전 핸들 조작과 함께 브레이크를 밟아, 피하려는 조작인지 진로변경인지 가릴 수 없음"
        else "충돌 직전 핸들 조작이 진로변경 시작인지 피하려는 조작인지 기록만으로 구분하기 어려움"
        "uncertain" -> when (lc.reason) {
            "curve_or_lc" -> "곡선에 들어선 것인지 진로를 바꾸던 중인지 차량 기록만으로 구분할 수 없음"
            "bend_hold" -> "진행 방향이 바뀐 채 유지돼 굽은 길을 따라간 움직임으로 보여 진로변경으로 단정할 수 없음"
            "no_steering" -> "핸들 기록이 없어 차로변경 여부를 확정할 수 없음"
            "short_for_speed" -> "빠르게 달릴 때는 진로변경이 10초 가까이 걸릴 수 있는데 충돌 전 기록이 ${Js.str(m.bufferSec)}초뿐이라 차로를 유지했다고 확정할 수 없음"
            "road_change" -> "기록 중에 도로가 굽었다가 펴지는 등 도로 모양이 바뀌어, 차로를 바꿨는지 차량 기록만으로 확정할 수 없음"
            "heading_lag" -> "진행 방향 기록이 핸들보다 늦게 반응해, 충돌 직전 움직임을 아직 다 볼 수 없음"
            "steer_activity" -> "진행 방향은 차로를 지킨 것처럼 보이지만 핸들이 도로를 따라갈 때보다 크게 움직여, 차로를 지켰다고 확정할 수 없음"
            else -> "차량 기록만으로는 내 차가 차로를 바꿨는지 확정할 수 없음"
        }
        "too_slow" -> "거의 멈춘 상태여서 차량 기록으로 누가 진로를 바꿨는지 측정할 수 없음"
        else -> when (lc.reason) {
            "impact_unknown" -> "충돌 순간을 차가 직접 감지하지 못해 차로변경 여부를 측정하지 않았음"
            "short_buffer" -> "충돌 전 기록이 짧아 차로변경 여부를 측정할 수 없음"
            else -> "차량 기록이 부족해 차로변경 여부를 측정할 수 없음"
        }
    }
}

internal class DriverCheck(var reason: String?, var askable: Boolean, val question: String, var hint: String?)

/** engine.js 1234행 `driverCheck(choice, m, ctx, answers)`. */
internal fun driverCheck(choice: String, m: WindowMetrics, ctx: Ctx, answers: Any?): DriverCheck {
    val lc = m.laneChange
    val sl = Js.get(answers, "self_lane_change")
    val selfP: Any? = if (Js.truthy(answers) && Js.truthy(sl)) Js.get(sl, "noul") else null
    val yes = ctx.laneChangeConfirmed == true
    val no = ctx.laneChangeConfirmed == false
    val statedSelf = !Js.isNullish(selfP) && Js.toNumber(selfP) >= Thresh.SELF_LC
    val q = confirmQuestion(lc.direction)
    val out = DriverCheck(null, false, q, null)
    val opt = Charts.CHART_OPTIONS[choice]
    if (choice == "none" || opt == null) return out
    if (opt.me == "B" || opt.chart == "252-2") {
        val movement = (lc.status == "detected" || lc.status == "starting") && lc.direction != null && lc.reason != "evasive"
        if (!movement) return out
        if (no) out.reason = "운전자는 \"$q\"에 \"아니요\"라고 답했음 — 기록의 움직임은 굽은 길·차로가 어긋난 곳을 따라간 것이거나 다른 움직임일 수 있어, 블랙박스 영상으로 확인해야 함"
        else if (!yes) {
            out.reason = "기록상 내 차가 ${DIR_KO[lc.direction]}으로 움직였지만, 굽은 길이나 차로가 어긋난 곳을 따라가도 기록이 똑같이 보여 운전자 확인이 필요함"
            out.askable = true
            out.hint = confirmHint(lc)
        }
    } else if (opt.me == "A") {
        if (yes) out.reason = "운전자는 \"$q\"에 \"예\"라고 답했는데 기록상으로는 차로를 유지한 것으로 나와 서로 맞지 않음"
        else if (statedSelf) out.reason = "운전자는 진술에서 차로를 바꾸던 중이었다고 했는데 기록상으로는 차로를 유지한 것으로 나와 서로 맞지 않음"
        else if (!m.quality.signalAvailable) out.reason = "방향지시등 기록이 없어 충돌 직전 내 차가 진로를 바꾸려 했는지 확인할 수 없음"
        else if (m.signal.recentOn == "LEFT" || m.signal.recentOn == "RIGHT") {
            out.reason = "충돌 직전 ${Js.str(Thresh.RECENT_SIGNAL_SEC)}초 안에 내 차의 ${DIR_KO[m.signal.recentOn]} 방향지시등이 켜져 있어, 기록상 차로 유지로 보여도 진로를 바꾸기 시작했을 수 있음"
        } else if (lc.status == "none" && !no) {
            out.reason = "기록상 내 차는 차로를 유지한 것으로 보이지만, 곡선이 바뀌는 곳 등에서는 천천히 진로를 바꿔도 이렇게 보일 수 있어 운전자 확인이 필요함"
            out.askable = true
        }
    }
    return out
}

/** engine.js 1271행 `confirmHint(lc)`: 확인 질문에 덧붙이는 말. */
internal fun confirmHint(lc: LaneChange): String? = when (lc.status) {
    "starting" -> "피하려고 핸들을 꺾은 거라면 \"아니요\""
    "detected" -> "굽은 길이나 차로를 따라 달린 거라면 \"아니요\""
    else -> null
}

/** engine.js 1273행 `DIRECTION_CAVEAT_KO`. */
internal val DIRECTION_CAVEAT_KO: Map<String, String> = linkedMapOf(
    "opposite_signal" to "움직임과 반대쪽 방향지시등이 켜져 있어 움직인 방향을 잘못 쟀을 수 있어",
    "opposite_move" to "바로 앞에 반대쪽으로 움직인 기록이 있어 움직인 방향을 잘못 쟀을 수 있어",
    "road_curve" to "굽은 길에서는 움직인 방향을 잘못 잴 수 있어",
    "late_reference" to "도로 방향 기준을 충돌 무렵 구간에서 잡아 움직인 방향을 잘못 잴 수 있어",
)

private fun hasCaution(ctx: Ctx, list: List<String>): Boolean = (ctx.navCautions ?: emptyList()).any { it in list }

private const val IMPACT_UNKNOWN_REASON = "충돌 순간을 차가 직접 감지하지 못해(음성·버튼 신고) 과실 범위를 계산하지 않음 — 기록 속 움직임이 사고 전인지, 사고 뒤 갓길로 옮긴 것인지 가릴 수 없음"
private const val IMPACT_UNKNOWN_AIRBAG_REASON = "에어백이 터진 시각이 기록 범위 밖이라 충돌 순간을 알 수 없어 과실 범위를 계산하지 않음"
private val AIRBAG_PREFIX = jsRegex("^airbag_")
private val AB_PREFIX = jsRegex("^[AB] ")

/** JS `x != null ? x : 0` 다음 산술에 쓰는 값. */
private fun numOr0(v: Any?): Double = if (Js.isNullish(v)) 0.0 else Js.toNumber(v)

/**
 * engine.js 1284행 `computeFault(answers, m, ctx)`. [answers] 는 판정 응답의 `answers` (JS 값 모델:
 * `{ chart: { choice, probabilities }, evidence_sufficient: { noul }, ... }`, null 가능).
 */
fun computeFault(answers: Any?, m: WindowMetrics, ctxIn: Ctx?): FaultResult {
    val ctx = ctxIn ?: Ctx()
    val chRaw = if (Js.truthy(answers)) Js.get(answers, "chart") else null
    val ch: Any? = if (Js.truthy(chRaw)) chRaw else emptyMap<String, Any?>()
    val pr = Js.get(ch, "probabilities")
    val probs: Any? = if (Js.truthy(pr)) pr else emptyMap<String, Any?>()
    val ranked = Js.entries(probs).filter { Charts.CHART_OPTIONS.containsKey(it.first) }
        .sortedWith { a, b -> NUMERIC_ASC.compare(Js.toNumber(b.second), Js.toNumber(a.second)) }
    val chChoice = Js.get(ch, "choice")
    var choice: String? = if (Js.truthy(chChoice) && Charts.CHART_OPTIONS.containsKey(Js.str(chChoice))) Js.str(chChoice) else ranked.firstOrNull()?.first
    if (choice.isNullOrEmpty()) choice = "none"
    fun pOf(k: String): Double = numOr0(Js.get(probs, k))
    val folded = choice == "c252_2"
    val pChoice = if (folded) pOf("c252_2") + pOf("c252_me_B") else pOf(choice)
    val ev = if (Js.truthy(answers) && Js.truthy(Js.get(answers, "evidence_sufficient"))) Js.get(Js.get(answers, "evidence_sufficient"), "noul") else null
    val evidence: Double? = if (Js.isNullish(ev)) null else Js.toNumber(ev)

    val reasons = ArrayList<String>()
    var confirm: Confirm? = null
    if (!m.impact.known) {
        reasons.add(if (AIRBAG_PREFIX.test(m.impact.reason ?: "")) IMPACT_UNKNOWN_AIRBAG_REASON else IMPACT_UNKNOWN_REASON)
    } else {
        if (choice == "none") reasons.add("사고 유형이 차로변경 사고 도표에 해당하지 않거나 판단 근거가 부족함")
        if (choice != "none" && pChoice < Thresh.CHART_MIN) reasons.add("사고 유형 판단 확률이 낮음 (${Js.str(round(pChoice * 100))}%)")
        if (evidence != null && evidence < Thresh.EVIDENCE_MIN) reasons.add("차량 기록과 진술만으로는 근거가 부족함")
        val unresolved = reasons.size
        val conflict = recordConflict(choice, m, ctx)
        if (conflict != null) reasons.add(conflict)
        else if (choice == "none" && m.laneChange.status in listOf("completed", "starting", "uncertain", "unmeasurable")) {
            recordConflict("c252_me_B", m, ctx)?.let { reasons.add(it) }
        }
        val g = driverCheck(choice, m, ctx, answers)
        g.reason?.let { reasons.add(it) }
        val lcNow = m.laneChange
        val unanswered = ctx.laneChangeConfirmed == null
        val movement = (lcNow.status == "detected" || lcNow.status == "starting") && lcNow.direction != null && lcNow.reason != "evasive"
        if (unanswered && movement && pOf("none") < 0.8) {
            confirm = Confirm(confirmQuestion(lcNow.direction), lcNow.direction, confirmHint(lcNow))
        } else if (g.askable && unresolved == 0 && Charts.CHART_OPTIONS[choice]!!.me == "A" && Charts.CHART_OPTIONS[choice]!!.chart != "252-2") {
            val hypo = ctx.copy(laneChangeConfirmed = false)
            if (recordConflict(choice, m, hypo) == null && driverCheck(choice, m, hypo, answers).reason == null) {
                confirm = Confirm(g.question, null, null)
            }
        }
    }

    if (reasons.isNotEmpty()) {
        return FaultResult(
            status = "abstain", option = choice, reasons = LinkedHashSet(reasons).toList(), confidence = round(pChoice, 2),
            evidenceChecklist = evidenceChecklist(m, ctx), confirm = confirm,
        )
    }

    val effChoice = if (folded) "c252_me_B" else choice
    val simultaneous = folded || (effChoice == "c252_me_B" && pOf("c252_2") >= Thresh.ALT_MIN)
    val opt = Charts.CHART_OPTIONS[effChoice]!!
    val chart = Charts.CHARTS[opt.chart]!!
    val base = chart.base(opt.me)!!
    val caveats = ArrayList<String>()
    val evals = ArrayList<ModifierEval>()
    fun partyKo(party: String) = if (party == opt.me) "내 차" else "상대 차"
    fun noulOf(name: String): Double? {
        val a = Js.get(answers, name)
        if (!Js.truthy(a)) return null
        val v = Js.get(a, "noul")
        return if (Js.isNullish(v)) null else Js.toNumber(v)
    }
    if (!chart.source.verified) {
        caveats.add("이 유형은 기본과실(${Js.str(chart.baseA)}:${Js.str(chart.baseB)})만 반영했어요. 가감 요소는 협회 원문을 확인한 뒤 반영합니다.")
        for (h in chart.pendingHints ?: emptyList()) caveats.add("확인 예정 가감 요소: $h")
    } else {
        for (mod in chart.modifiers) {
            var p: Double? = null
            var source: String? = null
            var basis: List<String> = emptyList()
            var why: String
            if (mod.id == "B_signal") {
                if (opt.me == "B") {
                    source = "vehicle"; basis = listOf("vehicle")
                    val st = m.signal.status
                    p = if (st in listOf("none", "late", "after", "at_start")) 1.0 else if (st in listOf("borderline", "unavailable", "indeterminate")) 0.5 else 0.0
                    why = when (st) {
                        "unavailable" -> "방향지시등 기록이 없어 확인할 수 없음 — 범위에만 반영"
                        "ok" -> "방향지시등 ${Js.str(m.signal.leadM)}m 전 점등 (기준 ${Js.str(m.signal.requiredM)}m 충족)"
                        "borderline" -> if (m.signal.leadM.let { it != null && it != 0.0 && !it.isNaN() }) "방향지시등 ${Js.str(m.signal.leadM)}m 전 점등 — 기준 ${Js.str(m.signal.requiredM)}m 안팎(측정 오차 범위)" else "움직이기 시작한 무렵 점등 — 시작 시점의 측정 오차 범위"
                        "indeterminate" -> "점등 시점이 기록 밖이라 기준 충족 여부를 알 수 없음 — 범위에만 반영"
                        "none" -> "방향지시등 미점등"
                        else -> "방향지시등 점등이 늦음"
                    }
                    if (m.laneChange.status == "starting") {
                        p = 0.5
                        why += " — 충돌 직전에 시작한 움직임이라 시작 시점을 정확히 잴 수 없어 범위에만 반영"
                    } else if (m.laneChange.status == "detected" && !m.laneChange.directionCaveat.isNullOrEmpty() && p == 1.0) {
                        p = 0.5
                        why += " — ${DIRECTION_CAVEAT_KO[m.laneChange.directionCaveat] ?: "움직인 방향을 잘못 잴 수 있어"} 범위에만 반영"
                    }
                } else {
                    source = "model"; basis = listOf("statement")
                    p = noulOf("other_signal_fail")
                    why = "상대 차 방향지시등은 내 차가 측정할 수 없음 — 진술 해석"
                }
            } else if (mod.id == "B_forbidden") {
                source = "model"
                basis = if (hasCaution(ctx, listOf("LANE_CHANGE_CAMERA_ALERT", "CUT_IN_VIOLATION_ENFORCEMENT"))) listOf("statement", "nav") else listOf("statement")
                p = noulOf("forbidden_zone")
                why = "진로변경 금지장소 — 진술·내비 정보 해석"
            } else if (mod.id == "B_buslane") {
                source = "model"
                basis = if (hasCaution(ctx, listOf("BUS_LANE_AVAILABLE_NOTICE", "BUS_LANE_VIOLATION_ENFORCEMENT"))) listOf("statement", "nav") else listOf("statement")
                p = noulOf("bus_lane")
                why = "전용차로 위반 — 진술·내비 정보 해석"
            } else {
                why = "현저한 과실·중과실(과속·음주·휴대전화 등)은 차량 기록만으로 판단하지 않음"
            }
            var decision = decide(p)
            if (decision == "applied" && source == "model") {
                decision = "possible"
                why += " (차량이 직접 잰 값이 아니라 범위에만 반영)"
            }
            val mine = mod.party == opt.me
            evals.add(
                ModifierEval(
                    id = mod.id, label = AB_PREFIX.matcher(mod.label).replaceFirst(java.util.regex.Matcher.quoteReplacement("${partyKo(mod.party)} ")),
                    chartLabel = mod.label, party = mod.party, delta = mod.delta,
                    p = if (p == null) null else round(p, 2), source = source, basis = basis, decision = decision, why = why,
                    effectOnMe = if (mine) +mod.delta else -mod.delta,
                ),
            )
        }
    }

    // 같은 그룹(현저/중과실)은 중복 적용하지 않는다
    val rank = mapOf("applied" to 2, "possible" to 1)
    val keep = HashMap<String, ModifierEval>()
    for (e in evals) {
        val mod = chart.modifiers.first { it.id == e.id }
        val re = rank[e.decision]
        if (mod.group == null || re == null) continue
        val k = keep[mod.group]
        if (k == null || re > rank[k.decision]!! || (re == rank[k.decision] && (e.delta ?: 0.0) > (k.delta ?: 0.0))) keep[mod.group] = e
    }
    for (e in evals) {
        val mod = chart.modifiers.first { it.id == e.id }
        if (mod.group != null && rank[e.decision] != null && keep[mod.group] !== e) e.decision = "not_applied"
    }

    var likely = base
    var up = 0.0
    var down = 0.0
    for (e in evals) {
        if (e.decision == "applied") likely += e.effectOnMe
        else if (e.decision == "possible") { if (e.effectOnMe > 0) up += e.effectOnMe else down += -e.effectOnMe }
    }
    likely = clamp(likely)
    var low = clamp(likely - down)
    val high = clamp(likely + up)
    if (simultaneous) {
        val simBase = Charts.CHARTS["252-2"]!!.baseA
        if (simBase < low) low = simBase
        evals.add(
            ModifierEval(
                id = "simultaneous", label = "상대 차도 동시에 진로변경 (도표 252-2, 기본 50:50)", chartLabel = "252-2",
                party = "B", delta = null, p = round(pOf("c252_2"), 2), source = "model", basis = listOf("statement"), decision = "possible",
                why = "상대 차의 진로변경은 내 차가 측정할 수 없음 — 진술 해석", effectOnMe = simBase - likely,
            ),
        )
        caveats.add("상대 차의 진로변경은 내 차가 잴 수 없어, 동시 진로변경(252-2)은 범위의 아래쪽 끝으로만 넣었어요")
    }
    if (opt.me == "B") {
        caveats.add("\"${confirmQuestion(m.laneChange.direction)}\"에 \"예\"라고 답한 것을 바탕으로 계산했어요")
        val said = parseStatement(ctx.driverStatement)
        if (said.selfDeny) caveats.add("진술(차로를 바꾸지 않았다)과 확인 답(예)이 서로 달라요 — 확인 답을 따랐어요")
        if (m.signal.status == "unavailable") caveats.add("방향지시등 기록이 없어 신호 위반 여부는 범위에만 넣었어요")
    } else if (opt.me == "A") {
        caveats.add("\"${confirmQuestion(null)}\"에 \"아니요\"라고 답한 것과 차로를 유지한 기록을 바탕으로 계산했어요")
    }
    val alt = ranked.firstOrNull { (k, pv) ->
        k != effChoice && k != "none" && !(simultaneous && k == "c252_2") && !(folded && k == "c252_me_B") && Js.toNumber(pv) >= Thresh.ALT_MIN
    }
    val pNone = pOf("none")
    if (pNone >= Thresh.ALT_MIN) caveats.add("차로변경 사고 도표에 해당하지 않을 가능성도 ${Js.str(round(pNone * 100))}% 있어요")
    return FaultResult(
        status = "estimate",
        option = effChoice,
        judgedOption = choice,
        chartId = chart.id,
        chartTitle = chart.title,
        optionLabel = opt.label + (if (simultaneous) " · 동시 진로변경 가능성 포함" else ""),
        me = opt.me,
        other = opt.other,
        base = base,
        likely = likely,
        low = low,
        high = high,
        modifiers = evals,
        confidence = round(pChoice, 2),
        alternative = alt?.let { Alternative(it.first, round(Js.toNumber(it.second), 2), Charts.CHART_OPTIONS[it.first]!!.label) },
        verified = chart.source.verified,
        source = chart.source,
        caveats = caveats,
    )
}

/** engine.js 1478행 `evidenceChecklist(m, ctx)`. */
fun evidenceChecklist(m: WindowMetrics, ctxIn: Ctx?): List<String> {
    val ctx = ctxIn ?: Ctx()
    val evacuate = roadContext(m, ctx) != "urban"
    val list = arrayListOf(
        "내 블랙박스 원본 보존 (메모리카드 덮어쓰기 방지)",
        "상대 차 블랙박스 영상 공유 요청",
        if (evacuate) "현장 사진은 대피한 뒤 경찰·보험사 안내에 따라 안전할 때만" else "차량 최종 위치·파손 부위·노면 표시(실선/점선) 사진",
    )
    if (m.laneChange.status != "detected") list.add("누가 차로를 바꿨는지 보여줄 영상이나 목격자 연락처")
    if (!evacuate) list.add("주변 상가·교차로 CCTV 위치 기록 (보관 기간이 짧음)")
    return list
}

/** engine.js 1494행 `roadContext(m, ctx)`: 'highway' | 'urban' | 'caution'. */
fun roadContext(m: WindowMetrics, ctxIn: Ctx?): String {
    val ctx = ctxIn ?: Ctx()
    val rt = roadTypeOf(ctx)
    if (rt == "highway") return "highway"
    val peak = m.peakSpeedKph
    val informative = peak != null && (m.impact.known || peak >= Thresh.STILL_KPH)
    if (!informative) return "caution"
    if (rt == "urban") return if (peak!! <= Thresh.PHOTO_MAX_KPH) "urban" else "caution"
    return if (peak!! >= Thresh.FAST_KPH) "highway" else "caution"
}

/** engine.js 1506행 `respondMode(m, ctx)`: 'emergency' | roadContext. */
fun respondMode(m: WindowMetrics, ctxIn: Ctx?): String {
    val ctx = ctxIn ?: Ctx()
    if (m.airbagDeployed || ctx.injuryReported == true) return "emergency"
    return roadContext(m, ctx)
}

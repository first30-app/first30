package com.first30.core.engine

import com.first30.core.model.Ctx
import com.first30.core.model.JsMath

/**
 * engine.js 5단계 "현장 30초 동반자" (1491~1667행): 안전 → 봉인 → 과실(또는 구조) → 말하기 → 다음 단계 카드.
 * 카드의 `tts` 는 TextToSpeech 로 읽는 문장이다. 문구는 JS 와 바이트 단위로 같아야 한다.
 */

/** 카드 하나. [confirm] 은 확인 질문 카드에만(예/아니요를 받아 ctx.laneChangeConfirmed 를 넣고 run 을 다시 부른다). [at] 은 몇 초에 보여 줄지. */
data class Card(
    val kind: String,
    val title: String,
    val lines: List<String>,
    val tts: String,
    val confirm: Confirm? = null,
    var at: Int? = null,
) {
    fun toJs(): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("kind" to kind)
        if (confirm != null) m["confirm"] = confirm.toJs()
        m["title"] = title
        m["lines"] = lines
        m["tts"] = tts
        at?.let { m["at"] = it.toDouble() }
        return m
    }
}

/** companion 결과 `{ mode, cards }`. mode: 'urban' | 'highway' | 'caution' | 'emergency'. */
data class Flow(val mode: String, val cards: List<Card>) {
    fun toJs(): Map<String, Any?> = linkedMapOf("mode" to mode, "cards" to cards.map { it.toJs() })
}

private class SafetyText(val title: String, val lines: List<String>, val tts: String)

/** engine.js 1512행 `safetyCard(m, ctx)`. */
private fun safetyCard(m: WindowMetrics, ctx: Ctx): SafetyText {
    val mode = respondMode(m, ctx)
    val road = roadContext(m, ctx)
    val evacuate = road != "urban"
    val withPassengers = (ctx.occupants ?: 0) > 0
    val hurt = if (withPassengers) "나와 동승자가 다쳤는지 확인" else "다친 사람이 있는지 확인"
    if (mode == "emergency") {
        val cause = if (m.airbagDeployed) "에어백이 터졌어요." else "다친 사람이 있다고 하셨어요."
        return if (evacuate) SafetyText(
            "먼저 사람부터",
            listOf(
                "움직일 수 있으면 비상등을 켜고 가드레일 밖으로 먼저 대피",
                "대피한 곳에서 119에 신고 (위치는 내비게이션 화면이나 주변 이정표로 확인)",
                "움직이기 어려우면 그 자리에서 바로 119에 신고하고 구조를 기다리기",
            ) + (if (withPassengers) listOf("동승자도 함께 대피했는지 확인") else emptyList()),
            "$cause 움직일 수 있으면 먼저 가드레일 밖으로 대피한 뒤 119에 신고하세요. 움직이기 어려우면 그 자리에서 바로 119에 신고하세요.",
        ) else SafetyText(
            "먼저 사람부터",
            listOf(hurt, "119에 바로 신고 (위치는 내비게이션 화면에서 확인해 알려 주기)", "움직이기 어려우면 무리해서 내리지 않기", "안전하면 비상등 켜기"),
            "$cause 다친 곳이 있는지 먼저 확인하고, 바로 119에 신고하세요.",
        )
    }
    if (mode == "highway") {
        val sure = roadTypeOf(ctx) == "highway"
        return SafetyText(
            "비트박스로 2차 사고 막기",
            listOf("비상등 켜기", "트렁크 열기", if (withPassengers) "동승자와 함께 가드레일 밖으로 대피" else "가드레일 밖으로 대피", "안전한 곳에서 신고 (한국도로공사 1588-2504 긴급견인)"),
            "${if (sure) "고속도로예요." else "차가 빠르게 다니는 도로예요."} 비상등을 켜고 트렁크를 연 다음, 가드레일 밖으로 먼저 대피하세요.",
        )
    }
    if (mode == "caution") {
        return SafetyText(
            "먼저 안전한 곳으로",
            listOf(
                "비상등 켜기", hurt, "차가 빠르게 다니는 도로(고속도로·자동차 전용도로)라면 사진보다 대피가 먼저 — 가드레일 밖으로",
                "차가 느린 일반도로에서 안전하면 사진을 찍고 차를 갓길로 옮기기",
            ),
            "비상등을 켜고 다친 사람이 없는지 확인하세요. 차가 빠르게 다니는 도로라면 사진보다 대피가 먼저예요.",
        )
    }
    return SafetyText("안전 확보 후 기록", listOf("비상등 켜기", hurt, "안전하면 사진부터 찍고 갓길로 이동"), "비상등을 켜고, 다친 사람이 없는지 먼저 확인하세요.")
}

/** engine.js 1560행 `SPEECH_GUIDE`. */
internal object SpeechGuide {
    val say = listOf("다치신 데는 없으세요?", "사진 먼저 찍고 보험 접수할게요.", "연락처랑 보험사 정보 교환해요.")
    val sayHighway = listOf("다치신 데는 없으세요?", "일단 가드레일 밖으로 피해요.", "보험사·경찰은 안전한 데서 부를게요.")
    val sayCaution = listOf("다치신 데는 없으세요?", "일단 차 밖 안전한 곳으로 피해요.", "보험사·경찰은 안전한 데서 부를게요.")
    val avoid = listOf(
        "\"제가 다 잘못했어요\" 같은 책임 단정 발언",
        "현장에서 과실 비율을 두고 다투기",
        "기록 없이 \"그냥 가세요\" 하고 헤어지기",
        "블랙박스 전원 끄기·영상 지우기",
    )
    const val principle = "퍼스트30은 다친 척이나 과장 신고를 돕지 않습니다. 실제로 아프면 진료를 받으세요."
}

/** engine.js `F.SPEECH_PRINCIPLE`. */
const val SPEECH_PRINCIPLE: String = SpeechGuide.principle

private fun n(x: Double?): String = Js.str(x)

/** engine.js 1573행 `faultCard(result, m, ctx, mode)`. */
private fun faultCard(result: FaultResult, m: WindowMetrics, ctx: Ctx, mode: String): Card {
    val evacuate = roadContext(m, ctx) != "urban"
    val usedStandIn = result.judge?.usedStandIn == true
    val judgeLine = if (usedStandIn) listOf("판정 모델 대신 규칙 기반 대체 판정을 썼어요${if (result.judge!!.fallback) " (판정 모델 연결 실패)" else ""}") else emptyList()
    val quiet = if (mode == "emergency") "과실 참고 정보는 화면에 남겨 둘게요. 안전이 확인된 뒤에 보세요." else null
    if (result.status == "estimate") {
        val range = if (result.low!! == result.high!!) "${n(result.likely)}%" else "${n(result.low)}~${n(result.high)}% (가능성 높은 값 ${n(result.likely)}%)"
        val title = if (result.verified == true) "예상 내 과실 $range" else "예상 내 과실 ${n(result.base)}% (기본과실만 · 가감 요소 미반영)"
        val chart = Charts.CHARTS[result.chartId]!!
        val roleNote = if (chart.baseA == chart.baseB) "" else " · 내 차는 ${Js.str(result.me)}"
        val sim = Charts.CHARTS["252-2"]!!.baseA
        val lines = ArrayList<String>()
        lines.add("유형: ${result.chartTitle}$roleNote — 기본 내 과실 ${n(result.base)}%")
        for (e in result.modifiers) {
            if (e.decision != "applied" && e.decision != "possible") continue
            lines.add(
                "${if (e.decision == "applied") "반영" else "가능성"}: ${e.label}" +
                    if (e.id == "simultaneous") " (내 과실 ${n(sim)}%까지)" else " (내 과실 ${if (e.effectOnMe > 0) "+" else ""}${n(e.effectOnMe)}%p)",
            )
        }
        result.alternative?.let { lines.add("다른 해석 가능성: ${it.title} (${n(JsMath.round(it.p * 100))}%)") }
        lines.addAll(result.caveats)
        lines.addAll(judgeLine)
        lines.add("과속·음주·휴대전화 같은 현저한 과실·중과실은 반영하지 않았어요")
        lines.add("손해보험협회 인정기준에 근거한 참고치이며, 최종 비율은 보험사·분쟁심의위원회가 정합니다")
        return Card(
            kind = "estimate", title = if (mode == "emergency") "안전이 확인되면 보세요 · $title" else title,
            lines = lines,
            tts = quiet ?: if (result.verified == true)
                "예상 과실은 ${if (result.low!! == result.high!!) "${n(result.likely)}퍼센트" else "${n(result.low)}에서 ${n(result.high)}퍼센트 사이"}예요. 참고용이니 현장에서는 다투지 마세요."
            else "기본 과실은 ${n(result.base)}퍼센트예요. 참고용이니 현장에서는 다투지 마세요.",
        )
    }
    val confirm = result.confirm
    if (confirm != null) {
        val hint = confirm.hint
        return Card(
            kind = "abstain", confirm = confirm,
            title = "확인이 필요해요 — ${confirm.question}",
            lines = (if (!hint.isNullOrEmpty()) listOf("${hint}라고 답해 주세요") else emptyList()) + result.reasons + judgeLine +
                listOf("답에 따라 예상 과실을 계산하거나 판단을 보류해요") + result.evidenceChecklist.map { "□ $it" },
            tts = quiet ?: if (evacuate) "과실 참고 정보는 화면에 남겨 둘게요. 지금은 대피한 곳에서 기다리고, 안전해진 뒤에 확인하세요."
            else "${confirm.question} 예 또는 아니요로 답해 주세요.${if (!hint.isNullOrEmpty()) " ${hint.replace("\"", "")}예요." else ""}",
        )
    }
    return Card(
        kind = "abstain",
        title = if (evacuate) "과실 판단 보류 — 안전해진 뒤 증거를 챙기세요" else "과실 판단 보류 — 증거부터 확보하세요",
        lines = result.reasons + judgeLine + result.evidenceChecklist.map { "□ $it" },
        tts = quiet ?: if (evacuate) "지금 기록만으로는 과실을 판단하기 어려워요. 대피한 곳에서 기다리고, 블랙박스 원본은 안전해진 뒤에 챙기세요."
        else "지금 기록만으로는 과실을 판단하기 어려워요. 화면에 나온 증거부터 확보하세요.",
    )
}

/** engine.js 1625행 `companion(result, m, ctx, seal)`. [seal] null = 봉인 실패. */
fun companion(result: FaultResult, m: WindowMetrics, ctxIn: Ctx?, seal: Seal? = null): Flow {
    val ctx = ctxIn ?: Ctx()
    val mode = respondMode(m, ctx)
    val evacuate = roadContext(m, ctx) != "urban"
    val s = safetyCard(m, ctx)
    val safety = Card("safety", s.title, s.lines, s.tts)
    val known = m.impact.known
    val sealCard = Card(
        kind = "evidence", title = "사고 기록 봉인",
        lines = listOf(
            "${if (known) "충돌" else "신고"} 전 ${n(m.bufferSec)}초 차량 기록을 봉인했어요${if (known) "" else " (충돌 순간은 기록에서 확인하지 못했어요)"}",
            if (seal != null) "무결성 지문(SHA-256) ${seal.hash.jsSlice(0, 12)}… — 기록이 나중에 바뀌면 지문이 달라져요" else "봉인하지 못했어요 — 블랙박스 원본을 따로 보관하세요",
        ),
        tts = if (seal != null) "사고 전 기록을 봉인했어요." else "기록 봉인에 실패했어요. 블랙박스 원본을 따로 보관하세요.",
    )
    val fault = faultCard(result, m, ctx, mode)
    val sayList = if (!evacuate) SpeechGuide.say else if (roadContext(m, ctx) == "highway") SpeechGuide.sayHighway else SpeechGuide.sayCaution
    val speech = Card(
        kind = "speech", title = "현장에서 이렇게 말하세요",
        lines = sayList.map { "○ $it" } + SpeechGuide.avoid.map { "✕ $it" },
        tts = if (evacuate) "말은 대피한 다음에 하세요. 책임을 단정하는 말은 피하세요." else "책임을 단정하는 말은 피하고, 다친 데 없는지부터 물어보세요.",
    )
    val next = Card(
        kind = "next", title = "다음 단계",
        lines = listOf("내 보험사 사고 접수 (봉인 기록 첨부 가능)", if (evacuate) "상대 차량 번호·보험사 확인 (안전해진 뒤에)" else "상대 차량 번호·보험사 확인", "블랙박스 원본 보존", SpeechGuide.principle),
        tts = if (evacuate) "안전해진 뒤에 보험 접수와 상대 차 정보 확인을 잊지 마세요." else "보험 접수와 상대 차 정보 확인을 잊지 마세요.",
    )
    val cards: List<Card> = if (mode == "emergency") {
        val rescue = Card(
            kind = "rescue", title = "구조 요청 확인",
            lines = listOf("119 신고가 됐는지 확인", if (evacuate) "가드레일 밖 안전한 곳에서 구조를 기다리기" else "움직이기 어려우면 차 안에서 구조를 기다리기", "블랙박스는 끄지 말고 그대로 두기"),
            tts = "119 신고가 됐는지 확인하세요. 구조대가 올 때까지 안전한 곳에 있으세요.",
        )
        listOf(safety, sealCard, rescue, next, fault)
    } else listOf(safety, sealCard, fault, speech, next)
    listOf(0, 5, 10, 20, 25).forEachIndexed { i, at -> cards[i].at = at }
    return Flow(mode, cards)
}

package com.first30.app.ui

import com.first30.core.engine.Card
import com.first30.core.engine.RunOutput

/**
 * 차량 화면이 그리는 값. 엔진의 [RunOutput] 에서 화면에 필요한 것만 옮긴다(데모 demo/src.html `renderCard`·`renderSteps`·`renderChips`).
 * 문구는 엔진 카드 그대로 쓴다 — 고지문·증거 체크리스트·말하기 목록은 카드 `lines` 안에 이미 있다.
 */
data class IncidentUi(
    /** 'urban' | 'highway' | 'caution' | 'emergency' (flow.mode). */
    val mode: String,
    val modeLabel: String,
    val modeTone: Tone,
    /** 봉인 성공 여부와 지문 앞 12자. */
    val sealed: Boolean,
    val sealHashShort: String?,
    /** flow 순서 그대로(긴급 모드: 안전 → 봉인 → 구조 → 다음 → 과실). */
    val cards: List<CardUi>,
    /** 세션 시작 시각(타임라인 0초). */
    val startedAtMs: Long,
    /** 확인 질문 답: true 예 / false 아니요 / null 아직. */
    val answer: Boolean?,
) {
    /** 빠른 도로 모드 — 질문은 화면에만(소리 내어 묻지 않음, 카드 tts 가 이미 그렇게 돼 있다). */
    val fastRoad: Boolean get() = mode == "highway" || mode == "caution"

    /** [elapsedSec] 초에 보여 줄 카드 번호(`at` 이 지난 마지막 카드). */
    fun activeIndexAt(elapsedSec: Double): Int {
        var i = 0
        cards.forEachIndexed { k, c -> if (elapsedSec >= c.atSec) i = k }
        return i
    }

    /**
     * 뒤 카드에 띄우는 작은 확인 질문 막대: 띄우면 질문을 가진 카드(그 `confirm`), 아니면 null.
     *
     * - 아직 답이 없고([answer] == null, [answeredLocally] == false — 막대에서 누른 뒤 새 결과가 오기 전),
     * - 확인 질문 카드가 있고,
     * - 타임라인이 그 카드를 지났고(30초가 끝난 뒤에도 그대로 — 마지막 카드가 질문 카드인 긴급 모드는 막대 없음),
     * - 지금 보이는 카드([shownIndex])가 그 카드보다 뒤일 때만. 질문 카드 자체(큰 버튼이 있다)와 앞 카드에는 띄우지 않는다.
     */
    fun questionBarCard(shownIndex: Int, timelineIndex: Int, answeredLocally: Boolean = false): CardUi? {
        if (answer != null || answeredLocally) return null
        val k = cards.indexOfFirst { it.confirm != null }
        if (k < 0) return null
        return if (timelineIndex > k && shownIndex > k) cards[k] else null
    }

    companion object {
        const val TOTAL_SEC = 30
    }
}

enum class Tone { NEUTRAL, OK, WARN, CRIT }

data class CardUi(
    val index: Int,
    /** 엔진 kind: safety | evidence | estimate | abstain | speech | next | rescue. */
    val kind: String,
    /** 단계 표시: 안전·봉인·과실·말하기·다음·구조, 확인 질문 카드는 '확인'. */
    val stepLabel: String,
    val atSec: Int,
    val title: String,
    val lines: List<LineUi>,
    /** 읽는 문장(= 자막). */
    val tts: String,
    val confirm: ConfirmUi?,
) {
    /** "03 · 과실 · 10초" */
    val header: String get() = "${(index + 1).toString().padStart(2, '0')} · $stepLabel · ${atSec}초"
}

data class LineUi(val text: String, val style: LineStyle)

/** 데모의 li 클래스: ○ 할 말(say) / ✕ 피할 말(avoid) / □ 증거 체크리스트(box) / 그 밖(plain). */
enum class LineStyle { PLAIN, SAY, AVOID, BOX }

data class ConfirmUi(val question: String, val hint: String?, val direction: String?)

private val KIND_KO = mapOf(
    "safety" to "안전", "evidence" to "봉인", "estimate" to "과실", "abstain" to "과실",
    "speech" to "말하기", "next" to "다음", "rescue" to "구조",
)

private val MODE_KO = mapOf(
    "urban" to ("일반도로 안내" to Tone.NEUTRAL),
    "highway" to ("고속도로 · 비트박스" to Tone.WARN),
    "caution" to ("빠른 도로일 수 있음 · 대피 먼저" to Tone.WARN),
    "emergency" to ("긴급 · 사람 먼저" to Tone.CRIT),
)

private val DEFAULT_AT = listOf(0, 5, 10, 20, 25)

/**
 * 엔진은 봉인(지문)을 만들었지만 앱 내부 저장소에 쓰지 못한 경우: 봉인 카드와 다음 단계가 "봉인했어요·첨부 가능"이라고
 * 말하지 않게, 엔진이 봉인 실패 때 쓰는 문장으로 바꾼다(지문 줄은 저장된 파일이 없으므로 뺀다).
 */
private fun unsavedSeal(c: CardUi): CardUi = when (c.kind) {
    "evidence" -> c.copy(
        lines = listOf(LineUi("기록을 저장하지 못했어요 — 블랙박스 원본을 따로 보관하세요", LineStyle.PLAIN)),
        tts = "기록 봉인에 실패했어요. 블랙박스 원본을 따로 보관하세요.",
    )
    "next" -> c.copy(lines = c.lines.map { it.copy(text = it.text.replace(" (봉인 기록 첨부 가능)", "")) })
    else -> c
}

fun lineUi(line: String): LineUi = when {
    line.startsWith("○ ") -> LineUi(line.substring(2), LineStyle.SAY)
    line.startsWith("✕ ") -> LineUi(line.substring(2), LineStyle.AVOID)
    line.startsWith("□ ") -> LineUi(line.substring(2), LineStyle.BOX)
    else -> LineUi(line, LineStyle.PLAIN)
}

fun cardUi(index: Int, c: Card): CardUi = CardUi(
    index = index,
    kind = c.kind,
    stepLabel = if (c.confirm != null) "확인" else KIND_KO[c.kind] ?: c.kind,
    atSec = c.at ?: DEFAULT_AT.getOrElse(index) { 25 },
    title = c.title,
    lines = c.lines.map(::lineUi),
    tts = c.tts,
    confirm = c.confirm?.let { ConfirmUi(it.question, it.hint?.takeIf { h -> h.isNotEmpty() }, it.direction) },
)

/** RunOutput → 화면 값. [answer] 는 ctx.laneChangeConfirmed, [stored] 는 IncidentSession.stored(false 면 봉인 실패로 표시). */
fun RunOutput.toUi(startedAtMs: Long, answer: Boolean?, stored: Boolean? = null): IncidentUi {
    val (label, tone) = MODE_KO[flow.mode] ?: (flow.mode to Tone.NEUTRAL)
    return IncidentUi(
        mode = flow.mode,
        modeLabel = label,
        modeTone = tone,
        sealed = seal != null && stored != false,
        sealHashShort = seal?.hash?.take(12),
        cards = flow.cards.mapIndexed(::cardUi).let { if (stored == false) it.map(::unsavedSeal) else it },
        startedAtMs = startedAtMs,
        answer = answer,
    )
}

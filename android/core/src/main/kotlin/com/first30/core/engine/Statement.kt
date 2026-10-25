package com.first30.core.engine

import java.util.regex.Pattern

/**
 * engine/local-judge.js 진술 해석 `parseStatement(raw)` (주어·부정·의도·인용 가리기).
 *
 * 정규식은 JS 원문(`re.source`)을 그대로 적고 [jsRegex] 로 옮긴다 (원문은 node 로 local-judge.js 에서 뽑아 옮겼다).
 * 입력은 [bmpOnly] 로 서로게이트를 바꿔, JS(u 플래그 없음)처럼 UTF-16 코드 단위로 맞춘다 — 결과는 참거짓·주어뿐이라 영향이 없다.
 */

/** parseStatement 결과 (cues). [reportedSelf] 는 JS 에서 참일 때만 키가 생긴다(null = 키 없음). */
data class StatementCues(
    var otherLC: Boolean = false,
    var selfLC: Boolean = false,
    var selfDeny: Boolean = false,
    var intentOnly: Boolean = false,
    var otherNoSignal: Boolean = false,
    var selfNoSignal: Boolean = false,
    var otherLateSignal: Boolean = false,
    var selfLateSignal: Boolean = false,
    var simultaneous: Boolean = false,
    var solidLine: Boolean = false,
    var solidLineBy: String? = null,
    var busLane: Boolean = false,
    var busLaneBy: String? = null,
    var rearEnd: Boolean = false,
    var otherType: Boolean = false,
    var reportedSelf: Boolean? = null,
) {
    fun toJs(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "otherLC" to otherLC, "selfLC" to selfLC, "selfDeny" to selfDeny, "intentOnly" to intentOnly,
        "otherNoSignal" to otherNoSignal, "selfNoSignal" to selfNoSignal, "otherLateSignal" to otherLateSignal,
        "selfLateSignal" to selfLateSignal, "simultaneous" to simultaneous, "solidLine" to solidLine, "solidLineBy" to solidLineBy,
        "busLane" to busLane, "busLaneBy" to busLaneBy, "rearEnd" to rearEnd, "otherType" to otherType,
    ).also { m -> reportedSelf?.let { m["reportedSelf"] = it } }
}

internal object Rx {
    val RX_noSignal = jsRegex("(깜빡이|깜박이|깜빡이등|방향 ?지시등)(을|를|도|은|는|이|가)?\\s?.{0,6}?(없이|안 ?켜|안 ?켰|안 ?켠|안 ?넣|안 ?했|안 ?하|없었|없더|없던|못 ?켜|못 ?켰|못 ?켠|켜지 ?않|넣지 ?않)") // flags: g
    val RX_forgotSignal = jsRegex("(깜빡이|깜박이|깜빡이등|방향 ?지시등)(을|를)? ?(켜는 ?(걸|것을?)|켜야 (하는데|했는데)|넣는 ?걸) ?(깜빡|잊|까먹|못)") // flags: g
    val RX_lateSignal = jsRegex("(깜빡이|깜박이|깜빡이등|방향 ?지시등)(을|를|도|은|는)?\\s?[^.!?]{0,14}?(늦게|들어오면서|들어오며|넘어오면서|동시에 켜)") // flags: g
    val RX_lateSignal2 = jsRegex("(들어오|넘어오|들어가|넘어가|끼어들)(면서|며) ?(깜빡이|깜박이|깜빡이등|방향 ?지시등)(을|를)? ?(켜|켰|넣)") // flags: g
    val RX_alsoNoSignal = jsRegex("(상대방?|상대 ?차|그 ?차|옆 ?차|앞 ?차|저 ?차|저|제|나|내)(도|는|가)? ?(안|못) ?(켰|켜|넣)") // flags: g
    val RX_comeIn = jsRegex("(끼어 ?(들(?!기)|든|듦)|들어 ?(오|와|왔|온|올)|밀고 들어|칼치기|넘어 ?(오|와|왔|온)|(차로|차선)에서 (갑자기 )?나(오|와|왔|온)|(갑자기|훅|불쑥) ?(나왔|나와|튀어나)|(차선|차로) ?쪽으로 ?(붙|밀고|넘어|들어))") // flags: g
    val RX_goIn = jsRegex("(들어 ?(가|갔|간|갈|감)|넘어 ?(가|갔|간)|(차선|차로)(을|를)? ?(바꾸|바꿨|바꿔|바꾼|옮기|옮겼|옮겨|옮긴|넘은|넘었|넘어서|넘는)|(차선|차로|진로) ?변경(?! ?(금지|위반|단속|구간))|\\d ?차로로 ?(옮기|옮겼|옮겨|옮긴|이동|들어)|(차선|차로)(으로|로) ?(이동하|이동했|이동해|옮기|옮겼|옮겨|옮긴))") // flags: g
    val RX_simultaneous = jsRegex("((?<!는 |은 |ㄴ )동시에 ?(같은 ?(차선|차로)|들어|넘어|(차선|차로)(을|를)? ?(바꾸|바꿨|바꿔|바꾼|변경)|진로)|같이 (들어|차선|차로|진로)|둘 ?다 ([가-힣]+ )?(들어|바꾸|바꿨|차선|차로)|양쪽에서 (동시에 |같이 )?(들어|바꾸|바꿨)|서로 같은 차(선|로))") // flags:
    val RX_solidLine = jsRegex("(실선|진로 ?변경 ?금지|차선 ?변경 ?금지)") // flags: g
    val RX_busLane = jsRegex("((버스 ?전용 ?차로|버스 ?차로|전용 ?차로)(를|을)? ?(위반|침범|타고|달리|로 달리|로 끼어|에서 (갑자기 )?끼어)|(버스 ?전용 ?차로|전용 ?차로) ?위반)") // flags: g
    val RX_rearEnd = jsRegex("(뒤에서 (박|받|들이|추돌)|(뒷|뒤) ?차가 (박|받|들이|와서 박)|추돌)") // flags:
    val RX_otherType = jsRegex("(교차로|사거리|삼거리|좌회전|우회전|유턴|주차장|주차하|주차 중|후진|골목|횡단보도)") // flags:
    val RX_selfDeny = jsRegex("((차선|차로|진로)(을|를|은|는)? ?(안|못) ?(바꾸|바꿨|바꿔|옮기|옮겼|변경)|(차선|차로|진로) ?변경(을|은|는)? ?(안|못) ?(했|하)|(끼어든|들어간|바꾼|옮긴) ?적(이|은|도)? ?(없|않))") // flags: g
    val SELF_SUBJ = jsRegex("(내가|제가|나는|저는|난 |전 |내 ?차가|내 ?차는|제 ?차가|제 ?차는|저희 ?차가|저희 ?차는|우리 ?차가|우리 ?차는|저희가|우리가|나도|저도)") // flags: g
    val OTHER_SUBJ = jsRegex("(상대방?(이|가|은|는|도)|상대방 |상대 ?차량(이|은|도)?|상대 ?차(가|는|도)|그 ?차(가|는|도)|저 ?차(가|는)|옆 ?차(가|는|도)|앞 ?차(가|는)|앞에 ?차가|뒤 ?차(가|는)|뒷 ?차(가|는)|(오른|왼)쪽 ?차(가|는)|택시가|버스가|트럭이|오토바이가|저쪽에서|옆에서)") // flags: g
    val NEG_NOUN = jsRegex("^([^.,!?]{0,3}?(은|는|이|가)?\\s?(아니|아냐|아닌|아님)|[^.,!?]{0,4}?(없|않)|[^.,!?]{0,8}?(거|게|건|것)\\s?(아니|아냐|아닌|아님))") // flags:
    val NEG_VERB = jsRegex("^([^.,!?\\s]{0,3}\\s?(게|건|것은|거)\\s?(아니|아냐|아닌|아님)|[^.,!?\\s]{0,3}지\\s?(않|못)|\\s?안\\s?(했|해|하|함)|[^.,!?\\s]{0,2}\\s?적(이|은|도)?\\s?(없|않))") // flags:
    val HEDGE_NOUN = jsRegex("^[^.,!?]{0,4}(는지|ㄴ지|은지|던지)") // flags:
    val NOT_SELF_AFTER = jsRegex("^.{0,6}?(건|것은|게)\\s?(제가|내가|저희가|우리가|저|나)\\s?(아니|아냐|아닌)") // flags:
    val SELF_AFTER = jsRegex("^(.{0,6}?(건|것은|게|거)\\s?(저|제|나|내|저희|우리)(예요|에요|요|야|였|입니다|이에요|임|\$|\\s)|[^.!?]{0,6},\\s?(제가|내가|저희가|우리가)(?!\\s?아니)|[^.!?,\\s]{0,4}\\s(내가|제가)(\\s|\$|[.!?]))") // flags:
    val OTHER_AFTER = jsRegex("^(.{0,4}?(던|는|ㄴ|하던|하는|한|중인|중이던)\\s?(옆 ?차|상대|그 ?차|앞 ?차|뒤 ?차|뒷 ?차|(오른|왼)쪽 ?차|택시|버스|트럭|오토바이|차(를|가|와|랑|하고|에|는|도))|.{0,6}?(건|것은|게)\\s?(상대|그 ?차|옆 ?차|저 ?차|앞 ?차|뒤 ?차))") // flags:
    val HEDGE = jsRegex("(모르겠|모름|모르고|기억이 안|기억 안|확실하지 않|확실치 않|잘 모르|어려워|어렵|애매|단정하기|말하긴 힘|말하기 힘)") // flags:
    val HEDGE_AFTER = jsRegex("^\\s?(는지|ㄴ지|은지|는 지)") // flags:
    val INTENT_AFTER = jsRegex("^\\s?(하)?(려|으려)(고|는|던|면|\\s)") // flags:
    val ATTEMPT_AFTER = jsRegex("^\\s?(하)?(려|으려)다") // flags:
    val RESULT_AFTER = jsRegex("^\\s?있(었|던|는|다|어)") // flags:
    val REPORTED_AFTER = jsRegex("^[^.,!?]{0,4}?(다|라|냐|자)고") // flags:
    val CONDITIONAL_AFTER = jsRegex("^[^.,!?\\s]{0,3}?(으면|다면|더라면|라면)") // flags:
    val QUESTION_AFTER = jsRegex("^[^.,!?]{0,4}?(나요|냐|는지|은지|을까|ㄹ까)") // flags:
    val QUOTED = jsRegex("[\"“”'‘’「」『』][^\"“”'‘’「」『』]{0,80}[\"“”'‘’「」『』]") // flags: g
    val PUSHED_BEFORE = jsRegex("밀려(서|나)?\\s?[^.!?]{0,10}\$") // flags:
    // parseStatement 안의 정규식 리터럴
    val ACTIVE_NO_SIGNAL = jsRegex("(못 ?켰|안 ?켰|안 ?켜고|안 ?넣었|못 ?넣었)")
    val SIGNAL_WORD = jsRegex("(깜빡이|깜박이|방향 ?지시등)")
    val SELF_LC_KEEP = jsRegex("(제가|내가|저희가|우리가) [^.!?]{0,12}(들어갔|넘어갔|바꿨|옮겼)")
    val NEG_BEFORE = jsRegex("(^|\\s)(안|못)\\s?\$")
    val WS_RUN = jsRegex("\\s+")
}

private fun lastEnd(re: Pattern, s: String): Int {
    var e = -1
    for (m in re.all(s)) e = m.end
    return e
}

/** local-judge.js `subjectOf(s, idx, end)`: 'self' | 'other' | null. */
private fun subjectOf(s: String, idx: Int, end: Int?): String? {
    if (end != null) {
        val rest = s.jsSlice(end)
        if (Rx.NOT_SELF_AFTER.test(rest)) return "other"
        if (Rx.SELF_AFTER.test(rest)) return "self"
        if (Rx.OTHER_AFTER.test(rest)) return "other"
    }
    val win = s.jsSlice(Math.max(0, idx - 14), idx)
    val se = lastEnd(Rx.SELF_SUBJ, win)
    val oe = lastEnd(Rx.OTHER_SUBJ, win)
    if (se < 0 && oe < 0) return null
    return if (se >= oe) "self" else "other"
}

private fun negated(s: String, m: JsMatch): Boolean =
    Rx.NEG_VERB.test(s.jsSlice(m.end)) || Rx.NEG_BEFORE.test(s.jsSlice(Math.max(0, m.index - 3), m.index))

private fun notOwnAct(rest: String): Boolean = Rx.REPORTED_AFTER.test(rest) || Rx.CONDITIONAL_AFTER.test(rest) || Rx.QUESTION_AFTER.test(rest)

/** `positiveMatches(re, s)` (neg = NEG_NOUN). */
private fun positiveMatches(re: Pattern, s: String): List<JsMatch> =
    re.all(s).filter { m -> val rest = s.jsSlice(m.end); !Rx.NEG_NOUN.test(rest) && !Rx.HEDGE_NOUN.test(rest) }

/** `violatorOf(s, m)`. */
private fun violatorOf(s: String, m: JsMatch): String? {
    val end = m.end
    subjectOf(s, m.index, end)?.let { return it }
    val tail = s.jsSlice(end, end + 24)
    // goIn 먼저, comeIn 다음 — 안정 정렬이라 같은 위치면 goIn 이 앞
    val verbs = (Rx.RX_goIn.all(tail).map { it to "self" } + Rx.RX_comeIn.all(tail).map { it to "other" }).sortedBy { it.first.index }
    if (verbs.isEmpty()) return null
    val (x, def) = verbs[0]
    return subjectOf(s, end + x.index, end + x.index + x.text.length) ?: def
}

/** local-judge.js `parseStatement(raw)`. null = JS 의 빈 값(`String(raw || '')`). */
fun parseStatement(raw: String?): StatementCues {
    val s0 = Rx.WS_RUN.replaceAllLiteral(bmpOnly(raw ?: ""), " ")
    val s = jsTrim(Rx.QUOTED.replaceAllLiteral(s0, " (인용) "))
    val cues = StatementCues(
        simultaneous = Rx.RX_simultaneous.all(s).any { m -> !Rx.NEG_VERB.test(s.jsSlice(m.end)) },
        rearEnd = Rx.RX_rearEnd.test(s),
        otherType = Rx.RX_otherType.test(s),
    )
    val hedged = Rx.HEDGE.test(s)
    fun skipAct(m: JsMatch): Boolean {
        val rest = s.jsSlice(m.end)
        if (!Rx.ATTEMPT_AFTER.test(rest) && Rx.INTENT_AFTER.test(rest)) { cues.intentOnly = true; return true }
        if (Rx.RESULT_AFTER.test(rest)) return true
        if (Rx.PUSHED_BEFORE.test(s.jsSlice(Math.max(0, m.index - 14), m.index))) return true
        return false
    }
    for (m in Rx.RX_comeIn.all(s)) {
        if (skipAct(m)) continue
        val end = m.end
        val who = subjectOf(s, m.index, end) ?: (if (Rx.ATTEMPT_AFTER.test(s.jsSlice(end))) "self" else null)
        if (negated(s, m)) { if (who == "self") cues.selfDeny = true; continue }
        if (who == "self") {
            if (notOwnAct(s.jsSlice(end))) { cues.reportedSelf = true; continue }
            cues.selfLC = true
        } else cues.otherLC = true
    }
    for (m in Rx.RX_goIn.all(s)) {
        if (skipAct(m)) continue
        val end = m.end
        val who = subjectOf(s, m.index, end)
        if (negated(s, m)) { if (who != "other") cues.selfDeny = true; continue }
        if (who == "other") cues.otherLC = true
        else if (notOwnAct(s.jsSlice(end))) cues.reportedSelf = true
        else cues.selfLC = true
    }
    for (m in Rx.RX_selfDeny.all(s)) {
        val who = subjectOf(s, m.index, m.end)
        if (who != "other") cues.selfDeny = true
    }
    for (m in Rx.RX_noSignal.all(s)) {
        val end = m.end
        if (hedged || Rx.HEDGE_AFTER.test(s.jsSlice(end)) || negated(s, m)) continue
        val active = Rx.ACTIVE_NO_SIGNAL.test(m.text)
        val who = subjectOf(s, m.index, end) ?: when {
            cues.otherLC && !cues.selfLC -> "other"
            cues.selfLC && !cues.otherLC -> "self"
            active && !cues.otherLC -> "self"
            else -> null
        }
        if (who == "self") cues.selfNoSignal = true else if (who == "other") cues.otherNoSignal = true
    }
    if (!hedged && Rx.SIGNAL_WORD.test(s)) {
        for (m in Rx.RX_alsoNoSignal.all(s)) {
            if (m.group(1) in listOf("저", "제", "나", "내")) cues.selfNoSignal = true else cues.otherNoSignal = true
        }
    }
    for (m in Rx.RX_forgotSignal.all(s)) {
        val who = subjectOf(s, m.index, m.end)
        if (who == "other") cues.otherNoSignal = true else cues.selfNoSignal = true
    }
    for (re in listOf(Rx.RX_lateSignal, Rx.RX_lateSignal2)) {
        for (m in re.all(s)) {
            if (hedged || negated(s, m)) continue
            val who = subjectOf(s, m.index, m.end) ?: "other"
            if (who == "self") cues.selfLateSignal = true else cues.otherLateSignal = true
        }
    }
    if (cues.selfDeny && cues.selfLC && !Rx.SELF_LC_KEEP.test(s)) cues.selfLC = false
    val solid = positiveMatches(Rx.RX_solidLine, s)
    if (solid.isNotEmpty()) { cues.solidLine = true; cues.solidLineBy = violatorOf(s, solid[0]) }
    val bus = positiveMatches(Rx.RX_busLane, s)
    if (bus.isNotEmpty()) { cues.busLane = true; cues.busLaneBy = violatorOf(s, bus[0]) }
    return cues
}

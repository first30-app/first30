package com.first30.core.engine

import com.first30.core.model.Ctx

/**
 * engine.js 2단계 "사실 문장" (982~1132행): 측정값을 사람이 읽는 문장으로 바꾼다. 문구는 JS 와 바이트 단위로 같아야 한다.
 */

/** renderFacts 결과. [driverConfirmation] null = JS null (확인 답이 없음). */
data class Facts(
    val vehicle: List<String>,
    val place: List<String>,
    val driverStatement: String,
    val driverConfirmation: String?,
) {
    fun toJs(): Map<String, Any?> = linkedMapOf(
        "vehicle" to vehicle, "place" to place, "driverStatement" to driverStatement, "driverConfirmation" to driverConfirmation,
    )
}

/** engine.js 983행 `DIR_KO`. */
internal val DIR_KO: Map<String, String> = mapOf("LEFT" to "왼쪽", "RIGHT" to "오른쪽")

/** engine.js 984행 `CAUTION_KO`. */
internal val CAUTION_KO: Map<String, String> = linkedMapOf(
    "LANE_CHANGE_CAMERA_ALERT" to "진로변경 위반 단속 카메라 구간 안내가 있었음",
    "CUT_IN_VIOLATION_ENFORCEMENT" to "끼어들기 단속 구간 안내가 있었음",
    "BUS_LANE_AVAILABLE_NOTICE" to "버스전용차로 운영 구간 안내가 있었음",
    "BUS_LANE_UNAVAILABLE_NOTICE" to "버스전용차로 비운영 시간 안내가 있었음",
    "BUS_LANE_VIOLATION_ENFORCEMENT" to "버스전용차로 위반 단속 구간 안내가 있었음",
    "ACCIDENT_PRONE_AREA" to "사고 다발 구간 안내가 있었음",
    "SCHOOL_ZONE" to "어린이보호구역 안내가 있었음",
)

/** engine.js 993행 `UNCERTAIN_KO`. */
internal val UNCERTAIN_KO: Map<String, String> = linkedMapOf(
    "too_large" to "이동 폭이 차로 하나보다 커서 (곡선 도로·회전·연속 차로변경 가능성)",
    "noisy_heading" to "방향 기록의 흔들림이 커서",
    "steering_mismatch" to "핸들 조작 기록이 진로변경과 맞지 않아 (곡선 도로나 방향 측정 오차 가능성)",
    "no_steering" to "핸들 기록이 없어 (진행 방향만으로는 곡선 진입·측정 오차와 구분할 수 없음)",
    "curve_or_lc" to "핸들이 꺾인 채 유지돼 있어 (곡선에 들어선 것인지 진로를 바꾸던 중인지 구분할 수 없음)",
    "bend_hold" to "핸들은 가운데로 돌아왔는데 진행 방향이 바뀐 채 유지돼 있어 (굽은 길을 따라간 움직임으로 보임)",
    "borderline" to "차로 유지와 차로변경의 경계에 있어",
    "baseline_sensitive" to "도로 방향 기준을 조금만 바꿔도 결과가 달라져",
    "short_for_speed" to "빠르게 달릴 때는 진로변경이 10초 가까이 걸릴 수 있는데 충돌 전 기록이 짧아",
    "late_reference" to "기록 앞부분이 고르지 않아 도로 방향 기준을 충돌 직전 구간에서 잡을 수밖에 없어",
    "opposite_pair" to "바로 앞에 반대쪽으로 크게 틀어진 움직임이 붙어 있어 (S자 회피·연속 움직임이거나 도로 방향 기준이 치우친 경우)",
    "heading_lag" to "위치 기반 진행 방향이 핸들보다 늦게 반응해 충돌 직전 움직임이 아직 방향 기록에 다 담기지 않아",
    "road_change" to "기록 중에 도로가 굽었다가 펴지는 등 도로 모양이 바뀌어 옆 이동을 잴 기준이 없어",
    "steer_activity" to "진행 방향은 차로를 지킨 것처럼 보여도 그동안 핸들이 도로를 따라갈 때보다 크게 움직여 (굽기가 바뀌는 길이거나 진로변경 중일 수 있음)",
)

private fun n(x: Double?): String = Js.str(x)

private val AIRBAG_REASON = jsRegex("^airbag_")

/** engine.js 1009행 `UNMEASURABLE_KO` (사유 → 문장). 없는 사유는 null. */
internal fun unmeasurableKo(reason: String?, m: WindowMetrics?): String? = when (reason) {
    "missing" -> m!!.let { "위치·방향 기록이 부족해 (결측 약 ${n(com.first30.core.model.JsMath.round(Math.max(it.quality.headingMissing, it.quality.speedMissing) * 100))}%) 차로변경 여부를 측정할 수 없음" }
    "short_buffer" -> "충돌 전 기록이 ${n(m!!.bufferSec)}초뿐이라 차로변경 여부를 측정할 수 없음"
    "too_few_frames" -> "충돌 전 차량 기록이 거의 없어 차로변경 여부를 측정할 수 없음"
    "impact_unknown" -> if (m != null && AIRBAG_REASON.test(m.impact.reason ?: ""))
        "에어백이 터진 시각이 기록 범위 밖이라 충돌 순간을 알 수 없어 차로변경 여부를 측정하지 않았음 — 누가 진로를 바꿨는지는 블랙박스 영상으로 확인해야 함"
    else "충돌 순간을 차가 직접 감지하지 못해(음성·버튼 신고) 차로변경 여부를 측정하지 않았음 — 누가 진로를 바꿨는지는 블랙박스 영상으로 확인해야 함"
    else -> null
}

private val PLATE_RX = jsRegex("\\d{2,3}\\s?[가-힣]\\s?\\d{4}")
private val PHONE_RX = jsRegex("01[016789][-\\s.]?\\d{3,4}[-\\s.]?\\d{4}")

/** engine.js 1018행 `redact(text)`: 진술에서 차량 번호·전화번호를 지운다. null = JS 의 빈 값(`String(text || '')`). */
fun redact(text: String?): String {
    val s = text ?: ""
    return PHONE_RX.replaceAllLiteral(PLATE_RX.replaceAllLiteral(s, "[차량번호]"), "[전화번호]")
}

/** engine.js 1025행 `signalSentence(s, d)`. */
internal fun signalSentence(s: SignalFacts, d: String?): String? {
    val dd = Js.str(d)
    return when (s.status) {
        "none" -> "그 움직임 동안 $dd 방향지시등은 켜지 않았음${if (!s.note.isNullOrEmpty()) " (${s.note})" else ""}"
        "after" -> "움직이기 시작하고 ${n(Math.abs(s.leadSec ?: 0.0))}초 뒤에야 $dd 방향지시등을 켰음"
        "at_start" -> "움직이기 시작하면서 거의 동시에 $dd 방향지시등을 켰음 (진로변경 법정 기준인 ${n(s.requiredM)}m 전보다 늦음)"
        "late" -> "움직이기 약 ${n(s.leadM)}m 전에 $dd 방향지시등을 켰음 (진로변경 법정 기준 ${n(s.requiredM)}m보다 짧음)"
        "borderline" -> "움직이기 약 ${n(s.leadM)}m 전에 $dd 방향지시등을 켰음 (${if (!s.note.isNullOrEmpty()) s.note else "진로변경 법정 기준 ${n(s.requiredM)}m 안팎이라 측정 오차 범위에서 충족 여부를 단정할 수 없음"})"
        "ok" -> "움직이기 약 ${n(s.leadM)}m 전부터 $dd 방향지시등을 켜고 있었음 (진로변경 법정 기준 ${n(s.requiredM)}m 이상)"
        "indeterminate" -> "$dd 방향지시등은 기록 시작 시점부터 켜져 있었음 (켠 시점은 기록 밖이라 확인 불가)"
        else -> null
    }
}

/** engine.js 1039행 `confirmQuestion(dir)` — 기록이 보여 준 방향으로 묻는다. */
fun confirmQuestion(dir: String?): String {
    val ko = dir?.let { DIR_KO[it] }
    return if (ko != null) "그때 $ko 차로로 진로를 바꾸던 중이었나요?" else "그때 차로를 바꾸던 중이었나요?"
}

/** engine.js 1040행 `confirmationText(ctx, m)`. */
internal fun confirmationText(ctx: Ctx, m: WindowMetrics?): String? {
    val ans = ctx.laneChangeConfirmed ?: return null
    val dir = m?.laneChange?.direction
    return "운전자 확인 답변: \"${confirmQuestion(dir)}\"에 \"${if (ans) "예" else "아니요"}\""
}

/** engine.js 1046행 `renderFacts(m, ctx)`. */
fun renderFacts(m: WindowMetrics, ctxIn: Ctx?): Facts {
    val ctx = ctxIn ?: Ctx()
    val vehicle = ArrayList<String>()
    val lc = m.laneChange
    val known = m.impact.known
    if (!known) {
        vehicle.add(unmeasurableKo("impact_unknown", m)!!)
        if (m.endSpeedKph != null) vehicle.add(if (m.stoppedAtEnd == true) "신고할 때 내 차는 멈춰 있었음" else "신고할 때 내 차 속도는 시속 약 ${n(m.endSpeedKph)}km였음")
    } else {
        if (m.impactSpeedKph != null) vehicle.add(if (m.stoppedAtImpact == true) "충돌 순간 내 차는 거의 멈춰 있었음" else "충돌 순간 내 차 속도는 시속 약 ${n(m.impactSpeedKph)}km였음")
        val d: String? = lc.direction?.let { DIR_KO[it] }
        val dd = Js.str(d)
        val lookAlike = "굽은 길이나 차로가 어긋난 곳을 따라갈 때도 기록은 같게 나옴"
        when (lc.status) {
            "detected" -> vehicle.add(
                if (lc.endT == null)
                    "충돌 ${n(sec(lc.startT ?: 0.0))}초 전부터 내 차가 ${dd}으로 진행 방향을 틀어 옆으로 약 ${n(lc.lateralM)}m 움직이고 있었음 (진행 방향 최대 ${n(lc.maxHeadingDeg)}도) — 차로를 바꿀 때의 움직임과 같지만, $lookAlike"
                else
                    "충돌 ${n(sec(lc.startT ?: 0.0))}초 전부터 ${n(sec(lc.endT!!))}초 전까지 내 차가 ${dd}으로 옆으로 약 ${n(lc.lateralM)}m 움직였음 (진행 방향 최대 ${n(lc.maxHeadingDeg)}도) — 차로를 바꾼 직후의 움직임과 같지만, $lookAlike",
            )
            "completed" -> vehicle.add(
                "충돌 ${n(sec(lc.startT ?: 0.0))}초 전부터 ${n(lc.completedSecBefore)}초 전까지 내 차가 ${dd}으로 옆으로 약 ${n(lc.lateralM)}m 움직였음 (차로를 바꾼 움직임과 같음)${if (lc.keptLaneAfter == true) " — 그 뒤 충돌까지 옆으로 거의 움직이지 않았음" else ""}",
            )
            "starting" -> vehicle.add(
                if (lc.reason == "evasive")
                    "충돌 ${n(sec(lc.startT ?: 0.0))}초 전부터 내 차가 ${dd}으로 방향을 틀면서 브레이크를 밟았음 (옆 이동 약 ${n(lc.lateralM)}m) — 진로변경인지 피하려는 조작인지 기록만으로 구분할 수 없음"
                else if (lc.basis == "steering")
                    "충돌 직전 ${n(sec(lc.startT ?: 0.0))}초 동안 내 차 핸들이 ${dd}으로 꺾여 있었음 (진행 방향 변화 최대 ${n(lc.maxHeadingDeg)}도) — 진로변경을 시작했는지, 피하려고 꺾었는지, 진로변경을 마무리하던 중인지는 기록만으로 구분할 수 없음"
                else
                    "충돌 직전 ${n(sec(lc.startT ?: 0.0))}초 동안 내 차가 ${dd}으로 진행 방향을 틀었음 (옆 이동 약 ${n(lc.lateralM)}m, 방향 최대 ${n(lc.maxHeadingDeg)}도) — 진로변경을 시작했는지, 피하려고 핸들을 꺾었는지는 기록만으로 구분할 수 없음",
            )
            "uncertain" -> vehicle.add(
                if (lc.lateralM == null || lc.maxHeadingDeg == null)
                    "${lc.reason?.let { UNCERTAIN_KO[it] } ?: "차량 기록만으로는"} 차로를 바꿨는지 확정할 수 없음"
                else
                    "최근 기록상 내 차는 옆으로 약 ${n(lc.lateralM)}m 움직이고 진행 방향이 최대 ${n(lc.maxHeadingDeg)}도 바뀌었지만, ${lc.reason?.let { UNCERTAIN_KO[it] } ?: ""} 차로를 바꿨는지 확정할 수 없음",
            )
            "too_slow" -> vehicle.add(
                if (m.recentSpeedKph != null && m.recentSpeedKph < 2) "충돌 전 4초 동안 내 차는 거의 멈춰 있었음"
                else "충돌 전 내 차는 매우 느리게 움직이고 있어 (최근 4초 평균 시속 약 ${n(m.recentSpeedKph)}km) 차로변경 여부를 측정하지 않음",
            )
            "unmeasurable" -> vehicle.add(unmeasurableKo(lc.reason, m) ?: unmeasurableKo("too_few_frames", m)!!)
            else -> vehicle.add("충돌 전 ${n(lc.windowSec)}초 동안 내 차는 차로를 유지했음 (측면 이동 약 ${n(lc.lateralM)}m, 진행 방향 변화 ${n(lc.maxHeadingDeg)}도)")
        }
        if (lc.steeringCorroborated == true && (lc.status == "detected" || lc.status == "completed")) {
            vehicle.add("핸들도 ${dd}으로 꺾였다가 돌아와 진행 방향 기록과 일치함")
        }
        if (lc.status == "detected" || lc.status == "completed" || lc.status == "starting") {
            val s = if (m.signal.status == "unavailable") "방향지시등 기록이 없어 신호를 켰는지 확인할 수 없음" else signalSentence(m.signal, d)
            if (!s.isNullOrEmpty()) vehicle.add(s)
        } else if (m.signal.recentOn == "LEFT" || m.signal.recentOn == "RIGHT") {
            val ko = DIR_KO[m.signal.recentOn]
            vehicle.add(
                if (m.signal.stateAtImpact == m.signal.recentOn) "충돌 순간 내 차의 $ko 방향지시등이 켜져 있었음"
                else "충돌 직전 ${n(Thresh.RECENT_SIGNAL_SEC)}초 안에 내 차의 $ko 방향지시등이 켜져 있었음",
            )
        }
        if (m.quality.steeringSign == "reversed") vehicle.add("핸들 각도와 진행 방향 변화의 부호가 반대로 기록돼 있어 핸들 기록을 쓰지 않았음 (차량 연동 설정 확인 필요)")

        val b = m.brake
        if (b.applied == null) {
            if (m.impactSpeedKph != null) vehicle.add("브레이크 기록이 없어 확인할 수 없음")
        } else if (b.applied) {
            if (b.onAtBufferStart && b.releasedSecBefore == null) vehicle.add("기록 시작부터 충돌 순간까지 브레이크를 밟고 있었음")
            else if (b.releasedSecBefore != null) vehicle.add("충돌 ${n(b.onsetSecBefore)}초 전에 브레이크를 밟았다가 충돌 ${n(b.releasedSecBefore)}초 전에 뗐음")
            else if ((b.onsetSecBefore ?: 0.0) < 0.1) vehicle.add("충돌 순간에 브레이크를 밟았음")
            else vehicle.add("충돌 ${n(b.onsetSecBefore)}초 전부터 브레이크를 밟고 있었음")
        } else if (m.impactSpeedKph != null) vehicle.add("충돌 직전 1.5초 동안 브레이크를 밟지 않았음")
        val md = m.maxDecelKphPerS
        if (md != null && md >= 10) vehicle.add("충돌 직전 급감속이 있었음 (1초에 시속 약 ${n(com.first30.core.model.JsMath.round(md))}km 감소)")
    }
    if (m.hazardOn) vehicle.add(if (known) "충돌 전 기록에 비상등이 켜진 구간이 있었음" else "신고 전 기록에 비상등이 켜진 구간이 있었음")
    if (m.airbagDeployed) vehicle.add("에어백이 전개됐음")

    val place = ArrayList<String>()
    val rt = roadTypeOf(ctx)
    place.add(if (rt == "highway") "사고 장소는 고속도로" else if (rt == "urban") "사고 장소는 일반도로" else "도로 유형 정보 없음")
    // 자기 키만 본다 (engine.js 1122) — 'constructor' 같은 상속 속성이 사실 문장에 섞이지 않도록
    for (c in ctx.navCautions ?: emptyList()) CAUTION_KO[c]?.let { place.add("내비게이션: $it") }

    val statement = redact(jsTrim(ctx.driverStatement ?: "").jsSlice(0, 300))
    return Facts(
        vehicle = vehicle,
        place = place,
        driverStatement = if (statement.isNotEmpty()) "운전자 본인 진술(사고 직후 음성, 확인되지 않은 주장): \"$statement\"" else "운전자 진술 없음",
        driverConfirmation = confirmationText(ctx, m),
    )
}

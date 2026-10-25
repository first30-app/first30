package com.first30.core.engine

/**
 * engine.js 3단계 "Jev 요청" (1134~1176행) `buildJevRequest(facts, opts)`.
 * 요청 형식은 engine/jev-client.js 주석(POST .../v1/systemone, { model, state, questions }) 참고.
 */

/** buildJevRequest 결과. [driverConfirmation] 이 null 이면 `state.driver_confirmation` 키가 없다. */
data class JevRequest(
    val model: String,
    val vehicleFacts: List<String>,
    val placeFacts: List<String>,
    val driverStatement: String,
    val driverConfirmation: String?,
) {
    fun toJs(): Map<String, Any?> {
        val state = linkedMapOf<String, Any?>(
            "vehicle_facts" to vehicleFacts,
            "place_facts" to placeFacts,
            "driver_statement" to driverStatement,
        )
        if (driverConfirmation != null) state["driver_confirmation"] = driverConfirmation
        return linkedMapOf("model" to model, "state" to state, "questions" to JEV_QUESTIONS)
    }

    companion object {
        private const val STATEMENT_ONLY = "운전자 진술은 확인되지 않은 본인 주장이므로, 진술 말고 다른 근거(차량 기록·내비게이션 정보)가 없으면 높게 답하지 마세요."

        /** 질문 6개 (Choice 1 + Noul 5). 내용이 고정이라 한 번만 만든다. */
        val JEV_QUESTIONS: Map<String, Any?> = linkedMapOf(
            "chart" to linkedMapOf(
                "type" to "choice",
                "instructions" to "차량 기록과 장소 정보를 바탕으로 이 사고가 아래 차로변경 사고 유형 중 어디에 해당하는지 고르세요. 어느 유형에도 맞지 않거나 근거가 부족하면 none을 고르세요. 운전자 진술은 확인되지 않은 본인 주장이므로 차량 기록과 어긋나면 차량 기록을 우선하세요. 차량 기록의 \"옆으로 움직임\"은 굽은 길을 따라간 움직임일 수도 있습니다.",
                "criteria" to Charts.CHART_OPTIONS.mapValuesTo(LinkedHashMap()) { it.value.criteria },
            ),
            "evidence_sufficient" to linkedMapOf(
                "type" to "noul",
                "instructions" to "주어진 차량 기록과 진술만으로 사고 유형을 판단할 근거가 충분하다.",
            ),
            "self_lane_change" to linkedMapOf(
                "type" to "noul",
                "instructions" to "운전자가 진술이나 확인 답변에서 사고 직전 자기 차가 진로(차로)를 바꾸고 있었다고 스스로 인정한다. 바꾸려고 했다·기다렸다처럼 의도만 말한 경우, 바꾸지 않았다고 부정한 경우, 상대 차 이야기만 한 경우, 남의 주장을 옮긴 경우(\"상대가 제가 끼어들었다고 우겨요\"), 가정·질문(\"바꿨으면\", \"바꾼 거냐고\")은 해당하지 않는다.",
            ),
            "other_signal_fail" to linkedMapOf(
                "type" to "noul",
                "instructions" to "상대 차가 방향지시등을 켜지 않았거나 늦게 켜고 진로를 바꿨다는 구체적인 근거가 있다. $STATEMENT_ONLY",
            ),
            "forbidden_zone" to linkedMapOf(
                "type" to "noul",
                "instructions" to "진로를 바꾼 차가 진로변경이 금지된 곳(백색 실선 구간 등)에서 진로를 바꿨다는 구체적인 근거가 있다. $STATEMENT_ONLY",
            ),
            "bus_lane" to linkedMapOf(
                "type" to "noul",
                "instructions" to "진로를 바꾼 차가 버스·다인승 전용차로를 위반했다는 구체적인 근거가 있다. $STATEMENT_ONLY",
            ),
        )
    }
}

/** run/buildJevRequest 옵션. [model] 비었거나 null 이면 'jev-latest'. [onJudgeFailure] 'label'(기본) | 'abstain'. */
data class RunOpts(val model: String? = null, val onJudgeFailure: String? = null)

/** engine.js 1135행 `buildJevRequest(facts, opts)`. */
fun buildJevRequest(facts: Facts, opts: RunOpts? = null): JevRequest = JevRequest(
    model = opts?.model?.takeIf { it.isNotEmpty() } ?: "jev-latest",
    vehicleFacts = facts.vehicle,
    placeFacts = facts.place,
    driverStatement = facts.driverStatement,
    driverConfirmation = facts.driverConfirmation?.takeIf { it.isNotEmpty() },
)

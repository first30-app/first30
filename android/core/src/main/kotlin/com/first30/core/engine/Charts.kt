package com.first30.core.engine

/**
 * engine/charts.js — 과실비율 인정기준 도표 데이터 (차로변경 사고 MVP 범위). 값·문구 그대로.
 * 기본과실·수정요소 숫자는 손해보험협회 정보포털 원문에서 확인한 것만 수치로 쓴다(verified:false 도표는 수정요소를 계산하지 않음).
 */

/** 도표 출처 (`source`). [note] null = 키 없음. */
data class ChartSource(val name: String, val url: String, val checked: String, val verified: Boolean, val note: String? = null) {
    fun toJs(): Map<String, Any?> = linkedMapOf<String, Any?>("name" to name, "url" to url, "checked" to checked, "verified" to verified)
        .also { m -> note?.let { m["note"] = it } }
}

/** 수정요소. party: 가산 대상 차량, delta: 가산 %p, group: 같은 그룹은 중복 적용하지 않음. */
data class ChartModifier(val id: String, val party: String, val delta: Double, val label: String, val group: String? = null, val basis: String? = null)

data class Chart(
    val id: String,
    val title: String,
    val summary: String,
    /** 기본과실 A, B (%). */
    val baseA: Double,
    val baseB: Double,
    val source: ChartSource,
    val modifiers: List<ChartModifier>,
    val pendingHints: List<String>? = null,
) {
    fun base(party: String?): Double? = when (party) { "A" -> baseA; "B" -> baseB; else -> null }
}

/** Jev Choice 선택지 → (도표, 내 차 역할). 'none' 은 chart/me/other 가 없다. */
data class ChartOption(val key: String, val chart: String?, val me: String?, val other: String?, val label: String, val criteria: String)

object Charts {
    val CHARTS: Map<String, Chart> = linkedMapOf(
        "252" to Chart(
            id = "252",
            title = "후행 직진(A) 대 선행 진로변경(B)",
            summary = "같은 방향으로 가던 중, 앞서 진로를 바꾸는 B와 뒤에서 직진하던 A가 충돌한 사고",
            baseA = 30.0, baseB = 70.0,
            source = ChartSource(
                name = "손해보험협회 자동차사고 과실비율 정보포털 · 도표 252",
                url = "https://accident.knia.or.kr/myaccident-content?chartNo=252",
                checked = "2026-10-23",
                verified = true,
            ),
            modifiers = listOf(
                ChartModifier("A_notable", "A", 10.0, "A 현저한 과실", group = "A_negligence"),
                ChartModifier("A_gross", "A", 20.0, "A 중과실", group = "A_negligence"),
                ChartModifier("B_signal", "B", 10.0, "B 진로변경 신호 불이행·지연", basis = "도로교통법 제38조 — 진로변경 지점 30m 전(고속도로 100m 전)부터 신호"),
                ChartModifier("B_buslane", "B", 10.0, "B 버스·다인승 전용차로 위반"),
                ChartModifier("B_forbidden", "B", 20.0, "B 진로변경 금지장소(백색 실선 등)"),
                ChartModifier("B_notable", "B", 10.0, "B 현저한 과실", group = "B_negligence"),
                ChartModifier("B_gross", "B", 20.0, "B 중과실", group = "B_negligence"),
            ),
        ),
        "252-2" to Chart(
            id = "252-2",
            title = "좌우 차로에서 같은 차로로 동시 진로변경",
            summary = "왼쪽·오른쪽 차로의 두 차가 가운데 차로로 동시에 진로를 바꾸다 충돌한 사고",
            baseA = 50.0, baseB = 50.0,
            source = ChartSource(
                name = "손해보험협회 과실분쟁 다발 사고 안내(굿모닝경제 보도) · 도표 252-2",
                url = "https://www.goodkyung.com/news/articleView.html?idxno=239410",
                checked = "2026-10-23",
                verified = false,
                note = "기본과실 5:5만 확인했다. 수정요소 수치는 정보포털 원문 대조 후 반영한다.",
            ),
            modifiers = emptyList(),
            pendingHints = listOf(
                "방향지시등 불이행·지연",
                "정체 차로에서 대기하다 진로를 바꾼 경우(협회 안내상 10% 가산 가능)",
            ),
        ),
    )

    /** 넣은 순서 = JS 객체 순서 (c252_me_B, c252_me_A, c252_2, none) — 요청의 criteria 순서. */
    val CHART_OPTIONS: Map<String, ChartOption> = linkedMapOf(
        "c252_me_B" to ChartOption(
            "c252_me_B", "252", "B", "A",
            "내 차가 앞서 진로변경 (도표 252, 내 차 B)",
            "내 차가 앞서 진로(차로)를 바꾸던 중, 같은 방향 뒤쪽에서 직진하던 상대 차와 충돌했다",
        ),
        "c252_me_A" to ChartOption(
            "c252_me_A", "252", "A", "B",
            "상대 차가 앞서 진로변경 (도표 252, 내 차 A)",
            "내 차는 차로를 유지하며 직진했고, 상대 차가 앞쪽에서 내 차로로 진로를 바꾸다 충돌했다",
        ),
        "c252_2" to ChartOption(
            "c252_2", "252-2", "A", "B",
            "양쪽 동시 진로변경 (도표 252-2)",
            "내 차와 상대 차가 서로 반대쪽 차로에서 같은 차로로 거의 동시에 진로를 바꾸다 충돌했다",
        ),
        "none" to ChartOption(
            "none", null, null, null,
            "해당 없음 · 근거 부족",
            "위의 차로변경 사고 유형에 해당하지 않거나(추돌·교차로·주차장 등), 주어진 사실만으로는 판단할 근거가 부족하다",
        ),
    )
}

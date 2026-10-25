package com.first30.core.scenarios

import com.first30.core.model.Ctx
import com.first30.core.model.Frame

/**
 * Expected outcome of a scenario (`expect` in scenarios.js). Kotlin null = key absent in JS.
 * [confirm] = an app confirm question is expected; [confirmed] = expected result after answering "예".
 */
data class Expect(
    val status: String,
    val option: String? = null,
    val likely: Int? = null,
    val low: Int? = null,
    val high: Int? = null,
    val mode: String? = null,
    val confirm: Boolean? = null,
    val confirmed: Expect? = null,
) {
    /** As a JS object (null fields omitted, numbers as Double), key order as in scenarios.js. */
    fun toJsMap(): Map<String, Any?> = buildMap {
        put("status", status)
        confirm?.let { put("confirm", it) }
        confirmed?.let { put("confirmed", it.toJsMap()) }
        option?.let { put("option", it) }
        likely?.let { put("likely", it.toDouble()) }
        low?.let { put("low", it.toDouble()) }
        high?.let { put("high", it.toDouble()) }
        mode?.let { put("mode", it) }
    }
}

/** One demo/test incident (scenarios.js `SCENARIOS` entry). [frames] = `synth(spec)` (JS 140행). */
data class Scenario(
    val id: String,
    val title: String,
    val ctx: Ctx,
    val spec: SynthSpec,
    val expect: Expect,
) {
    val frames: List<Frame> by lazy { Synth.synthFrames(spec) }
}

/** Port of scenarios/scenarios.js `SCENARIOS` (50~138행), same order. */
object Scenarios {
    private const val URBAN = "urban"
    private fun urban(statement: String, confirmed: Boolean? = null, navCautions: List<String> = emptyList(), trigger: String = "impact") =
        Ctx(trigger = trigger, roadType = URBAN, navCautions = navCautions, driverStatement = statement, laneChangeConfirmed = confirmed)

    val ALL: List<Scenario> = listOf(
        // JS 51~57행
        Scenario(
            id = "me_late_signal",
            title = "내가 오른쪽 차로로 들어가다 충돌 · 깜빡이를 늦게 켬",
            ctx = urban("오른쪽 차로로 들어가다가 옆 차랑 부딪혔어", confirmed = true),
            spec = SynthSpec(50.0, laneChange = LaneChangeSpec(-2.2, 4.0, "RIGHT"), signal = SignalSpec(-2.6, "RIGHT")),
            expect = Expect("estimate", option = "c252_me_B", likely = 80, low = 80, high = 80),
        ),
        // JS 58~65행 — 굽은 길·차로 어긋남을 따라간 움직임도 기록이 같아 운전자 확인 전에는 숫자를 내지 않는다. "예" → 신호 없음 +10 → 80%
        Scenario(
            id = "confirm_needed",
            title = "진로변경 같은 움직임 · 진술 없음 → 앱이 먼저 확인 질문",
            ctx = urban(""),
            spec = SynthSpec(60.0, laneChange = LaneChangeSpec(-2.4, 4.5, "LEFT")),
            expect = Expect(
                "abstain", confirm = true,
                confirmed = Expect("estimate", option = "c252_me_B", likely = 80, low = 80, high = 80),
            ),
        ),
        // JS 66~72행
        Scenario(
            id = "other_cutin_no_signal",
            title = "직진 중 상대가 끼어듦 · \"깜빡이 없었다\"고 진술",
            ctx = urban("오른쪽 차가 깜빡이도 안 켜고 갑자기 들어왔어", confirmed = false),
            spec = SynthSpec(brakingSpeed(60.0, -0.8, 20.0), brakeFromS = -0.8),
            expect = Expect("estimate", option = "c252_me_A", likely = 30, low = 20, high = 30),
        ),
        // JS 73~79행
        Scenario(
            id = "other_cutin_unclear",
            title = "직진 중 상대가 끼어듦 · 상대 깜빡이 언급 없음",
            ctx = urban("오른쪽 차가 갑자기 들어왔어", confirmed = false),
            spec = SynthSpec(brakingSpeed(55.0, -0.6, 18.0), brakeFromS = -0.6),
            expect = Expect("estimate", option = "c252_me_A", likely = 30, low = 30, high = 30),
        ),
        // JS 80~87행 — 상대도 동시에 바꿨다는 건 진술뿐 → 252(내 차 B)로 계산하고 252-2(50:50)는 범위의 아래쪽 끝으로만
        Scenario(
            id = "simultaneous",
            title = "양쪽에서 같은 차로로 동시에 진로변경",
            ctx = urban("옆 차랑 동시에 같은 차로로 들어가다가 부딪혔어", confirmed = true),
            spec = SynthSpec(45.0, laneChange = LaneChangeSpec(-2.2, 4.0, "LEFT"), signal = SignalSpec(-6.0, "LEFT")),
            expect = Expect("estimate", option = "c252_me_B", likely = 70, low = 50, high = 70),
        ),
        // JS 88~94행
        Scenario(
            id = "highway_late_signal",
            title = "고속도로 · 100m 기준보다 늦게 깜빡이",
            ctx = Ctx(trigger = "impact", roadType = "highway", navCautions = emptyList(), driverStatement = "2차로로 들어가다 뒤차랑 닿았어", laneChangeConfirmed = true),
            spec = SynthSpec(100.0, laneChange = LaneChangeSpec(-2.2, 4.0, "RIGHT"), signal = SignalSpec(-4.4, "RIGHT")),
            expect = Expect("estimate", option = "c252_me_B", likely = 80, low = 80, high = 80, mode = "highway"),
        ),
        // JS 95~101행
        Scenario(
            id = "proper_signal",
            title = "깜빡이를 충분히 일찍 켜고 진로변경",
            ctx = urban("차선 바꾸는데 뒤에서 오던 차랑 부딪혔어", confirmed = true),
            spec = SynthSpec(40.0, laneChange = LaneChangeSpec(-2.2, 4.0, "RIGHT"), signal = SignalSpec(-7.0, "RIGHT")),
            expect = Expect("estimate", option = "c252_me_B", likely = 70, low = 70, high = 70),
        ),
        // JS 102~108행
        Scenario(
            id = "solid_line",
            title = "실선 구간에서 진로변경",
            ctx = urban("실선 구간이었는데 들어가다가 박았어", confirmed = true, navCautions = listOf("LANE_CHANGE_CAMERA_ALERT")),
            spec = SynthSpec(50.0, laneChange = LaneChangeSpec(-2.2, 4.0, "RIGHT"), signal = SignalSpec(-6.0, "RIGHT")),
            expect = Expect("estimate", option = "c252_me_B", likely = 70, low = 70, high = 90),
        ),
        // JS 109~115행
        Scenario(
            id = "airbag",
            title = "에어백 전개 · 앞차가 갑자기 들어옴",
            ctx = urban("앞에 차가 갑자기 들어와서 박았어", confirmed = false, trigger = "airbag"),
            spec = SynthSpec(brakingSpeed(45.0, -1.2, 22.0), brakeFromS = -1.2, airbag = true),
            expect = Expect("estimate", option = "c252_me_A", likely = 30, low = 30, high = 30, mode = "emergency"),
        ),
        // JS 116~123행 — 충돌 시각을 모르면 기록 속 움직임이 사고 전인지 뒤인지 가릴 수 없다 → 숫자 없이 기록 봉인과 증거 안내만
        Scenario(
            id = "voice_report",
            title = "음성으로 신고 · 충돌 순간을 차가 감지하지 못함",
            ctx = urban("오른쪽 차로로 들어가다가 옆 차랑 부딪혔어", trigger = "voice"),
            spec = SynthSpec(50.0, laneChange = LaneChangeSpec(-2.2, 4.0, "RIGHT"), signal = SignalSpec(-2.6, "RIGHT")),
            expect = Expect("abstain"),
        ),
        // JS 124~130행
        Scenario(
            id = "unclear_slow",
            title = "정체 중 저속 접촉 · 진술 없음",
            ctx = urban(""),
            spec = SynthSpec(5.0),
            expect = Expect("abstain"),
        ),
        // JS 131~137행
        Scenario(
            id = "rear_end",
            title = "신호 대기 중 뒤차 추돌 (차로변경 사고 아님)",
            ctx = urban("신호 대기 중에 뒷차가 박았어"),
            spec = SynthSpec(0.0, brakeFromS = -10.0),
            expect = Expect("abstain"),
        ),
    )

    fun byId(id: String): Scenario = ALL.first { it.id == id }
}

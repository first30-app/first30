package com.first30.core.spec

import com.first30.core.engine.Charts
import com.first30.core.engine.JEV_ENDPOINT
import com.first30.core.engine.JevHttpResponse
import com.first30.core.engine.JevJudge
import com.first30.core.engine.JevTransport
import com.first30.core.engine.RunOpts
import com.first30.core.engine.SPEECH_PRINCIPLE
import com.first30.core.engine.analyzeWindow
import com.first30.core.engine.buildJevRequest
import com.first30.core.engine.computeFault
import com.first30.core.engine.Facts
import com.first30.core.engine.parseStatement
import com.first30.core.engine.renderFacts
import com.first30.core.engine.run
import com.first30.core.engine.sealEvidence
import com.first30.core.engine.validateJevAnswers
import com.first30.core.model.Ctx
import com.first30.core.seal.Canonical
import com.first30.core.spec.Spec.FIXED_NOW
import com.first30.core.spec.Spec.SCENARIOS
import com.first30.core.spec.Spec.anyMatch
import com.first30.core.spec.Spec.assertMatch
import com.first30.core.spec.Spec.assertNoMatch
import com.first30.core.spec.Spec.bool
import com.first30.core.spec.Spec.confirmed
import com.first30.core.spec.Spec.eqNum
import com.first30.core.spec.Spec.failingJev
import com.first30.core.spec.Spec.highway
import com.first30.core.spec.Spec.json
import com.first30.core.spec.Spec.keptLane
import com.first30.core.spec.Spec.noul
import com.first30.core.spec.Spec.nums
import com.first30.core.spec.Spec.obj
import com.first30.core.spec.Spec.ok
import com.first30.core.spec.Spec.path
import com.first30.core.spec.Spec.runLocal
import com.first30.core.spec.Spec.sc
import com.first30.core.spec.Spec.str
import com.first30.core.spec.Spec.tMs
import com.first30.core.spec.Spec.ts
import com.first30.core.spec.Spec.urban
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/** engine.test.js "과실 계산" · "대체 판정기" · "Jev 연동" · "봉인" · "안전 안내" (279~516행). Method names = JS line numbers. */
class JudgeSpecTest {
    private val fullB = obj(
        "chart" to obj("choice" to "c252_me_B", "probabilities" to obj("c252_me_B" to 0.9, "c252_me_A" to 0.05, "c252_2" to 0.03, "none" to 0.02)),
        "evidence_sufficient" to noul(0.9), "self_lane_change" to noul(0.9), "other_signal_fail" to noul(0.1), "forbidden_zone" to noul(0.1), "bus_lane" to noul(0.1),
    )
    private val strongA = obj(
        "chart" to obj("choice" to "c252_me_A", "probabilities" to obj("c252_me_A" to 0.95, "none" to 0.05)),
        "evidence_sufficient" to noul(0.9), "other_signal_fail" to noul(0.95), "forbidden_zone" to noul(0.95), "bus_lane" to noul(0.95),
    )

    // ---------- 과실 계산 ----------
    @Test @DisplayName("현저한 과실·중과실은 차량 기록만으로 평가하지 않는다(not_assessed)")
    fun l279() {
        val m = analyzeWindow(sc("proper_signal").frames, urban())
        val r = computeFault(fullB, m, Ctx(laneChangeConfirmed = true))
        for (id in listOf("A_notable", "A_gross", "B_notable", "B_gross")) {
            assertEquals("not_assessed", r.modifiers.first { it.id == id }.decision)
        }
        eqNum(70, r.likely)
    }

    @Test @DisplayName("진술만 근거인 가감 요소는 Jev가 높게 답해도 \"반영\"하지 않고 범위에만 넣는다")
    fun l292() {
        val m = analyzeWindow(sc("other_cutin_no_signal").frames, urban())
        val r = computeFault(strongA, m, Ctx(navCautions = emptyList(), laneChangeConfirmed = false))
        ok(r.modifiers.all { it.decision != "applied" }, r.modifiers.map { it.toJs() }.toString())
        eqNum(30, r.likely)
        eqNum(0, r.low)
    }

    @Test @DisplayName("내비 구간 안내가 있어도 판정 모델 해석은 \"반영\"하지 않는다 — 범위는 0~100 으로 제한")
    fun l304() {
        val m = analyzeWindow(sc("other_cutin_no_signal").frames, urban())
        val r = computeFault(strongA, m, Ctx(navCautions = listOf("LANE_CHANGE_CAMERA_ALERT", "BUS_LANE_VIOLATION_ENFORCEMENT"), laneChangeConfirmed = false))
        for (id in listOf("B_forbidden", "B_buslane", "B_signal")) assertEquals("possible", r.modifiers.first { it.id == id }.decision, id)
        assertEquals(true, r.modifiers.first { it.id == "B_forbidden" }.basis.contains("nav"))
        eqNum(30, r.likely)
        eqNum(0, r.low) // 30 - 10 - 20 - 10 → 0
    }

    @Test @DisplayName("차량 기록과 판정 유형이 어긋나면 판단 보류")
    fun l317() {
        val mLC = analyzeWindow(sc("me_late_signal").frames, urban())
        val mKeep = analyzeWindow(sc("other_cutin_unclear").frames, urban())
        fun ans(choice: String) = obj("chart" to obj("choice" to choice, "probabilities" to obj(choice to 0.9, "none" to 0.1)), "evidence_sufficient" to noul(0.9))
        assertEquals("abstain", computeFault(ans("c252_me_A"), mLC, Ctx()).status)
        assertEquals("abstain", computeFault(ans("c252_me_B"), mKeep, Ctx()).status)
        assertEquals("abstain", computeFault(ans("c252_2"), mKeep, Ctx()).status)
    }

    @Test @DisplayName("유형 확률이 낮으면 판단 보류")
    fun l326() {
        val m = analyzeWindow(sc("me_late_signal").frames, urban())
        val answers = obj("chart" to obj("choice" to "c252_me_B", "probabilities" to obj("c252_me_B" to 0.45, "c252_2" to 0.4, "none" to 0.15)), "evidence_sufficient" to noul(0.9))
        val r = computeFault(answers, m, Ctx())
        assertEquals("abstain", r.status)
        ok(r.evidenceChecklist.size >= 3)
    }

    @Test @DisplayName("2순위 해석(역할 포함 이름)과 \"해당 없음\" 가능성을 함께 보여준다")
    fun l334() {
        val m = analyzeWindow(sc("me_late_signal").frames, urban())
        val answers = LinkedHashMap(fullB).apply {
            put("chart", obj("choice" to "c252_me_B", "probabilities" to obj("c252_me_B" to 0.62, "c252_2" to 0.1, "none" to 0.28)))
        }
        val r = computeFault(answers, m, Ctx(laneChangeConfirmed = true))
        assertEquals("estimate", r.status)
        ok(r.caveats.anyMatch("해당하지 않을 가능성도 28%"))
        val answers2 = LinkedHashMap(answers).apply {
            put("chart", obj("choice" to "c252_me_B", "probabilities" to obj("c252_me_B" to 0.65, "c252_me_A" to 0.3, "none" to 0.05)))
        }
        val r2 = computeFault(answers2, m, Ctx(laneChangeConfirmed = true))
        assertEquals(Charts.CHART_OPTIONS["c252_me_A"]!!.label, r2.alternative!!.title)
    }

    @Test @DisplayName("동시 진로변경(252-2)은 진술 근거라 252(내 차 B)로 계산하고 50%를 범위 아래쪽 끝으로만 넣는다")
    fun l347() {
        val s = sc("simultaneous")
        val out = runLocal(s.frames, s.ctx)
        assertEquals("c252_2", out.result.judgedOption)
        assertEquals("c252_me_B", out.result.option)
        val card = out.flow.cards.first { it.kind == "estimate" }
        assertMatch(card.title, "50~70%")
        ok(card.lines.anyMatch("가능성: 상대 차도 동시에 진로변경"))
        // "~하는 동시에"는 동시 진로변경이 아니다
        for (st in listOf("깜빡이 켜는 동시에 들어갔어요", "브레이크 밟는 동시에 부딪혔어요")) {
            assertEquals(false, parseStatement(st).simultaneous, st)
            val o = runLocal(s.frames, confirmed(s.ctx.copy(driverStatement = st)))
            eqNum(70, o.result.low, st)
        }
        assertEquals(true, parseStatement("제가 들어가는데 옆 차도 동시에 들어왔어요").simultaneous)
    }

    // ---------- 대체 판정기(진술 해석) ----------
    @Test @DisplayName("진술 해석: 주어·부정·신호등 혼동·교차로/주차장")
    fun l365() {
        val P = ::parseStatement
        var c = P("내가 깜빡이 안 켜고 들어갔어")
        assertEquals(true, c.selfNoSignal); assertEquals(false, c.otherNoSignal); assertEquals(true, c.selfLC)
        c = P("오른쪽 차가 깜빡이도 안 켜고 갑자기 들어왔어")
        assertEquals(true, c.otherNoSignal); assertEquals(true, c.otherLC)
        assertEquals(false, P("신호가 없었어").otherNoSignal)
        assertEquals(true, P("교차로에서 신호등이 없었는데 옆에서 차가 나왔어").otherType)
        assertEquals(false, P("오른쪽 차가 들어왔어. 실선은 아니고 점선이었어").solidLine)
        assertEquals(true, P("실선 구간이었는데 들어가다가 박았어").solidLine)
        assertEquals(false, P("버스전용차로는 아니었어").busLane)
        assertEquals(false, P("동시에 들어간 건 아니고 내가 먼저 들어갔어").simultaneous)
        assertEquals(true, P("옆 차랑 동시에 같은 차로로 들어가다가 부딪혔어").simultaneous)
        assertEquals(false, P("상대가 깜빡이를 안 켰는지 잘 모르겠어").otherNoSignal)
        assertEquals(true, P("끼어들려다가 부딪혔어").selfLC)
        assertEquals(true, P("옆 차가 차선 바꾸다가 박았어").otherLC)
    }

    @Test @DisplayName("진술 해석 결과: 자기 진술과 기록이 어긋나면 보류, 교차로·주차장은 도표 밖")
    fun l383() {
        val straight = sc("other_cutin_unclear").frames
        for (st in listOf("내가 깜빡이 안 켜고 들어갔어", "주차장에서 나오는데 옆 차가 들어왔어", "교차로에서 신호등이 없었는데 옆에서 차가 나왔어")) {
            assertEquals("abstain", runLocal(straight, urban(st)).result.status, st)
        }
    }

    @Test @DisplayName("대체 판정기의 진술 Noul 은 0.6 이하 → 진술 키워드만으로 과실이 0%가 되지 않는다")
    fun l391() {
        val straight = sc("other_cutin_unclear").frames
        val out = runLocal(straight, keptLane(urban("상대가 깜빡이 없이 실선에서 버스전용차로로 끼어들었어")))
        for (k in listOf("other_signal_fail", "forbidden_zone", "bus_lane")) ok((path(out.judged!!.answers, k, "noul") as Double) <= 0.6)
        ok(out.result.modifiers.all { it.decision != "applied" })
        eqNum(30, out.result.likely)
    }

    // ---------- Jev 연동 ----------
    @Test @DisplayName("Jev 요청 모양: Choice 선택지 4개(none 포함), Noul 5개(본인 진로변경 인정 포함), 원시 숫자 배열 없음")
    fun l400() {
        val s = SCENARIOS[0]
        val m = analyzeWindow(s.frames, s.ctx)
        val req = buildJevRequest(renderFacts(m, s.ctx)).toJs()
        assertEquals(listOf("c252_2", "c252_me_A", "c252_me_B", "none"), (path(req, "questions", "chart", "criteria") as Map<*, *>).keys.map { it as String }.sorted())
        assertMatch(path(req, "questions", "chart", "instructions") as String, "none을 고르세요")
        val questions = path(req, "questions") as Map<*, *>
        assertEquals(5, questions.values.count { (it as Map<*, *>)["type"] == "noul" })
        ok(questions["self_lane_change"] != null)
        ok((path(req, "state", "vehicle_facts") as List<*>).all { it is String })
        ok(json(req).length < 4500)
    }

    @Test @DisplayName("Jev 응답 검증: 선택지 밖 답·프로토타입 키·확률 키 오류는 거부")
    fun l413() {
        val req = buildJevRequest(Facts(vehicle = emptyList(), place = emptyList(), driverStatement = "", driverConfirmation = null)).toJs()
        val okNoul = obj("evidence_sufficient" to noul(0.5), "self_lane_change" to noul(0.5), "other_signal_fail" to noul(0.5), "forbidden_zone" to noul(0.5), "bus_lane" to noul(0.5))
        fun body(chart: Map<String, Any?>) = obj("answers" to LinkedHashMap(okNoul).apply { put("chart", chart) })
        assertThrows(Exception::class.java) { validateJevAnswers(req, body(obj("choice" to "zzz", "probabilities" to obj()))) }
        assertThrows(Exception::class.java) { validateJevAnswers(req, body(obj("choice" to "constructor", "probabilities" to obj("constructor" to 1.0)))) }
        assertThrows(Exception::class.java) { validateJevAnswers(req, body(obj("choice" to "none", "probabilities" to obj("none" to 0.5, "hack" to 0.5)))) }
        assertThrows(Exception::class.java) { validateJevAnswers(req, body(obj("choice" to "none", "probabilities" to obj("none" to 1.5)))) }
        assertDoesNotThrow { validateJevAnswers(req, body(obj("choice" to "none", "probabilities" to obj("none" to 0.9, "c252_me_A" to 0.1)))) }
    }

    @Test @DisplayName("Jev 호출 실패 시 대체 판정기로 내려가고, 결과와 안내 카드에 표시한다")
    fun l423() {
        val s = SCENARIOS[0]
        val failing = failingJev("x", 503)
        val out = run(s.frames, s.ctx.copy(now = FIXED_NOW), failing)
        assertMatch(out.judgeError, "503")
        assertEquals("local-standin", out.judged!!.model)
        assertEquals(listOf(true, true), listOf(out.result.judge!!.standIn, out.result.judge!!.fallback))
        eqNum(80, out.result.likely)
        val card = out.flow.cards.first { it.kind == "estimate" }
        ok(card.lines.anyMatch("규칙 기반 대체 판정.*연결 실패"))
        val strict = run(s.frames, s.ctx.copy(now = FIXED_NOW), failing, RunOpts(onJudgeFailure = "abstain"))
        assertEquals("abstain", strict.result.status)
    }

    @Test @DisplayName("Jev 정상 응답을 그대로 쓴다 (모의 fetch) — 상대 신호는 진술 근거라 범위로")
    fun l437() {
        val s = sc("other_cutin_unclear")
        var sentUrl: String? = null
        var sentHeaders: Map<String, String>? = null
        var sentBody: String? = null
        val fake = JevTransport { url, headers, body, _ ->
            sentUrl = url; sentHeaders = headers; sentBody = body
            JevHttpResponse(true, 200) {
                fun n(x: Double) = obj("type" to "noul", "noul" to x)
                obj(
                    "provider" to "TypeSafe",
                    "answers" to obj(
                        "chart" to obj(
                            "type" to "choice", "choice" to "c252_me_A",
                            "probabilities" to obj("c252_me_A" to 0.91, "none" to 0.05, "c252_2" to 0.03, "c252_me_B" to 0.01), "confidence" to 0.88,
                        ),
                        "evidence_sufficient" to n(0.82),
                        "self_lane_change" to n(0.05),
                        "other_signal_fail" to n(0.86),
                        "forbidden_zone" to n(0.04),
                        "bus_lane" to n(0.03),
                    ),
                )
            }
        }
        val out = run(s.frames, s.ctx.copy(now = FIXED_NOW), JevJudge(apiKey = "k", transport = fake))
        assertEquals("https://api.typesafe.ai/v1/systemone", sentUrl)
        assertEquals(JEV_ENDPOINT, sentUrl)
        assertEquals("Bearer k", sentHeaders!!["Authorization"])
        assertEquals("jev-latest", Json.parseToJsonElement(sentBody!!).jsonObject["model"]!!.jsonPrimitive.content)
        assertNull(out.judgeError)
        assertEquals("jev-latest", out.judged!!.model) // 응답에 model 이 없으면 요청 값으로 채운다
        assertEquals(false, out.result.judge!!.standIn)
        assertEquals(nums(20, 30, 30), listOf(out.result.low, out.result.likely, out.result.high))
    }

    // ---------- 봉인 ----------
    @Test @DisplayName("같은 기록은 같은 봉인 지문, 한 프레임만 바뀌어도 다른 지문")
    fun l465() {
        val s = SCENARIOS[0]
        val m = analyzeWindow(s.frames, s.ctx)
        val a = sealEvidence(s.frames, m, s.ctx.copy(now = FIXED_NOW))
        val b = sealEvidence(s.frames, m, s.ctx.copy(now = FIXED_NOW))
        val tampered = s.frames.mapIndexed { i, f -> if (i == 95) f.copy(turnSignal = str(if (f.ts == "NONE") "RIGHT" else "NONE")) else f }
        val c = sealEvidence(tampered, m, s.ctx.copy(now = FIXED_NOW))
        assertEquals(a.hash, b.hash)
        assertNotEquals(a.hash, c.hash)
    }

    @Test @DisplayName("순수 JS SHA-256 대체 구현이 표준 결과와 같다")
    fun l476() {
        // JS 는 sha256Fallback 을 node:crypto 와 비교한다. Kotlin 은 봉인 지문 함수(Canonical.sha256Hex)를 MessageDigest 와 비교한다.
        for (s in listOf("", "abc", "퍼스트30 사고 기록", "x".repeat(55), "y".repeat(56), "z".repeat(1000))) {
            val want = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            assertEquals(want, Canonical.sha256Hex(s.toByteArray(Charsets.UTF_8)), s.take(10))
            assertEquals(want, Canonical.sha256Hex(s), s.take(10))
        }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Canonical.sha256Hex("abc"))
    }

    // ---------- 안전 안내 ----------
    @Test @DisplayName("부상 신고만 있으면 \"에어백\" 음성이 나오지 않고, 고속도로 긴급 상황엔 대피 안내가 함께 나온다")
    fun l485() {
        val straight = sc("other_cutin_unclear").frames
        val injured = runLocal(straight, urban().copy(injuryReported = true))
        assertEquals("emergency", injured.flow.mode)
        assertNoMatch(injured.flow.cards[0].tts, "에어백")
        val hwAirbag = runLocal(straight.map { f -> f.copy(airbag = bool(f.tMs >= -50)) }, highway())
        assertEquals("emergency", hwAirbag.flow.mode)
        ok(hwAirbag.flow.cards[0].lines.anyMatch("가드레일 밖"))
        assertMatch(hwAirbag.flow.cards[0].tts, "가드레일")
    }

    @Test @DisplayName("과실 카드에는 현저한 과실·중과실 미반영과 참고치 고지가 항상 붙는다")
    fun l496() {
        for (s in SCENARIOS) {
            val out = runLocal(s.frames, s.ctx)
            val card = out.flow.cards.firstOrNull { it.kind == "estimate" } ?: continue
            ok(card.lines.anyMatch("현저한 과실·중과실은 반영하지 않았어요"), s.id)
            ok(card.lines.anyMatch("참고치"), s.id)
        }
    }

    @Test @DisplayName("안내 문구는 과장·허위 신고를 권하지 않는다")
    fun l506() {
        val banned = "(뒷목|아픈 ?척|다친 ?척|드러누|입원|진단서|합의금|보험금을? (더|많이)|한방병원|목 잡|과장해)"
        val statements = listOf("", "옆 차가 들어왔어", "뒤차가 박았어 목이 아파", "보험금 많이 받으려면 어떻게 해")
        for (s in SCENARIOS) {
            for (st in statements) {
                val out = runLocal(s.frames, s.ctx.copy(driverStatement = st))
                val text = json(out.flow.toJs()).replaceFirst(SPEECH_PRINCIPLE, "")
                assertNoMatch(text.replace(Regex("운전자 본인 진술[^\"]*\"[^\"]*\""), ""), banned, "${s.id} / $st")
            }
        }
    }
}

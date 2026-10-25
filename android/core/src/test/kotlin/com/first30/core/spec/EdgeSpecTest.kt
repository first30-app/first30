package com.first30.core.spec

import com.first30.core.engine.RunOpts
import com.first30.core.engine.analyzeWindow
import com.first30.core.engine.parseStatement
import com.first30.core.engine.renderFacts
import com.first30.core.engine.run
import com.first30.core.model.Ctx
import com.first30.core.model.Frame
import com.first30.core.model.JsMath
import com.first30.core.model.JsScalar
import com.first30.core.scenarios.SpeedProfile
import com.first30.core.spec.Spec.FIXED_NOW
import com.first30.core.spec.Spec.PI
import com.first30.core.spec.Spec.SCENARIOS
import com.first30.core.spec.Spec.anyMatch
import com.first30.core.spec.Spec.assertMatch
import com.first30.core.spec.Spec.assertNoMatch
import com.first30.core.spec.Spec.bool
import com.first30.core.spec.Spec.bump
import com.first30.core.spec.Spec.confirmed
import com.first30.core.spec.Spec.failingJev
import com.first30.core.spec.Spec.hd
import com.first30.core.spec.Spec.highway
import com.first30.core.spec.Spec.json
import com.first30.core.spec.Spec.lc
import com.first30.core.spec.Spec.num
import com.first30.core.spec.Spec.ok
import com.first30.core.spec.Spec.path
import com.first30.core.spec.Spec.physFrames
import com.first30.core.spec.Spec.ramp
import com.first30.core.spec.Spec.rampDown
import com.first30.core.spec.Spec.roadFrames
import com.first30.core.spec.Spec.runLocal
import com.first30.core.spec.Spec.sc
import com.first30.core.spec.Spec.sd
import com.first30.core.spec.Spec.sig
import com.first30.core.spec.Spec.str
import com.first30.core.spec.Spec.synth
import com.first30.core.spec.Spec.tMs
import com.first30.core.spec.Spec.ts
import com.first30.core.spec.Spec.urban
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** engine.test.js "2차·3차·4차 검토 경계 상황" (518~917행). Method names = JS line numbers. */
class EdgeSpecTest {
    private fun notMeB(out: com.first30.core.engine.RunOutput) = !(out.result.status == "estimate" && out.result.option == "c252_me_B")
    private fun notMeA(out: com.first30.core.engine.RunOutput) = !(out.result.status == "estimate" && out.result.option == "c252_me_A")

    // ---------- 2차 검토 경계 상황 ----------
    @Test @DisplayName("곡선 진입·진출(차로 유지)은 \"내 차 진로변경\"으로 단정하지 않는다")
    fun l531() {
        val cases = listOf(
            100.0 to ramp(-4.0, -2.0, 1.0 / 1500), 100.0 to ramp(-4.0, -2.5, 1.0 / 1000), 80.0 to ramp(-3.5, -2.0, 1.0 / 600), 60.0 to ramp(-3.0, -1.5, 1.0 / 400),
            100.0 to rampDown(-4.0, -2.0, 1.0 / 1500), 80.0 to rampDown(-3.5, -2.0, 1.0 / 600), 60.0 to rampDown(-3.0, -1.5, 1.0 / 400),
        )
        for ((kph, k) in cases) {
            for (steer in listOf(true, false)) {
                val out = runLocal(roadFrames(kph, k, steer), urban("오른쪽 차가 갑자기 들어왔어"))
                assertNotEquals("detected", out.metrics.laneChange.status, "$kph steer=$steer")
                ok(notMeB(out))
            }
        }
    }

    @Test @DisplayName("차선 안 흔들림(방향 ±0.3~1°, 주기 4~8초)은 어떤 위상에서도 \"내 차 진로변경\"이 되지 않는다")
    fun l543() {
        for (kph in listOf(60.0, 100.0, 120.0)) for ((A, P) in listOf(0.5 to 4.0, 0.8 to 8.0, 1.0 to 5.0, 0.6 to 6.0)) {
            var ph = 0.0
            while (ph < 1) {
                val fr = synth(kph).map { f ->
                    val t = f.tMs / 1000
                    f.copy(
                        headingDeg = num(72 + A * JsMath.sin(2 * PI * (t / P + ph))),
                        steeringDeg = num(JsMath.round(2 * A * JsMath.cos(2 * PI * (t / P + ph)) * 10) / 10),
                    )
                }
                val out = runLocal(fr, highway("옆 차가 들어왔어"))
                assertNotEquals("detected", out.metrics.laneChange.status, "$kph ±$A P$P ph$ph")
                ph += 1.0 / 8
            }
        }
    }

    @Test @DisplayName("회피 조향(충돌 1~2초 전 한쪽으로 꺾으며 제동)은 진로변경으로 단정하지 않는다")
    fun l556() {
        for ((kph, t0, peak) in listOf(Triple(60.0, -1.0, 4.0), Triple(60.0, -1.4, 4.0), Triple(60.0, -1.6, 4.0), Triple(80.0, -2.0, 3.0), Triple(50.0, -1.5, 6.0))) {
            val fr = synth(kph, brakeFromS = t0 + 0.2).map { f ->
                val t = f.tMs / 1000
                f.copy(
                    headingDeg = num(72 - (if (t < t0) 0.0 else peak * Math.min(1.0, (t - t0) / 0.6))),
                    steeringDeg = num(if (t < t0) 0.0 else -8 * Math.min(1.0, (t - t0) / 0.3)),
                )
            }
            val out = runLocal(fr, urban("오른쪽 차가 깜빡이도 안 켜고 갑자기 들어와서 왼쪽으로 피했는데 박았어"))
            assertEquals("abstain", out.result.status, "$kph $t0")
        }
    }

    @Test @DisplayName("핸들 기록이 없으면 진행 방향만으로 진로변경을 확정하지 않는다 (신호 거리 부풀림도 막힘)")
    fun l567() {
        val fr = synth(100.0, laneChange = lc(-3.0, 6.0, "RIGHT"), signal = sig(-5.9, "RIGHT")).map { it.copy(steeringDeg = JsScalar.Null) }
        val out = runLocal(fr, highway("들어가다 닿았어"))
        assertEquals("uncertain", out.metrics.laneChange.status)
        assertEquals("no_steering", out.metrics.laneChange.reason)
        assertEquals("abstain", out.result.status)
    }

    @Test @DisplayName("기록이 8초보다 짧으면 차로변경을 측정하지 않는다")
    fun l575() {
        val s = sc("me_late_signal")
        val out = runLocal(s.frames.filter { it.tMs >= -3000 }, s.ctx)
        assertEquals("unmeasurable", out.metrics.laneChange.status)
        assertEquals("short_buffer", out.metrics.laneChange.reason)
        assertEquals("abstain", out.result.status)
        ok(!out.facts!!.vehicle.anyMatch("차로를 유지했음"))
    }

    @Test @DisplayName("방향지시등·브레이크 채널이 없거나 숫자 enum 이면 \"켜지 않음\"으로 단정하지 않는다")
    fun l584() {
        val s = sc("proper_signal")
        for (v in listOf<JsScalar>(JsScalar.Null, JsScalar.Undefined)) {
            val out = runLocal(s.frames.map { it.copy(turnSignal = v, brake = v) }, s.ctx)
            assertEquals("unavailable", out.metrics.signal.status)
            // 신호를 켰는지 모르면 70% 하나로 단정하지 않고 70~80% 범위로
            assertEquals("possible", out.result.modifiers.first { it.id == "B_signal" }.decision)
            assertEquals(Spec.nums(70, 70, 80), listOf(out.result.low, out.result.likely, out.result.high))
            ok(out.facts!!.vehicle.contains("방향지시등 기록이 없어 신호를 켰는지 확인할 수 없음"))
            ok(out.facts!!.vehicle.contains("브레이크 기록이 없어 확인할 수 없음"))
            ok(!out.facts!!.vehicle.anyMatch("켜지 않"))
        }
        val numeric = runLocal(s.frames.map { it.copy(turnSignal = num(if (it.ts == "RIGHT") 2.0 else 0.0)) }, s.ctx)
        assertEquals("unavailable", numeric.metrics.signal.status)
        val lower = runLocal(s.frames.map { it.copy(turnSignal = str(if (it.ts == "RIGHT") "right" else "off")) }, s.ctx)
        assertEquals("ok", lower.metrics.signal.status)
    }

    @Test @DisplayName("음성·버튼·급감속·알 수 없는 트리거는 충돌 시각을 모른다 → 진로변경을 측정하지 않고 숫자도 내지 않는다")
    fun l602() {
        // 직진 중 충돌 → 2초 뒤 오른쪽 신호를 켜고 갓길로 이동 → 정차 → "사고 났어"
        val fr = synth(
            SpeedProfile { t -> if (t < -6) 60.0 else Math.max(0.0, 60 - (t + 6) * 25) }, fromS = -20.0,
            laneChange = lc(-12.0, 4.0, "RIGHT"), signal = sig(-12.5, "RIGHT"),
        )
        for (trigger in listOf("voice", "button", "decel", "VOICE", "speech", "gsensor", null)) {
            val out = runLocal(fr, urban("오른쪽 차가 갑자기 들어왔어요").copy(trigger = trigger))
            assertEquals(false, out.metrics.impact.known, trigger.toString())
            assertEquals("impact_unknown", out.metrics.laneChange.reason)
            assertEquals("abstain", out.result.status)
            ok(out.result.reasons.anyMatch("사고 뒤 갓길로 옮긴 것인지"))
            // 사실 문장·봉인값에 진로변경·신호 판단을 남기지 않는다
            ok(!out.facts!!.vehicle.anyMatch("움직이고 있었음|방향지시등을 켰음|켜고 있었음"), out.facts!!.vehicle.joinToString(" / "))
            assertEquals("unmeasurable", path(out.seal!!.payload, "metrics", "laneChange", "status"))
            assertEquals(trigger?.lowercase() ?: "unknown", out.seal!!.payload["trigger"])
            val sealCard = out.flow.cards.first { it.kind == "evidence" }
            assertMatch(sealCard.lines[0], "^신고 전 20초 차량 기록을 봉인했어요")
        }
        // 에어백·충격 감지만 충돌 시각으로 믿는다 (대소문자 무관)
        val s = sc("me_late_signal")
        for (trigger in listOf("impact", "AIRBAG", " Impact ")) {
            val out = runLocal(s.frames, s.ctx.copy(trigger = trigger))
            assertEquals("estimate", out.result.status, trigger)
            assertMatch(out.flow.cards.first { it.kind == "evidence" }.lines[0], "^충돌 전 10초")
        }
    }

    @Test @DisplayName("진술 해석 2: 제 차·저희 차, 뒤에 오는 주어, \"아닌/아님\", 얼버무림")
    fun l628() {
        val P = ::parseStatement
        var c = P("제 차가 깜빡이 없이 들어갔어요")
        assertEquals(listOf(true, true, false, false), listOf(c.selfLC, c.selfNoSignal, c.otherNoSignal, c.otherLC))
        assertEquals(true, P("저희 차가 차선을 바꾸다가 부딪혔어요").selfLC)
        c = P("끼어든 건 저예요"); assertEquals(listOf(true, false), listOf(c.selfLC, c.otherLC))
        c = P("깜빡이를 늦게 켠 건 저예요"); assertEquals(listOf(true, false), listOf(c.selfLateSignal, c.otherLateSignal))
        c = P("옆 차가 들어온 게 아니라 제가 들어갔어요"); assertEquals(listOf(true, false), listOf(c.selfLC, c.otherLC))
        c = P("차선 변경하던 옆 차가 저를 박았어요"); assertEquals(listOf(true, false), listOf(c.otherLC, c.selfLC))
        assertEquals(false, P("오른쪽 차가 들어왔어요. 실선이 아닌 점선이었어요").solidLine)
        assertEquals(true, P("오른쪽 차가 들어왔어요. 실선이 아닌 점선이었어요").otherLC)
        assertEquals(false, P("옆 차가 들어왔어요 실선 아님").solidLine)
        assertEquals(true, P("옆 차가 들어왔어요 실선 아님").otherLC)
        assertEquals(false, P("버스전용차로가 아닌 일반 차로였어요").busLane)
        assertEquals(false, P("상대가 깜빡이를 안 켰다는 건 아니고요 잘 모르겠어요").otherNoSignal)
    }

    @Test @DisplayName("고속도로 안내는 대피와 부딪히지 않고, 긴급 상황엔 10초에 과실을 말하지 않는다")
    fun l645() {
        val straight = sc("other_cutin_unclear").frames
        val hw = runLocal(straight, highway(""))
        assertNoMatch(json(hw.flow.toJs()), "사진 먼저 찍고|노면 표시\\(실선/점선\\) 사진")
        val em = runLocal(straight.map { f -> f.copy(airbag = bool(f.tMs >= -50)) }, highway("옆 차가 들어왔어"))
        assertEquals("rescue", em.flow.cards[2].kind)
        assertNoMatch(em.flow.cards[2].tts, "퍼센트")
        val fault = em.flow.cards.first { it.kind == "estimate" || it.kind == "abstain" }
        assertEquals(25, fault.at)
        assertNoMatch(fault.tts, "퍼센트")
    }

    @Test @DisplayName("동승자가 있으면 안전 안내에 함께 넣는다")
    fun l658() {
        val out = runLocal(SCENARIOS[0].frames, SCENARIOS[0].ctx.copy(occupants = 1))
        ok(out.flow.cards[0].lines.anyMatch("동승자"))
    }

    @Test @DisplayName("판정 모델에 보내는 진술에서 차량 번호·전화번호를 지운다")
    fun l663() {
        val m = analyzeWindow(SCENARIOS[0].frames, urban())
        val facts = renderFacts(m, urban("12가 3456 차가 들어왔어요 제 번호는 010-1234-5678"))
        assertNoMatch(facts.driverStatement, "3456|1234-5678")
        assertMatch(facts.driverStatement, "\\[차량번호\\].*\\[전화번호\\]")
    }

    @Test @DisplayName("판정 모델 실패 + 보류 모드에서는 \"대체 판정을 썼어요\" 문구를 붙이지 않는다")
    fun l670() {
        val s = SCENARIOS[0]
        val out = run(s.frames, s.ctx.copy(now = FIXED_NOW), failingJev("x", 500), RunOpts(onJudgeFailure = "abstain"))
        val card = out.flow.cards.first { it.kind == "abstain" }
        ok(!card.lines.anyMatch("대체 판정을 썼어요"))
    }

    // ---------- 3차 검토 경계 상황 ----------
    @Test @DisplayName("짧은 굽은 길·교차로 차로 어긋남(방향지시등 없음)은 \"내 차 진로변경\"으로 단정하지 않는다")
    fun l679() {
        // 시속 60km, 충돌 3.5~1초 전에 약 3° 꺾이는 굽은 길 (핸들은 꺾었다 되돌림)
        val bend = roadFrames(60.0, { t -> if (t >= -3.5 && t < -1) 1.0 / 800 else 0.0 })
        val o1 = runLocal(bend, urban("옆 차가 들어왔어요"))
        assertNotEquals("detected", o1.metrics.laneChange.status, o1.metrics.laneChange.toJs().toString())
        ok(notMeB(o1))
        // 차로가 옆으로 약 3m 어긋나는 교차로 (S자, 핸들 왼쪽→오른쪽)
        val jog = synth(40.0).map { f ->
            val t = f.tMs / 1000
            val dev = if (t >= -3 && t < -1.5) 4 * JsMath.sin(PI * (t + 3) / 1.5) else if (t >= -1.5 && t < 0) -4 * JsMath.sin(PI * (t + 1.5) / 1.5) else 0.0
            val st = if (t >= -3 && t < -1.5) 5 * JsMath.cos(PI * (t + 3) / 1.5) else if (t >= -1.5 && t < 0) -5 * JsMath.cos(PI * (t + 1.5) / 1.5) else 0.0
            f.copy(headingDeg = num(72 + dev), steeringDeg = num(JsMath.round(st * 10) / 10))
        }
        ok(notMeB(runLocal(jog, urban("옆 차가 들어왔어요"))))
    }

    @Test @DisplayName("S자 회피(먼저 제동, 왼쪽으로 피했다 바로잡음)는 진로변경으로 단정하지 않는다")
    fun l696() {
        val fr = synth(60.0, brakeFromS = -2.4).map { f ->
            val t = f.tMs / 1000
            val dev = if (t >= -1.8 && t < -0.6) -3 * JsMath.sin(PI * (t + 1.8) / 1.2) else 0.0
            val st = if (t >= -1.8 && t < -0.6) -6 * JsMath.cos(PI * (t + 1.8) / 1.2) else 0.0
            f.copy(headingDeg = num(72 + dev), steeringDeg = num(JsMath.round(st * 10) / 10))
        }
        val out = runLocal(fr, urban("오른쪽 차가 갑자기 들어와서 피했어요"))
        ok(notMeB(out), out.metrics.laneChange.toJs().toString())
    }

    @Test @DisplayName("느린 고속도로 진로변경(7.5초, 신호 켬)이 \"차로 유지\"로 바뀌지 않는다")
    fun l707() {
        val fr = synth(100.0, laneChange = lc(-6.5, 7.5, "RIGHT"), signal = sig(-9.0, "RIGHT"))
        val out = runLocal(fr, highway("옆 차가 들어왔어요"))
        assertNotEquals("none", out.metrics.laneChange.status)
        ok(notMeA(out))
    }

    @Test @DisplayName("도로 유형을 몰라도 시속 80km 이상이면 고속도로 안전 안내(대피 먼저)")
    fun l714() {
        val out = runLocal(synth(100.0), Ctx(driverStatement = ""))
        assertEquals("highway", out.flow.mode)
        assertMatch(out.flow.cards[0].title, "비트박스")
        assertNoMatch(json(out.flow.toJs()), "사진부터 찍고")
    }

    @Test @DisplayName("핸들 부호가 반대로 들어오면 핸들 기록을 쓰지 않고 그 사실을 남긴다")
    fun l721() {
        val s = sc("me_late_signal")
        val out = runLocal(s.frames.map { it.copy(steeringDeg = num(-it.sd)) }, s.ctx)
        assertEquals("reversed", out.metrics.quality.steeringSign)
        assertNotEquals("detected", out.metrics.laneChange.status)
        ok(out.facts!!.vehicle.anyMatch("부호가 반대로"))
    }

    @Test @DisplayName("2Hz 프레임에서도 방향지시등 선행 거리가 크게 틀리지 않는다")
    fun l729() {
        val frames = synth(50.0, laneChange = lc(-2.2, 4.0, "RIGHT"), signal = sig(-5.2, "RIGHT")).filter { it.tMs % 500 == 0.0 }
        val m = analyzeWindow(frames, urban())
        assertEquals("detected", m.laneChange.status, m.laneChange.toJs().toString())
        ok(abs(m.signal.leadM!! - 42) <= 8, m.signal.leadM.toString()) // 실제 약 42m
        assertEquals("ok", m.signal.status)
    }

    @Test @DisplayName("진술 해석 3: 쉼표 뒤 주어, \"안 했어요\", \"제가 아니라\", 얼버무림, 위반 정황 없는 전용차로")
    fun l738() {
        val P = ::parseStatement
        assertEquals(true, P("끼어들었어요, 제가").selfLC)
        assertEquals(false, P("끼어들었어요, 제가").otherLC)
        assertEquals(false, P("저는 차선 변경 안 했어요").selfLC)
        val c = P("차선 바꾼 건 제가 아니라 상대예요")
        assertEquals(listOf(true, false), listOf(c.otherLC, c.selfLC))
        assertEquals(false, P("실선이었는지 모르겠어요").solidLine)
        assertEquals(false, P("실선 넘어간 거 아니에요").solidLine)
        assertEquals(false, P("버스가 전용차로에서 나오다가 부딪혔어요").busLane)
        assertEquals(true, P("상대가 버스전용차로를 타고 오다가 끼어들었어").busLane)
        assertEquals(true, P("동시에 차선을 바꿨어요").simultaneous)
    }

    // ---------- 4차 검토 경계 상황 ----------
    @Test @DisplayName("같은 쪽 신호를 켠 채 굽은 길을 따라간 \"차로 유지\"는 어떤 진술에도 \"내 차 진로변경\" 숫자가 나오지 않는다")
    fun l767() {
        // 시속 60km, 충돌 3.5~1초 전 오른쪽으로 약 3° 굽은 길, 오른쪽 신호는 10초 전부터(끼어들 틈 대기) 또는 0.5초 전
        val k = (3 * PI / 180) / (60 / 3.6) / (2.5 * 2 / PI)
        for (signal in listOf(sig(-10.0, "RIGHT"), sig(-0.5, "RIGHT"))) {
            val fr = physFrames(60.0, roadK = bump(-3.5, -1.0, k), signal = signal)
            for (st in listOf("왼쪽 차가 갑자기 들어왔어요", "옆 차가 들어왔어요", "", "저는 차선 안 바꿨고 깜빡이만 켜 놨어요", "차선 바꾸려고 깜빡이 켜고 기다리는데 박았어요")) {
                val out = runLocal(fr, urban(st))
                ok(notMeB(out), "${signal.fromS} \"$st\" ${out.metrics.laneChange.toJs()}")
            }
            // 핸들이 가운데로 돌아왔는데 방향이 새 값에 머물러 있으면 굽은 길로 본다
            assertEquals("bend_hold", runLocal(fr, urban("")).metrics.laneChange.reason)
        }
    }

    @Test @DisplayName("\"내 차 진로변경\"은 운전자 확인이 있어야 숫자가 나온다 — 없으면 확인 질문, \"아니요\"면 보류")
    fun l781() {
        val s = sc("me_late_signal")
        val ask = runLocal(s.frames, s.ctx.copy(laneChangeConfirmed = null, driverStatement = "옆 차선 차랑 부딪혔어"))
        assertEquals("abstain", ask.result.status)
        assertMatch(ask.result.confirm!!.question, "오른쪽 차로로 진로를 바꾸던 중이었나요")
        val card = ask.flow.cards.first { it.kind == "abstain" }
        ok(card.confirm != null)
        assertMatch(card.title, "확인이 필요해요")
        assertMatch(card.tts, "예 또는 아니요")
        ok(ask.facts!!.vehicle.anyMatch("굽은 길이나 차로가 어긋난 곳"))
        val yes = runLocal(s.frames, confirmed(s.ctx.copy(driverStatement = "")))
        assertEquals(listOf<Any?>("estimate", "c252_me_B", 80.0), listOf<Any?>(yes.result.status, yes.result.option, yes.result.likely))
        ok(yes.result.caveats.anyMatch("\"그때 오른쪽 차로로 진로를 바꾸던 중이었나요\\?\"에 \"예\""), yes.result.caveats.joinToString(" / "))
        assertEquals("운전자 확인 답변: \"그때 오른쪽 차로로 진로를 바꾸던 중이었나요?\"에 \"예\"", yes.request!!.driverConfirmation)
        val no = runLocal(s.frames, s.ctx.copy(laneChangeConfirmed = false))
        assertEquals("abstain", no.result.status)
        assertNull(no.result.confirm)
        ok(no.result.reasons.anyMatch("\"아니요\"라고 답했음"), no.result.reasons.joinToString(" / "))
        assertEquals("운전자 확인 답변: \"그때 오른쪽 차로로 진로를 바꾸던 중이었나요?\"에 \"아니요\"", no.request!!.driverConfirmation)
        // 질문은 기록 내용을 앞세우지 않고 그대로 묻는다 (유도 질문 방지). 굽은 길을 따라간 경우의 답만 덧붙여 질문의 뜻을 분명히 한다.
        assertEquals("그때 오른쪽 차로로 진로를 바꾸던 중이었나요? 예 또는 아니요로 답해 주세요. 굽은 길이나 차로를 따라 달린 거라면 아니요예요.", card.tts)
        // 고속도로에서는 대피가 먼저 — 질문은 대피 뒤로 미룬다
        val hw = sc("highway_late_signal")
        val hwAsk = runLocal(hw.frames, hw.ctx.copy(laneChangeConfirmed = null, driverStatement = ""))
        val hwCard = hwAsk.flow.cards.first { it.kind == "abstain" }
        ok(hwCard.confirm != null)
        assertMatch(hwCard.tts, "대피한 곳에서 기다리고")
        assertNoMatch(hwCard.tts, "답해 주세요") // 대피한 운전자를 차로 다시 부르지 않는다
    }

    @Test @DisplayName("\"상대 진로변경(A)\"은 충돌 직전 내 방향지시등이 켜져 있거나 본인이 진로변경을 말하면 내지 않는다")
    fun l811() {
        val s = sc("other_cutin_unclear")
        val withSignal = s.frames.map { it.copy(turnSignal = str(if (it.tMs >= -2000) "LEFT" else "NONE")) }
        val a = runLocal(withSignal, s.ctx)
        assertEquals("none", a.metrics.laneChange.status)
        assertEquals("abstain", a.result.status)
        ok(a.result.reasons.anyMatch("방향지시등이 켜져 있어"))
        ok(a.facts!!.vehicle.anyMatch("왼쪽 방향지시등이 켜져 있었음"))
        for (st in listOf("제가 차선 바꾸는데 옆 차도 들어왔어요", "저희가 들어가는데 옆에서도 동시에 들어오더라고요")) {
            assertEquals("abstain", runLocal(s.frames, urban(st)).result.status, st)
        }
        assertEquals("abstain", runLocal(s.frames, confirmed(s.ctx)).result.status)
    }

    @Test @DisplayName("실제 진로변경을 \"차로 유지(A)\"로 읽지 않는다 — 느린 고속도로 진로변경·차선 안 흔들림·짧은 기록")
    fun l827() {
        // (a) 시속 130km, 9초 진로변경을 마친 순간 충돌, 10초 기록 → 기록이 짧아 "유지"로 단정하지 않는다
        val oa = runLocal(synth(130.0, laneChange = lc(-9.0, 9.0, "RIGHT")), highway("옆 차가 들어왔어요"))
        assertNotEquals(true, oa.result.option == "c252_me_A" && oa.result.status == "estimate", oa.metrics.laneChange.toJs().toString())
        // (b) 20초 기록, 시속 120km, 7초 진로변경의 절반에서 충돌, 차선 안 흔들림 ±0.3m (흔들림 위상 8가지)
        var ph = 0.0
        while (ph < 1) {
            val v = 120 / 3.6
            val P = 5.0
            val A = JsMath.atan((0.3 * 2 * PI / P) / v) * 180 / PI
            val fr = physFrames(120.0, fromS = -20.0, lc = lc(-3.5, 7.0, "RIGHT")).map { f ->
                val t = f.tMs / 1000
                f.copy(headingDeg = num(f.hd + A * JsMath.cos(2 * PI * (t / P + ph))))
            }
            val ob = runLocal(fr, highway("옆 차가 들어왔어요"))
            ok(notMeA(ob), "ph$ph ${ob.metrics.laneChange.toJs()}")
            ph += 0.125
        }
    }

    @Test @DisplayName("진로변경 직전의 차선 안 흔들림이 시작점을 앞당겨 신호 거리를 줄이지 않는다")
    fun l844() {
        // 시속 36km, 2.9초짜리 빠른 진로변경, 신호는 진로변경 약 43m 전에 켬, 그 앞에 차선 안 흔들림
        val v = 10.0
        val P = 4.0
        val A = JsMath.atan((0.12 * 2 * PI / P) / v) * 180 / PI // 차선 안 ±0.12m 흔들림 (방향 ±1.1°)
        val fr = synth(36.0, laneChange = lc(-2.7, 2.9, "RIGHT"), signal = sig(-7.0, "RIGHT")).map { f ->
            val t = f.tMs / 1000
            f.copy(headingDeg = num(f.hd + (if (t < -2.7) A * JsMath.sin(2 * PI * t / P) else 0.0)))
        }
        val out = runLocal(fr, confirmed(urban()))
        assertEquals("detected", out.metrics.laneChange.status, out.metrics.laneChange.toJs().toString())
        assertEquals("ok", out.metrics.signal.status, out.metrics.signal.toJs().toString())
        ok(abs(out.metrics.signal.leadM!! - 43) <= 6, out.metrics.signal.leadM.toString()) // 실제 약 43m
        Spec.eqNum(70, out.result.likely)
    }

    @Test @DisplayName("사실 문장: 충돌 직전에 끝난 움직임은 \"…까지\", 시작 단계는 진로변경으로 단정하지 않는다")
    fun l858() {
        val o1 = runLocal(synth(60.0, laneChange = lc(-5.4, 3.6, "RIGHT"), signal = sig(-8.0, "RIGHT")), urban())
        assertEquals("detected", o1.metrics.laneChange.status)
        ok(o1.facts!!.vehicle.anyMatch("초 전부터 .*초 전까지 내 차가 오른쪽으로"), o1.facts!!.vehicle.joinToString(" / "))
        val o2 = runLocal(synth(50.0, laneChange = lc(-1.2, 4.0, "RIGHT")), urban())
        assertEquals("starting", o2.metrics.laneChange.status)
        ok(!o2.facts!!.vehicle.anyMatch("진로를 바꿨음"), o2.facts!!.vehicle.joinToString(" / "))
        ok(o2.facts!!.vehicle.anyMatch("그 움직임 동안 오른쪽 방향지시등은 켜지 않았음"))
    }

    @Test @DisplayName("도로 유형을 모를 때: 신고 시점에 달리고 있어도 최근 속도로 고속도로 안내, 속도도 모르면 대피 먼저")
    fun l870() {
        val moving = runLocal(synth(100.0), Ctx(trigger = "voice", driverStatement = ""))
        assertEquals("highway", moving.flow.mode)
        val nothing = runLocal(emptyList<Frame>(), Ctx(trigger = "button", driverStatement = ""))
        assertEquals("caution", nothing.flow.mode)
        assertMatch(nothing.flow.cards[0].title, "먼저 안전한 곳으로")
        ok(nothing.flow.cards[0].lines.anyMatch("사진보다 대피가 먼저"))
        assertNoMatch(json(nothing.flow.toJs()), "사진부터 찍고")
    }

    @Test @DisplayName("진술 해석 4: 의도·명사형·\"적 없어요\"·얼버무림·위반한 차 구분")
    fun l880() {
        val P = ::parseStatement
        var c = P("차로 변경하려고 깜빡이만 켜고 기다리는데 옆 차가 들어왔어요")
        assertEquals(listOf(false, true), listOf(c.selfLC, c.otherLC))
        assertEquals(false, P("끼어들기 단속 구간이었어요").otherLC)
        assertEquals(false, P("진로변경 금지 구간인데 상대가 들어왔어요").selfLC)
        c = P("저는 끼어든 적 없어요"); assertEquals(listOf(false, true), listOf(c.selfLC, c.selfDeny))
        assertEquals(false, P("제가 차선을 바꾼 적은 없어요").selfLC)
        assertEquals(false, P("상대가 깜빡이를 안 켰다고 말하긴 어려워요").otherNoSignal)
        assertEquals(false, P("제 차가 밀려서 옆 차로로 넘어갔어요").selfLC)
        assertEquals(true, P("2차로에서 3차로로 옮기는 중이었어요").selfLC)
        c = P("저도 깜빡이 안 켰고 상대도 안 켰어요"); assertEquals(listOf(true, true), listOf(c.selfNoSignal, c.otherNoSignal))
        assertEquals(false, P("저 차가 안 켰어요 깜빡이").selfNoSignal)
        assertEquals("other", P("버스전용차로로 달리던 택시가 갑자기 나왔어요").busLaneBy)
        assertEquals("other", P("실선 넘어서 들어온 건 상대예요").solidLineBy)
        // 내가 진로를 바꾼 사고에서 "상대가 전용차로·실선 위반"은 내 과실 가산이 아니다
        val s = sc("proper_signal")
        for (st in listOf("버스전용차로로 달리던 택시가 있었는데 제가 차선 바꾸다가 부딪혔어요", "실선 넘어서 들어온 건 상대예요. 저도 차선 바꾸던 중이었어요")) {
            val out = runLocal(s.frames, confirmed(urban(st)))
            assertEquals("estimate", out.result.status, st)
            ok(out.result.modifiers.all { it.id !in listOf("B_buslane", "B_forbidden") || it.decision == "not_applied" }, "$st ${out.result.modifiers.map { it.toJs() }}")
        }
    }

    @Test @DisplayName("진술 해석 5: 남의 말 옮기기·가정·질문·따옴표 인용은 \"본인 진로변경 인정\"이 아니다")
    fun l904() {
        val P = ::parseStatement
        for (st in listOf(
            "상대가 \"제가 차선 바꿨어요\"라고 했어요", "제가 차선 바꿨으면 제 잘못이겠죠", "제가 들어갔다면 제가 박았겠죠",
            "상대 운전자는 제가 끼어들었다고 우겨요", "제가 끼어들었다고 하는데 아니에요", "경찰이 제가 차선 바꾼 거냐고 물었어요",
        )) {
            assertEquals(false, P(st).selfLC, st)
        }
        assertEquals(true, P("제가 차선을 바꿨는데 뒤차가 박았어요").selfLC)
        assertEquals(false, P("상대가 자기가 차선 바꿨다고 하더라고요").selfLC)
        // 인정이 아니면 기록에 진로변경 움직임이 있어도 숫자 대신 확인 질문
        val s = sc("me_late_signal")
        val out = runLocal(s.frames, urban("상대 운전자는 제가 끼어들었다고 우겨요"))
        assertEquals("abstain", out.result.status)
        ok(out.result.confirm != null)
    }
}

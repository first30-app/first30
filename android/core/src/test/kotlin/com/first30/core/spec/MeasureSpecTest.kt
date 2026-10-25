package com.first30.core.spec

import com.first30.core.engine.analyzeWindow
import com.first30.core.engine.computeFault
import com.first30.core.engine.localJudge
import com.first30.core.engine.renderFacts
import com.first30.core.engine.sealEvidence
import com.first30.core.model.Ctx
import com.first30.core.model.Frame
import com.first30.core.model.JsScalar
import com.first30.core.scenarios.SpeedProfile
import com.first30.core.spec.Spec.FIXED_NOW
import com.first30.core.spec.Spec.SCENARIOS
import com.first30.core.spec.Spec.anyMatch
import com.first30.core.spec.Spec.assertMatch
import com.first30.core.spec.Spec.confirmed
import com.first30.core.spec.Spec.curveFrames
import com.first30.core.spec.Spec.eqNum
import com.first30.core.spec.Spec.highway
import com.first30.core.spec.Spec.lc
import com.first30.core.spec.Spec.num
import com.first30.core.spec.Spec.nums
import com.first30.core.spec.Spec.ok
import com.first30.core.spec.Spec.rng
import com.first30.core.spec.Spec.runLocal
import com.first30.core.spec.Spec.sc
import com.first30.core.spec.Spec.sig
import com.first30.core.spec.Spec.str
import com.first30.core.spec.Spec.synth
import com.first30.core.spec.Spec.tMs
import com.first30.core.spec.Spec.urban
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.floor

/** engine.test.js "측정" · "방향지시등" · "브레이크" · "결측·순서" (58~276행). Method names = JS line numbers. */
class MeasureSpecTest {
    // ---------- 측정 ----------
    @Test @DisplayName("차로변경 측정: 오른쪽, 측면 이동 1~2.5m, 핸들 일치")
    fun l058() {
        val s = sc("me_late_signal")
        val m = analyzeWindow(s.frames, s.ctx)
        assertEquals("detected", m.laneChange.status)
        assertEquals("RIGHT", m.laneChange.direction)
        val lat = m.laneChange.lateralM!!
        ok(lat >= 1 && lat <= 2.5, lat.toString())
        assertEquals(true, m.laneChange.steeringCorroborated)
        assertEquals("late", m.signal.status)
        ok(m.signal.leadM!! < 30)
    }

    @Test @DisplayName("직진 차량은 차로 유지(none)로 측정하고, 사실 문장에 실제 측정값을 쓴다")
    fun l069() {
        val s = sc("other_cutin_no_signal")
        val m = analyzeWindow(s.frames, s.ctx)
        assertEquals("none", m.laneChange.status)
        assertNull(m.laneChange.direction)
        assertEquals(true, m.brake.applied)
        eqNum(0.8, m.brake.onsetSecBefore)
        val facts = renderFacts(m, s.ctx)
        ok(facts.vehicle.anyMatch("차로를 유지했음 \\(측면 이동 약 0m"), facts.vehicle.joinToString(" / "))
        ok(!facts.vehicle.anyMatch("1m 미만"))
    }

    @Test @DisplayName("곡선 도로(R800·60km/h, R3000·100km/h)는 차로변경으로 잡지 않는다")
    fun l081() {
        // 고속 주행은 느린 진로변경까지 가리려면 20초 기록이 필요하다 (앱 링버퍼 20초)
        for ((R, kph, ctx, fromS) in listOf(
            listOf(800.0, 60.0, urban("오른쪽 차가 갑자기 들어왔어"), -10.0),
            listOf(3000.0, 100.0, highway("옆 차가 끼어들었어"), -20.0),
        )) {
            val out = runLocal(curveFrames(R as Double, kph as Double, 1.0, fromS as Double), ctx as Ctx)
            assertEquals("none", out.metrics.laneChange.status, "R$R: ${out.metrics.laneChange.toJs()}")
            assertEquals(true, out.metrics.road!!.curveCompensated)
            assertEquals("c252_me_A", out.result.option)
        }
    }

    @Test @DisplayName("1Hz로 갱신되는 GPS 방향 ±2° 잡음 직진은 차로변경으로 단정하지 않는다")
    fun l091() {
        val r = rng(7)
        var held = 72.0
        val frames = synth(100.0).mapIndexed { i, f ->
            if (i % 10 == 0) held = 72 + (r() * 4 - 2)
            f.copy(headingDeg = num(held))
        }
        val out = runLocal(frames, highway("옆 차가 들어왔어"))
        assertNotEquals("detected", out.metrics.laneChange.status)
        ok(out.result.status == "abstain" || out.result.option == "c252_me_A", out.result.toJs().toString())
        assertNotEquals(true, out.result.option == "c252_me_B" && out.result.status == "estimate")
    }

    @Test @DisplayName("기록 앞부분의 방향 치우침(0.7°)이 가짜 차로변경을 만들지 않는다")
    fun l104() {
        val frames = synth(100.0, fromS = -20.0).map { f -> f.copy(headingDeg = num(if (f.tMs < -18500) 72.7 else 72.0)) }
        val m = analyzeWindow(frames, highway())
        assertEquals("none", m.laneChange.status, m.laneChange.toJs().toString())
        val near = synth(60.0).map { f -> f.copy(headingDeg = num(if (f.tMs < -8500) 72.7 else 72.0)) }
        assertEquals("none", analyzeWindow(near, urban()).laneChange.status)
    }

    @Test @DisplayName("시속 120km 완만한 진로변경(방향 변화 2° 미만)도 움직임으로 잡고, 확인 질문에 \"예\"면 신호 없음 +10 → 80%")
    fun l112() {
        val frames = synth(120.0, fromS = -20.0, laneChange = lc(-4.5, 7.0, "RIGHT"))
        val out = runLocal(frames, confirmed(highway("2차로로 들어가다 닿았어")))
        assertEquals("detected", out.metrics.laneChange.status, out.metrics.laneChange.toJs().toString())
        ok(out.metrics.laneChange.maxHeadingDeg!! < 2.5)
        assertEquals("c252_me_B", out.result.option)
        eqNum(80, out.result.likely) // 신호 없음 +10
        // 확인 답이 없으면(진술로 인정해도) 굽은 길과 구분할 수 없어 확인 질문
        val noAdmit = runLocal(frames, highway("2차로로 들어가다 닿았어"))
        assertEquals("abstain", noAdmit.result.status)
        assertEquals("RIGHT", noAdmit.result.confirm!!.direction)
    }

    @Test @DisplayName("충돌 1.4초 전에 시작한 진로변경 + \"상대가 들어왔다\" 진술 → 직진(A)으로 단정하지 않고 판단 보류")
    fun l125() {
        val frames = synth(50.0, laneChange = lc(-1.4, 4.0, "RIGHT"))
        val out = runLocal(frames, urban("오른쪽 차가 깜빡이 없이 들어왔어"))
        assertEquals("starting", out.metrics.laneChange.status)
        assertEquals("abstain", out.result.status)
        ok(out.result.reasons.anyMatch("회피|피하려는"), out.result.reasons.joinToString(" / "))
    }

    @Test @DisplayName("정체에서 출발해 바로 진로변경(10초 평균은 느림)도 진로변경 구간 속도로 판단한다")
    fun l133() {
        val frames = synth(SpeedProfile { t -> if (t < -3) 0.0 else 25.0 }, laneChange = lc(-2.6, 3.0, "LEFT"), signal = sig(-3.0, "LEFT"))
        val m = analyzeWindow(frames, urban())
        assertEquals("detected", m.laneChange.status, m.laneChange.toJs().toString())
        assertEquals("LEFT", m.laneChange.direction)
    }

    @Test @DisplayName("진로변경을 마치고 3초 넘게 지나 추돌 → 252 적용하지 않고 판단 보류")
    fun l140() {
        val frames = synth(60.0, laneChange = lc(-8.8, 4.0, "RIGHT"), signal = sig(-10.0, "RIGHT", toS = -4.0))
        val out = runLocal(frames, urban("뒤차가 와서 박았어"))
        assertEquals("completed", out.metrics.laneChange.status, out.metrics.laneChange.toJs().toString())
        ok(out.metrics.laneChange.completedSecBefore!! > 2)
        assertEquals("abstain", out.result.status)
        ok(out.result.reasons.anyMatch("추돌 사고인지"))
    }

    @Test @DisplayName("저속(정체) 접촉은 측정하지 않고, 진술만으로 과실을 내지 않는다")
    fun l149() {
        val out = runLocal(synth(5.0), urban("옆 차가 끼어들었어"))
        assertEquals("too_slow", out.metrics.laneChange.status)
        assertEquals("abstain", out.result.status)
    }

    // ---------- 방향지시등 ----------
    @Test @DisplayName("고속도로는 신호 기준이 100m, 늦게 켜면 late")
    fun l157() {
        val s = sc("highway_late_signal")
        val m = analyzeWindow(s.frames, s.ctx)
        eqNum(100, m.signal.requiredM)
        val lead = m.signal.leadM!!
        ok(lead >= 50 && lead < 100, lead.toString())
        assertEquals("late", m.signal.status)
    }

    @Test @DisplayName("충분히 일찍 켠 신호는 ok")
    fun l165() {
        val s = sc("proper_signal")
        val m = analyzeWindow(s.frames, s.ctx)
        assertEquals("ok", m.signal.status)
        ok(m.signal.leadM!! >= 30)
    }

    @Test @DisplayName("진로변경 시작 뒤에 켠 신호는 after(0m), 가산 반영")
    fun l172() {
        val frames = synth(50.0, laneChange = lc(-2.2, 4.0, "RIGHT"), signal = sig(-1.0, "RIGHT"))
        val out = runLocal(frames, confirmed(urban()))
        assertEquals("after", out.metrics.signal.status)
        eqNum(0, out.metrics.signal.leadM)
        ok(out.facts!!.vehicle.anyMatch("초 뒤에야"))
        eqNum(80, out.result.likely)
    }

    @Test @DisplayName("켰다가 끄고 들어가면 none")
    fun l181() {
        val frames = synth(50.0, laneChange = lc(-2.2, 4.0, "RIGHT"), signal = sig(-8.0, "RIGHT", toS = -5.0))
        assertEquals("none", analyzeWindow(frames, urban()).signal.status)
    }

    @Test @DisplayName("깜빡임 단위(0.4초 켜짐/0.4초 꺼짐)로 들어온 방향지시등도 한 구간으로 본다")
    fun l187() {
        val frames = synth(50.0, laneChange = lc(-2.2, 4.0, "RIGHT")).map { f ->
            val on = f.tMs >= -7000 && floor((f.tMs + 7000) / 400) % 2 == 0.0
            f.copy(turnSignal = str(if (on) "RIGHT" else "NONE"))
        }
        val m = analyzeWindow(frames, urban())
        assertEquals("ok", m.signal.status, m.signal.toJs().toString())
        ok(m.signal.leadM!! > 60)
    }

    @Test @DisplayName("기준 거리 근처(측정 오차 범위)는 ok/late 로 단정하지 않고 borderline → 범위에만 반영")
    fun l197() {
        // 고속도로 100km/h, 실제 선행 거리 약 97m
        val frames = synth(100.0, laneChange = lc(-2.2, 4.0, "RIGHT"), signal = sig(-5.7, "RIGHT"))
        val out = runLocal(frames, confirmed(highway()))
        assertEquals("borderline", out.metrics.signal.status, out.metrics.signal.toJs().toString())
        val s = out.result.modifiers.first { it.id == "B_signal" }
        assertEquals("possible", s.decision)
        eqNum(70, out.result.low)
        eqNum(80, out.result.high)
        val sentence = out.facts!!.vehicle.firstOrNull { Regex("방향지시등을 켰음").containsMatchIn(it) }
        assertMatch(sentence, "안팎")
    }

    @Test @DisplayName("도로 유형을 모르면 30m~100m 사이 신호 거리는 단정하지 않는다")
    fun l210() {
        val frames = synth(60.0, laneChange = lc(-2.2, 4.0, "RIGHT"), signal = sig(-5.5, "RIGHT"))
        val m = analyzeWindow(frames, Ctx(trigger = "impact", driverStatement = ""))
        assertEquals("unknown", m.roadType)
        assertEquals("borderline", m.signal.status)
        val facts = renderFacts(m, Ctx())
        ok(facts.place.contains("도로 유형 정보 없음"))
    }

    @Test @DisplayName("기록 시작부터 켜져 있고 거리가 기준 미만이면 indeterminate → 신호 가산은 범위에만 (70~80)")
    fun l219() {
        val frames = synth(10.0, laneChange = lc(-3.0, 6.0, "LEFT"), signal = sig(-10.0, "LEFT"))
        val m = analyzeWindow(frames, urban())
        assertEquals("detected", m.laneChange.status)
        assertEquals(true, m.signal.onAtBufferStart)
        assertEquals("indeterminate", m.signal.status)
        val judged = localJudge(emptyMap<String, Any?>(), m, Ctx(laneChangeConfirmed = true))
        val r = computeFault(judged.answers, m, Ctx(laneChangeConfirmed = true))
        assertEquals("possible", r.modifiers.first { it.id == "B_signal" }.decision)
        assertEquals(nums(70, 70, 80), listOf(r.low, r.likely, r.high))
    }

    // ---------- 브레이크 ----------
    @Test @DisplayName("브레이크를 밟았다가 뗀 뒤 충돌하면 밟은 시점과 뗀 시점을 함께 쓴다")
    fun l233() {
        val frames = synth(50.0).map { f -> f.copy(brake = JsScalar.Bool(f.tMs >= -1200 && f.tMs <= -400)) }
        val m = analyzeWindow(frames, urban())
        assertEquals(listOf<Any?>(true, 1.2, 0.4), listOf<Any?>(m.brake.applied, m.brake.onsetSecBefore, m.brake.releasedSecBefore))
        val facts = renderFacts(m, urban())
        ok(facts.vehicle.contains("충돌 1.2초 전에 브레이크를 밟았다가 충돌 0.4초 전에 뗐음"))
    }

    // ---------- 결측·순서 ----------
    @Test @DisplayName("방향 결측이 적으면 보간해서 같은 결과, 10%를 넘으면 측정 불가 → 판단 보류")
    fun l242() {
        val s = sc("me_late_signal")
        val few = s.frames.mapIndexed { i, f -> if (i % 25 == 3) f.copy(headingDeg = JsScalar.Null) else f }
        val a = runLocal(few, s.ctx)
        assertEquals("detected", a.metrics.laneChange.status)
        eqNum(80, a.result.likely)
        val many = s.frames.mapIndexed { i, f -> if (i % 5 == 0) f.copy(headingDeg = if (i % 2 != 0) JsScalar.Undefined else num(Double.NaN)) else f }
        val b = runLocal(many, s.ctx)
        assertEquals("unmeasurable", b.metrics.laneChange.status)
        assertEquals("abstain", b.result.status)
    }

    @Test @DisplayName("null 방향을 0도로 계산해 가짜 차로변경을 만들지 않는다")
    fun l254() {
        val frames = synth(60.0).mapIndexed { i, f -> if (i == 40) f.copy(headingDeg = JsScalar.Null) else f }
        assertEquals("none", analyzeWindow(frames, urban()).laneChange.status)
    }

    @Test @DisplayName("프레임 순서가 뒤집혀도 같은 측정값·같은 봉인 지문")
    fun l260() {
        val s = SCENARIOS[0]
        val rev = s.frames.reversed()
        val m1 = analyzeWindow(s.frames, s.ctx)
        val m2 = analyzeWindow(rev, s.ctx)
        assertEquals(m1.toJs(), m2.toJs())
        val s1 = sealEvidence(s.frames, m1, s.ctx.copy(now = FIXED_NOW))
        val s2 = sealEvidence(rev, m2, s.ctx.copy(now = FIXED_NOW))
        assertEquals(s1.hash, s2.hash)
    }

    @Test @DisplayName("프레임이 3개 미만이어도 안전 카드와 봉인은 나오고, 과실은 판단 보류")
    fun l270() {
        val out = runLocal(
            listOf(
                Frame(t = num(-100.0), speedKph = num(40.0), headingDeg = num(72.0)),
                Frame(t = num(0.0), speedKph = num(38.0), headingDeg = num(72.0), airbag = JsScalar.Bool(true)),
            ),
            urban(),
        )
        assertEquals("safety", out.flow.cards[0].kind)
        assertEquals("emergency", out.flow.mode)
        assertMatch(out.seal!!.hash, "^[0-9a-f]{64}$")
        assertEquals("abstain", out.result.status)
    }
}

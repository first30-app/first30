package com.first30.core.spec

import com.first30.core.engine.LocalJudge
import com.first30.core.engine.RunOutput
import com.first30.core.engine.analyzeWindow
import com.first30.core.engine.computeFault
import com.first30.core.engine.renderFacts
import com.first30.core.engine.run
import com.first30.core.engine.sealEvidence
import com.first30.core.model.Ctx
import com.first30.core.model.Frame
import com.first30.core.scenarios.SpeedProfile
import com.first30.core.spec.Spec.DriveLc
import com.first30.core.spec.Spec.DriveSignal
import com.first30.core.spec.Spec.FIXED_NOW
import com.first30.core.spec.Spec.SCENARIOS
import com.first30.core.spec.Spec.Weave
import com.first30.core.spec.Spec.anyMatch
import com.first30.core.spec.Spec.assertMatch
import com.first30.core.spec.Spec.assertNoMatch
import com.first30.core.spec.Spec.bool
import com.first30.core.spec.Spec.confirmed
import com.first30.core.spec.Spec.eqNum
import com.first30.core.spec.Spec.hd
import com.first30.core.spec.Spec.highway
import com.first30.core.spec.Spec.json
import com.first30.core.spec.Spec.keptLane
import com.first30.core.spec.Spec.lc
import com.first30.core.spec.Spec.noul
import com.first30.core.spec.Spec.num
import com.first30.core.spec.Spec.nums
import com.first30.core.spec.Spec.obj
import com.first30.core.spec.Spec.ok
import com.first30.core.spec.Spec.physDrive
import com.first30.core.spec.Spec.rampDown
import com.first30.core.spec.Spec.rng
import com.first30.core.spec.Spec.runLocal
import com.first30.core.spec.Spec.sc
import com.first30.core.spec.Spec.sig
import com.first30.core.spec.Spec.synth
import com.first30.core.spec.Spec.tMs
import com.first30.core.spec.Spec.urban
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** engine.test.js "시연 화면 빌드"(대체) · "5차·6차·7차 검토" (919~1258행) + 내비 주의 지점 회귀 테스트. Method names = JS line numbers. */
class LateReviewSpecTest {
    private fun notMeA(out: RunOutput) = !(out.result.status == "estimate" && out.result.option == "c252_me_A")
    private fun signalDecision(out: RunOutput) = out.result.modifiers.first { it.id == "B_signal" }.decision

    // ---------- 시연 화면 빌드 (대체) ----------
    /**
     * JS 920행 '시연 화면 빌드: 단독 실행용 index.html 과 게시용 조각이 만들어진다' 는 demo/build.js(HTML 빌드)를 검사한다 —
     * Kotlin 에는 해당하는 것이 없어서, 대신 고정 시나리오 봉인 지문이 안정적이고 JS 값과 같은지 본다.
     * 다시 만들기: 저장소 루트에서
     *   node -e "const F=require('./engine/index.js');require('./scenarios/scenarios.js');(async()=>{const sc=F.SCENARIOS[0];
     *     const o=await F.run(sc.frames,{...sc.ctx,now:'2026-10-23T15:00:00.000Z'},F.localJudge);console.log(o.seal.hash)})()"
     * (봉인 형식·엔진 측정값·시나리오 합성식이 바뀌면 값이 바뀐다.)
     */
    @Test @DisplayName("봉인 지문 고정값: me_late_signal 시나리오의 봉인 지문이 실행마다 같고 JS 엔진 값과 같다 (시연 화면 빌드 테스트 대체)")
    fun l920() {
        val s = SCENARIOS[0]
        assertEquals("me_late_signal", s.id)
        val a = runLocal(s.frames, s.ctx)
        val b = runLocal(s.frames, s.ctx)
        assertEquals(a.seal!!.hash, b.seal!!.hash)
        assertEquals(a.seal!!.canonical, b.seal!!.canonical)
        assertEquals(sealEvidence(s.frames, analyzeWindow(s.frames, s.ctx), s.ctx.copy(now = FIXED_NOW)).hash, a.seal!!.hash)
        assertEquals("394202d2e8c1d9d23b1b6c47106ddc83dbda3cd9fd8cd369e46baa64a980970e", a.seal!!.hash)
        assertEquals(8113, a.seal!!.canonical.length)
    }

    // ---------- 5차 검토 경계 상황 ----------
    @Test @DisplayName("곡선이 끝나는 곳에서 같은 쪽으로 진로를 바꾸던 중 충돌 → \"상대 진로변경(A)\" 숫자를 내지 않는다 (일정한 곡선의 차로 유지는 그대로 A)")
    fun l971() {
        // 오른쪽 곡선이 충돌 3~5초 전에 끝나는데 그때 오른쪽 진로변경이 이어지면 진행 방향은 곡선이 계속되는 것처럼 보인다
        for ((kph, R, te, D) in listOf(
            listOf(60.0, 1500.0, -3.0, 8.0), listOf(75.0, 1500.0, -3.0, 7.0), listOf(75.0, 1500.0, -3.0, 8.0), listOf(75.0, 2000.0, -3.0, 8.0),
            listOf(75.0, 2500.0, -4.0, 8.0), listOf(75.0, 2500.0, -3.0, 8.0), listOf(60.0, 1000.0, -3.0, 7.0),
        )) {
            val fr = physDrive(kph, roadK = rampDown(te - 2, te, 1 / R), lc = DriveLc(-0.5 * D, D, "RIGHT"))
            val out = runLocal(fr, urban("옆 차가 들어왔어요"))
            ok(notMeA(out), "$kph R$R $te D$D: ${out.metrics.laneChange.toJs()}")
        }
        val keep = physDrive(75.0, roadK = { 1.0 / 1500 })
        val k = runLocal(keep, keptLane(urban("옆 차가 들어왔어요")))
        assertEquals(listOf<Any?>("estimate", "c252_me_A", true), listOf<Any?>(k.result.status, k.result.option, k.metrics.road!!.curveCompensated))
        // 답 전에는 "상대 진로변경"도 숫자 대신 확인 질문 — 정직하게 "예"(진로를 바꾸던 중)라고 답하면 기록과 어긋나 보류
        val ask = runLocal(keep, urban("옆 차가 들어왔어요"))
        assertEquals(listOf<Any?>("abstain", "그때 차로를 바꾸던 중이었나요?"), listOf<Any?>(ask.result.status, ask.result.confirm?.question))
        assertEquals("abstain", runLocal(keep, confirmed(urban("옆 차가 들어왔어요"))).result.status)
    }

    @Test @DisplayName("도로 모양이 바뀌어 기준이 없으면(road_change) 숫자 없이 보류하고, 사실 문장에 빈 값이 나오지 않는다")
    fun l987() {
        val fr = physDrive(60.0, roadK = rampDown(-5.0, -3.0, 1.0 / 1000), lc = DriveLc(-3.5, 7.0, "RIGHT"))
        for (st in listOf("옆 차가 들어왔어요", "제가 차선 바꾸다가 부딪혔어요")) {
            val out = runLocal(fr, urban(st))
            assertEquals("abstain", out.result.status, st)
            if (out.metrics.laneChange.reason == "road_change") {
                ok(out.facts!!.vehicle.anyMatch("도로 모양이 바뀌어"))
            }
            assertNoMatch(out.facts!!.vehicle.joinToString(" "), "null|undefined|NaN")
        }
    }

    @Test @DisplayName("에어백 트리거가 늦게 와도 기록상 에어백이 처음 켜진 순간을 충돌 시각으로 본다")
    fun l999() {
        // 시속 60km 직진 중 10초 전 충돌·에어백 전개 → 시속 40km로 갓길(오른쪽)로 옮기는 중에 앱이 에어백 상태를 보고 트리거
        val fr = synth(SpeedProfile { t -> if (t <= -10) 60.0 else 40.0 }, fromS = -20.0, laneChange = lc(-2.5, 4.0, "RIGHT"))
            .map { f -> f.copy(airbag = bool(f.tMs >= -10000)) }
        val out = runLocal(fr, Ctx(trigger = "airbag", roadType = "urban", navCautions = emptyList(), driverStatement = ""))
        eqNum(-10000, out.metrics.impact.anchoredMs)
        eqNum(60, out.metrics.impactSpeedKph)
        assertNotEquals("detected", out.metrics.laneChange.status) // 사고 뒤 갓길로 옮긴 움직임을 사고 전 진로변경으로 읽지 않는다
        assertEquals("emergency", out.flow.mode)
    }

    @Test @DisplayName("봉인은 기록이 없던 값을 null 로 남긴다 (꺼짐·안 밟음으로 채우지 않는다)")
    fun l1010() {
        val s = sc("proper_signal")
        val fr = s.frames.mapIndexed { i, f ->
            f.copy(turnSignal = com.first30.core.model.JsScalar.Null, brake = if (i % 2 != 0) com.first30.core.model.JsScalar.Null else f.brake, airbag = com.first30.core.model.JsScalar.Undefined)
        }
        val out = runLocal(fr, s.ctx)
        assertEquals(4.0, out.seal!!.payload["version"])
        @Suppress("UNCHECKED_CAST")
        val frames = out.seal!!.payload["frames"] as List<Map<String, Any?>>
        val last = frames.takeLast(2)
        ok(last.all { it.containsKey("ts") && it["ts"] == null && it.containsKey("ab") && it["ab"] == null })
        ok(last.any { it.containsKey("b") && it["b"] == null } && last.any { it["b"] == false })
    }

    @Test @DisplayName("빠른 도로 안전 안내: 부상·에어백이면 대피 뒤 119, 일반도로라도 시속 80km 이상이면 대피 먼저")
    fun l1020() {
        val hw = sc("highway_late_signal")
        val em = runLocal(hw.frames, hw.ctx.copy(injuryReported = true))
        val s = em.flow.cards[0]
        assertEquals("emergency", em.flow.mode)
        assertMatch(s.lines[0], "대피")
        ok(s.tts.indexOf("대피") < s.tts.indexOf("119"), s.tts)
        val fast = runLocal(synth(100.0), urban("옆 차가 들어왔어요"))
        assertEquals("caution", fast.flow.mode)
        assertMatch(fast.flow.cards[0].title, "먼저 안전한 곳으로")
        val ask = runLocal(hw.frames, hw.ctx.copy(roadType = "urban", laneChangeConfirmed = null, driverStatement = ""))
        val card = ask.flow.cards.first { it.kind == "abstain" }
        ok(card.confirm != null)
        assertNoMatch(card.tts, "답해 주세요") // 빠른 도로에서는 질문을 소리 내어 묻지 않는다
        ok(ask.result.evidenceChecklist.anyMatch("대피한 뒤"))
    }

    @Test @DisplayName("위치 기반 진행 방향이 핸들보다 늦게 반응해도(1.5초, 1Hz) 실제 진로변경을 \"상대 진로변경(A)\"으로 읽지 않는다")
    fun l1037() {
        for ((kph, D, ph) in listOf(Triple(70.0, 4.0, 0.65), Triple(110.0, 6.0, 0.55), Triple(50.0, 3.0, 0.85))) {
            val base = physDrive(kph, lc = DriveLc(-ph * D, D, "RIGHT"))
            // 1.5초 늦게, 1초에 한 번만 갱신되는 진행 방향
            var held: Double? = null
            var upd = Double.NEGATIVE_INFINITY
            val fr = base.map { f ->
                val src = base.firstOrNull { g -> g.tMs >= f.tMs - 1500 } ?: base[0]
                if (f.tMs - upd >= 1000) { held = src.hd; upd = f.tMs }
                f.copy(headingDeg = com.first30.core.model.JsScalar.of(held))
            }
            val out = runLocal(fr, urban("옆 차가 들어왔어요"))
            ok(notMeA(out), "$kph D$D $ph: ${out.metrics.laneChange.toJs()}")
        }
    }

    @Test @DisplayName("충돌 직전에 시작한 움직임(starting)도 \"예\"라고 답하면 내 진로변경으로 계산하되, 신호 가산은 범위에만 넣는다")
    fun l1052() {
        val late = synth(50.0, laneChange = lc(-1.0, 4.0, "RIGHT"), signal = sig(-1.5, "RIGHT"))
        val ask = runLocal(late, urban(""))
        assertEquals("starting", ask.metrics.laneChange.status)
        assertEquals("abstain", ask.result.status)
        assertMatch(ask.result.confirm!!.question, "오른쪽")
        val yes = runLocal(late, confirmed(urban("")))
        assertEquals(listOf("estimate", "c252_me_B"), listOf(yes.result.status, yes.result.option))
        assertEquals("possible", signalDecision(yes))
        assertEquals(nums(70, 80), listOf(yes.result.low, yes.result.high))
        // 그쪽 신호를 한 번도 켜지 않았어도 범위에만 (피하려고 꺾은 경우도 신호는 켜지 않으므로)
        val y2 = runLocal(synth(50.0, laneChange = lc(-1.0, 4.0, "RIGHT")), confirmed(urban("")))
        assertEquals("possible", signalDecision(y2))
        assertEquals(nums(70, 80), listOf(y2.result.low, y2.result.high))
        // 그 무렵 브레이크를 밟았으면(피하려는 조작일 수 있음) 묻지 않고, "예"라고 해도 숫자를 내지 않는다
        val ev = synth(50.0, laneChange = lc(-1.0, 4.0, "RIGHT"), brakeFromS = -1.3)
        val evAsk = runLocal(ev, urban(""))
        assertEquals("evasive", evAsk.metrics.laneChange.reason)
        assertNull(evAsk.result.confirm)
        assertEquals("abstain", runLocal(ev, confirmed(urban(""))).result.status)
    }

    @Test @DisplayName("진술 해석이 틀려도 \"내 차 진로변경\" 숫자는 나오지 않는다 — 숫자는 확인 질문의 \"예\"로만")
    fun l1076() {
        val s = sc("confirm_needed") // 기록에 진로변경 같은 움직임, 확인 전
        for (st in listOf(
            "제가 넘어갔다니 말도 안 돼요", "차선을 바꿨던 것 같기도 하고 잘 모르겠어요", "아까 전에 차선 바꿨는데 그 뒤로 계속 직진했어요",
            "차선 바꾸라는 안내가 나왔어요", "차선 바꾸지도 않았는데 옆 차가 와서 박았어요", "제가 차선 바꾸다가 부딪혔어요",
        )) {
            val out = runLocal(s.frames, urban(st))
            assertEquals("abstain", out.result.status, st)
            ok(out.result.confirm != null, st)
        }
    }

    // ---------- 6차 검토 경계 상황 ----------
    @Test @DisplayName("진로변경 앞 차선 안 흔들림(방향 선반)이 시작점을 앞당겨 제때 켠 신호를 \"늦게 켬\"으로 읽지 않는다")
    fun l1087() {
        // 시속 60km, 5.8초 진로변경의 70% 지점에서 충돌, 신호는 실제 시작 55m 전(기준 30m 충족), 앞에 ±0.25m 흔들림
        val startS = -0.7 * 5.8
        val fr = physDrive(
            60.0, lc = DriveLc(startS, 5.8, "RIGHT", W = 3.3), signal = DriveSignal(startS - 55 / (60 / 3.6), "RIGHT"),
            weave = Weave(A = 0.25, P = 8.2, ph = 3.5),
        )
        val out = runLocal(fr, confirmed(urban("")))
        ok(abs(out.metrics.laneChange.startT!! - startS * 1000) <= 600, out.metrics.laneChange.startT.toString())
        assertEquals("ok", out.metrics.signal.status)
        eqNum(70, out.result.likely)
    }

    @Test @DisplayName("시작 무렵에 켠 신호(시작 시점 오차 안)는 \"늦게 켬\"으로 단정하지 않고 범위로")
    fun l1098() {
        val out = runLocal(synth(50.0, laneChange = lc(-2.2, 4.0, "RIGHT"), signal = sig(-2.0, "RIGHT")), confirmed(urban("")))
        assertEquals("borderline", out.metrics.signal.status)
        assertEquals("possible", signalDecision(out))
        assertEquals(nums(70, 80), listOf(out.result.low, out.result.high))
    }

    @Test @DisplayName("진행 방향이 핸들보다 2초 늦으면 신호 선행 거리 판정 여유에 그 지연을 더한다")
    fun l1106() {
        // 시속 100km 고속도로, 실제로는 기준(100m)보다 짧은 90m 전에 켰는데 방향 지연 때문에 128m로 재진다 → "충족"으로 단정하지 않는다
        val startS = -0.8 * 6
        val base = physDrive(100.0, lc = DriveLc(startS, 6.0, "RIGHT"), signal = DriveSignal(startS - 90 / (100 / 3.6), "RIGHT"))
        val fr = base.map { f -> f.copy(headingDeg = (base.firstOrNull { g -> g.tMs >= f.tMs - 2000 } ?: base[0]).headingDeg) }
        val out = runLocal(fr, confirmed(highway("")))
        ok(out.metrics.quality.headingLagSec!! >= 1.5, out.metrics.quality.headingLagSec.toString())
        assertNotEquals("ok", out.metrics.signal.status)
        assertNotEquals("not_applied", signalDecision(out))
    }

    @Test @DisplayName("도로 유형을 모르거나, 음성 신고인데 기록 내내 멈춰 있었으면 \"사진 먼저\" 안내를 하지 않는다")
    fun l1117() {
        for (kph in listOf(30.0, 60.0, 79.0)) {
            val out = runLocal(synth(kph), Ctx(trigger = "impact", navCautions = emptyList(), driverStatement = ""))
            assertEquals("caution", out.flow.mode, kph.toString())
            assertNoMatch(json(out.flow.toJs()), "사진부터 찍고|사진 먼저 찍고")
        }
        val still = runLocal(synth(0.0, fromS = -20.0), Ctx(trigger = "voice", roadType = "urban", navCautions = emptyList(), driverStatement = ""))
        assertEquals("caution", still.flow.mode)
        assertEquals("caution", runLocal(synth(75.0), urban("")).flow.mode)
        assertEquals("urban", runLocal(synth(60.0), urban("")).flow.mode)
        // 속도로만 고속도로라고 가늠했으면 "고속도로예요"라고 단정해 말하지 않는다
        val inferred = runLocal(synth(100.0), Ctx(trigger = "impact", navCautions = emptyList(), driverStatement = ""))
        assertEquals("highway", inferred.flow.mode)
        assertNoMatch(inferred.flow.cards[0].tts, "고속도로예요")
        assertMatch(inferred.flow.cards[4].lines.joinToString(" "), "안전해진 뒤")
    }

    @Test @DisplayName("판정이 \"내 차 진로변경\"이어도 기록에 방향이 없으면 빈칸 문장 없이 보류한다")
    fun l1135() {
        val m = analyzeWindow(sc("other_cutin_unclear").frames, urban())
        for (answer in listOf(null, false)) {
            val r = computeFault(
                obj("chart" to obj("choice" to "c252_me_B", "probabilities" to obj("c252_me_B" to 0.9, "none" to 0.1)), "evidence_sufficient" to noul(0.9)),
                m, Ctx(laneChangeConfirmed = answer),
            )
            assertEquals("abstain", r.status)
            assertNull(r.confirm)
            assertNoMatch(r.reasons.joinToString(" "), "내 차가 으로|그때 {2}|undefined|null")
        }
    }

    @Test @DisplayName("에어백 시각 맞춤은 트리거까지 이어진 에어백 구간으로만, 앞쪽으로만 한다")
    fun l1145() {
        val fr = synth(60.0, fromS = -20.0, laneChange = lc(-2.8, 4.0, "RIGHT"))
        val ctx = Ctx(trigger = "airbag", roadType = "urban", navCautions = emptyList(), driverStatement = "")
        // 동떨어진 짧은 에어백 값(잡음)은 쓰지 않는다
        val spurious = runLocal(fr.map { f -> f.copy(airbag = bool(f.tMs == -12000.0 || f.tMs >= 0)) }, ctx)
        assertNull(spurious.metrics.impact.anchoredMs)
        assertEquals("detected", spurious.metrics.laneChange.status)
        // 트리거 뒤에 켜진 에어백으로 앞당기지 않는다
        val later = runLocal(
            fr.map { f -> f.copy(airbag = bool(false)) } +
                Frame.of(600, 40.0, 80.0, 0.0, "NONE", brake = true, airbag = true),
            ctx,
        )
        assertNull(later.metrics.impact.anchoredMs)
        assertEquals(true, later.metrics.impact.known)
        // 기록 첫머리부터 켜져 있었으면 전개 시각이 기록 밖 → 충돌 시각을 모름
        val before = runLocal(fr.map { f -> f.copy(airbag = bool(true)) }, ctx)
        assertEquals(false, before.metrics.impact.known)
        assertEquals("abstain", before.result.status)
        assertMatch(before.result.reasons.joinToString(" "), "에어백이 터진 시각이 기록 범위 밖")
    }

    @Test @DisplayName("ctx 가 null 이어도 안전 안내와 봉인은 나오고, 같은 시각 프레임이 섞여 들어와도 지문은 같다")
    fun l1163() {
        val s = sc("proper_signal")
        val out = run(s.frames, null, LocalJudge)
        assertEquals(5, out.flow.cards.size)
        ok(out.seal != null && Regex("^[0-9a-f]{64}$").matches(out.seal!!.hash))
        val dup = s.frames + s.frames[50].copy(steeringDeg = num(3.3))
        val m = analyzeWindow(dup, s.ctx)
        val a = sealEvidence(dup, m, s.ctx.copy(now = FIXED_NOW))
        val b = sealEvidence(dup.reversed(), m, s.ctx.copy(now = FIXED_NOW))
        assertEquals(a.hash, b.hash)
        assertEquals(m.laneChange.startT, analyzeWindow(dup.reversed(), s.ctx).laneChange.startT)
    }

    // ---------- 7차 검토 보강 (L1·O3) ----------
    // 검토 재현: 시속 118km, 왼쪽 진로변경(충돌 4.5초 전부터 4초)과 함께 충돌 4.1~2.9초 전 오른쪽으로 굽기 시작하는 길(반경 1500m), 신호 없음
    private val flipRampK: (Double) -> Double = { t -> if (t <= -4.0559) 0.0 else if (t >= -2.941) 1.0 / 1500 else (t + 4.0559) / (4.0559 - 2.941) / 1500 }
    private val flipLC = DriveLc(-4.4889, 3.9895, "LEFT")

    @Test @DisplayName("곡률이 바뀌는 길에서 방향을 거꾸로 읽은 움직임은 \"예\"라고 해도 신호 가산을 확정하지 않는다 (검토 재현)")
    fun l1182() {
        val fr = physDrive(118.0, fromS = -14.0, lc = flipLC, roadK = flipRampK)
        val out = runLocal(fr, confirmed(highway("")))
        val l = out.metrics.laneChange
        assertEquals(listOf("detected", "RIGHT"), listOf(l.status, l.direction)) // 실제는 왼쪽 — 기록만으로는 가릴 수 없다
        assertEquals("opposite_move", l.directionCaveat)
        assertEquals(listOf("estimate", "c252_me_B"), listOf(out.result.status, out.result.option))
        val s = out.result.modifiers.first { it.id == "B_signal" }
        assertEquals("possible", s.decision)
        assertMatch(s.why, "움직인 방향을 잘못 쟀을 수 있어 범위에만 반영")
        assertEquals(nums(70, 80), listOf(out.result.low, out.result.high)) // 고정 80이 아니라 70을 포함한 범위
        // 실제 방향(왼쪽)의 신호를 켜 두었으면: 예전에는 "오른쪽 신호 미점등"으로 +10 확정 → 이제 범위에만
        val sigFr = physDrive(118.0, fromS = -14.0, lc = flipLC, roadK = flipRampK, signal = DriveSignal(flipLC.startS - 3, "LEFT"))
        val o2 = runLocal(sigFr, confirmed(highway("")))
        assertEquals("opposite_signal", o2.metrics.laneChange.directionCaveat)
        assertEquals("possible", signalDecision(o2))
        assertEquals(nums(70, 80), listOf(o2.result.low, o2.result.high))
        // 정직하게 "아니요"라고 하면 숫자 없음 (기존 규칙 그대로)
        assertEquals("abstain", runLocal(fr, keptLane(highway(""))).result.status)
    }

    @Test @DisplayName("도로 방향 기준이 곡선 맞춤이면 detected 의 신호 가산은 범위에만, 곧은 길의 진로변경은 그대로 확정")
    fun l1203() {
        // 반경 1500m 곡선을 시속 75km로 달리다 충돌 2.5초 전부터 오른쪽 진로변경, 신호 없음
        val cv = runLocal(physDrive(75.0, roadK = { 1.0 / 1500 }, lc = DriveLc(-2.5, 5.0, "RIGHT")), confirmed(urban("")))
        assertEquals(true, cv.metrics.road!!.curveCompensated)
        assertEquals(listOf("detected", "road_curve"), listOf(cv.metrics.laneChange.status, cv.metrics.laneChange.directionCaveat))
        val cvSig = cv.result.modifiers.first { it.id == "B_signal" }
        assertEquals("possible", cvSig.decision)
        assertMatch(cvSig.why, "굽은 길에서는 움직인 방향을 잘못 잴 수 있어 범위에만 반영")
        assertEquals(nums(70, 80), listOf(cv.result.low, cv.result.high))
        // 대조: 곧은 길의 진로변경(신호 없음)은 고속도로·시내 모두 지금처럼 +10 확정 → 80
        for ((fr, ctx) in listOf(
            physDrive(118.0, fromS = -14.0, lc = DriveLc(-3.5, 6.0, "RIGHT")) to highway(""),
            physDrive(60.0, lc = DriveLc(-3.0, 5.0, "LEFT")) to urban(""),
        )) {
            val out = runLocal(fr, confirmed(ctx))
            assertEquals("detected", out.metrics.laneChange.status)
            assertNull(out.metrics.laneChange.directionCaveat)
            assertEquals("applied", signalDecision(out))
            assertEquals(nums(80, 80, 80), listOf(out.result.low, out.result.likely, out.result.high))
        }
        // 대조: 차선 안 흔들림이 있는 곧은 길 진로변경도 방향 경고를 켜지 않는다
        val r = rng(77)
        var seen = 0
        for (i in 0 until 120) {
            // JS 와 같은 순서로 난수를 뽑는다: kph, D, startS, dir, A, P, ph
            val kph = 40 + com.first30.core.model.JsMath.round(r() * 80)
            val D = 3.5 + r() * 4
            val startS = -(1.6 + r() * 3)
            val dir = if (r() < 0.5) "LEFT" else "RIGHT"
            val A = 0.1 + r() * 0.4
            val P = 3 + r() * 6
            val ph = r() * 6.28
            val fr = physDrive(kph, lc = DriveLc(startS, D, dir), weave = Weave(A, P, ph))
            val m = analyzeWindow(fr, urban(""))
            if (m.laneChange.status != "detected") continue
            seen++
            assertNull(m.laneChange.directionCaveat, "kph=$kph D=$D startS=$startS")
        }
        ok(seen >= 20, seen.toString())
    }

    @Test @DisplayName("detected 확인 질문에도 굽은 길 안내를 덧붙여 읽고(뜻만 분명히, 예/아니요를 함께), 빠른 도로에서는 소리 내어 묻지 않는다")
    fun l1237() {
        val s = sc("confirm_needed")
        val ask = runLocal(s.frames, s.ctx.copy(laneChangeConfirmed = null, roadType = "urban", driverStatement = ""))
        assertEquals("detected", ask.metrics.laneChange.status)
        assertEquals("굽은 길이나 차로를 따라 달린 거라면 \"아니요\"", ask.result.confirm!!.hint)
        val card = ask.flow.cards.first { it.confirm != null }
        ok(card.lines.contains("굽은 길이나 차로를 따라 달린 거라면 \"아니요\"라고 답해 주세요"), card.lines.joinToString(" / "))
        assertMatch(card.tts, "예 또는 아니요로 답해 주세요\\. 굽은 길이나 차로를 따라 달린 거라면 아니요예요\\.$")
        assertNoMatch(card.tts, "오른쪽으로 움직|기록") // 기록 내용을 앞세우지 않는다
        // starting 은 피하려는 조작 안내 (같은 형식)
        val late = runLocal(synth(50.0, laneChange = lc(-1.0, 4.0, "RIGHT")), urban(""))
        assertEquals("피하려고 핸들을 꺾은 거라면 \"아니요\"", late.result.confirm!!.hint)
        assertMatch(late.flow.cards.first { it.confirm != null }.tts, "예 또는 아니요로 답해 주세요\\. 피하려고 핸들을 꺾은 거라면 아니요예요\\.$")
        // 빠른 도로(고속도로·caution)에서는 질문도 안내도 소리 내지 않는다 (화면에만)
        for (ctx in listOf(highway(""), Ctx(trigger = "impact", navCautions = emptyList(), driverStatement = ""))) {
            val fast = runLocal(physDrive(118.0, fromS = -14.0, lc = DriveLc(-3.5, 6.0, "RIGHT")), ctx)
            val c = fast.flow.cards.firstOrNull { it.confirm != null }
            ok(c != null && c.lines.anyMatch("굽은 길이나 차로를 따라"))
            assertNoMatch(c!!.tts, "답해 주세요|굽은 길|아니요")
            ok(fast.flow.mode in listOf("highway", "caution"))
        }
    }

    // ---------- 회귀: 내비 주의 지점은 자기 키만 ----------
    @Test @DisplayName("내비 주의 지점: constructor·toString·__proto__ 같은 상속 속성 이름은 장소 문장에 넣지 않고, 아는 주의 지점만 넣는다")
    fun navCautionOwnKeysOnly() {
        val s = sc("other_cutin_unclear")
        val m = analyzeWindow(s.frames, urban())
        val bogus = urban().copy(navCautions = listOf("constructor", "toString", "__proto__", "hasOwnProperty"))
        val place = renderFacts(m, bogus).place
        ok(!place.anyMatch("내비게이션:"), place.joinToString(" / "))
        val out = runLocal(s.frames, bogus)
        ok(!out.facts!!.place.anyMatch("내비게이션:"))
        assertNoMatch(out.facts!!.place.joinToString(" "), "undefined|function")
        val known = renderFacts(m, urban().copy(navCautions = listOf("constructor", "LANE_CHANGE_CAMERA_ALERT")))
        assertEquals(1, known.place.count { Regex("^내비게이션: ").containsMatchIn(it) })
    }
}

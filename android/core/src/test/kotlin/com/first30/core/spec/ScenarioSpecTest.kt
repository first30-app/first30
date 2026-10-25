package com.first30.core.spec

import com.first30.core.spec.Spec.SCENARIOS
import com.first30.core.spec.Spec.assertMatch
import com.first30.core.spec.Spec.confirmed
import com.first30.core.spec.Spec.eqNum
import com.first30.core.spec.Spec.ok
import com.first30.core.spec.Spec.runLocal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** engine.test.js "시나리오" — one dynamic test per scenario (`시나리오 ${id}: ${title}`). */
class ScenarioSpecTest {
    @TestFactory
    fun scenarios(): List<DynamicTest> = SCENARIOS.map { sc ->
        DynamicTest.dynamicTest("시나리오 ${sc.id}: ${sc.title}") {
            val out = runLocal(sc.frames, sc.ctx)
            val e = sc.expect
            assertEquals(e.status, out.result.status, out.result.toJs().toString())
            assertEquals(e.confirm == true, out.result.confirm != null, "confirm")
            e.confirmed?.let { c ->
                val yes = runLocal(sc.frames, confirmed(sc.ctx))
                assertEquals(c.status, yes.result.status, "status")
                assertEquals(c.option, yes.result.option, "option")
                eqNum(c.likely, yes.result.likely, "likely")
                eqNum(c.low, yes.result.low, "low")
                eqNum(c.high, yes.result.high, "high")
            }
            // 답이 있어야 숫자가 나오는 시나리오는, 답을 빼면 숫자 대신 확인 질문이 나와야 한다
            if (sc.ctx.laneChangeConfirmed != null && e.status == "estimate") {
                val before = runLocal(sc.frames, sc.ctx.copy(laneChangeConfirmed = null))
                assertEquals("abstain", before.result.status, "before answer")
                ok(before.result.confirm != null, "question before answer")
            }
            e.option?.let { assertEquals(it, out.result.option) }
            e.likely?.let { eqNum(it, out.result.likely, "likely ${out.result.modifiers.map { m -> m.toJs() }}") }
            e.low?.let { eqNum(it, out.result.low) }
            e.high?.let { eqNum(it, out.result.high) }
            // JS `e.verified` — no scenario sets it (Kotlin Expect has no such field).
            e.mode?.let { assertEquals(it, out.flow.mode) }
            assertMatch(out.seal!!.hash, "^[0-9a-f]{64}$")
            assertEquals(5, out.flow.cards.size)
            assertNull(out.judgeError)
            assertEquals(true, out.result.judge!!.standIn)
        }
    }
}

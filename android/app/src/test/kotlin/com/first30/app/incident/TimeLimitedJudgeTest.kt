package com.first30.app.incident

import com.first30.core.engine.Judge
import com.first30.core.engine.LocalJudge
import com.first30.core.engine.run
import com.first30.core.scenarios.Scenarios
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TimeLimitedJudgeTest {
    @Test
    fun `slow judge falls back to the local judge`() {
        val s = Scenarios.ALL.first { it.id == "me_late_signal" }
        val slow = Judge { r, i -> Thread.sleep(2_000); LocalJudge.judge(r, i) }
        val out = run(s.frames, s.ctx, TimeLimitedJudge(slow, timeoutMs = 100))
        assertNotNull(out.judgeError)
        assertTrue(out.result.judge!!.fallback)
        assertEquals("estimate", out.result.status)
    }

    @Test
    fun `fast judge passes through`() {
        val s = Scenarios.ALL.first { it.id == "me_late_signal" }
        val out = run(s.frames, s.ctx, TimeLimitedJudge(LocalJudge, timeoutMs = 3000))
        assertEquals(null, out.judgeError)
        assertEquals(80.0, out.result.likely)
    }
}

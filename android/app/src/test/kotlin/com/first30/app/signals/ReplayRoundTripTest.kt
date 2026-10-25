package com.first30.app.signals

import com.first30.core.engine.run
import com.first30.core.model.Frame
import com.first30.core.model.JsScalar
import com.first30.core.scenarios.Scenario
import com.first30.core.scenarios.Scenarios
import com.first30.core.scenarios.Synth
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** Scenario → FakeVehicleSource → live 10 Hz SignalRingBuffer → snapshot on the trigger must equal the scenario frames. */
class ReplayRoundTripTest {

    private class Capture(private val buffer: SignalRingBuffer) : VehicleSink {
        var event: TriggerEvent? = null
        var frames: List<Frame>? = null
        override fun onSignal(update: SignalUpdate) = buffer.update(update)
        override fun onTrigger(event: TriggerEvent) {
            this.event = event
            frames = buffer.snapshot(event.atMs)
        }
    }

    private suspend fun TestScope.replay(frames: List<Frame>, kind: String, headingPeriodMs: Long? = null, startAt: Long = 0): Capture {
        val clock = MonoClock.of { testScheduler.currentTime }
        if (startAt > 0) testScheduler.advanceTimeBy(startAt)
        val buffer = SignalRingBuffer(clock)
        backgroundScope.launch { buffer.runSampler() }
        val cap = Capture(buffer)
        FakeVehicleSource(frames, kind, this, clock, headingPeriodMs).replay(cap)
        return cap
    }

    private fun summary(s: Scenario, frames: List<Frame>): List<Any?> {
        val r = run(frames, s.ctx).result
        return listOf(r.status, r.option, r.likely, r.low, r.high, r.confirm != null)
    }

    @TestFactory
    fun `all scenarios round-trip exactly`(): List<DynamicTest> = Scenarios.ALL.map { s ->
        DynamicTest.dynamicTest(s.id) {
            runTest {
                val cap = replay(s.frames, s.ctx.trigger!!, startAt = 1234)
                assertEquals(s.ctx.trigger, cap.event!!.kind)
                assertEquals(0L, cap.event!!.atMs % 100)
                assertEquals(s.frames, cap.frames, "replayed frames differ for ${s.id}")
                assertEquals(summary(s, s.frames), summary(s, cap.frames!!))
                val r = run(cap.frames!!, s.ctx).result
                assertEquals(s.expect.status, r.status)
                s.expect.likely?.let { assertEquals(it.toDouble(), r.likely) }
            }
        }
    }

    @Test
    fun `20 s record is cut to the 200-frame window`() = runTest {
        val s = Scenarios.ALL.first { it.id == "highway_late_signal" }
        val frames = Synth.synthFrames(s.spec.copy(fromS = -25.0))
        val cap = replay(frames, "impact")
        assertEquals(frames.takeLast(200), cap.frames)
    }

    @Test
    fun `1 Hz heading is held between updates`() = runTest {
        val s = Scenarios.ALL.first { it.id == "me_late_signal" }
        val cap = replay(s.frames, "impact", headingPeriodMs = 1000)
        val got = cap.frames!!
        assertEquals(s.frames.size, got.size)
        // heading only changes on whole seconds from the first frame; other channels are untouched
        got.zip(s.frames).forEachIndexed { i, (g, e) ->
            assertEquals(e.copy(headingDeg = JsScalar.Undefined), g.copy(headingDeg = JsScalar.Undefined))
            val src = s.frames[i - i % 10]
            assertEquals(src.headingDeg, g.headingDeg, "frame $i")
        }
        assertNotNull(run(got, s.ctx).result.status)
        assertTrue(got.none { it.headingDeg == JsScalar.Null })
    }
}

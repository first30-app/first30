package com.first30.app.signals

import com.first30.core.model.JsScalar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SignalRingBufferTest {
    private var now = 0L
    private val clock = MonoClock.of { now }

    @Test
    fun `nothing is recorded before any channel was received`() {
        val b = SignalRingBuffer(clock)
        repeat(5) { b.tick(it * 100L) }
        assertEquals(0, b.size)
        assertEquals(emptyList<Any>(), b.snapshot(400))
    }

    @Test
    fun `never-received channels are JS null, not 0 false or NONE`() {
        val b = SignalRingBuffer(clock)
        b.update(SignalUpdate.Speed(50.0, 0))
        b.tick(0)
        val f = b.snapshot(0).single()
        assertEquals(JsScalar.Num(50.0), f.speedKph)
        assertEquals(JsScalar.Null, f.headingDeg)
        assertEquals(JsScalar.Null, f.steeringDeg)
        assertEquals(JsScalar.Null, f.turnSignal)
        assertEquals(JsScalar.Null, f.brake)
        assertEquals(JsScalar.Null, f.airbag)
        assertEquals(JsScalar.Num(0.0), f.t)
    }

    @Test
    fun `explicitly unavailable value becomes null again`() {
        val b = SignalRingBuffer(clock)
        b.update(SignalUpdate.TurnSignal("LEFT", 0)); b.tick(0)
        b.update(SignalUpdate.TurnSignal(null, 50)); b.tick(100)
        val fs = b.snapshot(100)
        assertEquals(JsScalar.Str("LEFT"), fs[0].turnSignal)
        assertEquals(JsScalar.Null, fs[1].turnSignal)
    }

    @Test
    fun `hold-last for a slow channel and out-of-order updates are ignored`() {
        val b = SignalRingBuffer(clock)
        b.update(SignalUpdate.Heading(72.0, 0))
        for (i in 0 until 10) {
            b.update(SignalUpdate.Steering(i.toDouble(), i * 100L))
            b.tick(i * 100L)
        }
        b.update(SignalUpdate.Heading(10.0, -5)) // older than the stored one → ignored
        b.tick(1000)
        val fs = b.snapshot(1000)
        assertEquals(11, fs.size)
        assertTrue(fs.all { it.headingDeg == JsScalar.Num(72.0) })
        assertEquals(JsScalar.Num(9.0), fs.last().steeringDeg)
    }

    @Test
    fun `keeps only the most recent 20 s (200 frames)`() {
        val b = SignalRingBuffer(clock)
        assertEquals(200, b.capacity)
        for (i in 0 until 250) {
            b.update(SignalUpdate.Speed(i.toDouble(), i * 100L))
            b.tick(i * 100L)
        }
        assertEquals(200, b.size)
        val fs = b.snapshot(249 * 100L)
        assertEquals(200, fs.size)
        assertEquals(-19_900.0, fs.first().t.finiteOrNull())
        assertEquals(0.0, fs.last().t.finiteOrNull())
        assertEquals(JsScalar.Num(50.0), fs.first().speedKph)
    }

    @Test
    fun `retiming - t=0 at the trigger, time order, extra t=0 sample between ticks`() {
        val b = SignalRingBuffer(clock)
        for (i in 0..10) {
            b.update(SignalUpdate.Speed(i.toDouble(), i * 100L))
            b.tick(i * 100L)
        }
        b.update(SignalUpdate.Brake(true, 1030))
        val fs = b.snapshot(1040)
        val ts = fs.map { it.t.finiteOrNull()!! }
        assertEquals(ts.sorted(), ts)
        assertEquals(listOf(-1040.0, -940.0), ts.take(2))
        assertEquals(listOf(-40.0, 0.0), ts.takeLast(2))
        assertEquals(JsScalar.Null, fs[fs.size - 2].brake)
        assertEquals(JsScalar.Bool(true), fs.last().brake)

        // A trigger in the past excludes later samples; a trigger on a tick adds no extra sample.
        val past = b.snapshot(500)
        assertEquals(6, past.size)
        assertEquals(0.0, past.last().t.finiteOrNull())
        assertEquals(JsScalar.Num(5.0), past.last().speedKph)
    }

    @Test
    fun `steering minus zero survives`() {
        val b = SignalRingBuffer(clock)
        b.update(SignalUpdate.Steering(-0.0, 0)); b.tick(0)
        assertEquals(JsScalar.Num(-0.0), b.snapshot(0).single().steeringDeg)
    }

    @Test
    fun `late trigger - values that arrived after the impact instant do not leak into the t=0 frame`() {
        val b = SignalRingBuffer(clock)
        b.update(SignalUpdate.Steering(5.0, 0)); b.update(SignalUpdate.Airbag(false, 0)); b.update(SignalUpdate.Speed(60.0, 0))
        b.tick(100)
        b.update(SignalUpdate.Speed(58.0, 120))     // before the impact at 150 → used
        b.update(SignalUpdate.Steering(-30.0, 170)) // after the impact → not used
        b.update(SignalUpdate.Airbag(true, 180))    // after the impact → not used
        b.update(SignalUpdate.Brake(true, 190))     // first ever brake value, after the impact → null
        val f = b.snapshot(150).last()
        assertEquals(JsScalar.Num(0.0), f.t)
        assertEquals(JsScalar.Num(58.0), f.speedKph)
        assertEquals(JsScalar.Num(5.0), f.steeringDeg)
        assertEquals(JsScalar.Bool(false), f.airbag)
        assertEquals(JsScalar.Null, f.brake)
    }
}

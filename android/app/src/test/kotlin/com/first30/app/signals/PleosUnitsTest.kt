package com.first30.app.signals

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PleosUnitsTest {
    @Test
    fun `front-wheel angle is scaled to steering-wheel degrees, sign kept (left negative)`() {
        assertEquals(6.0, PleosUnits.steeringWheelDeg(0.4f)!!, 1e-6)
        assertEquals(-6.0, PleosUnits.steeringWheelDeg(-0.4f)!!, 1e-6)
        assertEquals(30.0, PleosUnits.steeringWheelDeg(30f, ratio = 1.0)!!, 1e-9)
    }

    @Test
    fun `missing or non-finite steering is null`() {
        assertNull(PleosUnits.steeringWheelDeg(null))
        assertNull(PleosUnits.steeringWheelDeg(Float.NaN))
        assertNull(PleosUnits.steeringWheelDeg(Float.POSITIVE_INFINITY))
        assertThrows<IllegalArgumentException> { PleosUnits.steeringWheelDeg(1f, ratio = 0.0) }
    }

    @Test
    fun `speed m per s to km per h`() {
        assertEquals(36.0, PleosUnits.speedKph(10f)!!, 1e-9)
        assertNull(PleosUnits.speedKph(null))
        assertNull(PleosUnits.speedKph(Float.NaN))
    }

    @Test
    fun `turn signal enum names map to engine strings, unknown is no record`() {
        assertEquals("NONE", PleosUnits.turnSignal("OFF"))
        assertEquals("LEFT", PleosUnits.turnSignal("LEFT"))
        assertEquals("RIGHT", PleosUnits.turnSignal("right"))
        assertNull(PleosUnits.turnSignal(null))
        assertNull(PleosUnits.turnSignal("2"))
    }
}

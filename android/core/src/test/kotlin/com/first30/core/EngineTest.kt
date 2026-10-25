package com.first30.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EngineTest {
    @Test
    fun versionIsSet() {
        assertTrue(Engine.VERSION.isNotBlank())
        assertEquals(4, Engine.SEAL_FORMAT_VERSION)
    }
}

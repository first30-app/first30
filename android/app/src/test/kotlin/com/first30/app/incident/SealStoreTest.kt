package com.first30.app.incident

import com.first30.core.engine.EngineClock
import com.first30.core.engine.run
import com.first30.core.model.Ctx
import com.first30.core.scenarios.Scenarios
import com.first30.core.seal.Canonical
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class SealStoreTest {
    @TempDir
    lateinit var tmp: File

    private fun seal() = run(Scenarios.ALL[0].frames, Scenarios.ALL[0].ctx.copy(now = "2026-10-25T00:00:00.000Z"), clock = EngineClock.fixed(0)).seal!!

    @Test
    fun `save, load, verify`() {
        val store = FileSealStore(File(tmp, "seals"))
        val s = seal()
        store.save(s)
        store.save(s) // idempotent
        assertEquals(listOf(s.hash), store.list())
        assertEquals(StoredSeal(s.hash, s.canonical), store.load(s.hash))
        assertTrue(store.verify(s.hash))
        assertTrue(File(tmp, "seals/${s.hash}.json").isFile)
        val body = File(tmp, "seals/${s.hash}.json").readText()
        for (k in listOf("latitude", "longitude", "\\\"lat\\\"", "\\\"lon\\\"")) assertFalse(body.contains(k), k)
    }

    @Test
    fun `tampered canonical fails verify`() {
        val store = FileSealStore(File(tmp, "seals"))
        val s = seal()
        store.save(s)
        val f = File(tmp, "seals/${s.hash}.json")
        f.writeText(f.readText().replace("\\\"version\\\":4", "\\\"version\\\":5"))
        assertTrue(f.readText().contains("version\\\":5"))
        assertFalse(store.verify(s.hash))
        f.writeText("not json")
        assertFalse(store.verify(s.hash))
        assertNull(store.load(s.hash))
    }

    @Test
    fun `refuses mismatched hash and bad names`() {
        val store = FileSealStore(tmp)
        assertThrows<IllegalArgumentException> { store.save(Canonical.sha256Hex("a"), "b") }
        assertThrows<IllegalArgumentException> { store.save("../x", "b") }
        assertFalse(store.verify("../../etc/passwd"))
        assertFalse(store.verify(Canonical.sha256Hex("missing")))
        val h = Canonical.sha256Hex("한글 \u0000 \"q\"")
        store.save(h, "한글 \u0000 \"q\"")
        assertTrue(store.verify(h))
        assertEquals(Ctx().toString(), Ctx().toString())
    }
}

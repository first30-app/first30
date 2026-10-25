package com.first30.core.seal

import com.first30.core.JsUndefined
import com.first30.core.golden.Golden
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CanonicalTest {

    /** Every { hash, canonical, payload } in tests/ and scenarios/ must re-serialize byte-identically. */
    @Test
    fun goldenSealsAreByteIdentical() {
        var seals = 0
        val failures = mutableListOf<String>()
        for (file in Golden.allFixtureFiles()) {
            Golden.walk(Golden.load(file)) { node ->
                if (node is Map<*, *> && node.keys == setOf("hash", "canonical", "payload")) {
                    seals++
                    val canonical = node["canonical"] as String
                    val hash = node["hash"] as String
                    val mine = stableStringify(node["payload"])
                    if (mine != canonical) {
                        val at = mine.zip(canonical).indexOfFirst { (a, b) -> a != b }.let { if (it < 0) minOf(mine.length, canonical.length) else it }
                        failures += "$file: canonical differs at $at: ...${mine.substring(maxOf(0, at - 40), minOf(mine.length, at + 40))}... vs ...${canonical.substring(maxOf(0, at - 40), minOf(canonical.length, at + 40))}..."
                    }
                    if (sha256Hex(canonical) != hash) failures += "$file: hash mismatch"
                }
            }
        }
        assertTrue(failures.isEmpty()) { "${failures.size} failures:\n" + failures.take(10).joinToString("\n") }
        assertEquals(384, seals, "seal object count")
    }

    /** test t051: sha256 over raw bytes matches the JS fallback results. */
    @Test
    fun sha256BytesMatchesFallbackFixture() {
        val file = Golden.index().tests.first { it.raw["fnCounts"].let { c -> c is Map<*, *> && "sha256Fallback" in c } }.file
        @Suppress("UNCHECKED_CAST")
        val calls = Golden.loadObject(file)["calls"] as List<Map<String, Any?>>
        var n = 0
        for (c in calls.filter { it["fn"] == "sha256Fallback" }) {
            val bytes = (c["args"] as List<*>)[0] as ByteArray
            assertEquals(c["result"], Canonical.sha256Hex(bytes)); n++
        }
        assertTrue(n > 0)
    }

    /** numbers.json (test/dump-numbers.js): String(x) and JSON.stringify(x) for ~29k doubles. */
    @Test
    fun numberCorpusMatchesNode() {
        val root = Json.parseToJsonElement(Golden.resourceText("numbers.json")).jsonObject
        val cases = root["cases"]!!.jsonArray
        val bad = mutableListOf<String>()
        for (row in cases) {
            val (hex, str, js) = row.jsonArray.map { it.jsonPrimitive.content }
            val d = java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong(hex, 16))
            val s = jsNumberToString(d)
            val j = stableStringify(d)
            if (s != str || j != js) bad += "$hex: String=$s (node $str) JSON=$j (node $js)"
        }
        assertTrue(bad.isEmpty()) { "${bad.size}/${cases.size} mismatches:\n" + bad.take(20).joinToString("\n") }
        assertTrue(cases.size >= 20_000)
    }

    @Test
    fun numberUnitCases() {
        assertEquals("0", jsNumberToString(-0.0))
        assertEquals("0", stableStringify(-0.0))
        assertEquals("1e+21", jsNumberToString(1e21))
        assertEquals("100000000000000000000", jsNumberToString(1e20))
        assertEquals("1e-7", jsNumberToString(1e-7))
        assertEquals("0.000001", jsNumberToString(1e-6))
        assertEquals("1.5e-7", jsNumberToString(1.5e-7))
        assertEquals("0.30000000000000004", jsNumberToString(0.1 + 0.2))
        assertEquals("5e-324", jsNumberToString(Double.MIN_VALUE))
        assertEquals("1.7976931348623157e+308", jsNumberToString(Double.MAX_VALUE))
        assertEquals("123456789012345680000", jsNumberToString(123456789012345680000.0))
        assertEquals("2e-323", jsNumberToString(java.lang.Double.longBitsToDouble(4)))
        assertEquals("-12.5", jsNumberToString(-12.5))
        assertEquals("NaN", jsNumberToString(Double.NaN))
        assertEquals("-Infinity", jsNumberToString(Double.NEGATIVE_INFINITY))
        assertEquals("null", stableStringify(Double.NaN))
        assertEquals("null", stableStringify(Double.POSITIVE_INFINITY))
        assertEquals("3", stableStringify(3))
        assertEquals("9007199254740992", stableStringify(9007199254740993L)) // Long → double, as JS
        // JDK 17 Double.toString gives "1.9999999999999998E23" and "9.999999999999999E22" here (not shortest; fixed in JDK 19).
        assertEquals("2e+23", jsNumberToString(2e23))
        assertEquals("1e+23", jsNumberToString(1e23))
    }

    @Test
    fun stringEscaping() {
        assertEquals("\"a\\\"b\\\\c\"", stableStringify("a\"b\\c"))
        assertEquals("\"\\b\\f\\n\\r\\t\\u0000\\u001f\"", stableStringify("\b\u000C\n\r\t\u0000\u001F"))
        assertEquals("\"\u007f  한글/\"", stableStringify("\u007f  한글/"))
        assertEquals("\"\\ud800x\\udc00\"", stableStringify("\uD800x\uDC00"))
        assertEquals("\"🚗\"", stableStringify("🚗")) // paired surrogates stay raw
        assertEquals("\"\\udc00\\ud800\"", stableStringify("\uDC00\uD800"))
    }

    @Test
    fun structureRules() {
        val fn = Golden.FnRef("f")
        assertEquals("null", stableStringify(JsUndefined))
        assertEquals("null", stableStringify(null))
        assertEquals("[null,null,1,]", stableStringify(listOf(JsUndefined, null, 1.0, fn)))
        assertEquals(
            "{\"A\":2,\"B\":null,\"a\":1,\"🚗\":4,\"￿\":3}",
            stableStringify(mapOf("a" to 1, "￿" to 3, "🚗" to 4, "u" to JsUndefined, "f" to fn, "B" to null, "A" to 2)),
        )
        assertEquals("{}", stableStringify(emptyMap<String, Any?>()))
        assertEquals("{\"0\":1,\"1\":255,\"10\":0,\"2\":0}", stableStringify(byteArrayOf(1, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0)).let {
            // keys sorted as strings: "0","1","10","2",...
            it.replace(Regex(",\"[3-9]\":0"), "")
        })
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc"))
    }
}

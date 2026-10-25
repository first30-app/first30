package com.first30.core.engine

import com.first30.core.golden.Golden
import com.first30.core.golden.JsDiff
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 * Kotlin prepare vs the golden fixtures.
 *
 * 1. golden/prepare.json: the full `prepare(frames)` output `{ pre, quality }` for every frame set (frames/<id>.json,
 *    source "frames") and for short inline frame-array args of recorded analyzeWindow / run calls (source "inline",
 *    id = first 16 hex of sha256 of the call's encoded args[0] JSON). Exact, including the sign of zero.
 * 2. Every recorded top-level `prepare` call (tests/NNN.json, fn "prepare"), if any.
 * 3. Every analyzeWindow evaluation (see [GoldenCases]): its `quality` is prepare's quality object, later extended by
 *    analyzeWindow (`steeringSign`, `headingLagSec`, and `steeringAvailable` forced to false when the sign check says
 *    'reversed'). Undoing exactly those changes must give `prepare(frames).quality`.
 */
class PrepareTest {
    private fun sha16(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.substring(0, 16)

    /** id → decoded inline frame array, from every analyzeWindow / run call whose args[0] is a plain JSON array. */
    private val inlineInputs: Map<String, Any?> by lazy {
        val out = HashMap<String, Any?>()
        for (t in Golden.index().tests) {
            val calls = Golden.loadJson(t.file).jsonObject["calls"]!!.jsonArray
            for (c in calls) {
                val co = c.jsonObject
                val fn = co["fn"]?.jsonPrimitive?.content
                if (fn != "analyzeWindow" && fn != "run") continue
                val a0 = (co["args"] as? JsonArray)?.getOrNull(0) as? JsonArray ?: continue
                out.putIfAbsent(sha16(a0.toString()), Golden.decode(a0))
            }
        }
        out
    }

    @Test
    fun prepareJsonEveryFrameSetMatches() {
        val root = Golden.loadJson("prepare.json").jsonObject
        val entries = root["entries"] as JsonObject
        val failures = ArrayList<String>()
        var compared = 0
        var threwCases = 0
        for ((id, e) in entries) {
            val eo = e.jsonObject
            val source = eo["source"]!!.jsonPrimitive.content
            val input: Any? = when (source) {
                "frames" -> Golden.load("frames/$id.json")
                "inline" -> if (inlineInputs.containsKey(id)) inlineInputs[id] else { failures.add("$id: inline input not found"); continue }
                else -> { failures.add("$id: unknown source $source"); continue }
            }
            compared++
            if (eo.containsKey("threw")) {
                threwCases++
                val threw = runCatching { prepare(JsDiff.framesOf(input)) }.isFailure
                if (!threw) failures.add("$id: JS threw ${eo["threw"]}, Kotlin did not")
                continue
            }
            val expected = Golden.decode(eo["result"]!!)
            val d = JsDiff().compare(expected, prepare(JsDiff.framesOf(input)).toJs())
            if (!d.ok) failures.add("$id ($source)\n    " + d.diffs.take(12).joinToString("\n    "))
        }
        println("[PrepareTest] compared ${compared}/${entries.size} prepare.json entries (threw=$threwCases), mismatches=${failures.size}")
        assertTrue(entries.size >= 500, "expected the full prepare.json set, got ${entries.size}")
        assertTrue(failures.isEmpty(), "${failures.size} mismatches:\n" + failures.take(25).joinToString("\n"))
    }

    @Test
    fun recordedPrepareCallsMatch() {
        val cases = GoldenCases.prepare
        val failures = ArrayList<String>()
        for (c in cases) {
            val d = JsDiff().compare(c.expected, prepare(JsDiff.framesOf(c.frames)).toJs())
            if (!d.ok) failures.add("${c.label}\n    " + d.diffs.take(12).joinToString("\n    "))
        }
        println("[PrepareTest] compared ${cases.size} recorded prepare calls, mismatches=${failures.size}")
        assertTrue(failures.isEmpty(), failures.take(25).joinToString("\n"))
    }

    @Test
    fun qualityMatchesEveryAnalyzeWindowResult() {
        val cases = GoldenCases.analyzeWindow
        val failures = ArrayList<String>()
        for (c in cases) {
            @Suppress("UNCHECKED_CAST")
            val q = LinkedHashMap((c.expected as Map<String, Any?>)["quality"] as Map<String, Any?>)
            if (q.remove("steeringSign") == "reversed") q["steeringAvailable"] = true
            q.remove("headingLagSec")
            val d = JsDiff().compare(q, prepare(JsDiff.framesOf(c.frames)).quality.toJs(), "quality")
            if (!d.ok) failures.add("${c.label}\n    " + d.diffs.joinToString("\n    "))
        }
        println("[PrepareTest] compared prepare().quality against ${cases.size} analyzeWindow results, mismatches=${failures.size}")
        assertTrue(failures.isEmpty(), "${failures.size} mismatches:\n" + failures.take(25).joinToString("\n"))
    }
}

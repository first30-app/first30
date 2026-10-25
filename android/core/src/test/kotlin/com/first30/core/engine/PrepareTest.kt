package com.first30.core.engine

import com.first30.core.golden.JsDiff
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Kotlin prepare vs the golden fixtures.
 *
 * 1. Every recorded top-level `prepare` call (tests/NNN.json, fn "prepare") — full `{ pre, quality }` result, exact.
 *    (The current JS test suite never calls F.prepare directly, so this set may be empty; it is reported, not skipped.)
 * 2. Every analyzeWindow evaluation (see [GoldenCases]): its `quality` is prepare's quality object, later extended by
 *    analyzeWindow (engine.js 886~891행: `steeringSign`, `headingLagSec`, and `steeringAvailable` forced to false when the
 *    sign check says 'reversed'). Undoing exactly those changes must give `prepare(frames).quality`.
 */
class PrepareTest {
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

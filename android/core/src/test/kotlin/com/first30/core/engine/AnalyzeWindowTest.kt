package com.first30.core.engine

import com.first30.core.golden.JsDiff
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Kotlin analyzeWindow vs every recorded analyzeWindow evaluation in the golden fixtures (exact, including the sign of zero). */
class AnalyzeWindowTest {
    @Test
    fun everyRecordedCallMatches() {
        val cases = GoldenCases.analyzeWindow
        assertTrue(cases.size > 300, "expected the full golden set, got ${cases.size}")
        val failures = ArrayList<String>()
        for (c in cases) {
            val kt = analyzeWindow(JsDiff.framesOf(c.frames), JsDiff.ctxOf(c.ctx)).toJs()
            val d = JsDiff().compare(c.expected, kt)
            if (!d.ok) failures.add("${c.label}\n    " + d.diffs.take(12).joinToString("\n    "))
        }
        println("[AnalyzeWindowTest] compared ${cases.size} analyzeWindow results, mismatches=${failures.size}")
        assertTrue(failures.isEmpty(), "${failures.size}/${cases.size} mismatches:\n" + failures.take(25).joinToString("\n"))
    }
}

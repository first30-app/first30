package com.first30.core

/**
 * Judgment engine — Kotlin port of the JS engine (engine/engine.js, charts.js, local-judge.js, jev-client.js) (pure Kotlin/JVM, no Android dependencies).
 *
 * Entry point: [com.first30.core.engine.run] `(frames, ctx, judge, opts, clock)` → `RunOutput` (`toJs()` = the JS run() object).
 * Stages (com.first30.core.engine): prepare → analyzeWindow → renderFacts → buildJevRequest → Judge (LocalJudge / JevJudge)
 * → computeFault → companion, plus sealEvidence. Verified against the JS golden fixtures (android/core/src/test/resources/golden).
 */
object Engine {
    const val VERSION: String = "0.0.1"

    /** Evidence seal format version (see engine/engine.js sealEvidence). */
    const val SEAL_FORMAT_VERSION: Int = 4
}

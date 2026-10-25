package com.first30.core.engine

import com.first30.core.golden.Golden

/**
 * Every recorded analyzeWindow / prepare evaluation in the golden fixtures.
 *
 * analyzeWindow sources:
 *  1. tests/NNN.json calls with fn "analyzeWindow" (args [frames, ctx] → result)
 *  2. tests/NNN.json calls with fn "run": `result.metrics` is exactly `analyzeWindow(frames, ctx)` (engine.js 1706행;
 *     when `analysisError` is set it is `analyzeWindow([], ctx)` instead — handled too)
 *  3. scenarios/<id>.json `analyzeWindow` field (frames, ctx), and each `runs.*.output.metrics` (frames, runs.*.ctx)
 * prepare sources: tests/NNN.json calls with fn "prepare".
 */
object GoldenCases {
    class Case(val label: String, val frames: Any?, val ctx: Any?, val expected: Any?)

    @Suppress("UNCHECKED_CAST")
    private fun calls(file: String): List<Map<String, Any?>> =
        (Golden.loadObject(file)["calls"] as List<Map<String, Any?>>)

    @Suppress("UNCHECKED_CAST")
    val analyzeWindow: List<Case> by lazy {
        val out = ArrayList<Case>()
        val ix = Golden.index()
        for (t in ix.tests) {
            calls(t.file).forEachIndexed { ci, c ->
                if (c.containsKey("threw")) return@forEachIndexed
                val args = c["args"] as List<Any?>
                when (c["fn"]) {
                    "analyzeWindow" -> out.add(Case("${t.file}#$ci analyzeWindow", args[0], args.getOrNull(1), c["result"]))
                    "run" -> {
                        val r = c["result"] as Map<String, Any?>
                        val frames = if (r["analysisError"] != null) emptyList<Any?>() else args[0]
                        out.add(Case("${t.file}#$ci run.metrics", frames, args.getOrNull(1), r["metrics"]))
                    }
                }
            }
        }
        for (s in ix.scenarios) {
            val g = Golden.loadObject(s.file)
            out.add(Case("${s.file} analyzeWindow", g["frames"], g["ctx"], g["analyzeWindow"]))
            (g["runs"] as Map<String, Any?>).forEach { (k, v) ->
                val run = v as Map<String, Any?>
                val output = run["output"] as Map<String, Any?>
                val frames = if (output["analysisError"] != null) emptyList<Any?>() else g["frames"]
                out.add(Case("${s.file} runs.$k.metrics", frames, run["ctx"], output["metrics"]))
            }
        }
        out
    }

    @Suppress("UNCHECKED_CAST")
    val prepare: List<Case> by lazy {
        val out = ArrayList<Case>()
        for (t in Golden.index().tests) {
            calls(t.file).forEachIndexed { ci, c ->
                if (c["fn"] == "prepare" && !c.containsKey("threw")) {
                    val args = c["args"] as List<Any?>
                    out.add(Case("${t.file}#$ci prepare", args[0], null, c["result"]))
                }
            }
        }
        out
    }
}

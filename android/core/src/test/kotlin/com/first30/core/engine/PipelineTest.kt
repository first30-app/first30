package com.first30.core.engine

import com.first30.core.JsUndefined
import com.first30.core.golden.Golden
import com.first30.core.golden.JsDiff
import com.first30.core.model.Ctx
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Kotlin renderFacts → buildJevRequest → parseStatement → localJudge → validateJevAnswers → computeFault → companion →
 * sealEvidence → run vs every recorded call in the golden fixtures (tests/NNN.json and scenarios/<id>.json).
 *
 * Exact deep equality via [JsDiff] (key presence, sign of zero); only `latencyMs` is ignored (clock value).
 * Calls that threw in JS must throw in Kotlin. Run calls with the test fakes `jevJudge` (engine.test.js
 * `makeJevJudge({ fetchImpl })`) are reproduced with [JevJudge] and fake transports — see [judgeFor].
 */
class PipelineTest {
    /** 2026-01-01T00:00:00.000Z — the frozen clock of the golden dump (index.json frozenClock). */
    private val clock = EngineClock.fixed(1767225600000L)

    class Call(val label: String, val file: String, val fn: String, val args: List<Any?>, val result: Any?, val threw: Any?)

    @Suppress("UNCHECKED_CAST")
    private val calls: List<Call> by lazy {
        val out = ArrayList<Call>()
        for (t in Golden.index().tests) {
            (Golden.loadObject(t.file)["calls"] as List<Map<String, Any?>>).forEachIndexed { i, c ->
                out.add(Call("${t.file}#$i", t.file, c["fn"] as String, c["args"] as List<Any?>, c["result"], c["threw"]))
            }
        }
        out
    }

    /** Scenario runs: scenarios/<id>.json runs.<k> = { ctx, output } with frames, F.localJudge, no opts. */
    @Suppress("UNCHECKED_CAST")
    private val scenarioRuns: List<Call> by lazy {
        val out = ArrayList<Call>()
        for (s in Golden.index().scenarios) {
            val g = Golden.loadObject(s.file)
            (g["runs"] as Map<String, Any?>).forEach { (k, v) ->
                val r = v as Map<String, Any?>
                out.add(Call("${s.file} runs.$k", s.file, "run", listOf(g["frames"], r["ctx"], Golden.FnRef("localJudge")), r["output"], null))
            }
        }
        out
    }

    private fun byFn(fn: String) = calls.filter { it.fn == fn }

    @Suppress("UNCHECKED_CAST")
    private fun obj(v: Any?) = v as Map<String, Any?>

    private fun ctx(v: Any?): Ctx? = JsDiff.ctxOf(v)

    private fun opts(v: Any?): RunOpts? {
        if (v == null || v === JsUndefined) return null
        val m = obj(v)
        require(setOf("model", "onJudgeFailure").containsAll(m.keys)) { "unknown opts ${m.keys}" }
        return RunOpts(m["model"] as String?, m["onJudgeFailure"] as String?)
    }

    private fun withoutLatency(v: Any?): Any? = if (v is Map<*, *>) LinkedHashMap(v).also { it.remove("latencyMs") } else v

    private fun fail(failures: List<String>, n: Int, what: String) {
        println("[PipelineTest] $what: compared $n, mismatches=${failures.size}")
        assertTrue(failures.isEmpty(), "$what: ${failures.size}/$n mismatches:\n" + failures.take(15).joinToString("\n"))
    }

    private fun diff(label: String, js: Any?, kt: Any?, failures: MutableList<String>) {
        val d = JsDiff().compare(js, kt)
        if (!d.ok) failures.add("$label\n    " + d.diffs.take(12).joinToString("\n    "))
    }

    // ---------------- test fakes (engine.test.js) ----------------

    /** engine.test.js `fetchImpl: async () => ({ ok: false, status })`. */
    private fun failingTransport(status: Int) = JevTransport { _, _, _, _ -> JevHttpResponse(false, status) { error("no body") } }

    /** engine.test.js 'Jev 정상 응답을 그대로 쓴다 (모의 fetch)' — `fake` returns this body. */
    private val okTransport = JevTransport { url, headers, body, _ ->
        check(url == JEV_ENDPOINT && headers["Authorization"] == "Bearer k" && body.contains("\"model\":\"jev-latest\""))
        JevHttpResponse(true, 200) {
            fun noul(x: Double) = linkedMapOf<String, Any?>("type" to "noul", "noul" to x)
            linkedMapOf<String, Any?>(
                "provider" to "TypeSafe",
                "answers" to linkedMapOf<String, Any?>(
                    "chart" to linkedMapOf<String, Any?>(
                        "type" to "choice", "choice" to "c252_me_A",
                        "probabilities" to linkedMapOf<String, Any?>("c252_me_A" to 0.91, "none" to 0.05, "c252_2" to 0.03, "c252_me_B" to 0.01),
                        "confidence" to 0.88,
                    ),
                    "evidence_sufficient" to noul(0.82),
                    "self_lane_change" to noul(0.05),
                    "other_signal_fail" to noul(0.86),
                    "forbidden_zone" to noul(0.04),
                    "bus_lane" to noul(0.03),
                ),
            )
        }
    }

    /** args[2] of a recorded run → Kotlin judge. */
    private fun judgeFor(c: Call, v: Any?): Judge? = when {
        v == null || v === JsUndefined -> null
        v is Golden.FnRef && v.name == "localJudge" -> LocalJudge
        v is Golden.FnRef && v.name == "jevJudge" -> when {
            c.file.startsWith("tests/t048") -> JevJudge(apiKey = "x", transport = failingTransport(503)) // failing 503
            c.file.startsWith("tests/t066") -> JevJudge(apiKey = "x", transport = failingTransport(500)) // failing 500
            c.file.startsWith("tests/t049") -> JevJudge(apiKey = "k", transport = okTransport)
            else -> error("${c.label}: unknown jevJudge fake")
        }
        else -> error("${c.label}: unsupported judge $v")
    }

    // ---------------- per function ----------------

    @Test
    fun renderFactsCalls() {
        val f = ArrayList<String>()
        val cs = byFn("renderFacts")
        for (c in cs) diff(c.label, c.result, renderFacts(JsInput.windowMetrics(obj(c.args[0])), ctx(c.args.getOrNull(1))).toJs(), f)
        // stage check: every run's facts == renderFacts(run.metrics, ctx)
        var n = 0
        for (c in byFn("run") + scenarioRuns) {
            val out = obj(c.result)
            if (out["facts"] == null) continue
            n++
            diff("${c.label} facts", out["facts"], renderFacts(JsInput.windowMetrics(obj(out["metrics"])), ctx(c.args.getOrNull(1))).toJs(), f)
        }
        fail(f, cs.size + n, "renderFacts (${cs.size} direct + $n run.facts)")
        assertEquals(5, cs.size)
    }

    @Test
    fun buildJevRequestCalls() {
        val f = ArrayList<String>()
        val cs = byFn("buildJevRequest")
        for (c in cs) diff(c.label, c.result, buildJevRequest(JsInput.facts(obj(c.args[0])), opts(c.args.getOrNull(1))).toJs(), f)
        var n = 0
        for (c in byFn("run") + scenarioRuns) {
            val out = obj(c.result)
            if (out["request"] == null) continue
            n++
            diff("${c.label} request", out["request"], buildJevRequest(JsInput.facts(obj(out["facts"])), opts(c.args.getOrNull(3))).toJs(), f)
        }
        fail(f, cs.size + n, "buildJevRequest (${cs.size} direct + $n run.request)")
    }

    @Test
    fun redactViaFacts() {
        // engine.test.js '판정 모델에 보내는 진술에서 차량 번호·전화번호를 지운다' expectations, plus a few direct checks
        assertEquals("[차량번호] 차가 들어왔어요 제 번호는 [전화번호]", redact("12가 3456 차가 들어왔어요 제 번호는 010-1234-5678"))
        assertEquals("", redact(null))
        assertEquals("[차량번호]", redact("123허4567"))
    }

    @Test
    fun parseStatementCalls() {
        val f = ArrayList<String>()
        val cs = byFn("parseStatement")
        for (c in cs) {
            val a = c.args.getOrNull(0)
            require(a == null || a === JsUndefined || a is String) { "${c.label}: non-string arg $a" }
            diff(c.label, c.result, parseStatement(a as? String).toJs(), f)
        }
        // stage check: every localJudge result's cues
        var n = 0
        for (c in byFn("run") + scenarioRuns) {
            val judged = obj(c.result)["judged"] as? Map<*, *> ?: continue
            if (judged["model"] != "local-standin") continue
            n++
            diff("${c.label} judged.cues", judged["cues"], parseStatement(ctx(c.args.getOrNull(1))?.driverStatement).toJs(), f)
        }
        fail(f, cs.size + n, "parseStatement (${cs.size} direct + $n judged.cues)")
    }

    @Test
    fun localJudgeCalls() {
        val f = ArrayList<String>()
        val cs = byFn("localJudge")
        for (c in cs) {
            val input = obj(c.args[1])
            val m = input["metrics"]?.let { JsInput.windowMetrics(obj(it)) }
            diff(c.label, c.result, localJudge(c.args[0], m, ctx(input["ctx"])).raw, f)
        }
        var n = 0
        for (c in byFn("run") + scenarioRuns) {
            val out = obj(c.result)
            val judged = out["judged"] as? Map<*, *> ?: continue
            if (judged["model"] != "local-standin") continue
            n++
            diff("${c.label} judged", judged, localJudge(null, JsInput.windowMetrics(obj(out["metrics"])), ctx(c.args.getOrNull(1))).raw, f)
        }
        fail(f, cs.size + n, "localJudge (${cs.size} direct + $n run.judged)")
    }

    @Test
    fun validateJevAnswersCalls() {
        val f = ArrayList<String>()
        val cs = byFn("validateJevAnswers")
        for (c in cs) {
            val r = runCatching { validateJevAnswers(obj(c.args[0]), c.args[1]) }
            if (c.threw != null) {
                val msg = (c.threw as Golden.ErrorRef).message
                if (r.isSuccess) f.add("${c.label}: JS threw '$msg', Kotlin returned")
                else if (r.exceptionOrNull()!!.message != msg) f.add("${c.label}: message js='$msg' kt='${r.exceptionOrNull()!!.message}'")
            } else {
                if (r.isFailure) f.add("${c.label}: Kotlin threw ${r.exceptionOrNull()}")
                else diff(c.label, c.result, r.getOrNull(), f)
            }
        }
        fail(f, cs.size, "validateJevAnswers")
        assertEquals(5, cs.size)
    }

    @Test
    fun makeJevJudgeCalls() {
        // makeJevJudge returns the function `jevJudge`; in Kotlin it is a JevJudge (its behaviour is compared in runCalls).
        val cs = byFn("makeJevJudge")
        for (c in cs) {
            assertEquals(Golden.FnRef("jevJudge"), c.result, c.label)
            val a = obj(c.args[0])
            val j: Judge = JevJudge(apiKey = a["apiKey"] as String?, transport = failingTransport(0))
            assertTrue(j is JevJudge)
        }
        println("[PipelineTest] makeJevJudge: compared ${cs.size}")
        assertEquals(3, cs.size)
    }

    @Test
    fun computeFaultCalls() {
        val f = ArrayList<String>()
        val cs = byFn("computeFault")
        for (c in cs) diff(c.label, c.result, computeFault(c.args[0], JsInput.windowMetrics(obj(c.args[1])), ctx(c.args.getOrNull(2))).toJs(), f)
        // stage check: run.result (without judge) == computeFault(judged.answers, metrics, ctx), when run did not override it
        var n = 0
        for (c in byFn("run") + scenarioRuns) {
            val out = obj(c.result)
            if (out["judgeError"] != null && opts(c.args.getOrNull(3))?.onJudgeFailure == "abstain") continue
            val judged = out["judged"] as? Map<*, *> ?: continue
            n++
            val expected = LinkedHashMap(obj(out["result"])).also { it.remove("judge") }
            diff("${c.label} result", expected, computeFault(judged["answers"], JsInput.windowMetrics(obj(out["metrics"])), ctx(c.args.getOrNull(1))).toJs(), f)
        }
        fail(f, cs.size + n, "computeFault (${cs.size} direct + $n run.result)")
        assertEquals(12, cs.size)
    }

    @Test
    fun sealEvidenceCalls() {
        val f = ArrayList<String>()
        val cs = byFn("sealEvidence")
        for (c in cs) {
            val m = c.args.getOrNull(1)?.takeIf { it !== JsUndefined }?.let { JsInput.windowMetrics(obj(it)) }
            diff(c.label, c.result, sealEvidence(JsDiff.framesOf(c.args[0]), m, ctx(c.args.getOrNull(2)), clock).toJs(), f)
        }
        fail(f, cs.size, "sealEvidence")
        assertEquals(7, cs.size)
    }

    @Test
    fun runCalls() {
        val f = ArrayList<String>()
        val cs = byFn("run") + scenarioRuns
        var threw = 0
        val judges = HashMap<String, Int>()
        for (c in cs) {
            val judgeArg = c.args.getOrNull(2)
            judges.merge((judgeArg as? Golden.FnRef)?.name ?: "default", 1, Int::plus)
            val r = runCatching {
                run(JsDiff.framesOf(c.args[0]), ctx(c.args.getOrNull(1)), judgeFor(c, judgeArg), opts(c.args.getOrNull(3)), clock)
            }
            if (c.threw != null) { threw++; if (r.isSuccess) f.add("${c.label}: JS threw, Kotlin returned"); continue }
            if (r.isFailure) { f.add("${c.label}: Kotlin threw ${r.exceptionOrNull()}"); continue }
            diff(c.label, withoutLatency(c.result), withoutLatency(r.getOrThrow().toJs()), f)
        }
        println("[PipelineTest] run judges: $judges, threw=$threw")
        fail(f, cs.size, "run (${byFn("run").size} tests + ${scenarioRuns.size} scenario runs)")
        assertTrue(cs.size >= 380)
    }

    /** Every recorded top-level fn is either compared here or by another test class. */
    @Test
    fun everyRecordedFunctionIsCovered() {
        val covered = setOf(
            "run", "renderFacts", "buildJevRequest", "parseStatement", "localJudge", "validateJevAnswers", "makeJevJudge",
            "computeFault", "sealEvidence",
            "analyzeWindow", // AnalyzeWindowTest
            "prepare", // PrepareTest
            "sha256Fallback", // CanonicalTest
            "synthFrames", // SynthTest (synth.json entries)
        )
        val fns = calls.map { it.fn }.toSet()
        assertTrue(covered.containsAll(fns), "uncovered recorded functions: ${fns - covered}")
    }
}

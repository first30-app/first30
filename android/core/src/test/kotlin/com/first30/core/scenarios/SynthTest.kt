package com.first30.core.scenarios

import com.first30.core.JsUndefined
import com.first30.core.golden.Golden
import com.first30.core.model.Ctx
import com.first30.core.model.Frame
import com.first30.core.model.JsScalar
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.math.abs

/**
 * Kotlin synthFrames / SCENARIOS vs the JS golden fixtures (golden/synth.json, golden/scenarios/<id>.json, golden/frames/).
 * Fixtures are read through com.first30.core.golden.Golden.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SynthTest {

    // ---------- golden reader: com.first30.core.golden.Golden ----------
    private val Undef = JsUndefined // {"$undef":true}

    /** [Golden.load] with function tags as the plain string "\$fn" (the form [specMap] uses for function-valued options). */
    private fun load(path: String): Any? = fnAsTag(Golden.load(path))

    private fun fnAsTag(v: Any?): Any? = when (v) {
        is Golden.FnRef -> "\$fn"
        is Map<*, *> -> v.entries.associate { (k, x) -> k to fnAsTag(x) }
        is List<*> -> v.map { fnAsTag(it) }
        else -> v
    }

    private fun scalar(v: Any?): JsScalar = when (v) {
        Undef -> JsScalar.Undefined
        null -> JsScalar.Null
        is Double -> JsScalar.Num(v)
        is String -> JsScalar.Str(v)
        is Boolean -> JsScalar.Bool(v)
        else -> error("not scalar: $v")
    }

    // ---------- stats ----------
    private var maxTrigDiff = 0.0
    private var trigInexact = 0
    private var zeroSignMismatch = 0
    private var negZeroInJs = 0
    private var negZeroInKt = 0
    private var framesCompared = 0

    @AfterAll
    fun report() {
        println(
            "[SynthTest] frames=$framesCompared maxTrigDiff=$maxTrigDiff trigInexact=$trigInexact " +
                "-0 in JS=$negZeroInJs -0 in Kotlin=$negZeroInKt zeroSignMismatch=$zeroSignMismatch",
        )
    }

    private fun isNegZero(d: Double) = d == 0.0 && 1.0 / d < 0

    private fun compareFrames(label: String, golden: List<*>, actual: List<Frame>) {
        assertEquals(golden.size, actual.size, "$label: frame count")
        golden.forEachIndexed { i, g ->
            @Suppress("UNCHECKED_CAST")
            val gm = g as Map<String, Any?>
            assertEquals(Frame.KEYS.toSet(), gm.keys, "$label[$i]: keys")
            val a = actual[i].toJsMap()
            framesCompared++
            for (k in Frame.KEYS) {
                val want = scalar(gm[k])
                val got = JsScalar.fromAny(a[k])
                val where = "$label[$i].$k"
                if (want is JsScalar.Num && got is JsScalar.Num) {
                    val w = want.value
                    val x = got.value
                    if (isNegZero(w)) negZeroInJs++
                    if (isNegZero(x)) negZeroInKt++
                    if (k == "headingDeg") { // atan/cos derived
                        val d = abs(w - x)
                        if (d > maxTrigDiff) maxTrigDiff = d
                        if (d != 0.0) trigInexact++
                        assertTrue(d <= 1e-9, "$where: $x vs JS $w")
                    } else {
                        assertTrue(w == x, "$where: $x vs JS $w") // == treats -0.0 == 0.0
                    }
                    if (w == 0.0 && x == 0.0 && isNegZero(w) != isNegZero(x)) zeroSignMismatch++
                } else {
                    assertEquals(want, got, where)
                }
            }
        }
    }

    // ---------- golden spec → SynthSpec ----------
    private fun d(v: Any?): Double? = when (v) {
        null, Undef -> null
        is Double -> v
        else -> error("not a number: $v")
    }

    @Suppress("UNCHECKED_CAST")
    private fun specOf(label: String, o: Map<String, Any?>): SynthSpec {
        val speed: SpeedProfile = when (val s = o["speed"]) {
            is Double -> SpeedProfile.constant(s)
            "\$fn" -> {
                val id = label.removePrefix("scenario:").substringBefore(':')
                Scenarios.byId(id).spec.speed
            }
            else -> error("$label: speed $s")
        }
        val lc = (o["laneChange"] as Map<String, Any?>?)?.let { LaneChangeSpec(d(it["startS"])!!, d(it["durS"])!!, it["dir"] as String) }
        val sig = (o["signal"] as Map<String, Any?>?)?.let { SignalSpec(d(it["fromS"])!!, it["dir"] as String, d(it["toS"])) }
        val known = setOf("speed", "laneChange", "signal", "brakeFromS", "airbag", "baseHeading", "fromS")
        assertTrue(known.containsAll(o.keys), "$label: unknown opts ${o.keys - known}")
        return SynthSpec(
            speed = speed, laneChange = lc, signal = sig, brakeFromS = d(o["brakeFromS"]),
            airbag = o["airbag"] == true, baseHeading = d(o["baseHeading"]), fromS = d(o["fromS"]),
        )
    }

    /** Kotlin SynthSpec in the same shape as the golden `spec` (speed function → "$fn"). */
    private fun specMap(s: SynthSpec): Map<String, Any?> = buildMap {
        put("speed", (s.speed as? SpeedProfile.Constant)?.kph ?: "\$fn")
        s.laneChange?.let { put("laneChange", mapOf("startS" to it.startS, "durS" to it.durS, "dir" to it.dir)) }
        s.signal?.let { put("signal", buildMap { put("fromS", it.fromS); it.toS?.let { t -> put("toS", t) }; put("dir", it.dir) }) }
        s.brakeFromS?.let { put("brakeFromS", it) }
        if (s.airbag) put("airbag", true)
        s.baseHeading?.let { put("baseHeading", it) }
        s.fromS?.let { put("fromS", it) }
    }

    /** Ctx.toJsMap with Int→Double and lists kept, comparable with decoded golden. */
    private fun ctxMap(c: Ctx): Map<String, Any?> = c.toJsMap()

    // ---------- tests ----------
    @Test
    fun synthEntriesMatchGolden() {
        @Suppress("UNCHECKED_CAST")
        val entries = (load("synth.json") as Map<String, Any?>)["entries"] as List<Map<String, Any?>>
        assertEquals(30, entries.size)
        for (e in entries) {
            val label = e["label"] as String
            @Suppress("UNCHECKED_CAST")
            val spec = specOf(label, e["opts"] as Map<String, Any?>)
            compareFrames(label, e["frames"] as List<*>, Synth.synthFrames(spec))
        }
    }

    @Test
    fun scenariosMatchGolden() {
        @Suppress("UNCHECKED_CAST")
        val index = (load("index.json") as Map<String, Any?>)["scenarios"] as List<Map<String, Any?>>
        assertEquals(index.map { it["id"] }, Scenarios.ALL.map { it.id }, "scenario order")
        assertEquals(12, Scenarios.ALL.size)
        for (sc in Scenarios.ALL) {
            @Suppress("UNCHECKED_CAST")
            val g = load("scenarios/${sc.id}.json") as Map<String, Any?>
            assertEquals(g["id"], sc.id)
            assertEquals(g["title"], sc.title, "${sc.id}: title")
            assertEquals(g["spec"], specMap(sc.spec), "${sc.id}: spec")
            assertEquals(g["ctx"], ctxMap(sc.ctx), "${sc.id}: ctx")
            assertEquals(g["expect"], sc.expect.toJsMap(), "${sc.id}: expect")
            compareFrames("scenario:${sc.id}", g["frames"] as List<*>, sc.frames)
        }
    }

    @Test
    fun brakingSpeedClosuresMatchKnownParameters() {
        // the dumper only recovered other_cutin_unclear's closure parameters; check them against the Kotlin closure
        @Suppress("UNCHECKED_CAST")
        val g = load("scenarios/other_cutin_unclear.json") as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val fn = g["speedFn"] as Map<String, Any?>
        val ref = brakingSpeed(fn["cruise"] as Double, fn["fromS"] as Double, fn["decelKphPerS"] as Double)
        val sp = Scenarios.byId("other_cutin_unclear").spec.speed
        var t = -10.0
        while (t <= 0.0) { assertEquals(ref.at(t), sp.at(t)); t += 0.05 }
    }

    @Test
    fun jsRoundSemantics() {
        val r = com.first30.core.model.JsMath::round
        assertEquals(3.0, r(2.5)); assertEquals(-2.0, r(-2.5)); assertEquals(1.0, r(0.5))
        assertTrue(isNegZero(r(-0.3))); assertTrue(isNegZero(r(-0.5))); assertTrue(isNegZero(r(-0.0)))
        assertEquals(0.0, r(0.49999999999999994)); assertEquals(-1.0, r(-0.5000000000000001))
        assertEquals(4503599627370497.0, r(4503599627370497.0))
        assertTrue(r(Double.NaN).isNaN())
    }
}

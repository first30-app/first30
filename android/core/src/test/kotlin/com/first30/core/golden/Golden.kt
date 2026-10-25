package com.first30.core.golden

import com.first30.core.JsFunction
import com.first30.core.JsUndefined
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Golden fixture loader (test/dump-golden.js 출력, 형식은 resources/golden/FORMAT.md).
 *
 * [load] 은 golden/ 아래 상대 경로의 파일을 classpath 에서 읽어 JS 값 모델로 풀어 준다:
 * - 객체 → `LinkedHashMap<String, Any?>`(파일 순서 유지), 배열 → `List<Any?>`, 문자열 → String, 참거짓 → Boolean, null → null
 * - 숫자 → 언제나 Double
 * - `{"$num":"NaN"|"Infinity"|"-Infinity"|"-0"}` → Double.NaN / ±Infinity / -0.0
 * - `{"$undef":true}` → [JsUndefined]
 * - `{"$frames":id}` → frames/<id>.json 을 (재귀적으로) 풀어 넣은 List (id 별로 캐시하되 매번 새 사본을 돌려준다)
 * - `{"$fn":name}` → [FnRef], `{"$error":msg}` → [ErrorRef], `{"$u8":hex}` → ByteArray
 * [index] 는 index.json 의 tests / scenarios 목록을 돌려준다.
 */
object Golden {
    private const val ROOT = "golden/"
    private val json = Json { isLenient = false }
    private val rawCache = HashMap<String, JsonElement>()

    data class FnRef(override val name: String) : JsFunction
    data class ErrorRef(val message: String)

    /** One row of index.json `tests` / `scenarios`. [file] is relative to golden/. */
    data class Entry(val name: String, val file: String, val raw: Map<String, Any?>)

    data class Index(val tests: List<Entry>, val scenarios: List<Entry>, val raw: Map<String, Any?>)

    /** Raw text of a classpath resource (path relative to the resources root). */
    fun resourceText(path: String): String {
        val stream = Golden::class.java.classLoader.getResourceAsStream(path)
            ?: error("test resource not found: $path")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** Parsed (still tagged) JSON of golden/<relPath>. */
    fun loadJson(relPath: String): JsonElement =
        synchronized(rawCache) { rawCache.getOrPut(relPath) { json.parseToJsonElement(resourceText(ROOT + relPath)) } }

    /** golden/<relPath> decoded into the JS value model. */
    fun load(relPath: String): Any? = decode(loadJson(relPath))

    /** Convenience: [load] cast to an object. */
    @Suppress("UNCHECKED_CAST")
    fun loadObject(relPath: String): Map<String, Any?> = load(relPath) as Map<String, Any?>

    fun index(): Index {
        val raw = loadObject("index.json")
        fun entries(key: String): List<Entry> = (raw[key] as? List<*>).orEmpty().map { e ->
            @Suppress("UNCHECKED_CAST") val m = e as Map<String, Any?>
            Entry(name = (m["name"] ?: m["id"]).toString(), file = m["file"] as String, raw = m)
        }
        return Index(entries("tests"), entries("scenarios"), raw)
    }

    /** All fixture files that hold engine outputs (tests/ + scenarios/), relative to golden/. */
    fun allFixtureFiles(): List<String> = index().let { ix -> ix.tests.map { it.file } + ix.scenarios.map { it.file } }

    /** Decode one tagged JSON element into the JS value model. */
    fun decode(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            else -> e.content.toDouble()
        }
        is JsonArray -> e.map { decode(it) }
        is JsonObject -> decodeObject(e)
    }

    private fun decodeObject(o: JsonObject): Any? {
        if (o.size == 1) {
            val (k, v) = o.entries.first()
            if (k.startsWith("$")) {
                val s = (v as? JsonPrimitive)?.content
                return when (k) {
                    "\$undef" -> JsUndefined
                    "\$num" -> when (s) {
                        "NaN" -> Double.NaN
                        "Infinity" -> Double.POSITIVE_INFINITY
                        "-Infinity" -> Double.NEGATIVE_INFINITY
                        "-0" -> -0.0
                        else -> error("unknown \$num: $s")
                    }
                    "\$fn" -> FnRef(s!!)
                    "\$error" -> ErrorRef(s!!)
                    "\$u8" -> hexToBytes(s!!)
                    "\$frames" -> decode(loadJson("frames/$s.json"))
                    else -> error("unknown tag $k")
                }
            }
        }
        val out = LinkedHashMap<String, Any?>(o.size * 2)
        for ((k, v) in o) out[k] = decode(v)
        return out
    }

    fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    /** Depth-first walk over a decoded value; [visit] sees every Map and List (including the root). */
    fun walk(v: Any?, visit: (Any) -> Unit) {
        when (v) {
            is Map<*, *> -> { visit(v); v.values.forEach { walk(it, visit) } }
            is List<*> -> { visit(v); v.forEach { walk(it, visit) } }
        }
    }
}

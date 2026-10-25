package com.first30.app.incident

import com.first30.core.engine.Seal
import com.first30.core.seal.Canonical
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A stored seal: the engine's canonical body and its sha256 [hash], kept together (CLAUDE.md item 7). */
data class StoredSeal(val hash: String, val canonical: String)

/**
 * Seal storage. Stores only `hash` + `canonical` (the engine's canonical body has no location; nothing else is added).
 * [verify] recomputes sha256 of the stored canonical and checks it against [hash].
 */
interface SealStore {
    /** Saves the pair. Throws [IllegalArgumentException] if [hash] is not sha256Hex([canonical]). Idempotent per hash. */
    fun save(hash: String, canonical: String): StoredSeal

    fun save(seal: Seal): StoredSeal = save(seal.hash, seal.canonical)

    fun load(hash: String): StoredSeal?

    /** true only if the seal exists, its stored hash is [hash] and sha256(stored canonical) == [hash]. */
    fun verify(hash: String): Boolean

    /** Hashes of all stored seals. */
    fun list(): List<String>
}

/**
 * [SealStore] in a directory — on the device `File(context.filesDir, "seals")` (app-internal storage), one file
 * `<hash>.json` = `{"canonical":"…","format":"first30-seal-file/1","hash":"…"}`, written atomically (temp + rename).
 */
class FileSealStore(val dir: File) : SealStore {
    private val lock = Any()

    override fun save(hash: String, canonical: String): StoredSeal {
        requireHash(hash)
        require(Canonical.sha256Hex(canonical) == hash) { "hash does not match canonical" }
        synchronized(lock) {
            dir.mkdirs()
            val target = fileOf(hash)
            if (target.exists() && verify(hash)) return StoredSeal(hash, canonical)
            val body = buildJsonObject {
                put("canonical", canonical)
                put("format", FORMAT)
                put("hash", hash)
            }.toString()
            val tmp = File(dir, "$hash.json.tmp")
            tmp.writeText(body, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw java.io.IOException("could not write ${target.name}")
            }
        }
        return StoredSeal(hash, canonical)
    }

    override fun load(hash: String): StoredSeal? {
        if (!HASH.matches(hash)) return null
        val f = fileOf(hash)
        if (!f.isFile) return null
        return try {
            val o = Json.parseToJsonElement(f.readText(Charsets.UTF_8)) as? JsonObject ?: return null
            val h = (o["hash"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val c = (o["canonical"] as? JsonPrimitive)?.takeIf { it.isString }?.jsonPrimitive?.content ?: return null
            StoredSeal(h, c)
        } catch (e: Exception) {
            null
        }
    }

    override fun verify(hash: String): Boolean {
        val s = load(hash) ?: return false
        return s.hash == hash && Canonical.sha256Hex(s.canonical) == hash
    }

    override fun list(): List<String> =
        dir.listFiles()?.mapNotNull { f -> f.name.removeSuffix(".json").takeIf { f.name.endsWith(".json") && HASH.matches(it) } }?.sorted()
            ?: emptyList()

    private fun fileOf(hash: String) = File(dir, "$hash.json")

    private fun requireHash(hash: String) = require(HASH.matches(hash)) { "not a sha256 hex hash: $hash" }

    companion object {
        const val FORMAT = "first30-seal-file/1"
        private val HASH = Regex("^[0-9a-f]{64}$")
    }
}

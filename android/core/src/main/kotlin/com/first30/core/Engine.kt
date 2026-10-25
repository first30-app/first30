package com.first30.core

/**
 * Judgment engine (Kotlin port of engine/engine.js — not ported yet).
 * Pure Kotlin/JVM: no Android dependencies.
 */
object Engine {
    const val VERSION: String = "0.0.1"

    /** Evidence seal format version (see engine/engine.js sealEvidence). */
    const val SEAL_FORMAT_VERSION: Int = 4
}

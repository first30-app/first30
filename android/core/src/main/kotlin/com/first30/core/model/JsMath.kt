package com.first30.core.model

import kotlin.math.floor

/**
 * JS `Math` semantics the port must reproduce exactly.
 *
 * - [round] = JS `Math.round`: nearest integer, ties toward +∞ (NOT `kotlin.math.round`, which is half-even),
 *   and results in [-0.5, -0] are `-0.0` (`Math.round(-0.3) === -0`). NaN/±Infinity/±0 pass through.
 * - [sin]/[cos]/[atan]: `StrictMath` (fdlibm), the same algorithm family V8 uses, so results match V8 to the last bit
 *   in practice (the golden comparison still allows 1e-9).
 */
object JsMath {
    fun round(x: Double): Double {
        if (x.isNaN() || x.isInfinite() || x == 0.0) return x // keeps -0.0
        if (x < 0.0 && x >= -0.5) return -0.0
        val r = floor(x)
        // x - floor(x) is exact for |x| < 2^52 (and 0 above, where x is already an integer)
        return if (x - r >= 0.5) r + 1.0 else r
    }

    fun sin(x: Double): Double = StrictMath.sin(x)
    fun cos(x: Double): Double = StrictMath.cos(x)
    fun atan(x: Double): Double = StrictMath.atan(x)
}

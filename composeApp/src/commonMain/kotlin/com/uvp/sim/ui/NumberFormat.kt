package com.uvp.sim.ui

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * KMP-friendly number formatting helpers.
 *
 * `String.format` and `"%02d".format(x)` are JVM-only — Kotlin/Native and
 * Kotlin/JS don't provide format strings. Since we only need a fixed set of
 * patterns across the UI (zero-padded integers, fixed-decimal doubles), a
 * small helper set covers every call site without pulling in a full format
 * engine.
 */

/** Zero-pad an integer to [width] decimal digits. Negative values are formatted with a leading '-'. */
internal fun pad(value: Int, width: Int): String {
    val abs = abs(value.toLong()).toString()
    val padded = if (abs.length >= width) abs else "0".repeat(width - abs.length) + abs
    return if (value < 0) "-$padded" else padded
}

/** Same as [pad] but for Long. */
internal fun pad(value: Long, width: Int): String {
    val abs = abs(value).toString()
    val padded = if (abs.length >= width) abs else "0".repeat(width - abs.length) + abs
    return if (value < 0) "-$padded" else padded
}

/**
 * Round a Double to [digits] decimal places and format as "1.5" / "-0.30" / "10.000".
 * No thousand separators, no scientific notation, no locale.
 */
internal fun round(value: Double, digits: Int): String {
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
    if (digits <= 0) return value.roundToLong().toString()
    val factor = tenPow(digits)
    val scaled = (value * factor).roundToLong()
    val abs = abs(scaled)
    val intPart = abs / factor
    val fracPart = abs % factor
    val fracStr = pad(fracPart, digits)
    val sign = if (scaled < 0) "-" else ""
    return "$sign$intPart.$fracStr"
}

private fun tenPow(n: Int): Long {
    var r = 1L
    repeat(n) { r *= 10 }
    return r
}

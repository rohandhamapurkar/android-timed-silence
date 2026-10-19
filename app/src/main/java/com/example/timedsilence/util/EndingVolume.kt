package com.example.timedsilence.util

/** Converts a user percentage to an audible device ring-volume step. */
object EndingVolume {
    const val DEFAULT_PERCENT = 100

    fun percent(value: Int): Int = value.coerceIn(1, 100)

    fun level(maximum: Int, percent: Int): Int {
        require(maximum > 0) { "No audible ring-volume steps available" }
        return ((maximum.toLong() * percent(percent) + 99) / 100).toInt().coerceIn(1, maximum)
    }
}

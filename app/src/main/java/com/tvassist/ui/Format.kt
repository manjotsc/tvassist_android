package com.tvassist.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

internal fun fmt(v: Double): String =
    if (v == v.roundToInt().toDouble()) v.roundToInt().toString()
    // Explicit locale → a stable dot decimal, matching HA's convention (not "22,5" in some locales).
    else String.format(java.util.Locale.US, "%.1f", v)

internal fun cap(s: String): String =
    s.replace('_', ' ').replaceFirstChar { it.uppercase() }


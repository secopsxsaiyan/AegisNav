package com.aegisnav.app.routing

// ── Turn arrow mapping ────────────────────────────────────────────────────────

/** Returns a Unicode arrow character for a GraphHopper sign constant. */
fun turnArrow(sign: Int): String = when (sign) {
    -7   -> "↩"  // U-turn
    -3   -> "↰"  // sharp left
    -2   -> "←"  // left
    -1   -> "↖"  // slight left
     0   -> "↑"  // straight
     1   -> "↗"  // slight right
     2   -> "→"  // right
     3   -> "↱"  // sharp right
     4   -> "🏁"  // finish
     5   -> "📍"  // via
     6   -> "🔄"  // roundabout
    else -> "↑"
}

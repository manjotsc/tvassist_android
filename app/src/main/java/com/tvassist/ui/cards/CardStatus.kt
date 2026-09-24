package com.tvassist.ui.cards

import com.tvassist.data.ha.Entity

/**
 * The one-line status for an entity, used by every surface that shows one: the control card
 * header, the sidebar tiles, and the Home list rows.
 *
 * There were three copies of this before 2026-09-14 and they had drifted apart — one dropped the
 * hvac mode from a thermostat, one printed raw Home Assistant states (`unavailable`, `heat_cool`)
 * without capitalising them, and the three disagreed on whether a light said "on" or "On". Each
 * domain now answers for itself; [compact] is the only distinction that was ever intentional, a
 * tile or list row having one short column where the card header can spell the state out.
 */
internal fun entityStatus(e: Entity, compact: Boolean): String = cardFor(e).status(e, compact)

/** "17%" / "On" / "Off" on a tile; "on · 17%" / "on" / "off" in the roomier card header. */
internal fun pctStatus(on: Boolean, value: Int?, compact: Boolean): String = when {
    !on -> if (compact) "Off" else "off"
    value == null -> if (compact) "On" else "on"
    compact -> "$value%"
    else -> "on · $value%"
}

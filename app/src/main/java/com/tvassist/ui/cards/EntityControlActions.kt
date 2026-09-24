package com.tvassist.ui.cards

import com.tvassist.data.ha.Entity
import com.tvassist.data.ha.HaRepository

/**
 * The HA service calls every card needs, whatever its domain. Per-domain calls are extension
 * functions in the card that issues them (`light/LightCard.kt` owns `setBrightnessPct`, and so
 * on), so adding a capability to one domain touches one file — they reach the repository
 * through [repository].
 *
 * Built once per host: the Home screen and the overlay each construct one.
 */
class EntityControlActions(private val repo: HaRepository) {
    /** The backing repository — per-domain extensions and entity_picture lookups both use it. */
    val repository: HaRepository get() = repo

    fun toggle(e: Entity) = repo.toggle(e)

    fun turnOn(e: Entity) = repo.callService("homeassistant", "turn_on", e.entityId)
    fun turnOff(e: Entity) = repo.callService("homeassistant", "turn_off", e.entityId)

    /** Fire a stateless button via <domain>.press. */
    fun press(e: Entity) = repo.callService(e.domain, "press", e.entityId)

    /** Activate: press for buttons, otherwise turn_on (scenes/scripts/etc.). */
    fun run(e: Entity) =
        if (e.isButton) press(e) else repo.callService(e.domain, "turn_on", e.entityId)
}

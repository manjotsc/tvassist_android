package com.tvassist.ui.cards

import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import com.tvassist.data.ha.Entity
import com.tvassist.data.settings.OverlayTile
import com.tvassist.ui.cap
import com.tvassist.ui.cards.airquality.AirQualityCard
import com.tvassist.ui.cards.alarm.AlarmCard
import com.tvassist.ui.cards.button.ButtonCard
import com.tvassist.ui.cards.camera.CameraCard
import com.tvassist.ui.cards.climate.ClimateCard
import com.tvassist.ui.cards.cover.CoverCard
import com.tvassist.ui.cards.fan.FanCard
import com.tvassist.ui.cards.generic.GenericCard
import com.tvassist.ui.cards.generic.GenericControls
import com.tvassist.ui.cards.light.LightCard
import com.tvassist.ui.cards.lock.LockCard
import com.tvassist.ui.cards.map.MapCard
import com.tvassist.ui.cards.media.MediaPlayerCard
import com.tvassist.ui.cards.switches.SwitchCard
import com.tvassist.ui.cards.update.UpdateCard
import com.tvassist.ui.voice.ConversationCard

/**
 * Everything one entity domain contributes to the UI, in one place.
 *
 * This exists because the alternative kept failing: before 2026-09-14 a domain's knowledge was
 * spread over the control card, the sidebar tile, the Home list and the tile-style picker, and
 * three separate copies of the status line had already drifted apart — one of them silently
 * dropping a thermostat's hvac mode. A drive-by contributor cannot be expected to find four
 * sites. Implementing this interface is a list the compiler hands you instead.
 *
 * **A card is one file** in `ui/cards/<domain>/`. It owns its controls, its status line, its
 * service calls (as extensions on [EntityControlActions]) and its tile styles. Icons stay in
 * `ui/EntityIcons.kt`, and the HA attribute accessors stay on [Entity] in `data/ha/HaModels.kt` —
 * that file owns the wire format.
 */
internal interface EntityCard {
    /**
     * HA domains this card claims. Registration is by domain and every domain is claimed at most
     * once, so unlike a predicate list there is no ordering that can quietly decide behaviour.
     */
    val domains: Set<String>

    /**
     * Tile styles this entity can be given, most-preferred first; the first is what `STYLE_AUTO`
     * resolves to. Ids are [OverlayTile] `STYLE_*` constants because they are persisted per tile in
     * the user's saved layout, so they cannot be renamed freely.
     *
     * **This is now what the picker offers**, which it was always meant to be. Until it was wired
     * up the picker walked a global list of all six styles, so any entity could be put on "Climate
     * card" (an inline thermostat with an empty header) or "Square" (a camera tile with no camera),
     * and `Auto`, `Full` and `Compact` rendered the identical tile for everything that was not a
     * lit light — three of the six options being one tile.
     *
     * Takes the entity for the same reason [ownsToggle] does: `Action` is worth offering on a
     * script or a scene and meaningless on a temperature sensor, and both arrive here as
     * [GenericCard].
     */
    fun variants(e: Entity): List<String> = emptyList()

    /**
     * What `Auto` draws for this domain — **today's appearance, preserved**.
     *
     * Auto is a rendering in its own right rather than an alias for the first rung, so adding rungs
     * to a domain never changes what its existing tiles look like. A plain row by default; the
     * cards that already had a look of their own say so.
     */
    fun autoStyle(e: Entity): String = OverlayTile.STYLE_AUTO

    /**
     * Controls this domain inlines on an overlay tile at [style], below the tile's header.
     *
     * Empty by default: most domains have nothing worth putting on a tile, and a card that offers
     * no rung past `Standard` never reaches here. This is the overlay only — the control card that
     * hold-for-more opens is [Controls], and it is untouched by the tile's style.
     */
    @Composable
    fun TileControls(e: Entity, actions: EntityControlActions, style: String) = Unit

    /**
     * Controls this domain puts *beside* the tile's header at [style], rather than under it.
     *
     * The counterpart to [TileControls] for a rung whose controls are one or two buttons: a second
     * line for those wastes a tile's height, and on a sidebar height is the scarce dimension. A
     * cover's Compact rung is the case this exists for — open and close, on the header's own line.
     */
    @Composable
    fun TileTrailing(e: Entity, actions: EntityControlActions, style: String) = Unit

    /**
     * Whether the entity reads as "active" — what tints its icon and highlights its tile.
     *
     * Defaults to [stateActive], Home Assistant's own answer, so a domain only overrides this when
     * it genuinely disagrees with upstream. It used to default to `e.isOn`, which tests the literal
     * state `"on"` — a test **no enum-stated domain ever passes**. Covers never highlighted; nor
     * did thermostats, which report `heat`/`cool`/`dry`; nor would vacuums, media players or alarm
     * panels have. Three separate bug reports, one cause.
     */
    fun isActive(e: Entity): Boolean = stateActive(e)

    /** One-line status. [compact] is a tile or list row; otherwise the roomier card header. */
    fun status(e: Entity, compact: Boolean): String = cap(e.state)

    /**
     * Whether [Controls] already gives the user a way to turn [e] on or off, so the control card
     * must not add its generic `Toggle` button underneath as well.
     *
     * This replaced a hand-written `(isToggleable && !isLock) || domain == "light"` condition at
     * the one call site — precisely the sort of predicate the registry exists to absorb. It was
     * also wrong in both directions: a cover already had Open/Close and a switch already had
     * Turn on/off, and both were handed a second button that made the same call under a different
     * name, while `light` had to be named explicitly to get the button it genuinely needs.
     *
     * Takes the entity because the answer is not always a property of the domain: a fan offers Off
     * inside its speed row, but only when the fan reports discrete speeds or a percentage at all.
     */
    fun ownsToggle(e: Entity): Boolean = false

    /**
     * Whether a plain press should open the card instead of acting on the entity.
     *
     * False everywhere but `media_player`. The default press is domain-agnostic — press a button,
     * toggle anything toggleable, open the card for the rest — and a media player is technically
     * toggleable, so it would have answered a press by cutting the power to a playing speaker.
     * This is the one domain where the useful first move is to look rather than touch.
     */
    fun pressOpens(e: Entity): Boolean = false

    /**
     * The body of the full control card, below the header and above Toggle/Close.
     *
     * Defaults to a state line and a turn on/off action. It briefly defaulted to stacking whatever
     * card features an entity supported, which let four domains carry no `Controls` at all; that
     * went to `experiments/hacards/` and every domain owns its own body again.
     */
    @Composable
    fun Controls(e: Entity, actions: EntityControlActions, firstFocus: FocusRequester) =
        GenericControls(e, actions, firstFocus)
}

/**
 * Whether an entity reads as active, mirroring Home Assistant's `common/entity/state_active.ts`
 * (checked against dev, 2026-09-15).
 *
 * The shape is "active unless proven otherwise": unavailable, unknown and `off` are inactive, then
 * a handful of domains name their own idle state. That inversion is the important part — the old
 * default asked "is the state the word `on`?", which is false for every domain whose state is an
 * enum, so those domains could never light up at all.
 *
 * **Two upstream behaviours worth knowing before they look like bugs:**
 * - A **lock is active when *unlocked***, not when locked. Upstream reads "active" as "wants your
 *   attention", and a thrown bolt does not. **[LockCard] overrides this back**: on a sidebar tile
 *   lit reads as "done", and a dark tile for a locked door says the opposite of what it is doing.
 *   This function stays a faithful port; the divergence lives in the one card it applies to.
 * - **Timestamp-stated domains** — button, scene, conversation and the rest of
 *   [TIMESTAMP_STATE_DOMAINS] — are active as soon as they hold any state at all, because their
 *   state *is* the moment they last fired. A scene pressed once therefore stays lit.
 */
internal fun stateActive(e: Entity): Boolean {
    val s = e.state
    if (e.domain in TIMESTAMP_STATE_DOMAINS) return s != "unavailable"
    if (s == "unavailable" || s == "unknown") return false
    // `alert` is the exception: its `off` means "acknowledged but still alerting", and `idle` is
    // what inactive means there instead.
    if (s == "off" && e.domain != "alert") return false
    return when (e.domain) {
        "alarm_control_panel" -> s != "disarmed"
        "alert" -> s != "idle"
        "cover" -> s != "closed"
        "device_tracker", "person" -> s != "not_home"
        "lawn_mower" -> s !in setOf("docked", "paused", "idle")
        "lock" -> s != "locked"
        "media_player" -> s != "standby"
        "vacuum" -> s !in setOf("idle", "docked", "paused")
        "valve" -> s != "closed"
        "plant" -> s == "problem"
        "group" -> s in setOf("on", "home", "open", "locked", "problem")
        "timer" -> s == "active"
        "camera" -> s in setOf("streaming", "recording")
        else -> true
    }
}

/** Domains whose state is a timestamp of when they last fired, not a condition. Upstream's list. */
private val TIMESTAMP_STATE_DOMAINS = setOf(
    "ai_task", "button", "conversation", "event", "image", "infrared", "input_button", "notify",
    "radio_frequency", "scene", "stt", "tag", "tts", "wake_word", "datetime",
)

/** Every card, keyed by domain. Adding one is a new file plus a line here. */
private val REGISTRY: Map<String, EntityCard> = buildMap {
    listOf(
        LightCard, ClimateCard, FanCard, CoverCard, LockCard, SwitchCard, ButtonCard,
        CameraCard, ConversationCard, AirQualityCard, MediaPlayerCard, MapCard, AlarmCard,
        UpdateCard,
    )
        .forEach { card ->
            card.domains.forEach { d ->
                require(put(d, card) == null) { "two cards claim the domain '$d'" }
            }
        }
}

/** The card for [e]; [GenericCard] handles any domain nothing claims. */
internal fun cardFor(e: Entity): EntityCard = cardForDomain(e.domain)

/**
 * The card for a bare domain string. Exists for the saved layout, which stores an entity *id* and
 * may be normalised long before that entity has been loaded from Home Assistant.
 */
internal fun cardForDomain(domain: String): EntityCard = REGISTRY[domain] ?: GenericCard

/** Styles the picker may offer for [e]: `Auto`, then whatever its card actually supports. */
internal fun stylesFor(e: Entity): List<String> =
    listOf(OverlayTile.STYLE_AUTO) + cardFor(e).variants(e)

/**
 * The style [saved] should actually render as.
 *
 * `Auto` resolves to the card's first variant, and so does anything the card does not offer — a
 * style saved by an older build, or by the picker back when it offered all six to everything.
 */
internal fun resolveTileStyle(e: Entity, saved: String): String {
    val shown = shownTileStyle(e, saved)
    return if (shown == OverlayTile.STYLE_AUTO) cardFor(e).autoStyle(e) else shown
}

/**
 * The style the editor's chip should name for [saved]: the stored value where [e]'s card offers
 * it, `Auto` where it does not — since Auto is what [resolveTileStyle] then draws.
 *
 * Display only. The stored value is left alone so a style the card does not offer *today* comes
 * back if it gains that rung. With [e] not loaded nothing can be judged, so the value is shown as
 * saved.
 */
internal fun shownTileStyle(e: Entity?, saved: String): String {
    val known = OverlayTile.known(saved)
    if (e == null) return known
    return if (known == OverlayTile.STYLE_AUTO || known !in cardFor(e).variants(e)) OverlayTile.STYLE_AUTO else known
}

package com.tvassist.ui.cards

import com.tvassist.data.ha.Entity
import com.tvassist.data.settings.OverlayTile
import com.tvassist.ui.cards.button.ButtonCard
import com.tvassist.ui.cards.camera.CameraCard
import com.tvassist.ui.cards.climate.ClimateCard
import com.tvassist.ui.cards.cover.CoverCard
import com.tvassist.ui.cards.fan.FanCard
import com.tvassist.ui.cards.generic.GenericCard
import com.tvassist.ui.cards.light.LightCard
import com.tvassist.ui.cards.lock.LockCard
import com.tvassist.ui.cards.media.MediaPlayerCard
import com.tvassist.ui.cards.switches.SwitchCard
import com.tvassist.ui.voice.ConversationCard
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the card registry: which domain gets which card, and what `STYLE_AUTO` resolves to.
 *
 * Both used to be hand-written `when` blocks in two different files — the control card dispatched
 * on domain, the sidebar mapped domain to a tile style, and nothing connected them. Getting a new
 * card half-wired (controls but no tile style, or the reverse) was easy and silent.
 */
class EntityCardRegistryTest {

    private fun entity(id: String) = Entity(entityId = id, state = "on", friendlyName = id)

    @Test fun eachDomainResolvesToItsOwnCard() {
        assertSame(LightCard, cardFor(entity("light.kitchen")))
        assertSame(ClimateCard, cardFor(entity("climate.ac")))
        assertSame(FanCard, cardFor(entity("fan.ceiling")))
        assertSame(CoverCard, cardFor(entity("cover.blind")))
        assertSame(LockCard, cardFor(entity("lock.front")))
        assertSame(CameraCard, cardFor(entity("camera.doorbell")))
        assertSame(ConversationCard, cardFor(entity("conversation.home_assistant")))
        assertSame(SwitchCard, cardFor(entity("switch.lamp")))
        assertSame(MediaPlayerCard, cardFor(entity("media_player.tv")))
    }

    @Test fun bothButtonDomainsShareOneCard() {
        assertSame(ButtonCard, cardFor(entity("button.doorbell")))
        assertSame(ButtonCard, cardFor(entity("input_button.test")))
    }

    /** A plain boolean is a plain boolean, whichever of the two domains it arrived in. */
    @Test fun bothBooleanDomainsShareOneCard() {
        assertSame(SwitchCard, cardFor(entity("switch.lamp")))
        assertSame(SwitchCard, cardFor(entity("input_boolean.guest_mode")))
    }

    @Test fun unclaimedDomainsFallBackToGeneric() {
        assertSame(GenericCard, cardFor(entity("sensor.temperature")))
        assertSame(GenericCard, cardFor(entity("weirddomain.thing")))
    }

    /**
     * A media player is the one domain whose press opens the card instead of acting.
     *
     * It is in [com.tvassist.data.ha.Entity.isToggleable], so without this hook the default press
     * would have run `homeassistant.toggle` — cutting the power to a playing speaker.
     */
    @Test fun onlyAMediaPlayerAnswersAPressByOpeningItsCard() {
        val player = entity("media_player.tv")
        assertTrue("still toggleable, which is the trap", player.isToggleable)
        assertTrue(cardFor(player).pressOpens(player))
        listOf("light.kitchen", "switch.lamp", "cover.blind", "lock.front", "fan.ceiling")
            .forEach { assertFalse(it, cardFor(entity(it)).pressOpens(entity(it))) }
    }

    /**
     * The control card adds its generic `Toggle` only where the card's own body offers no way to
     * turn the entity on or off.
     *
     * This was a `(isToggleable && !isLock) || domain == "light"` condition at the one call site,
     * and it was wrong in both directions: a switch already had Turn on/off and a cover already had
     * Open/Close, yet both were handed a second button making the identical call under a different
     * name. The switch card was where that finally showed — four things on one card all saying "On".
     */
    @Test fun onlyCardsWithoutTheirOwnOnOffGetTheFooterToggle() {
        listOf("switch.lamp", "input_boolean.guest_mode", "cover.blind", "lock.front").forEach {
            assertTrue(it, cardFor(entity(it)).ownsToggle(entity(it)))
        }
        // A light's body is sliders and nothing else, so the footer Toggle is its only on/off.
        assertFalse(cardFor(entity("light.kitchen")).ownsToggle(entity("light.kitchen")))
    }

    /**
     * A fan is the one domain where the answer depends on the entity rather than the domain: the
     * speed row carries its own Off, but a fan reporting neither speeds nor a percentage draws an
     * empty body — and suppressing the footer there would leave no way to turn it off at all.
     */
    @Test fun aFanOwnsTheToggleOnlyWhenItDrawsASpeedControl() {
        fun fan(attrs: Map<String, Int>) = Entity(
            entityId = "fan.ceiling", state = "on", friendlyName = "Fan",
            attributes = buildJsonObject { attrs.forEach { (k, v) -> put(k, v) } },
        )
        assertFalse("no speeds and no percentage", FanCard.ownsToggle(fan(emptyMap())))
        assertTrue("discrete speeds", FanCard.ownsToggle(fan(mapOf("speed_count" to 4))))
        assertTrue("a percentage", FanCard.ownsToggle(fan(mapOf("percentage" to 40))))
    }

    // STYLE_AUTO used to be a separate `when` in SidebarContent; these are the three cases it had.
    @Test fun autoStyleComesFromTheCardsFirstVariant() {
        fun auto(id: String) = resolveTileStyle(entity(id), OverlayTile.STYLE_AUTO)
        assertEquals(OverlayTile.STYLE_CLIMATE, auto("climate.ac"))
        assertEquals(OverlayTile.STYLE_SQUARE, auto("camera.doorbell"))
        assertEquals(OverlayTile.STYLE_ACTION, auto("script.bedtime"))
        // A light's Auto is Normal; everything not on the ladder stays a plain row.
        assertEquals(OverlayTile.STYLE_STANDARD, auto("light.kitchen"))
        assertEquals(OverlayTile.STYLE_AUTO, auto("weirddomain.thing"))
    }

    /**
     * Adding rungs to a domain must never change what its existing tiles look like, which is why
     * Auto is a rendering of its own rather than an alias for the first variant. A colour bulb has
     * three rungs and its Auto is still the plain row it has always been.
     */
    @Test fun autoIsUnaffectedByTheRungsADomainGains() {
        // Auto names its rendering rather than taking whichever variant happens to be first, so a
        // rung added to a domain never changes what its existing tiles look like.
        assertEquals(4, LightCard.variants(bulb("color_temp", "hs")).size)
        assertEquals(
            LightCard.autoStyle(bulb("color_temp", "hs")),
            resolveTileStyle(bulb("color_temp", "hs"), OverlayTile.STYLE_AUTO),
        )
        assertEquals(
            LightCard.autoStyle(bulb("brightness")),
            resolveTileStyle(bulb("brightness"), OverlayTile.STYLE_AUTO),
        )
    }

    /** A bulb, with whichever colour modes the test needs. */
    private fun bulb(vararg modes: String, bright: Boolean = true) = Entity(
        entityId = "light.kitchen",
        state = "on",
        friendlyName = "Kitchen",
        attributes = buildJsonObject {
            put("supported_color_modes", JsonArray(modes.map { JsonPrimitive(it) }))
            if (bright) put("brightness", 150)
        },
    )

    /**
     * A tile's style is how much of the entity's control surface the tile carries. The rungs a
     * light offers therefore follow what the bulb can actually do — a dimmer has a Compact rung, a
     * colour bulb a Full one, and an on/off-only bulb neither, because both would draw a plain row.
     */
    /**
     * Every rung a card offers must be one the sidebar renders as a control tile.
     *
     * `Standard` was missing from the dispatch's hand-written branch list for a day: it *was* the
     * plain row when that branch was written, gained brightness and warmth afterwards, and kept
     * rendering as a plain row with nothing under it. The dispatch now tests membership of
     * [OverlayTile.CONTROL_STYLES], and this asserts the cards agree with it.
     */
    @Test fun everyRungACardOffersIsOneTheSidebarDrawsControlsFor() {
        val samples = listOf(
            bulb("color_temp", "hs"), bulb("color_temp"), bulb("brightness"),
            entity("climate.ac"), entity("camera.doorbell"), entity("switch.lamp"),
            entity("script.bedtime"), entity("sensor.temperature"),
        )
        samples.forEach { e ->
            cardFor(e).variants(e).forEach { rung ->
                assertEquals("$rung is not a style this build knows", rung, OverlayTile.known(rung))
                assertTrue(
                    "${e.entityId} offers $rung, which the sidebar draws as neither",
                    rung in OverlayTile.CONTROL_STYLES ||
                        rung == OverlayTile.STYLE_STANDARD ||
                        rung == OverlayTile.STYLE_ICON,
                )
            }
        }
    }

    /** A domain's own look is never a rung — it is what Auto draws, and it carries no ladder. */
    @Test fun aDomainsOwnLookIsNeverOfferedAsARung() {
        listOf(entity("climate.ac"), entity("camera.doorbell"), entity("script.bedtime")).forEach { e ->
            assertFalse(e.entityId, cardFor(e).autoStyle(e) in OverlayTile.CONTROL_STYLES)
        }
    }

    @Test fun aLightOffersTheRungsItsBulbCanFill() {
        // Normal is the plain row and is always available; Compact and Full need something to
        // control. Auto renders as Normal, so the two are the same tile.
        // Icon tap and Normal suit any bulb; the control rungs need something to control.
        assertEquals(
            listOf(OverlayTile.STYLE_ICON, OverlayTile.STYLE_STANDARD),
            LightCard.variants(bulb("onoff", bright = false)),
        )
        assertEquals(
            listOf(
                OverlayTile.STYLE_ICON, OverlayTile.STYLE_STANDARD,
                OverlayTile.STYLE_COMPACT, OverlayTile.STYLE_FULL,
            ),
            LightCard.variants(bulb("color_temp", "hs")),
        )
        assertEquals(
            OverlayTile.STYLE_STANDARD,
            resolveTileStyle(bulb("color_temp", "hs"), OverlayTile.STYLE_AUTO),
        )
    }

    /** Normal carries no controls, so the sidebar must draw it as a plain row. */
    /** Icon tap is a shape, not a rung — it carries no controls whatever the bulb can do. */
    @Test fun iconTapIsNotAControlRung() {
        assertFalse(OverlayTile.STYLE_ICON in OverlayTile.CONTROL_STYLES)
        assertEquals("Icon tap", OverlayTile.label(OverlayTile.STYLE_ICON))
        assertEquals(OverlayTile.STYLE_ICON, OverlayTile.known("icon"))
    }

    @Test fun normalIsNotAControlRung() {
        assertFalse(OverlayTile.STYLE_STANDARD in OverlayTile.CONTROL_STYLES)
        assertTrue(OverlayTile.STYLE_COMPACT in OverlayTile.CONTROL_STYLES)
        assertTrue(OverlayTile.STYLE_FULL in OverlayTile.CONTROL_STYLES)
    }

    /** The label is what the picker shows; the id stays `standard` so saved layouts still load. */
    @Test fun theStandardIdIsLabelledNormal() {
        assertEquals("Normal", OverlayTile.label(OverlayTile.STYLE_STANDARD))
        assertEquals(OverlayTile.STYLE_STANDARD, OverlayTile.known("standard"))
    }

    /** A bulb's icon takes its actual colour, so a row of lamps is readable at a glance. */
    @Test fun aLitBulbTintsItsOwnIcon() {
        assertNull("an off bulb keeps the theme colour", stateTint(bulb("hs").copy(state = "off")))
        val warm = stateTint(
            Entity(
                entityId = "light.lamp", state = "on", friendlyName = "Lamp",
                attributes = buildJsonObject {
                    put("supported_color_modes", JsonArray(listOf(JsonPrimitive("color_temp"))))
                    put("color_temp_kelvin", 2200)
                },
            ),
        )
        val cool = stateTint(
            Entity(
                entityId = "light.lamp", state = "on", friendlyName = "Lamp",
                attributes = buildJsonObject {
                    put("supported_color_modes", JsonArray(listOf(JsonPrimitive("color_temp"))))
                    put("color_temp_kelvin", 6400)
                },
            ),
        )
        assertNotNull(warm)
        assertNotNull(cool)
        assertTrue("2200K should be warmer than 6400K", warm!!.red > warm.blue)
        assertTrue("6400K should be cooler than 2200K", cool!!.blue > cool.red)
    }

    /**
     * The domains you can simply press offer Icon tap, and only Icon tap.
     *
     * No Compact or Full rung: those two draw a domain's *controls*, and none of these has controls
     * that fit on a tile — a cover's position is a slider, a fan's speed a row of buttons. The press
     * is the control, which is the whole of what Icon tap renders.
     */
    @Test fun theDomainsYouCanPressOfferIconTapAndNothingElse() {
        listOf("switch.lamp", "lock.front").forEach { id ->
            val e = entity(id)
            assertEquals(id, listOf(OverlayTile.STYLE_ICON), cardFor(e).variants(e))
            assertEquals(id, listOf(OverlayTile.STYLE_AUTO, OverlayTile.STYLE_ICON), stylesFor(e))
            // Auto is untouched, so adding the rung changed no tile anybody had already saved.
            assertEquals(id, OverlayTile.STYLE_AUTO, cardFor(e).autoStyle(e))
            assertEquals(id, OverlayTile.STYLE_ICON, resolveTileStyle(e, OverlayTile.STYLE_ICON))
        }
    }

    /**
     * A cover and a fan carry the full ladder: Icon tap, Compact, Full.
     *
     * Compact and Full are [OverlayTile.CONTROL_STYLES], which is what routes a tile to
     * `InlineControlTile` — Compact fills its trailing slot, Full its controls slot. A rung missing
     * from that set renders as a plain row with nothing on it, which is how `Standard` shipped
     * broken for a day.
     */
    @Test fun aCoverAndAFanOfferIconTapCompactAndFull() {
        val ladder = listOf(OverlayTile.STYLE_ICON, OverlayTile.STYLE_COMPACT, OverlayTile.STYLE_FULL)
        listOf("cover.blind", "fan.ceiling").forEach { id ->
            val e = entity(id)
            assertEquals(id, ladder, cardFor(e).variants(e))
            // Auto is still what it always drew, so no saved tile changed appearance.
            assertEquals(id, OverlayTile.STYLE_AUTO, cardFor(e).autoStyle(e))
            ladder.forEach { assertEquals("$id/$it", it, resolveTileStyle(e, it)) }
        }
        assertTrue(OverlayTile.STYLE_COMPACT in OverlayTile.CONTROL_STYLES)
        assertTrue(OverlayTile.STYLE_FULL in OverlayTile.CONTROL_STYLES)
    }

    /**
     * A thermostat gets Compact and Full but **no Icon tap**, because `climate` is not a toggleable
     * domain: `performPress` would fall through to opening the card, and a square button whose only
     * action is the one hold already does is a button that lies about itself.
     */
    @Test fun aThermostatHasNoIconTapRung() {
        val e = entity("climate.ac")
        assertFalse("climate is not toggleable", e.isToggleable)
        assertEquals(listOf(OverlayTile.STYLE_COMPACT, OverlayTile.STYLE_FULL), cardFor(e).variants(e))
        // Auto is still the inline climate tile — mode and fan rows, no temperature control.
        assertEquals(OverlayTile.STYLE_CLIMATE, cardFor(e).autoStyle(e))
    }

    /** A domain with nothing to press and nothing to show keeps a picker holding Auto alone. */
    @Test fun domainsNotYetOnTheLadderOfferNoRungs() {
        listOf("sensor.temperature", "weirddomain.thing").forEach { id ->
            assertEquals(id, emptyList<String>(), cardFor(entity(id)).variants(entity(id)))
            assertEquals(id, listOf(OverlayTile.STYLE_AUTO), stylesFor(entity(id)))
        }
    }

    /**
     * Air quality offers **Full alone** — the one domain whose rung is about showing, not touching.
     *
     * No Icon tap: a readout cannot be actuated, so the square would be a button that does nothing.
     * No Compact: its trailing slot exists for controls, and this domain has none.
     */
    @Test fun aReadoutOffersFullAndNothingElse() {
        val e = entity("air_quality.home")
        assertEquals(listOf(OverlayTile.STYLE_FULL), cardFor(e).variants(e))
        assertEquals(listOf(OverlayTile.STYLE_AUTO, OverlayTile.STYLE_FULL), stylesFor(e))
        // Auto is still the plain row, so nobody's saved tile moved.
        assertEquals(OverlayTile.STYLE_AUTO, cardFor(e).autoStyle(e))
        assertEquals(OverlayTile.STYLE_FULL, resolveTileStyle(e, OverlayTile.STYLE_FULL))
        // And it is still never lit, which the rung does not change.
        assertFalse(cardFor(e).isActive(e))
    }

    /**
     * A card may legitimately offer no rungs — most domains are not on the ladder — but every card
     * must name a style `Auto` can render, or its tiles would draw nothing.
     */
    @Test fun everyCardsAutoStyleIsOneThisBuildKnows() {
        val e = entity("light.kitchen")
        listOf(
            LightCard, ClimateCard, FanCard, CoverCard, LockCard, SwitchCard, ButtonCard,
            CameraCard, ConversationCard, GenericCard,
        ).forEach {
            val auto = it.autoStyle(e)
            assertEquals("${it::class.simpleName} has an unknown autoStyle", auto, OverlayTile.known(auto))
        }
    }

    /**
     * Auto, Full and Compact used to render the identical tile for anything that was not a lit
     * light: the two branches passed [HaTile] the same arguments, and Auto resolved to Full because
     * only the camera and climate cards declared a variant at all. Three of six options, one tile.
     * Now a card offers only rungs it can fill, and every list still starts from Auto.
     */
    @Test fun everyEntityIsOfferedAutoAndOnlyRungsItCanFill() {
        listOf("light.kitchen", "switch.lamp", "cover.blind", "sensor.temperature").forEach { id ->
            val styles = stylesFor(entity(id))
            assertEquals("$id does not lead with Auto", OverlayTile.STYLE_AUTO, styles.first())
            assertEquals("$id offers a duplicate rung", styles.size, styles.toSet().size)
        }
        // The colour bulb is the one with all three rungs.
        assertEquals(
            listOf(
                OverlayTile.STYLE_AUTO, OverlayTile.STYLE_ICON, OverlayTile.STYLE_STANDARD,
                OverlayTile.STYLE_COMPACT, OverlayTile.STYLE_FULL,
            ),
            stylesFor(bulb("color_temp", "hs")),
        )
    }

    /**
     * The picker used to hand all six styles to everything, whatever the domain could render — so a
     * light could be set to the inline thermostat, and a sensor to a camera tile. Those looks are
     * now each domain's own [EntityCard.autoStyle] and are not offered to anything else.
     */
    @Test fun domainLooksBelongToTheirDomainAlone() {
        listOf("light.kitchen", "switch.lamp", "sensor.temperature").forEach { id ->
            val styles = stylesFor(entity(id))
            assertFalse("$id offered the inline thermostat", OverlayTile.STYLE_CLIMATE in styles)
            assertFalse("$id offered the camera tile", OverlayTile.STYLE_SQUARE in styles)
        }
        assertEquals(OverlayTile.STYLE_CLIMATE, ClimateCard.autoStyle(entity("climate.ac")))
        assertEquals(OverlayTile.STYLE_SQUARE, CameraCard.autoStyle(entity("camera.doorbell")))
    }

    /** "Run" suits a thing you fire once. A temperature sensor is not one. */
    @Test fun theRunTileIsOnlyForThingsThatAreRun() {
        listOf("script.bedtime", "scene.movie", "automation.dusk", "button.doorbell").forEach {
            assertEquals(it, OverlayTile.STYLE_ACTION, resolveTileStyle(entity(it), OverlayTile.STYLE_AUTO))
        }
        listOf("sensor.temperature", "lock.front").forEach {
            assertEquals(it, OverlayTile.STYLE_AUTO, resolveTileStyle(entity(it), OverlayTile.STYLE_AUTO))
        }
        // A light's Auto is Normal, which is still not the Run tile.
        assertEquals(
            OverlayTile.STYLE_STANDARD,
            resolveTileStyle(entity("light.kitchen"), OverlayTile.STYLE_AUTO),
        )
    }

    /**
     * A style the entity's card does not offer resolves to that card's own default rather than
     * rendering wrongly. Saved layouts are full of these, because the old picker allowed them.
     */
    @Test fun aStyleTheCardDoesNotOfferFallsBackInsteadOfRenderingWrong() {
        assertEquals(
            OverlayTile.STYLE_STANDARD,
            resolveTileStyle(entity("light.kitchen"), OverlayTile.STYLE_CLIMATE),
        )
        assertEquals(
            OverlayTile.STYLE_SQUARE,
            resolveTileStyle(entity("camera.doorbell"), OverlayTile.STYLE_ACTION),
        )
        // An on/off bulb asked for Compact has no brightness to put there, so it falls back too.
        assertEquals(
            OverlayTile.STYLE_STANDARD,
            resolveTileStyle(bulb("onoff", bright = false), OverlayTile.STYLE_COMPACT),
        )
        // One it does offer is kept.
        assertEquals(
            OverlayTile.STYLE_COMPACT,
            resolveTileStyle(bulb("brightness"), OverlayTile.STYLE_COMPACT),
        )
    }

    /** The registry's own `require` guards this; building it at all proves no domain is claimed twice. */
    @Test fun noDomainIsClaimedTwice() {
        val claimed = listOf(
            LightCard, ClimateCard, FanCard, CoverCard, LockCard, SwitchCard, ButtonCard,
            CameraCard, ConversationCard,
        ).flatMap { it.domains }
        assertEquals(claimed.size, claimed.toSet().size)
    }
}

package tech.shaffersoftworks.remotescreens.server.render

import tech.shaffersoftworks.remotescreens.server.data.homeassistant.EntityState
import tech.shaffersoftworks.remotescreens.shared.VarValue

/**
 * The names of the named variables. An entity's values are shared by every tile and card that shows it; a tile's
 * colours are its own, because two tiles of one lamp may wear different pastels.
 *
 * Kotlin helpers use full words; on-the-wire prefixes (`e.`, `t.`, `o.`) and key suffixes stay stable for players.
 */
object Vars {
    /** Values for `modifier.visibility`: the player's `Component.Visibility`. */
    const val VISIBLE = 1
    const val GONE = 0

    /** Wire key suffixes for tile colours (string values must not change). */
    const val KEY_BG = "bg"
    const val KEY_BG_INK = "bgink"
    const val KEY_LINE = "line"
    const val KEY_INK = "ink"
    const val KEY_INK2 = "ink2"
    const val KEY_ICON = "icon"
    const val KEY_FILL = "fill"
    const val KEY_NAME = "name"
    const val KEY_STATE = "state"
    const val KEY_VALUE = "value"
    const val KEY_ON = "on"
    const val KEY_OFF = "off"
    const val KEY_LEVEL = "level"
    const val KEY_LEVEL_REST = "level_rest"
    const val KEY_CONFIRM = "confirm"
    /** Camera card: spinner while HLS starts; still when the stream never comes. */
    const val KEY_CAM_WAITING = "cam_waiting"
    const val KEY_CAM_STILL = "cam_still"
    /** Named bitmap for a map-marker photo: `map.pic.<entityId>`. */
    const val MAP_PIC_PREFIX = "map.pic."

    fun entity(entityId: String, key: String) = "e.$entityId.$key"
    fun tile(tileId: String, key: String) = "t.$tileId.$key"
    fun option(scope: String, group: String, index: Int, key: String) = "o.$scope.$group.$index.$key"

    const val PAGE = "page"
    const val TIME = "sys.time"
    const val DATE = "sys.date"
    /** The date as the header shows it: "24 MAY 2024". */
    const val DAY = "sys.day"
    const val TEMP = "sys.temp"
    const val PEOPLE = "sys.people"
    const val CONNECTED = "sys.connected"
    /** Tablet battery as a percent string, e.g. "87%". */
    const val BATTERY = "sys.battery"
    const val BATTERY_CHARGING = "sys.battery_charging"
    const val BATTERY_IDLE = "sys.battery_idle"
    /** Tablet Wi‑Fi signal level 0–4 as text, or empty when unknown. */
    const val WIFI = "sys.wifi"
}

/** Binds the values every tile and card of an entity reads. Once per entity per document. */
fun DocContext.bindEntity(entityId: String, name: String? = null, icon: String? = null) {
    if (bindings.any { it.entityId == entityId && it.compute is EntityBinding }) return
    bind(entityId, EntityBinding(entityId, this, name, icon))
}

private class EntityBinding(
    val entityId: String,
    val docContext: DocContext,
    val name: String?,
    val icon: String?,
) : (Map<String, EntityState>) -> List<VarValue> {
    override fun invoke(states: Map<String, EntityState>): List<VarValue> {
        val view = Present.view(states[entityId], docContext.i18n, name, icon)
        return listOf(
            VarValue.string(Vars.entity(entityId, Vars.KEY_NAME), view.name),
            VarValue.string(Vars.entity(entityId, Vars.KEY_STATE), view.stateText),
            VarValue.string(Vars.entity(entityId, Vars.KEY_VALUE), view.value),
            VarValue.int(Vars.entity(entityId, Vars.KEY_ON), if (view.on) Vars.VISIBLE else Vars.GONE),
            VarValue.int(Vars.entity(entityId, Vars.KEY_OFF), if (view.on) Vars.GONE else Vars.VISIBLE),
            VarValue.float(Vars.entity(entityId, Vars.KEY_LEVEL), view.level ?: 0f),
            VarValue.float(Vars.entity(entityId, Vars.KEY_LEVEL_REST), 1f - (view.level ?: 0f)),
        )
    }
}

/**
 * A tile's colours follow whether its entity is on. The card itself stays grey; being on lights its icon, the strip
 * under its header (`line`) and its round badge (`bg`, with `bgink` drawn on it) in the tile's accent.
 */
fun DocContext.bindTileColors(tileId: String, entityId: String?, pastel: Pastel, alwaysOn: Boolean = false) =
    bindTileColors(tileId, entityId, pastel.argb, alwaysOn)

/** As above, lit in [accent] (ARGB). */
fun DocContext.bindTileColors(tileId: String, entityId: String?, accent: Int, alwaysOn: Boolean = false) {
    bind(entityId) { states ->
        val entityState = entityId?.let { states[it] }
        val view = Present.view(entityState, i18n)
        val on = alwaysOn || view.on
        val isAvailable = entityId == null || view.isAvailable
        val lit = on && isAvailable
        val iconColor = when {
            !isAvailable -> Role.INK_FAINT.argb
            on -> accent
            else -> Role.INK_DIM.argb
        }
        listOf(
            VarValue.color(Vars.tile(tileId, Vars.KEY_BG), if (lit) accent else Role.KEY.argb),
            VarValue.color(Vars.tile(tileId, Vars.KEY_BG_INK), if (lit) Role.KEY_ON_INK.argb else Role.INK.argb),
            // Mockup domain tiles: teal accent line under every header (brighter while on).
            VarValue.color(Vars.tile(tileId, Vars.KEY_LINE), if (lit) accent else Role.ACCENT_SOFT.argb),
            VarValue.color(Vars.tile(tileId, Vars.KEY_INK), if (!isAvailable) Role.INK_FAINT.argb else Role.INK.argb),
            VarValue.color(Vars.tile(tileId, Vars.KEY_INK2), if (!isAvailable) Role.INK_FAINT.argb else Role.INK_SOFT.argb),
            VarValue.color(Vars.tile(tileId, Vars.KEY_ICON), iconColor),
            VarValue.color(Vars.tile(tileId, Vars.KEY_FILL), Role.KEY.argb),
        )
    }
}

/** A row of choices (modes, options, speeds): the chosen one is teal, the rest plain grey keys. */
fun DocContext.bindChoices(
    scopeId: String,
    group: String,
    entityId: String,
    choices: List<String>,
    chosen: (EntityState) -> String?,
) {
    bind(entityId) { states ->
        val current = states[entityId]?.let(chosen)
        choices.flatMapIndexed { index, choice ->
            val on = choice == current
            listOf(
                VarValue.color(Vars.option(scopeId, group, index, Vars.KEY_BG), (if (on) Role.KEY_ON else Role.KEY).argb),
                VarValue.color(Vars.option(scopeId, group, index, Vars.KEY_INK), (if (on) Role.KEY_ON_INK else Role.INK).argb),
            )
        }
    }
}

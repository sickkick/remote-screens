@file:Suppress("RestrictedApi")

package tech.shaffersoftworks.remotescreens.server.render

import androidx.compose.remote.creation.dsl.Modifier
import androidx.compose.remote.creation.dsl.RcHorizontalPositioning
import androidx.compose.remote.creation.dsl.RcRowHorizontalPositioning
import androidx.compose.remote.creation.dsl.RcTextAlign
import androidx.compose.remote.creation.dsl.RcVerticalPositioning
import androidx.compose.remote.creation.dsl.background
import androidx.compose.remote.creation.dsl.clip
import androidx.compose.remote.creation.dsl.fillMaxWidth
import androidx.compose.remote.creation.dsl.horizontalWeight
import androidx.compose.remote.creation.dsl.padding
import androidx.compose.remote.creation.dsl.size
import tech.shaffersoftworks.remotescreens.server.data.homeassistant.EntityState
import tech.shaffersoftworks.remotescreens.shared.ActionRef
import tech.shaffersoftworks.remotescreens.shared.Catalogue
import kotlin.math.roundToInt

internal object CardChrome {
    const val HUE_SATURATION = 0.55f
    const val CAMERA_RESERVE_DP = 120f
    const val TOGGLE_DP = 132f
    const val HISTORY_RESERVE_DP = 140f
    const val HUE_DEGREES = 360f
    const val PANEL_CORNER_DP = 10f
    const val SECTION_GAP_DP = 12f
    const val HEADER_GAP_DP = 14f
    const val CARD_GAP_DP = 16f
    const val CAMERA_SPINNER_DP = 48f
    /** Marker photo size sent for interactive map pins. */
    const val MAP_MARKER_DP = 48f
    const val SECTION_TITLE_GAP_DP = 18f
    const val PANEL_PAD_DP = 20f
    const val WEATHER_ICON_ROW_DP = 28f
    const val CLOSE_KEY_DP = 40f
    const val SWATCH_DP = 46f
    const val FLOW_KEY_H_DP = 48f
    const val LEVEL_BAR_H_DP = 52f
    /** Segmented level bar height on detail cards (mockup style). */
    const val SEG_BAR_H_DP = 16f
    const val MEDIA_CTRL_DP = 60f
    const val TOGGLE_ICON_DP = 64f
    const val STEPPER_KEY_DP = 72f
    const val PLAY_KEY_DP = 80f
    const val ROW_GAP_DP = 6f
    const val FLOW_PAD_DP = 8f
    const val PANEL_MARGIN_DP = 32f
    const val PANEL_MAX_WIDTH_DP = 620f
    const val LEVEL_STEPS = HaRenderFlags.CARD_LEVEL_STEPS
    const val LEVEL_DEFAULT_MAX = 100.0
    const val FLOW_KEYS_SEGMENT_MAX = 5
    const val FLOW_KEY_SEG_H_DP = 42f
    const val FAN_PERCENTAGE_FLAG = HaRenderFlags.FAN_PERCENTAGE_FLAG
    const val FAN_OSCILLATE_FLAG = 2
    const val FAN_DIRECTION_FLAG = 4
    const val CLIMATE_TEMP_RANGE_FLAG = 2
    const val COVER_POSITION_FLAG = HaRenderFlags.COVER_POSITION_FLAG
    const val COVER_TILT_FLAG = 128
    const val DEFAULT_HUMIDITY_MIN = 0.0
    const val DEFAULT_HUMIDITY_MAX = 100.0
    const val DEFAULT_HUMIDITY_STEP = 5.0
    const val DEFAULT_CLIMATE_LIKE_MIN = 40.0
    const val DEFAULT_CLIMATE_LIKE_MAX = 60.0
    const val TODO_CARD_TAKE = 12
    const val WEATHER_REFRESH_S = 1800
    const val HISTORY_REFRESH_S = 300
    /** Opaque alpha in the high byte; not a theme colour (kept out of BuiltinThemes.kt hex inventory). */
    const val OPAQUE_ALPHA_BYTE = 0xFF
    const val OPAQUE_ARGB = OPAQUE_ALPHA_BYTE shl 24
    const val ALPHA_OPAQUE = 0xFF
    const val ALPHA_SHIFT = 24
    const val KELVIN_MIN = 2200
    const val KELVIN_SPAN = 4300f
    const val KELVIN_GREEN_BASE = 190
    const val KELVIN_GREEN_RANGE = 60
    const val KELVIN_BLUE_BASE = 120
    const val KELVIN_BLUE_RANGE = 135
    const val EFFECTS_TAKE = 24
    const val SOURCES_TAKE = 16
    const val LIBRARY_TAKE = 20
    const val FORECAST_DAYS = 7
}

internal fun ref(entityId: String, verb: String, arg: String = "") = ActionRef("e:$entityId", verb, arg)

internal fun DocContext.header(entityId: String, card: String) {
    val ink = role(Role.INK)
    val soft = role(Role.INK_SOFT)
    // Mockup: circular close on the left, title centred (balanced by a matching spacer).
    scope.Row(Modifier.fillMaxWidth(), vertical = RcVerticalPositioning.Center) {
        val doc = this@header
        doc.key(ActionRef("_", "close"), icon = "close", heightDp = CardChrome.CLOSE_KEY_DP, widthDp = CardChrome.CLOSE_KEY_DP, round = true)
        Column(Modifier.horizontalWeight(1f), horizontal = RcHorizontalPositioning.Center) {
            if (card == "energy") {
                doc.label(doc.i18n.t("ui.house"), Type.HEADLINE, ink, Weight.SEMI_BOLD, align = RcTextAlign.Center)
            } else {
                doc.label(
                    doc.text(Vars.entity(entityId, Vars.KEY_NAME)),
                    Type.HEADLINE, ink, Weight.SEMI_BOLD, align = RcTextAlign.Center,
                )
                doc.label(
                    doc.text(Vars.entity(entityId, Vars.KEY_STATE)),
                    Type.BODY, soft, align = RcTextAlign.Center,
                )
            }
        }
        Spacer(Modifier.size(doc.units.px(CardChrome.CLOSE_KEY_DP), doc.units.px(CardChrome.CLOSE_KEY_DP)))
    }
}

internal fun DocContext.bigToggle(entityId: String, entityState: EntityState?, verb: String = "toggle") {
    val spec = Catalogue.of(entityState?.domain ?: "")
    val r = ref(entityId, verb)
    predict(r, entityId)
    scope.Box(Modifier.fillMaxWidth(), horizontal = RcHorizontalPositioning.Center) {
        val doc = this@bigToggle
        Box(doc.tap(Modifier.size(doc.units.px(CardChrome.TOGGLE_DP), doc.units.px(CardChrome.TOGGLE_DP)).clip(doc.rounded((CardChrome.TOGGLE_DP) / 2f)).background(doc.color(Vars.tile(CardRenderer.TILE, Vars.KEY_BG))), r),
            horizontal = RcHorizontalPositioning.Center, vertical = RcVerticalPositioning.Center) {
            doc.toggleIcon(entityId, spec?.icon ?: "power", spec?.iconOn, CardChrome.TOGGLE_ICON_DP, doc.color(Vars.tile(CardRenderer.TILE, Vars.KEY_BG_INK)))
        }
    }
}

internal fun DocContext.section(title: String) {
    gap(CardChrome.SECTION_TITLE_GAP_DP)
    label(title, Type.BODY, role(Role.INK_SOFT), Weight.SEMI_BOLD)
    gap(CardChrome.FLOW_PAD_DP)
}

internal fun DocContext.bar(
    entityId: String,
    verb: String,
    width: Float,
    min: Double = 0.0,
    max: Double = CardChrome.LEVEL_DEFAULT_MAX,
    level: String = Vars.KEY_LEVEL,
) {
    // Keep the continuous float declared so predictions still update other readers.
    float(Vars.entity(entityId, level))
    segmentedLevelBar(
        entityId = entityId,
        levelKey = level,
        widthDp = width,
        heightDp = CardChrome.SEG_BAR_H_DP,
        steps = CardChrome.LEVEL_STEPS,
        levelOf = { states ->
            if (level == "tilt") {
                ((states[entityId]?.number("current_tilt_position") ?: 0.0) / CardChrome.LEVEL_DEFAULT_MAX).toFloat()
            } else {
                Present.view(states[entityId], i18n).level ?: 0f
            }
        },
    ) { step ->
        val value = min + (max - min) * step / CardChrome.LEVEL_STEPS.toDouble()
        ref(
            entityId,
            verb,
            if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, value),
        )
    }
}

internal fun DocContext.swatches(colors: List<Pair<Int, ActionRef>>) {
    scope.Row(Modifier.fillMaxWidth(), horizontal = RcRowHorizontalPositioning.SpaceBetween) {
        val doc = this@swatches
        for ((argb, r) in colors) {
            Box(doc.tap(Modifier.size(doc.units.px(CardChrome.SWATCH_DP), doc.units.px(CardChrome.SWATCH_DP)).clip(doc.rounded((CardChrome.SWATCH_DP) / 2f)).background(doc.fixed(argb or CardChrome.OPAQUE_ARGB.toInt())), r))
        }
    }
}

internal fun DocContext.flowKeys(
    entityId: String,
    verb: String,
    choices: List<Pair<String, String>>,
    chosen: (EntityState) -> String?,
) {
    bindChoices(entityId, verb, entityId, choices.map { it.first }, chosen)
    if (choices.size in 2..CardChrome.FLOW_KEYS_SEGMENT_MAX) {
        choiceRow(entityId, verb, choices, { ref(entityId, verb, it) }, CardChrome.FLOW_KEY_SEG_H_DP)
        return
    }
    scope.Flow(Modifier.fillMaxWidth()) {
        val doc = this@flowKeys
        choices.forEachIndexed { index, (value, word) ->
            Box(Modifier.padding(0f, 0f, doc.units.px(CardChrome.FLOW_PAD_DP), doc.units.px(CardChrome.FLOW_PAD_DP))) {
                doc.key(
                    ref(entityId, verb, value),
                    text = word,
                    bg = doc.color(Vars.option(entityId, verb, index, Vars.KEY_BG)),
                    ink = doc.color(Vars.option(entityId, verb, index, Vars.KEY_INK)),
                    heightDp = CardChrome.FLOW_KEY_SEG_H_DP,
                )
            }
        }
    }
}

val HUES = listOf(0, 30, 50, 120, 180, 220, 270, 320)
val KELVINS = listOf(2200, 2700, 3500, 4500, 5500, 6500)

/** The colour of white light at [k] kelvin, near enough for a swatch. */
fun kelvinColor(k: Int): Int {
    val t = ((k - CardChrome.KELVIN_MIN) / CardChrome.KELVIN_SPAN).coerceIn(0f, 1f)
    val r = 255
    val g = (CardChrome.KELVIN_GREEN_BASE + CardChrome.KELVIN_GREEN_RANGE * t).roundToInt()
    val b = (CardChrome.KELVIN_BLUE_BASE + CardChrome.KELVIN_BLUE_RANGE * t).roundToInt()
    return (CardChrome.ALPHA_OPAQUE shl CardChrome.ALPHA_SHIFT) or (r shl 16) or (g shl 8) or b
}

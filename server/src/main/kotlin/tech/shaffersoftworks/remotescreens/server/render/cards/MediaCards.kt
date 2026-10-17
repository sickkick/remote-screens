@file:Suppress("RestrictedApi")

package tech.shaffersoftworks.remotescreens.server.render.cards

import tech.shaffersoftworks.remotescreens.server.data.renderdata.PictureSlot
import androidx.compose.remote.creation.dsl.Modifier
import androidx.compose.remote.creation.dsl.RcContentScale
import androidx.compose.remote.creation.dsl.RcHorizontalPositioning
import androidx.compose.remote.creation.dsl.RcRowHorizontalPositioning
import androidx.compose.remote.creation.dsl.RcTextAlign
import androidx.compose.remote.creation.dsl.RcVerticalPositioning
import androidx.compose.remote.creation.dsl.background
import androidx.compose.remote.creation.dsl.clip
import androidx.compose.remote.creation.dsl.componentId
import androidx.compose.remote.creation.dsl.fillMaxSize
import androidx.compose.remote.creation.dsl.fillMaxWidth
import androidx.compose.remote.creation.dsl.size
import androidx.compose.remote.creation.dsl.visibility
import androidx.compose.remote.creation.dsl.width
import tech.shaffersoftworks.remotescreens.server.data.homeassistant.EntityState
import tech.shaffersoftworks.remotescreens.server.render.DocContext
import tech.shaffersoftworks.remotescreens.server.render.Role
import tech.shaffersoftworks.remotescreens.server.render.Type
import tech.shaffersoftworks.remotescreens.server.render.Vars
import tech.shaffersoftworks.remotescreens.server.render.Weight
import tech.shaffersoftworks.remotescreens.server.render.gap
import tech.shaffersoftworks.remotescreens.server.render.icon
import tech.shaffersoftworks.remotescreens.server.render.key
import tech.shaffersoftworks.remotescreens.server.render.label
import tech.shaffersoftworks.remotescreens.server.render.liveMap
import tech.shaffersoftworks.remotescreens.server.render.rounded
import tech.shaffersoftworks.remotescreens.server.render.spinner
import tech.shaffersoftworks.remotescreens.server.render.tap
import tech.shaffersoftworks.remotescreens.shared.VarValue

internal fun DocContext.mediaCard(entityId: String, entityState: EntityState?, w: Float) {
    val art = minOf(w, 280f)
    val px = units.px(art).toInt()
    picture(PictureSlot("art.card.$entityId", "attr:$entityId:entity_picture", px, px, 0))
    bind(entityId) { states ->
        val s = states[entityId]
        listOf(
            VarValue.string(Vars.entity(entityId, "title"), s?.attr("media_title") ?: ""),
            VarValue.string(Vars.entity(entityId, "artist"), listOfNotNull(s?.attr("media_artist"), s?.attr("media_album_name")).joinToString(" · ")),
            VarValue.int(Vars.entity(entityId, "playing"), if (s?.state == "playing") Vars.VISIBLE else Vars.GONE),
            VarValue.int(Vars.entity(entityId, "notplaying"), if (s?.state == "playing") Vars.GONE else Vars.VISIBLE),
        )
    }
    predict(ref(entityId, "play_pause"), entityId)
    scope.Column(Modifier.fillMaxWidth(), horizontal = RcHorizontalPositioning.Center) {
        val doc = this@mediaCard
        Image(doc.image("art.card.$entityId", px, px), Modifier.size(doc.units.px(art), doc.units.px(art)).clip(doc.rounded(CardChrome.PANEL_PAD_DP)), contentScale = RcContentScale.Crop)
        doc.gap(CardChrome.HEADER_GAP_DP)
        doc.label(doc.text(Vars.entity(entityId, "title")), Type.HEADLINE, doc.role(Role.INK), Weight.SEMI_BOLD, align = RcTextAlign.Center)
        doc.label(doc.text(Vars.entity(entityId, "artist")), Type.BODY, doc.role(Role.INK_SOFT), align = RcTextAlign.Center)
        doc.gap(CardChrome.HEADER_GAP_DP)
        Row(horizontal = RcRowHorizontalPositioning.Center, vertical = RcVerticalPositioning.Center) {
            doc.key(ref(entityId, "previous"), icon = "skip-previous", heightDp = CardChrome.MEDIA_CTRL_DP, widthDp = CardChrome.MEDIA_CTRL_DP, round = true)
            Spacer(width(doc.units.px(CardChrome.SECTION_TITLE_GAP_DP)))
            Box(doc.tap(
                size(doc.units.px(CardChrome.PLAY_KEY_DP), doc.units.px(CardChrome.PLAY_KEY_DP)).clip(doc.rounded((CardChrome.PLAY_KEY_DP) / 2f)).background(doc.role(
                    Role.KEY_ON)), ref(entityId, "play_pause")),
                horizontal = RcHorizontalPositioning.Center, vertical = RcVerticalPositioning.Center) {
                doc.icon("pause", CardChrome.CLOSE_KEY_DP, doc.role(Role.KEY_ON_INK), doc.int(Vars.entity(entityId, "playing")))
                doc.icon("play", CardChrome.CLOSE_KEY_DP, doc.role(Role.KEY_ON_INK), doc.int(Vars.entity(entityId, "notplaying")))
            }
            Spacer(width(doc.units.px(CardChrome.SECTION_TITLE_GAP_DP)))
            doc.key(ref(entityId, "next"), icon = "skip-next", heightDp = CardChrome.MEDIA_CTRL_DP, widthDp = CardChrome.MEDIA_CTRL_DP, round = true)
        }
    }
    section(i18n.t("ui.volume"))
    bar(entityId, "volume", w)
    val sources = entityState?.list("source_list").orEmpty()
    if (sources.isNotEmpty()) {
        structural(entityId) { it?.list("source_list")?.joinToString() ?: "" }
        section(i18n.t("ui.sources"))
        flowKeys(entityId, "source", sources.take(CardChrome.SOURCES_TAKE).map { it to it }) { it.attr("source") }
    }
    val library = data.browse[entityId].orEmpty()
    if (library.isNotEmpty()) {
        section(i18n.t("ui.library"))
        for (item in library.filter { it.canPlay }.take(CardChrome.LIBRARY_TAKE)) {
            key(ref(entityId, "play_media", "${item.contentType}|${item.contentId}"), text = item.title, icon = "play-circle-outline", heightDp = CardChrome.FLOW_KEY_H_DP, modifier = Modifier.fillMaxWidth())
            gap(CardChrome.ROW_GAP_DP)
        }
    }
}

internal fun DocContext.cameraCard(entityId: String, w: Float, h: Float) {
    val pw = units.px(w).toInt()
    val ph = units.px(h).toInt()
    picture(PictureSlot("cam.card.$entityId", "/api/camera_proxy/$entityId", pw, ph, 4, crop = false))
    val video = liveVideo(entityId, crop = false)
    val waiting = int(Vars.entity(entityId, Vars.KEY_CAM_WAITING), Vars.VISIBLE)
    val still = int(Vars.entity(entityId, Vars.KEY_CAM_STILL), Vars.GONE)
    val frame = Modifier.size(units.px(w), units.px(h))
        .clip(rounded(CardChrome.CARD_GAP_DP))
        .background(role(Role.TRACK))
        .componentId(video)
    scope.Box(frame, horizontal = RcHorizontalPositioning.Center, vertical = RcVerticalPositioning.Center) {
        val doc = this@cameraCard
        Image(
            doc.image("cam.card.$entityId", pw, ph),
            Modifier.fillMaxSize().visibility(still),
            contentScale = RcContentScale.Fit,
        )
        doc.spinner(CardChrome.CAMERA_SPINNER_DP, doc.role(Role.ACCENT), waiting)
    }
}

internal fun DocContext.mapCard(entityId: String, w: Float, h: Float) {
    val pw = units.px(w).toInt()
    val ph = units.px(h).toInt()
    val who = if (entityId.startsWith("person.") || entityId.startsWith("device_tracker.")) {
        listOf(entityId)
    } else {
        states.keys.filter { it.startsWith("person.") }
    }
    for (personId in who) {
        structural(personId) { state -> "${state?.number("latitude")},${state?.number("longitude")}" }
        val picturePath = states[personId]?.attr("entity_picture")
        if (!picturePath.isNullOrBlank()) {
            val markerPx = units.px(CardChrome.MAP_MARKER_DP).toInt().coerceAtLeast(1)
            picture(
                PictureSlot(
                    "${Vars.MAP_PIC_PREFIX}$personId",
                    "attr:$personId:entity_picture",
                    markerPx,
                    markerPx,
                    0,
                ),
            )
        }
    }
    picture(PictureSlot("map.card", "map:${mapStyle()}:${who.joinToString(",")}", pw, ph, 0))
    val mapComponent = liveMap(who)
    val frame = Modifier.size(units.px(w), units.px(h))
        .clip(rounded(CardChrome.CARD_GAP_DP))
        .background(role(Role.TRACK))
        .componentId(mapComponent)
    scope.Box(frame) {
        val doc = this@mapCard
        Image(
            doc.image("map.card", pw, ph),
            Modifier.fillMaxSize(),
            contentScale = RcContentScale.Crop,
        )
    }
    label(i18n.t("ui.map_credit"), Type.CAPTION, role(Role.INK_SOFT))
}

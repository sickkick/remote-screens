package tech.shaffersoftworks.remotescreens.player.ui.screens.player.remote

import android.graphics.Bitmap
import tech.shaffersoftworks.remotescreens.shared.MapMarker
import tech.shaffersoftworks.remotescreens.shared.ServerMessage
import tech.shaffersoftworks.remotescreens.shared.Slot
import tech.shaffersoftworks.remotescreens.shared.VarValue

/**
 * A document shown in a slot. Compared by identity, so the renderer loads each one into its player exactly once.
 * [predictions] maps an action's metadata to the values it will change, and may be updated in place when vars
 * arrive so the document object stays the same and the player is not reloaded.
 */
class SlotDocument(
    val revision: Int,
    val bytes: ByteArray,
    val predictions: MutableMap<String, List<VarValue>>,
)

/**
 * A camera's live video over the component with [componentId] in document [revision] of a slot. [url] is on the add-on
 * and wants [token], the player's own, as a bearer. [crop] fills the box; else the whole picture fits inside it.
 */
data class SlotVideo(
    val revision: Int,
    val componentId: Int,
    val url: String,
    val token: String,
    val crop: Boolean,
)

/**
 * An interactive map over the component with [componentId] in document [revision] of a slot. [tileUrl] has
 * `{z}`, `{x}` and `{y}`; [markers] are people (or trackers) to pin.
 */
data class SlotMap(
    val revision: Int,
    val componentId: Int,
    val tileUrl: String,
    val dark: Boolean,
    val markers: List<MapMarker>,
)

/**
 * Everything the Remote Compose players show: the pages of the screen, a card over them, an alert over both.
 * Values go to every shown document; each document takes the ones it names.
 */
data class RemoteScreenState(
    /** The shown documents. A slot without a document is hidden. */
    val documents: Map<Slot, SlotDocument> = emptyMap(),
    val values: Map<String, VarValue> = emptyMap(),
    val bitmaps: Map<String, Bitmap> = emptyMap(),
    /** Live video the add-on started for a shown document; it goes with the document. */
    val videos: Map<Slot, SlotVideo> = emptyMap(),
    /** Interactive map the add-on started for a shown document; it goes with the document. */
    val maps: Map<Slot, SlotMap> = emptyMap(),
    /** Named integers the player sets itself (free-form tabs), kept across document rebuilds. */
    val localInts: Map<String, Int> = emptyMap(),
    val page: Int = 0,
    val homePage: Int = 0,
    val pageCount: Int = 1,
    /** The pages a swipe moves through, in order. */
    val swipePages: List<Int> = listOf(0),
    /** Home Assistant's time zone for analog clocks; null keeps the tablet's zone. */
    val timeZone: String? = null,
) {
    val hasMainDocument: Boolean get() = Slot.MAIN in documents

    val isOverlayShown: Boolean get() = Slot.OVERLAY in documents || Slot.ALERT in documents

    /** The topmost shown overlay, which Back closes first. */
    val topOverlay: Slot?
        get() = when {
            Slot.ALERT in documents -> Slot.ALERT
            Slot.OVERLAY in documents -> Slot.OVERLAY
            else -> null
        }

    /** The video to play: only the topmost card's or alert's, as a tablet may decode one stream at a time. */
    val playingVideo: Pair<Slot, SlotVideo>?
        get() {
            val slot = topOverlay ?: return null
            return videos[slot]
                ?.takeIf { video -> video.revision == documents[slot]?.revision }
                ?.let { video -> slot to video }
        }

    /** The interactive map to show: only the topmost overlay's, matching its document revision. */
    val playingMap: Pair<Slot, SlotMap>?
        get() {
            val slot = topOverlay ?: return null
            return maps[slot]
                ?.takeIf { map -> map.revision == documents[slot]?.revision }
                ?.let { map -> slot to map }
        }

    /** The page to show after a new main document: the same one if it still exists, otherwise home. */
    val pageAfterNewDocument: Int get() = if (page < pageCount) page else homePage

    /** From a hidden page, a swipe goes to the nearest swipeable page in that direction. */
    val nextSwipePage: Int? get() = swipePages.firstOrNull { it > page }

    val previousSwipePage: Int? get() = swipePages.lastOrNull { it < page }

    fun withDocument(message: ServerMessage.Document, bytes: ByteArray): RemoteScreenState {
        val document = SlotDocument(message.rev, bytes, message.predictions.toMutableMap())
        val updated = copy(
            documents = documents + (message.slot to document),
            values = values + message.vars.associateBy(VarValue::name),
            videos = videos - message.slot,
            maps = maps - message.slot,
        )
        if (message.slot != Slot.MAIN) return updated
        val newPageCount = message.pageCount.coerceAtLeast(1)
        return updated.copy(
            homePage = message.homePage,
            pageCount = newPageCount,
            swipePages = message.swipePages
                .filter { it in 0 until newPageCount }
                .sorted()
                .ifEmpty { (0 until newPageCount).toList() },
        )
    }

    fun withVars(message: ServerMessage.Vars): RemoteScreenState {
        if (message.predictions.isNotEmpty()) {
            for (document in documents.values) document.predictions.putAll(message.predictions)
        }
        return copy(values = values + message.vars.associateBy(VarValue::name))
    }

    fun withTimeZone(timeZone: String?): RemoteScreenState =
        if (timeZone == null || timeZone == this.timeZone) this else copy(timeZone = timeZone)

    /** What a tap will change, applied before Home Assistant answers. Null when the tap predicts nothing. */
    fun withPrediction(slot: Slot, metadata: String): RemoteScreenState? {
        val predictedValues = documents[slot]?.predictions?.get(metadata) ?: return null
        return copy(values = values + predictedValues.associateBy(VarValue::name))
    }

    fun withBitmap(name: String, bitmap: Bitmap): RemoteScreenState = copy(bitmaps = bitmaps + (name to bitmap))

    /** A video for a document that is no longer shown is dropped. */
    fun withVideo(slot: Slot, video: SlotVideo): RemoteScreenState =
        if (documents[slot]?.revision == video.revision) copy(videos = videos + (slot to video)) else this

    /** Forgets [video] once it stopped; a newer one the add-on sent for the slot stays. */
    fun withoutVideo(slot: Slot, video: SlotVideo): RemoteScreenState =
        if (videos[slot] == video) copy(videos = videos - slot) else this

    /** A map for a document that is no longer shown is dropped. */
    fun withMap(slot: Slot, map: SlotMap): RemoteScreenState =
        if (documents[slot]?.revision == map.revision) copy(maps = maps + (slot to map)) else this

    fun withPage(index: Int): RemoteScreenState = copy(page = index.coerceIn(0, pageCount - 1))

    fun withLocalInt(name: String, value: Int): RemoteScreenState = copy(localInts = localInts + (name to value))

    /** The main document cannot be closed, only replaced or cleared. */
    fun closed(slot: Slot): RemoteScreenState =
        if (slot == Slot.MAIN) {
            this
        } else {
            copy(documents = documents - slot, videos = videos - slot, maps = maps - slot)
        }

    /** Hides every document and forgets the values, as when the screen must be paired again. */
    fun cleared(): RemoteScreenState =
        copy(
            documents = emptyMap(),
            values = emptyMap(),
            videos = emptyMap(),
            maps = emptyMap(),
            localInts = emptyMap(),
        )
}

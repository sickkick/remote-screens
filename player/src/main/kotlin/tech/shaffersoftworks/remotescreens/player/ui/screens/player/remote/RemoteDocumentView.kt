@file:Suppress("RestrictedApi")

package tech.shaffersoftworks.remotescreens.player.ui.screens.player.remote

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.compose.remote.core.CalendarSystemClock
import androidx.compose.remote.core.RemoteClock
import androidx.compose.remote.core.RemoteContext
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.remote.player.view.RemoteComposePlayer
import tech.shaffersoftworks.remotescreens.shared.Slot
import tech.shaffersoftworks.remotescreens.shared.VarType
import tech.shaffersoftworks.remotescreens.shared.VarValue
import java.io.ByteArrayInputStream
import java.time.ZoneId
import java.util.TimeZone
import kotlin.math.roundToInt

/** The user variable each main document reads to know which page to draw. */
const val PAGE_VARIABLE = "page"

/**
 * The only door to the Remote Compose player (still alpha). Holds one player per [Slot], stacked, and brings them
 * in line with a [RemoteScreenState]: each new document is loaded once, then only changed values and bitmaps are set.
 */
@SuppressLint("ViewConstructor")
class RemoteDocumentView(context: Context) : SwipeFrame(context) {
    private val players: Map<Slot, RemoteComposePlayer> = Slot.entries.associateWith { RemoteComposePlayer(context) }
    private val loadedDocuments = mutableMapOf<Slot, SlotDocument>()
    private var appliedValues: Map<String, VarValue> = emptyMap()
    private var appliedBitmaps: Map<String, Bitmap> = emptyMap()
    private var appliedPage: Int? = null
    private val liveVideoView = LiveVideoView(context)
    private val liveMapView = LiveMapView(context)
    private var videoSlot: Slot? = null
    private var mapSlot: Slot? = null
    private var lastState: RemoteScreenState? = null
    private var appliedTimeZone: String? = null

    /** A tap in a document: its slot, the revision of the document, and the tap's metadata. */
    var onAction: (slot: Slot, revision: Int, metadata: String) -> Unit = { _, _, _ -> }

    /** A camera's video stopped by itself, with the add-on's HTTP status when that was the cause. */
    var onVideoFailed: (slot: Slot, video: SlotVideo, httpCode: Int?) -> Unit = { _, _, _ -> }

    /** Off while the screen is in standby, so no camera is decoded for nobody. */
    var isVideoAllowed = true

    init {
        for (slot in Slot.entries) {
            val player = players.getValue(slot)
            player.visibility = if (slot == Slot.MAIN) View.INVISIBLE else View.GONE
            addView(player, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        liveVideoView.onFailed = { video, httpCode ->
            val slot = videoSlot
            stopVideo()
            if (slot != null) onVideoFailed(slot, video, httpCode)
        }
        // A text that changes while it is measured registers a relayout boundary in the middle of the partial
        // layout pass, which then throws ConcurrentModificationException on every frame (alpha20). A full pass
        // never walks that list, so the partial pass is never left to run.
        viewTreeObserver.addOnPreDrawListener {
            for (slot in loadedDocuments.keys) fullLayoutIfDirty(players.getValue(slot))
            placeVideo()
            placeMap()
            true
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        lastState?.let { state ->
            renderVideo(state)
            renderMap(state)
        }
    }

    override fun onDetachedFromWindow() {
        stopVideo()
        stopMap()
        super.onDetachedFromWindow()
    }

    fun render(state: RemoteScreenState) {
        lastState = state
        loadedDocuments.keys.retainAll(state.documents.keys)
        val newlyLoaded = loadNewDocuments(state)
        applyChangedValues(state.values, excludeSlots = newlyLoaded)
        applyChangedBitmaps(state.bitmaps, excludeSlots = newlyLoaded)
        appliedValues = state.values
        appliedBitmaps = state.bitmaps

        if (Slot.MAIN in newlyLoaded || state.page != appliedPage) {
            players.getValue(Slot.MAIN).setUserLocalInt(PAGE_VARIABLE, state.page)
            appliedPage = state.page
        }
        if (Slot.MAIN in loadedDocuments) {
            for ((name, value) in state.localInts) {
                players.getValue(Slot.MAIN).setUserLocalInt(name, value)
            }
        }
        for (slot in Slot.entries) {
            val isShown = slot in state.documents
            players.getValue(slot).visibility = when {
                isShown -> View.VISIBLE
                slot == Slot.MAIN -> View.INVISIBLE
                else -> View.GONE
            }
        }
        // A value set from outside a touch is not drawn until the player is asked to.
        for (slot in loadedDocuments.keys) players.getValue(slot).invalidate()
        renderVideo(state)
        renderMap(state)
    }

    private fun renderVideo(state: RemoteScreenState) {
        val activeVideo = state.playingVideo?.takeIf { isVideoAllowed && windowVisibility == View.VISIBLE }
        when {
            activeVideo == null -> stopVideo()
            activeVideo.first != videoSlot || activeVideo.second != liveVideoView.video ->
                startVideo(activeVideo.first, activeVideo.second)
        }
    }

    /** Just above its document player, so a card's video stays under an alert. */
    private fun startVideo(slot: Slot, video: SlotVideo) {
        (liveVideoView.parent as? ViewGroup)?.removeView(liveVideoView)
        val layoutParams = LayoutParams(0, 0).apply { gravity = Gravity.TOP or Gravity.START }
        // Insert above that slot only — never bringToFront, or the overlay sits over alerts and steals taps.
        addView(liveVideoView, indexOfChild(players.getValue(slot)) + 1, layoutParams)
        liveVideoView.visibility = View.INVISIBLE
        videoSlot = slot
        liveVideoView.play(video)
        placeVideo()
    }

    private fun stopVideo() {
        liveVideoView.stop()
        videoSlot = null
        (liveVideoView.parent as? ViewGroup)?.removeView(liveVideoView)
    }

    /** Over the image the video belongs to, wherever the document laid it out, scrolled, this frame. */
    private fun placeVideo() {
        placeOverlayView(videoSlot, liveVideoView, liveVideoView.video?.componentId)
    }

    private fun renderMap(state: RemoteScreenState) {
        val activeMap = state.playingMap?.takeIf { windowVisibility == View.VISIBLE }
        when {
            activeMap == null -> stopMap()
            activeMap.first != mapSlot || activeMap.second != liveMapView.map ->
                startMap(activeMap.first, activeMap.second, state.bitmaps)
            else -> liveMapView.show(activeMap.second, state.bitmaps)
        }
    }

    /** Just above its document player, so a card's map stays under an alert. */
    private fun startMap(slot: Slot, map: SlotMap, bitmaps: Map<String, Bitmap>) {
        (liveMapView.parent as? ViewGroup)?.removeView(liveMapView)
        val layoutParams = LayoutParams(0, 0).apply { gravity = Gravity.TOP or Gravity.START }
        addView(liveMapView, indexOfChild(players.getValue(slot)) + 1, layoutParams)
        liveMapView.visibility = View.INVISIBLE
        mapSlot = slot
        liveMapView.show(map, bitmaps)
        placeMap()
    }

    private fun stopMap() {
        liveMapView.stop()
        mapSlot = null
        (liveMapView.parent as? ViewGroup)?.removeView(liveMapView)
    }

    private fun placeMap() {
        placeOverlayView(mapSlot, liveMapView, liveMapView.map?.componentId)
    }

    private fun placeOverlayView(slot: Slot?, overlay: View, componentId: Int?) {
        if (slot == null || componentId == null) return
        val player = players.getValue(slot)
        val bounds = componentBounds(player, componentId)
        if (bounds == null) {
            overlay.visibility = View.INVISIBLE
            return
        }
        bounds.offset(player.left, player.top)
        val layoutParams = overlay.layoutParams as LayoutParams
        val previousBounds = Rect(
            layoutParams.leftMargin,
            layoutParams.topMargin,
            layoutParams.leftMargin + layoutParams.width,
            layoutParams.topMargin + layoutParams.height,
        )
        if (previousBounds != bounds) {
            layoutParams.leftMargin = bounds.left
            layoutParams.topMargin = bounds.top
            layoutParams.width = bounds.width()
            layoutParams.height = bounds.height()
            overlay.layoutParams = layoutParams
        }
        overlay.visibility = View.VISIBLE
    }

    /**
     * Where component [componentId] sits in [player]'s document. Uses the library location when it works; never
     * fails closed on that path alone (that left the overlay invisible and only the spinner or still showed).
     */
    private fun componentBounds(player: RemoteComposePlayer, componentId: Int): Rect? {
        val component = runCatching { player.document.document.getComponent(componentId) }.getOrNull()
        val width = component?.width?.roundToInt() ?: 0
        val height = component?.height?.roundToInt() ?: 0
        if (component == null || width <= 0 || height <= 0) return null
        val windowBounds = remoteContextOf(player)?.let { remoteContext ->
            val locationInWindow = floatArrayOf(0f, 0f)
            runCatching {
                component.getLocationInWindow(remoteContext, locationInWindow)
                val left = locationInWindow[0].roundToInt()
                val top = locationInWindow[1].roundToInt()
                Rect(left, top, left + width, top + height)
            }.getOrNull()
        }
        return windowBounds ?: run {
            val semanticBounds = IntArray(SEMANTIC_BOUND_SIZE)
            val hasSemanticBounds = runCatching {
                component.getBoundsInSemanticParent(semanticBounds, null)
                true
            }.getOrDefault(false)
            if (hasSemanticBounds) {
                Rect(
                    semanticBounds[BOUND_LEFT],
                    semanticBounds[BOUND_TOP],
                    semanticBounds[BOUND_RIGHT],
                    semanticBounds[BOUND_BOTTOM],
                )
            } else {
                null
            }
        }
    }

    private fun remoteContextOf(player: RemoteComposePlayer): RemoteContext? = runCatching {
        RemoteComposePlayer::class.java.getDeclaredMethod("getRemoteContext").run {
            isAccessible = true
            invoke(player) as RemoteContext
        }
    }.getOrNull()

    private fun loadNewDocuments(state: RemoteScreenState): Set<Slot> {
        if (state.timeZone != appliedTimeZone) {
            loadedDocuments.clear()
            appliedTimeZone = state.timeZone
        }
        val newlyLoaded = mutableSetOf<Slot>()
        for ((slot, document) in state.documents) {
            if (loadedDocuments[slot] === document) continue
            val player = players.getValue(slot)
            // Load with Home Assistant's zone so analog hands match the digital TIME/DATE vars.
            // Each document needs its own clock: CalendarSystemClock's calendar is not thread-safe.
            player.setDocument(RemoteDocument(ByteArrayInputStream(document.bytes), clockFor(state.timeZone)))
            // Action listeners belong to the loaded document, so each new document needs its own.
            player.addIdActionListener { _, metadata ->
                if (metadata != null) onAction(slot, document.revision, metadata)
            }
            for (value in state.values.values) player.applyValue(value)
            for ((name, bitmap) in state.bitmaps) player.setUserLocalBitmap(name, bitmap)
            loadedDocuments[slot] = document
            newlyLoaded += slot
        }
        return newlyLoaded
    }

    private fun clockFor(timeZone: String?): RemoteClock {
        if (timeZone.isNullOrEmpty()) return RemoteClock.SYSTEM
        return runCatching {
            CalendarSystemClock(TimeZone.getTimeZone(ZoneId.of(timeZone)))
        }.getOrDefault(RemoteClock.SYSTEM)
    }

    private fun applyChangedValues(values: Map<String, VarValue>, excludeSlots: Set<Slot>) {
        val changedValues = values.values.filter { value -> appliedValues[value.name] != value }
        if (changedValues.isEmpty()) return
        for (slot in loadedDocuments.keys - excludeSlots) {
            val player = players.getValue(slot)
            for (value in changedValues) player.applyValue(value)
        }
    }

    private fun applyChangedBitmaps(bitmaps: Map<String, Bitmap>, excludeSlots: Set<Slot>) {
        val changedBitmaps = bitmaps.filter { (name, bitmap) -> appliedBitmaps[name] !== bitmap }
        if (changedBitmaps.isEmpty()) return
        for (slot in loadedDocuments.keys - excludeSlots) {
            val player = players.getValue(slot)
            for ((name, bitmap) in changedBitmaps) player.setUserLocalBitmap(name, bitmap)
        }
    }

    private fun fullLayoutIfDirty(player: RemoteComposePlayer) {
        val rootLayout = runCatching { player.document.document.rootLayoutComponent }.getOrNull() ?: return
        if (rootLayout.needsMeasure()) {
            rootLayout.clearDirtyBoundaries()
            rootLayout.invalidateMeasure()
        }
    }

    private fun RemoteComposePlayer.applyValue(value: VarValue) {
        when (value.type) {
            VarType.STRING -> setUserLocalString(value.name, value.value)
            VarType.INT -> setUserLocalInt(value.name, value.asInt())
            VarType.FLOAT -> setUserLocalFloat(value.name, value.asFloat())
            VarType.COLOR -> setUserLocalColor(value.name, value.asColor())
        }
    }

    companion object {
        /** [Component.getBoundsInSemanticParent] writes left, top, right, bottom into a length-4 array. */
        private const val SEMANTIC_BOUND_SIZE = 4
        private const val BOUND_LEFT = 0
        private const val BOUND_TOP = 1
        private const val BOUND_RIGHT = 2
        private const val BOUND_BOTTOM = 3
    }
}

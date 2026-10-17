@file:Suppress("RestrictedApi")

package tech.shaffersoftworks.remotescreens.server.render

import androidx.compose.remote.creation.dsl.RcColor
import androidx.compose.remote.creation.dsl.RcFloat
import androidx.compose.remote.creation.dsl.RcImage
import androidx.compose.remote.creation.dsl.RcInteger
import androidx.compose.remote.creation.dsl.RcPath
import androidx.compose.remote.creation.dsl.RcScope
import androidx.compose.remote.creation.dsl.RcText
import tech.shaffersoftworks.remotescreens.server.data.homeassistant.EntityState
import tech.shaffersoftworks.remotescreens.server.data.homeassistant.HomeAssistantConfig
import tech.shaffersoftworks.remotescreens.server.data.renderdata.PictureSlot
import tech.shaffersoftworks.remotescreens.server.data.renderdata.RenderData
import tech.shaffersoftworks.remotescreens.server.i18n.I18n
import tech.shaffersoftworks.remotescreens.shared.ActionRef
import tech.shaffersoftworks.remotescreens.shared.VarValue
import java.awt.image.BufferedImage

private const val HEX_RADIX = 16

/**
 * Everything a renderer needs while it writes one document, and what it leaves behind: the named variables it
 * declared, how to compute their values from Home Assistant's states, the pictures to send, and the actions whose
 * effect can be shown before Home Assistant answers. Every renderer extends it, so its functions stay on one class.
 */
class DocContext(
    val units: Units,
    val palette: Palette,
    val i18n: I18n,
    val states: Map<String, EntityState>,
    val homeAssistantConfig: HomeAssistantConfig,
    val data: RenderData,
) {
    lateinit var scope: RcScope

    private val texts = HashMap<String, RcText>()
    private val ints = HashMap<String, RcInteger>()
    private val floats = HashMap<String, RcFloat>()
    private val colors = HashMap<String, RcColor>()
    private val images = HashMap<String, RcImage>()
    private val paths = HashMap<String, RcPath>()
    private val metas = HashMap<String, RcText>()
    private val literals = HashMap<String, RcText>()

    val bindings = mutableListOf<Binding>()
    val pictures = mutableListOf<PictureSlot>()
    /** Cameras whose live video plays over one of the document's images, for players that can play it. */
    val videos = mutableListOf<LiveVideo>()
    /** Maps whose interactive OSM view plays over one of the document's boxes, for players that can show it. */
    val maps = mutableListOf<LiveMap>()
    /** Action metadata to the action and the entity it changes. */
    val predicted = LinkedHashMap<String, Pair<ActionRef, String>>()
    /** What of an entity is drawn into the document itself (an icon, a list of modes), and what it was. */
    val structure = mutableListOf<Triple<String, (EntityState?) -> String, String>>()
    /** Rebuild after this many seconds (a forecast, a history graph), or 0 for never. */
    var refreshSeconds = 0

    /** Every declaration so far, in order, to replay at the top of the next build (see [build]). */
    private val declarations = mutableListOf<DocContext.() -> Unit>()

    private inline fun <T> declare(
        cache: HashMap<String, T>,
        key: String,
        noinline again: DocContext.() -> Unit,
        make: () -> T,
    ): T =
        cache.getOrPut(key) {
            declarations += again
            make()
        }

    fun text(name: String): RcText =
        declare(texts, name, { text(name) }) { scope.remoteNamedText(Rc.USER + name, "") }

    fun int(name: String, initial: Int = 0): RcInteger =
        declare(ints, name, { int(name, initial) }) { scope.remoteNamedInteger(Rc.USER + name, initial) }

    fun float(name: String, initial: Float = 0f): RcFloat =
        declare(floats, name, { float(name, initial) }) { scope.remoteNamedFloat(Rc.USER + name, initial) }

    fun color(name: String, initial: Int = 0): RcColor =
        declare(colors, name, { color(name, initial) }) { scope.remoteNamedColor(Rc.USER + name, initial) }

    /** A fixed text for canvas text operations, which take a declared text rather than a string. */
    fun literal(s: String): RcText = declare(literals, s, { literal(s) }) { scope.remoteText(s) }
    fun fixed(argb: Int): RcColor =
        declare(colors, "#" + argb.toUInt().toString(HEX_RADIX), { fixed(argb) }) { scope.remoteColor(argb) }

    fun role(r: Role): RcColor = fixed(palette[r])
    fun pastel(p: Pastel): RcColor = fixed(palette[p])

    /** A role or pastel in the screen's theme, so live colours sent later match the document. */
    val Role.argb: Int get() = palette[this]
    val Pastel.argb: Int get() = palette[this]

    /** The style a drawn map takes to sit well on the theme's page. */
    fun mapStyle(): String = if (palette.dark) "dark" else "light"

    /** Whether the document has a named variable [name], so a value for it is worth sending. */
    fun declares(name: String): Boolean = name in texts || name in ints || name in floats || name in colors

    fun image(name: String, w: Int, h: Int): RcImage = declare(images, name, { image(name, w, h) }) {
        val placeholder = BufferedImage(w.coerceAtLeast(1), h.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
        scope.remoteNamedBitmap(Rc.USER + name, placeholder)
    }

    fun path(icon: String?): RcPath {
        val svg = Icons.path(icon)
        return declare(paths, svg, { path(icon) }) { scope.remotePathData(Rc.path(svg)) }
    }

    fun meta(ref: ActionRef): RcText {
        val s = ref.encode()
        return declare(metas, s, { meta(ref) }) { scope.remoteText(s) }
    }

    fun bind(entityId: String?, compute: (Map<String, EntityState>) -> List<VarValue>) {
        bindings += Binding(entityId, compute)
    }

    fun predict(ref: ActionRef, entityId: String) {
        predicted[ref.encode()] = ref to entityId
    }

    fun picture(slot: PictureSlot) {
        if (pictures.none { it.name == slot.name }) pictures += slot
    }

    /** The component id to give the box [entityId]'s live video plays over, with `Modifier.componentId`. */
    fun liveVideo(entityId: String, crop: Boolean): Int {
        val componentId = LiveVideo.FIRST_COMPONENT_ID + videos.size
        videos += LiveVideo(entityId, componentId, crop)
        return componentId
    }

    /** The document is built again when [what] of the entity changes, such as its icon or its list of modes. */
    fun structural(entityId: String, what: (EntityState?) -> String) {
        structure += Triple(entityId, what, what(states[entityId]))
    }

    fun refreshEvery(seconds: Int) {
        refreshSeconds = if (refreshSeconds == 0) seconds else minOf(refreshSeconds, seconds)
    }

    companion object {
        /**
         * Builds a document twice. Variables, colours, paths and texts declared inside a layout component are lost
         * when the player reads the document (alpha20 and alpha21), so a first pass finds what [content] declares and
         * the second declares all of it at the top before laying anything out.
         */
        fun build(
            widthPx: Int,
            heightPx: Int,
            description: String,
            newContext: () -> DocContext,
            content: RcScope.(DocContext) -> Unit,
        ): Pair<ByteArray, DocContext> {
            val dry = newContext()
            Rc.document(widthPx, heightPx, description) {
                dry.scope = this
                content(dry)
            }
            val docContext = newContext()
            val bytes = Rc.document(widthPx, heightPx, description) {
                docContext.scope = this
                for (d in dry.declarations) d(docContext)
                content(docContext)
            }
            return bytes to docContext
        }
    }
}

class Binding(val entityId: String?, val compute: (Map<String, EntityState>) -> List<VarValue>)

/** A camera's live video over the component with [componentId]; [crop] when the feed fills its box rather than fits. */
data class LiveVideo(val entityId: String, val componentId: Int, val crop: Boolean) {
    companion object {
        /** Far above the ids the player gives components itself, so the video target is the only one with it. */
        const val FIRST_COMPONENT_ID = 0x5C_0000
    }
}

/** An interactive map of [entityIds] over the component with [componentId]; [dark] matches the theme. */
data class LiveMap(val entityIds: List<String>, val componentId: Int, val dark: Boolean) {
    companion object {
        /** Next to [LiveVideo.FIRST_COMPONENT_ID], so map and video targets never share an id. */
        const val FIRST_COMPONENT_ID = 0x5D_0000
    }
}

/** The component id to give the box an interactive map of [entityIds] plays over, with `Modifier.componentId`. */
fun DocContext.liveMap(entityIds: List<String>): Int {
    val componentId = LiveMap.FIRST_COMPONENT_ID + maps.size
    maps += LiveMap(entityIds, componentId, dark = palette.dark)
    return componentId
}

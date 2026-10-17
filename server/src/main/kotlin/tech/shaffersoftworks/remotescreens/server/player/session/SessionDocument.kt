package tech.shaffersoftworks.remotescreens.server.player.session

import tech.shaffersoftworks.remotescreens.server.data.homeassistant.EntityState
import tech.shaffersoftworks.remotescreens.server.data.renderdata.RenderData
import tech.shaffersoftworks.remotescreens.server.render.Alert
import tech.shaffersoftworks.remotescreens.server.render.AlertRenderer
import tech.shaffersoftworks.remotescreens.server.render.CardRenderer
import tech.shaffersoftworks.remotescreens.server.render.Rc
import tech.shaffersoftworks.remotescreens.server.render.RenderInput
import tech.shaffersoftworks.remotescreens.server.render.RenderedDoc
import tech.shaffersoftworks.remotescreens.server.render.ScreenRenderer
import tech.shaffersoftworks.remotescreens.shared.Screen
import tech.shaffersoftworks.remotescreens.shared.ServerMessage
import tech.shaffersoftworks.remotescreens.shared.Slot
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.Base64

/** Builds and publishes documents for each [Slot] on a [SessionHost]. */
internal class SessionDocument(
    private val host: SessionHost,
    private val media: SessionMedia,
) {
    private val logger = LoggerFactory.getLogger(SessionDocument::class.java)
    private val context get() = host.context
    private val homeAssistant get() = context.homeAssistant
    private val shownDocuments get() = host.shownDocuments
    private val buildMutex get() = host.buildMutex

    fun renderInput(renderData: RenderData) = RenderInput(
        states = homeAssistant.states.value,
        i18n = context.languageRepository.current(),
        homeAssistantConfig = homeAssistant.config.value,
        data = renderData,
        palette = host.palette,
    )

    suspend fun renderScreen(screen: Screen): RenderedDoc =
        ScreenRenderer.render(screen, host.device, renderInput(context.renderDataRepository.forScreen(screen)))

    suspend fun showMain(screen: Screen) {
        val rebuild: suspend () -> RenderedDoc? = { host.screen()?.let { renderScreen(it) } }
        val renderedDoc = buildMutex.withLock { renderScreen(screen) }
        show(Slot.MAIN, renderedDoc, rebuild)
    }

    suspend fun show(
        slot: Slot,
        renderedDoc: RenderedDoc,
        rebuild: suspend () -> RenderedDoc?,
        timeoutSeconds: Int = 0,
    ) {
        val states = homeAssistant.states.value
        val shown = ShownDocument(slot, renderedDoc, rebuild).also { it.revision = ++host.lastRevision }
        val vars = renderedDoc.vars(states)
        shown.sentVars = vars
        shownDocuments[slot] = shown
        val allVars = vars.values + context.screenClock.clockVars() + host.connectedVar() + host.deviceVars()
        val bytes = if (host.supportsDeflate) Rc.compress(renderedDoc.bytes) else renderedDoc.bytes
        host.send(
            ServerMessage.Document(
                slot = slot,
                rev = shown.revision,
                data = Base64.getEncoder().encodeToString(bytes),
                vars = allVars,
                predictions = renderedDoc.predictions(states, host::foresee),
                homePage = renderedDoc.homePage,
                pageCount = renderedDoc.pageIds.size.coerceAtLeast(1),
                swipePages = renderedDoc.swipePages,
                timeout = timeoutSeconds,
            ),
        )
        media.sendPictures(shown, states, isForced = true)
        for (video in renderedDoc.docContext.videos) media.sendVideo(shown, video)
        for (map in renderedDoc.docContext.maps) media.sendMap(shown, map, states)
    }

    /** Sends what changed in [states]; a document whose structure depends on a changed entity is built again. */
    suspend fun refresh(states: Map<String, EntityState>) {
        for (shown in shownDocuments.values.toList()) {
            if (shown.renderedDoc.stale(states)) {
                rebuild(shown)
                continue
            }
            val vars = shown.renderedDoc.vars(states)
            val changed = vars.values.filter { shown.sentVars[it.name] != it }
            shown.sentVars = vars
            if (changed.isNotEmpty()) {
                host.send(ServerMessage.Vars(changed, shown.renderedDoc.predictions(states, host::foresee)))
            }
            media.sendPictures(shown, states, isForced = false)
            for (map in shown.renderedDoc.docContext.maps) media.sendMap(shown, map, states)
        }
    }

    suspend fun rebuild(shown: ShownDocument) {
        val renderedDoc = buildMutex.withLock {
            runCatching { shown.rebuild() }
                .onFailure { logger.warn("Build failed: {}", it.message, it) }
                .getOrNull()
        } ?: return
        if (shownDocuments[shown.slot] === shown) show(shown.slot, renderedDoc, shown.rebuild)
    }

    /** Sends every value again: after a tap whose prediction Home Assistant did not confirm. */
    suspend fun resync() {
        val states = homeAssistant.states.value
        for (shown in shownDocuments.values) {
            val vars = shown.renderedDoc.vars(states)
            shown.sentVars = vars
            host.send(ServerMessage.Vars(vars.values.toList(), shown.renderedDoc.predictions(states, host::foresee)))
        }
    }

    suspend fun openCard(entityId: String?, kind: String?) {
        // Start the HLS stream while the card builds so the first frame is not waiting on camera/stream.
        media.warmCameraStream(entityId = entityId, kind = kind)
        val build: suspend () -> RenderedDoc? = {
            val renderData = context.renderDataRepository.forCard(entityId, kind)
            CardRenderer.render(entityId ?: "card.$kind", kind, host.device, renderInput(renderData))
        }
        val renderedDoc = buildMutex.withLock { build() } ?: return
        show(Slot.OVERLAY, renderedDoc, build)
    }

    suspend fun showAlert(alert: Alert) {
        host.currentAlert = alert
        media.warmCameraStream(camera = alert.camera)
        val build: suspend () -> RenderedDoc? = {
            host.currentAlert?.let { AlertRenderer.render(it, host.device, renderInput(RenderData())) }
        }
        val renderedDoc = buildMutex.withLock { build() } ?: return
        host.send(ServerMessage.Wake(true))
        show(Slot.ALERT, renderedDoc, build, timeoutSeconds = alert.timeout)
    }
}

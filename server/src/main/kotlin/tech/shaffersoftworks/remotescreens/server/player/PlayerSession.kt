package tech.shaffersoftworks.remotescreens.server.player

import tech.shaffersoftworks.remotescreens.server.data.homeassistant.EntityState
import tech.shaffersoftworks.remotescreens.server.player.action.ActionRouter
import tech.shaffersoftworks.remotescreens.server.player.action.TapMemory
import tech.shaffersoftworks.remotescreens.server.player.action.TapOutcome
import tech.shaffersoftworks.remotescreens.server.player.session.SessionDocument
import tech.shaffersoftworks.remotescreens.server.player.session.SessionHost
import tech.shaffersoftworks.remotescreens.server.player.session.SessionLifecycle
import tech.shaffersoftworks.remotescreens.server.player.session.SessionMedia
import tech.shaffersoftworks.remotescreens.server.player.session.ShownDocument
import tech.shaffersoftworks.remotescreens.server.render.Alert
import tech.shaffersoftworks.remotescreens.server.render.AlertButton
import tech.shaffersoftworks.remotescreens.server.render.Device
import tech.shaffersoftworks.remotescreens.server.render.Palette
import tech.shaffersoftworks.remotescreens.server.render.Rc
import tech.shaffersoftworks.remotescreens.server.render.Themes
import tech.shaffersoftworks.remotescreens.server.render.Vars
import tech.shaffersoftworks.remotescreens.shared.ActionRef
import tech.shaffersoftworks.remotescreens.shared.PlayerMessage
import tech.shaffersoftworks.remotescreens.shared.Protocol
import tech.shaffersoftworks.remotescreens.shared.Screen
import tech.shaffersoftworks.remotescreens.shared.ServerMessage
import tech.shaffersoftworks.remotescreens.shared.Slot
import tech.shaffersoftworks.remotescreens.shared.VarValue
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * One connected player: the documents it shows in each [Slot], kept up to date by sending only the values that
 * changed, and its taps turned into actions.
 */
@Suppress("TooManyFunctions")
class PlayerSession(
    private val socket: WebSocketSession,
    val hello: PlayerMessage.Hello,
    private val context: PlayerSessionContext,
    /** A pairing code no other connected player shows. */
    private val newPairingCode: () -> String,
) {
    private val logger = LoggerFactory.getLogger(PlayerSession::class.java)
    private val homeAssistant = context.homeAssistant
    private val layoutRepository = context.layoutRepository
    private val sendMutex = Mutex()
    private val buildMutex = Mutex()
    private val device = Device(hello.widthDp, hello.heightDp, hello.density)
    private val supportsDeflate = Rc.DEFLATE in hello.capabilities
    private val supportsVideo = Protocol.CAPABILITY_VIDEO in hello.capabilities
    private val supportsMap = Protocol.CAPABILITY_MAP in hello.capabilities
    private val tapMemory = TapMemory()

    var pairingCode: String? = null
        private set
    var screenId: String? = null
        private set
    var isAwake = true
        private set
    var page = 0
        private set
    var battery: Int? = null
        private set
    var charging: Boolean? = null
        private set
    /** Wi‑Fi signal level 0–4, or null when unknown. */
    var wifi: Int? = null
        private set
    var kiosk: String? = null
        private set

    private var themeId = Themes.DEFAULT
    private var palette: Palette = Themes.RPI
    private var previewScreen: Screen? = null
    private var previewJob: Job? = null

    private val shownDocuments = ConcurrentHashMap<Slot, ShownDocument>()
    private var lastRevision = 0
    private val jobs = mutableListOf<Job>()
    private var currentAlert: Alert? = null

    private val host = Host()
    private val media = SessionMedia(host)
    private val documents = SessionDocument(host, media)
    private val lifecycle = SessionLifecycle(host, documents)

    private fun screen(): Screen? =
        previewScreen ?: layoutRepository.layout.value.screens.firstOrNull { it.id == screenId }

    suspend fun send(message: ServerMessage) {
        val text = Protocol.encode(message)
        sendMutex.withLock { runCatching { socket.send(Frame.Text(text)) } }
    }

    suspend fun close() {
        runCatching { socket.close() }
    }

    fun stop() = jobs.forEach { it.cancel() }

    suspend fun welcome() = lifecycle.welcome()

    suspend fun adopted(token: String?) = lifecycle.adopted(token)

    private fun connectedVar(): VarValue =
        VarValue.int(Vars.CONNECTED, if (homeAssistant.isConnected.value) 1 else 0)

    /** Tablet battery and Wi‑Fi for the top bar; updated whenever the player sends [PlayerMessage.DeviceState]. */
    private fun deviceVars(): List<VarValue> {
        val i18n = context.languageRepository.current()
        val isCharging = charging == true
        return listOf(
            VarValue.string(Vars.BATTERY, battery?.let { i18n.percent(it) } ?: ""),
            VarValue.int(Vars.BATTERY_CHARGING, if (isCharging) Vars.VISIBLE else Vars.GONE),
            VarValue.int(Vars.BATTERY_IDLE, if (isCharging) Vars.GONE else Vars.VISIBLE),
            VarValue.string(Vars.WIFI, wifi?.toString() ?: ""),
        )
    }

    private fun foresee(ref: ActionRef, entity: EntityState): EntityState? =
        context.actionRouter.foresee(ref, context.actionRouter.target(ref, screen()), entity)

    /** Sends what changed in [states]; a document whose structure depends on a changed entity is built again. */
    suspend fun refresh(states: Map<String, EntityState>) = documents.refresh(states)

    suspend fun sendVars(vars: List<VarValue>) {
        if (screenId != null && vars.isNotEmpty()) send(ServerMessage.Vars(vars))
    }

    suspend fun sendConnected() = sendVars(listOf(connectedVar()))

    /** Once a second: rebuilds documents that refresh on a timer, and sends pictures that are due. */
    suspend fun tick(states: Map<String, EntityState>) {
        if (!isAwake) return
        for (shown in shownDocuments.values.toList()) {
            val docContext = shown.renderedDoc.docContext
            if (docContext.refreshSeconds > 0 && shown.renderedDoc.stale(states)) {
                documents.rebuild(shown)
            } else if (docContext.pictures.any { it.refreshSeconds > 0 }) {
                media.sendPictures(shown, states, isForced = false)
            }
        }
    }

    /** Rebuilds every document when night switches the screen to or from its dark theme. */
    suspend fun checkNight() = lifecycle.checkNight()

    suspend fun layoutChanged(screen: Screen?) = lifecycle.layoutChanged(screen)

    suspend fun showPreview(screen: Screen) = lifecycle.showPreview(screen)

    suspend fun receive(message: PlayerMessage) {
        when (message) {
            is PlayerMessage.Ping -> send(ServerMessage.Pong)
            is PlayerMessage.PageShown -> page = message.index
            is PlayerMessage.DeviceState -> {
                isAwake = message.awake
                page = message.page
                battery = message.battery
                charging = message.charging
                wifi = message.wifi
                kiosk = message.kiosk
                sendVars(deviceVars())
            }
            is PlayerMessage.SettingsChanged ->
                screenId?.let { id -> context.screenService.updateSettings(id) { message.settings } }
            is PlayerMessage.Action -> handleAction(message)
            is PlayerMessage.VideoFailed -> media.restartVideo(message)
            is PlayerMessage.Hello -> Unit
        }
    }

    private suspend fun handleAction(message: PlayerMessage.Action) {
        val ref = ActionRef.decode(message.metadata) ?: return
        val shown = shownDocuments[message.slot]
        if (shown == null || shown.revision != message.rev) {
            logger.debug("Tap on an old document ({} rev {}), ignored", message.slot, message.rev)
            return
        }
        when {
            ref.tile == "alert" -> handleAlertAction(ref)
            ref.local -> if (ref.verb == "close" && message.slot != Slot.MAIN) {
                shownDocuments.remove(message.slot)
                if (message.slot == Slot.ALERT) currentAlert = null
            }
            else -> handleOutcome(context.actionRouter.handle(ref, screen(), tapMemory))
        }
    }

    private suspend fun handleOutcome(outcome: TapOutcome) {
        when (outcome) {
            is TapOutcome.Card -> documents.openCard(outcome.entityId, outcome.kind)
            is TapOutcome.Confirm -> {
                send(ServerMessage.Vars(listOf(VarValue.string(outcome.varName, outcome.text))))
                jobs += context.scope.launch {
                    delay(ActionRouter.CONFIRM_MS)
                    send(ServerMessage.Vars(listOf(VarValue.string(outcome.varName, ""))))
                }
            }
            is TapOutcome.Code -> send(ServerMessage.Vars(listOf(VarValue.string(outcome.varName, outcome.shown))))
            is TapOutcome.Failed -> {
                send(ServerMessage.Toast(outcome.message))
                documents.resync()
            }
            TapOutcome.Done -> jobs += context.scope.launch {
                delay(RESYNC_MS)
                documents.resync()
            }
            TapOutcome.Ignored -> Unit
        }
        jobs.removeAll { !it.isActive }
    }

    suspend fun showAlert(alert: Alert) = documents.showAlert(alert)

    /** Closes the alert; for one of its buttons, also calls the button's service and tells Home Assistant. */
    private suspend fun handleAlertAction(ref: ActionRef) {
        val alert = currentAlert ?: return
        currentAlert = null
        shownDocuments.remove(Slot.ALERT)
        send(ServerMessage.Close(Slot.ALERT))
        if (ref.verb == "button") {
            alert.buttons.getOrNull(ref.arg.toIntOrNull() ?: -1)?.let { button -> pressAlertButton(alert, button) }
        }
    }

    private suspend fun pressAlertButton(alert: Alert, button: AlertButton) {
        button.service?.split('.', limit = 2)?.takeIf { it.size == 2 }?.let { (domain, service) ->
            runCatching { homeAssistant.callService(domain, service, button.data ?: JsonObject(emptyMap())) }
                .onFailure { send(ServerMessage.Toast(it.message ?: "Failed")) }
        }
        runCatching {
            val event = buildJsonObject {
                put("event_type", "remote_screens_alert_action")
                put(
                    "event_data",
                    buildJsonObject {
                        put("action", button.action ?: button.title)
                        alert.tag?.let { put("tag", it) }
                        put("screen", screenId)
                        put("device", hello.deviceId)
                    },
                )
            }
            homeAssistant.command("fire_event", event)
        }
    }

    suspend fun openPage(pageId: String) {
        val screen = screen() ?: return
        val pageIndex = screen.pages
            .indexOfFirst { it.id == pageId || it.title.equals(pageId, ignoreCase = true) }
            .takeIf { it >= 0 }
            ?: pageId.toIntOrNull()?.minus(1)
            ?: return
        send(ServerMessage.Wake(true))
        send(ServerMessage.OpenPage(pageIndex))
    }

    suspend fun closeAll() = lifecycle.closeAll()

    /** Bridges private session state to collaborators without exposing [SessionHost] on the public type. */
    private inner class Host : SessionHost {
        override val context get() = this@PlayerSession.context
        override val hello get() = this@PlayerSession.hello
        override val device get() = this@PlayerSession.device
        override val supportsDeflate get() = this@PlayerSession.supportsDeflate
        override val supportsVideo get() = this@PlayerSession.supportsVideo
        override val supportsMap get() = this@PlayerSession.supportsMap
        override val buildMutex get() = this@PlayerSession.buildMutex
        override val shownDocuments get() = this@PlayerSession.shownDocuments
        override val jobs get() = this@PlayerSession.jobs
        override val newPairingCode get() = this@PlayerSession.newPairingCode
        override val features get() = FEATURES

        override var lastRevision
            get() = this@PlayerSession.lastRevision
            set(value) { this@PlayerSession.lastRevision = value }
        override var themeId
            get() = this@PlayerSession.themeId
            set(value) { this@PlayerSession.themeId = value }
        override var palette
            get() = this@PlayerSession.palette
            set(value) { this@PlayerSession.palette = value }
        override var pairingCode
            get() = this@PlayerSession.pairingCode
            set(value) { this@PlayerSession.pairingCode = value }
        override var screenId
            get() = this@PlayerSession.screenId
            set(value) { this@PlayerSession.screenId = value }
        override var previewScreen
            get() = this@PlayerSession.previewScreen
            set(value) { this@PlayerSession.previewScreen = value }
        override var previewJob
            get() = this@PlayerSession.previewJob
            set(value) { this@PlayerSession.previewJob = value }
        override var currentAlert
            get() = this@PlayerSession.currentAlert
            set(value) { this@PlayerSession.currentAlert = value }

        override fun screen(): Screen? = this@PlayerSession.screen()
        override suspend fun send(message: ServerMessage) = this@PlayerSession.send(message)
        override fun connectedVar(): VarValue = this@PlayerSession.connectedVar()
        override fun deviceVars(): List<VarValue> = this@PlayerSession.deviceVars()
        override fun foresee(ref: ActionRef, entity: EntityState): EntityState? =
            this@PlayerSession.foresee(ref, entity)
    }

    companion object {
        val FEATURES = listOf(
            "predictions",
            "bitmaps",
            "alerts",
            "cards",
            "settings",
            Protocol.CAPABILITY_VIDEO,
            Protocol.CAPABILITY_MAP,
        )
        const val RESYNC_MS = 3000L
    }
}

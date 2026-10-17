package tech.shaffersoftworks.remotescreens.player.ui.screens.player

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import tech.shaffersoftworks.remotescreens.player.R
import tech.shaffersoftworks.remotescreens.player.data.connection.ConnectionRepository
import tech.shaffersoftworks.remotescreens.player.data.connection.ConnectionStatus
import tech.shaffersoftworks.remotescreens.player.data.device.DeviceRepository
import tech.shaffersoftworks.remotescreens.player.data.device.DisplayInfo
import tech.shaffersoftworks.remotescreens.player.data.device.FaceWakeMonitor
import tech.shaffersoftworks.remotescreens.player.data.device.ProximityWakeMonitor
import tech.shaffersoftworks.remotescreens.player.data.device.ScreenPowerRepository
import tech.shaffersoftworks.remotescreens.player.data.discovery.DiscoveryRepository
import tech.shaffersoftworks.remotescreens.player.data.image.ImageDecoder
import tech.shaffersoftworks.remotescreens.player.data.kiosk.KioskManager
import tech.shaffersoftworks.remotescreens.player.data.kiosk.KioskState
import tech.shaffersoftworks.remotescreens.player.data.preferences.PlayerPreferencesRepository
import tech.shaffersoftworks.remotescreens.player.data.update.UpdateRepository
import tech.shaffersoftworks.remotescreens.player.ui.screens.player.remote.RemoteScreenState
import tech.shaffersoftworks.remotescreens.player.ui.screens.player.remote.SlotMap
import tech.shaffersoftworks.remotescreens.player.ui.screens.player.remote.SlotVideo
import tech.shaffersoftworks.remotescreens.player.utils.decodeBase64OrNull
import tech.shaffersoftworks.remotescreens.shared.isNightAt
import tech.shaffersoftworks.remotescreens.player.utils.normalizeServerAddress
import tech.shaffersoftworks.remotescreens.shared.ActionRef
import tech.shaffersoftworks.remotescreens.shared.PlayerMessage
import tech.shaffersoftworks.remotescreens.shared.Protocol
import tech.shaffersoftworks.remotescreens.shared.ScreenSettings
import tech.shaffersoftworks.remotescreens.shared.ServerMessage
import tech.shaffersoftworks.remotescreens.shared.Slot
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalTime
import javax.inject.Inject

private const val MILLISECONDS_PER_SECOND = 1_000L
private val CLOSE_METADATA = ActionRef("_", "close").encode()
private const val MILLISECONDS_PER_MINUTE = 60_000L
private const val NIGHT_CHECK_INTERVAL_MS = 60_000L
private const val MIN_IDENTIFY_SECONDS = 1
private const val MAX_IDENTIFY_SECONDS = 60
private const val PAIRING_CODE_GROUP_SIZE = 3
/** After this many failed reconnects, look for the add-on over mDNS again (stale saved IP). */
private const val REDISCOVER_AFTER_FAILURES = 5
private val PLAYER_CAPABILITIES =
    listOf(
        "predictions",
        "bitmaps",
        "alerts",
        "cards",
        "settings",
        "deflate",
        Protocol.CAPABILITY_VIDEO,
        Protocol.CAPABILITY_MAP,
    )

/**
 * Runs a wall screen: finds and talks to the add-on, keeps the documents it sends, and does what a wall screen does
 * by itself (dim after a while without a touch, follow a night schedule, go back to the home page).
 */
@Suppress("TooManyFunctions", "LargeClass")
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val preferencesRepository: PlayerPreferencesRepository,
    private val connectionRepository: ConnectionRepository,
    private val discoveryRepository: DiscoveryRepository,
    private val updateRepository: UpdateRepository,
    private val deviceRepository: DeviceRepository,
    private val screenPowerRepository: ScreenPowerRepository,
    private val proximityWakeMonitor: ProximityWakeMonitor,
    private val faceWakeMonitor: FaceWakeMonitor,
    private val kioskManager: KioskManager,
    private val imageDecoder: ImageDecoder,
) : ViewModel() {

    data class ViewState(
        val infoPanel: InfoPanelState? = null,
        val remoteScreen: RemoteScreenState = RemoteScreenState(),
        val screenSettings: ScreenSettings = ScreenSettings(),
        val isSwipeEnabled: Boolean = true,
        val isAwake: Boolean = true,
        val isNight: Boolean = false,
        /** The brightness being tried in the settings dialog, until it closes. */
        val previewBrightnessPercent: Int? = null,
        /** The name shown big over everything while the add-on asks the screen to identify itself. */
        val identifyScreenName: String? = null,
        val availableUpdate: ServerMessage.UpdateAvailable? = null,
        val settingsDialog: SettingsDialogState? = null,
        val addressDialog: AddressDialogState? = null,
        /** The screen's Kiosk lock setting as last saved; null until it is read, so a restart does not unlock. */
        val isKioskEnabled: Boolean? = null,
        /** Unlocked on the tablet with the PIN, until "Lock again" or Home Assistant turns the kiosk on again. */
        val isLocallyUnlocked: Boolean = false,
        val isKioskPinDialogVisible: Boolean = false,
        /** Battery optimization may cut the connection while the screen is off; checked again on every resume. */
        val isBatteryRestricted: Boolean = false,
    ) {
        private val scheduledBrightnessPercent: Int
            get() = when {
                !isAwake -> screenSettings.standbyBrightness
                isNight -> screenSettings.nightBrightness
                else -> screenSettings.brightness
            }

        val brightnessPercent: Int get() = previewBrightnessPercent ?: scheduledBrightnessPercent

        /** Standby at brightness 0 covers the screen in black, as the backlight cannot go fully dark. */
        val isBlackout: Boolean get() = !isAwake && scheduledBrightnessPercent == 0

        val isAnyDialogVisible: Boolean
            get() = settingsDialog != null ||
                addressDialog != null ||
                availableUpdate != null ||
                isKioskPinDialogVisible

        /** Whether the player should be locked in front; null while the saved setting is still being read. */
        val isKioskWanted: Boolean?
            get() = isKioskEnabled?.let { isEnabled -> isEnabled && !isLocallyUnlocked }
    }

    private val _viewState = MutableStateFlow(ViewState())
    val viewState: StateFlow<ViewState> = _viewState.asStateFlow()

    private val _events = Channel<PlayerEvent>(Channel.BUFFERED)
    val events: Flow<PlayerEvent> = _events.receiveAsFlow()

    private var displayInfo: DisplayInfo? = null
    private var isPaired = false
    private var discoveryJob: Job? = null
    private var standbyJob: Job? = null
    private var backToHomeJob: Job? = null
    private var alertTimeoutJob: Job? = null
    private var identifyJob: Job? = null

    init {
        observeConnectionStatus()
        observeConnectionFailures()
        observeServerMessages()
        observeNightSchedule()
        observeKiosk()
        onUserInteraction()
    }

    override fun onCleared() {
        proximityWakeMonitor.stop()
        faceWakeMonitor.stop()
        connectionRepository.disconnect()
    }

    // region Display and connection

    /** The window was measured. The first time starts the connection; a new size asks for new documents. */
    fun onDisplayMeasured(measuredDisplay: DisplayInfo) {
        if (measuredDisplay.widthDp <= 0 || measuredDisplay.heightDp <= 0) return
        val previousDisplay = displayInfo
        displayInfo = measuredDisplay
        when {
            previousDisplay == null -> start()
            !previousDisplay.hasSameSizeAs(measuredDisplay) -> connectionRepository.reconnect()
        }
    }

    private fun start() {
        viewModelScope.launch {
            savedStateHandle.get<String>(EXTRA_SERVER)?.let { server -> preferencesRepository.setServer(server) }
            val server = preferencesRepository.server.first()
            if (server != null) connect(server) else searchForServer()
        }
    }

    private fun searchForServer() {
        _viewState.update { it.copy(infoPanel = InfoPanelState.Searching) }
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch {
            val server = discoveryRepository.discoverServers().first()
            preferencesRepository.setServer(server)
            connect(server)
        }
    }

    /**
     * Kept reconnecting to a saved address that never answers — often a changed HA IP. Ask mDNS again without
     * dropping the saved server until a different one is found.
     */
    private fun observeConnectionFailures() {
        viewModelScope.launch {
            connectionRepository.consecutiveFailures.collect { failures ->
                if (failures > 0 && failures % REDISCOVER_AFTER_FAILURES == 0) tryRediscover()
            }
        }
    }

    private fun tryRediscover() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = viewModelScope.launch {
            Timber.i("Connection keeps failing; looking for the add-on again")
            val discoveredServer = discoveryRepository.discoverServers().first()
            val savedServer = preferencesRepository.server.first()
            if (discoveredServer != savedServer) {
                preferencesRepository.setServer(discoveredServer)
                connect(discoveredServer)
            }
        }
    }

    private fun connect(server: String) {
        discoveryJob?.cancel()
        connectionRepository.connect(server, ::buildHello)
    }

    private suspend fun buildHello(): PlayerMessage.Hello {
        val display = displayInfo ?: DisplayInfo()
        return PlayerMessage.Hello(
            deviceId = preferencesRepository.deviceId(),
            deviceName = deviceRepository.deviceName,
            token = preferencesRepository.token.first(),
            widthDp = display.widthDp,
            heightDp = display.heightDp,
            density = display.density,
            appVersion = deviceRepository.appVersionCode,
            capabilities = PLAYER_CAPABILITIES,
            darkSystem = display.isSystemDark,
        )
    }

    private fun observeConnectionStatus() {
        viewModelScope.launch {
            connectionRepository.status.collect { status ->
                val hasMainDocument = _viewState.value.remoteScreen.hasMainDocument
                when (status) {
                    ConnectionStatus.CONNECTING -> if (!hasMainDocument && !isPaired) {
                        val server = connectionRepository.server.value.orEmpty()
                        _viewState.update { it.copy(infoPanel = InfoPanelState.Connecting(server)) }
                    }
                    ConnectionStatus.DISCONNECTED -> if (!hasMainDocument) {
                        _viewState.update { it.copy(infoPanel = InfoPanelState.Disconnected) }
                    }
                    ConnectionStatus.IDLE, ConnectionStatus.CONNECTED -> Unit
                }
            }
        }
    }

    private fun send(message: PlayerMessage) {
        viewModelScope.launch { connectionRepository.send(message) }
    }

    private fun sendDeviceState() {
        val currentState = _viewState.value
        val batteryStatus = deviceRepository.batteryStatus()
        send(
            PlayerMessage.DeviceState(
                awake = currentState.isAwake,
                page = currentState.remoteScreen.page,
                battery = batteryStatus.levelPercent,
                charging = batteryStatus.isCharging,
                wifi = deviceRepository.wifiLevel(),
                kiosk = kioskManager.lockTaskState().name.lowercase(),
            ),
        )
    }

    // endregion

    // region Messages from the add-on

    private fun observeServerMessages() {
        viewModelScope.launch {
            connectionRepository.messages.collect { message -> handleServerMessage(message) }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun handleServerMessage(message: ServerMessage) {
        Timber.d("Message %s", message::class.simpleName)
        when (message) {
            is ServerMessage.Welcome -> onWelcome(message)
            is ServerMessage.Document -> onDocument(message)
            is ServerMessage.Vars -> updateRemoteScreen { it.withVars(message) }
            is ServerMessage.Bitmap -> onBitmap(message)
            is ServerMessage.Settings -> onServerSettings(message.settings)
            is ServerMessage.OpenPage -> {
                closeTopOverlay()
                showPage(message.index)
                onUserInteraction()
            }
            is ServerMessage.Close -> closeSlot(message.slot)
            is ServerMessage.Identify -> identify(message.seconds)
            is ServerMessage.Wake -> wake(message.awake)
            is ServerMessage.UpdateAvailable -> onUpdateAvailable(message)
            is ServerMessage.Toast -> _events.send(PlayerEvent.ShowMessage(message.text))
            is ServerMessage.Video -> onVideo(message)
            is ServerMessage.LiveMap -> onMap(message)
            ServerMessage.Pong -> Unit
        }
    }

    private suspend fun onWelcome(welcome: ServerMessage.Welcome) {
        closeSlot(Slot.OVERLAY)
        closeSlot(Slot.ALERT)
        welcome.token?.let { token -> preferencesRepository.setPairing(token, welcome.screenName) }
        val pairingCode = welcome.pairingCode
        isPaired = pairingCode == null
        if (pairingCode != null) {
            if (welcome.token == null && preferencesRepository.token.first() != null) preferencesRepository.forget()
            val groupedCode = pairingCode.chunked(PAIRING_CODE_GROUP_SIZE).joinToString(" ")
            _viewState.update {
                it.copy(
                    remoteScreen = it.remoteScreen.cleared().withTimeZone(welcome.timeZone),
                    infoPanel = InfoPanelState.Pairing(groupedCode),
                )
            }
        } else {
            _viewState.update {
                it.copy(
                    remoteScreen = it.remoteScreen.withTimeZone(welcome.timeZone),
                    infoPanel = if (it.remoteScreen.hasMainDocument) null else it.infoPanel,
                )
            }
        }
        sendDeviceState()
    }

    private fun onDocument(document: ServerMessage.Document) {
        val bytes = decodeBase64OrNull(document.data)
        if (bytes == null) {
            Timber.w("Bad document data for %s", document.slot)
            return
        }
        updateRemoteScreen { it.withDocument(document, bytes) }
        when (document.slot) {
            Slot.MAIN -> {
                _viewState.update { it.copy(infoPanel = null) }
                showPage(_viewState.value.remoteScreen.pageAfterNewDocument)
            }
            Slot.ALERT -> {
                scheduleAlertTimeout(document.timeout)
                onUserInteraction()
            }
            Slot.OVERLAY -> onUserInteraction()
        }
    }

    private fun onBitmap(message: ServerMessage.Bitmap) {
        viewModelScope.launch {
            val bitmap = imageDecoder.decodeBitmap(message.data) ?: return@launch
            updateRemoteScreen { it.withBitmap(message.name, bitmap) }
        }
    }

    private suspend fun onVideo(message: ServerMessage.Video) {
        val server = connectionRepository.server.value ?: return
        val token = preferencesRepository.token.first() ?: return
        val video = SlotVideo(message.rev, message.componentId, "http://$server${message.path}", token, message.crop)
        updateRemoteScreen { it.withVideo(message.slot, video) }
    }

    private fun onMap(message: ServerMessage.LiveMap) {
        val map = SlotMap(
            revision = message.rev,
            componentId = message.componentId,
            tileUrl = message.tileUrl,
            dark = message.dark,
            markers = message.markers,
        )
        updateRemoteScreen { it.withMap(message.slot, map) }
    }

    /** The add-on starts the camera's stream again, as Home Assistant ends one nobody watched for a while. */
    fun onVideoFailed(slot: Slot, video: SlotVideo, httpCode: Int?) {
        updateRemoteScreen { it.withoutVideo(slot, video) }
        send(PlayerMessage.VideoFailed(slot, video.revision, video.componentId, httpCode))
    }

    private suspend fun onUpdateAvailable(update: ServerMessage.UpdateAvailable) {
        if (connectionRepository.server.value == null || preferencesRepository.token.first() == null) return
        // Enrolled kiosks install on their own; other tablets still get the confirm dialog.
        if (kioskManager.isDeviceOwner()) {
            viewModelScope.launch { installUpdate(update, silent = true) }
            return
        }
        _viewState.update { it.copy(availableUpdate = update) }
    }

    private fun scheduleAlertTimeout(timeoutSeconds: Int) {
        alertTimeoutJob?.cancel()
        if (timeoutSeconds <= 0) return
        alertTimeoutJob = viewModelScope.launch {
            delay(timeoutSeconds * MILLISECONDS_PER_SECOND)
            closeSlot(Slot.ALERT)
        }
    }

    private fun identify(seconds: Int) {
        identifyJob?.cancel()
        identifyJob = viewModelScope.launch {
            val screenName = preferencesRepository.screenName.first() ?: deviceRepository.model
            _viewState.update { it.copy(identifyScreenName = screenName) }
            wake(true)
            delay(seconds.coerceIn(MIN_IDENTIFY_SECONDS, MAX_IDENTIFY_SECONDS) * MILLISECONDS_PER_SECOND)
            _viewState.update { it.copy(identifyScreenName = null) }
        }
    }

    // endregion

    // region Documents and pages

    /** A tap in a document. Local verbs are handled here; the rest go to the add-on, shown at once if predicted. */
    fun onDocumentAction(slot: Slot, revision: Int, metadata: String) {
        onUserInteraction()
        val actionRef = ActionRef.decode(metadata) ?: return
        if (actionRef.local) {
            handleLocalAction(slot, actionRef)
            return
        }
        _viewState.value.remoteScreen.withPrediction(slot, metadata)?.let { predicted ->
            _viewState.update { it.copy(remoteScreen = predicted) }
        }
        send(PlayerMessage.Action(slot, revision, metadata))
    }

    private fun handleLocalAction(slot: Slot, actionRef: ActionRef) {
        val remoteScreen = _viewState.value.remoteScreen
        when (actionRef.verb) {
            "page" -> actionRef.arg.toIntOrNull()?.let(::showPage)
            "next" -> remoteScreen.nextSwipePage?.let(::showPage)
            "prev" -> remoteScreen.previousSwipePage?.let(::showPage)
            "back" -> showPage(actionRef.arg.toIntOrNull() ?: remoteScreen.homePage)
            "close" -> closeSlot(slot)
            "settings" -> openSettings()
            "set" -> {
                val parts = actionRef.arg.split('|', limit = 2)
                if (parts.size == 2) {
                    parts[1].toIntOrNull()?.let { value ->
                        updateRemoteScreen { it.withLocalInt(parts[0], value) }
                    }
                }
            }
            "url" -> actionRef.arg.takeIf { it.isNotBlank() }?.let { url ->
                viewModelScope.launch { _events.send(PlayerEvent.OpenUrl(url)) }
            }
        }
    }

    fun onSwipe(isNext: Boolean) {
        val remoteScreen = _viewState.value.remoteScreen
        if (remoteScreen.isOverlayShown) return
        val targetPage = if (isNext) remoteScreen.nextSwipePage else remoteScreen.previousSwipePage
        targetPage?.let(::showPage)
    }

    /** Back closes a card first, then goes home. */
    fun onBack() {
        if (closeTopOverlay()) return
        val remoteScreen = _viewState.value.remoteScreen
        if (remoteScreen.page != remoteScreen.homePage) showPage(remoteScreen.homePage)
    }

    private fun showPage(index: Int) {
        updateRemoteScreen { it.withPage(index) }
        send(PlayerMessage.PageShown(_viewState.value.remoteScreen.page))
        sendDeviceState()
    }

    /** Closes [slot], and tells the add-on so it stops rebuilding the document and sending it again. */
    private fun closeSlot(slot: Slot) {
        if (slot == Slot.ALERT) alertTimeoutJob?.cancel()
        if (slot == Slot.MAIN) return
        val document = _viewState.value.remoteScreen.documents[slot] ?: return
        updateRemoteScreen { it.closed(slot) }
        send(PlayerMessage.Action(slot, document.revision, CLOSE_METADATA))
    }

    private fun closeTopOverlay(): Boolean {
        val topOverlay = _viewState.value.remoteScreen.topOverlay ?: return false
        closeSlot(topOverlay)
        return true
    }

    private inline fun updateRemoteScreen(transform: (RemoteScreenState) -> RemoteScreenState) {
        _viewState.update { it.copy(remoteScreen = transform(it.remoteScreen)) }
    }

    // endregion

    // region Standby, night and back to home

    /** Any touch: wake up, and start the standby and back-to-home clocks again. */
    fun onUserInteraction() {
        if (!_viewState.value.isAwake) setAwake(true)
        restartClocks()
    }

    private fun restartClocks() {
        standbyJob?.cancel()
        backToHomeJob?.cancel()
        val settings = _viewState.value.screenSettings
        Timber.d("Standby clock restarted: %d min", settings.standbyMinutes)
        if (settings.standbyMinutes > 0) {
            standbyJob = viewModelScope.launch {
                delay(settings.standbyMinutes * MILLISECONDS_PER_MINUTE)
                setAwake(false)
            }
        }
        if (settings.backToHomeSeconds > 0) {
            backToHomeJob = viewModelScope.launch {
                delay(settings.backToHomeSeconds * MILLISECONDS_PER_SECOND)
                closeTopOverlay()
                showPage(_viewState.value.remoteScreen.homePage)
            }
        }
    }

    private fun wake(isAwake: Boolean) {
        if (isAwake) onUserInteraction() else setAwake(false)
    }

    private fun setAwake(isAwake: Boolean) {
        if (_viewState.value.isAwake == isAwake) return
        Timber.d("Awake: %b", isAwake)
        _viewState.update { it.copy(isAwake = isAwake) }
        updateScreenPower()
        updateWakeMonitors()
        sendDeviceState()
    }

    private fun updateScreenPower() {
        val state = _viewState.value
        // Face wake needs the panel on (blackout); lockNow often stops the camera.
        if (!state.isAwake && state.screenSettings.standbyScreenOff && !isFaceWakeActive()) {
            screenPowerRepository.turnScreenOff()
        } else {
            screenPowerRepository.turnScreenOn()
        }
    }

    /** Each opt-in source runs on its own; both may listen while asleep. */
    private fun updateWakeMonitors() {
        proximityWakeMonitor.stop()
        faceWakeMonitor.stop()
        val state = _viewState.value
        if (state.isAwake) return
        val settings = state.screenSettings
        if (settings.wakeOnProximity && proximityWakeMonitor.isUsable()) {
            proximityWakeMonitor.start { onUserInteraction() }
        }
        if (settings.wakeOnFace && faceWakeMonitor.isUsable()) {
            faceWakeMonitor.start { onUserInteraction() }
        }
    }

    /** Face path would run: setting on and a usable front camera (independent of proximity). */
    private fun isFaceWakeActive(): Boolean {
        val settings = _viewState.value.screenSettings
        return settings.wakeOnFace && faceWakeMonitor.isUsable()
    }

    fun onCameraPermissionResult(granted: Boolean) {
        if (!granted) {
            _events.trySend(PlayerEvent.ShowMessageResource(R.string.camera_permission_denied))
        }
        updateScreenPower()
        updateWakeMonitors()
    }

    /** The window is shown again. After standby turned the screen off, that means the power button was pressed. */
    fun onResumed() {
        _viewState.update { it.copy(isBatteryRestricted = !deviceRepository.isIgnoringBatteryOptimizations()) }
        if (screenPowerRepository.isScreenOff) onUserInteraction()
    }

    private fun applyScreenSettings(settings: ScreenSettings) {
        _viewState.update { it.copy(screenSettings = settings, isNight = settings.isNightAt(LocalTime.now())) }
        onUserInteraction()
    }

    /**
     * The add-on sends the settings again on every reconnect and layout save, so they are not a touch: they never wake
     * the screen, and the clocks only start again when their own times change.
     */
    private fun onServerSettings(settings: ScreenSettings) {
        val previousSettings = _viewState.value.screenSettings
        _viewState.update {
            it.copy(
                screenSettings = settings,
                isSwipeEnabled = settings.swipe,
                isNight = settings.isNightAt(LocalTime.now()),
            )
        }
        when {
            !_viewState.value.isAwake && settings.standbyMinutes == 0 -> onUserInteraction()
            settings.standbyMinutes != previousSettings.standbyMinutes ||
                settings.backToHomeSeconds != previousSettings.backToHomeSeconds -> restartClocks()
        }
        val wakeSourcesChanged =
            settings.standbyScreenOff != previousSettings.standbyScreenOff ||
                settings.wakeOnFace != previousSettings.wakeOnFace ||
                settings.wakeOnProximity != previousSettings.wakeOnProximity
        if (wakeSourcesChanged) {
            updateScreenPower()
            updateWakeMonitors()
        }
        if (settings.wakeOnFace && !previousSettings.wakeOnFace && !faceWakeMonitor.hasCameraPermission()) {
            viewModelScope.launch { _events.send(PlayerEvent.RequestCameraPermission) }
        }
        if (settings.kiosk != _viewState.value.isKioskEnabled) {
            viewModelScope.launch { preferencesRepository.setKioskEnabled(settings.kiosk) }
        }
    }

    private fun observeNightSchedule() {
        viewModelScope.launch {
            while (true) {
                _viewState.update { it.copy(isNight = it.screenSettings.isNightAt(LocalTime.now())) }
                delay(NIGHT_CHECK_INTERVAL_MS)
            }
        }
    }

    // endregion

    // region Kiosk

    private fun observeKiosk() {
        viewModelScope.launch {
            combine(
                preferencesRepository.isKioskEnabled,
                preferencesRepository.isKioskSuppressed,
            ) { isEnabled, isSuppressed -> isEnabled to isSuppressed }
                .collect { (isEnabled, isSuppressed) ->
                    _viewState.update {
                        it.copy(isKioskEnabled = isEnabled, isLocallyUnlocked = isEnabled && isSuppressed)
                    }
                }
        }
    }

    /** The activity locked or unlocked itself; the add-on shows how. */
    fun onKioskStateChanged() {
        sendDeviceState()
    }

    fun onKioskPinEntered(pin: String) {
        val kioskPin = _viewState.value.screenSettings.kioskPin
        _viewState.update { it.copy(isKioskPinDialogVisible = false) }
        if (kioskPin != null && pin == kioskPin) {
            openSettingsDialog(isPinVerified = true)
        } else {
            viewModelScope.launch { _events.send(PlayerEvent.ShowMessageResource(R.string.kiosk_pin_wrong)) }
        }
        onUserInteraction()
    }

    fun onKioskPinDismissed() {
        _viewState.update { it.copy(isKioskPinDialogVisible = false) }
        onUserInteraction()
    }

    fun onUnlockKioskRequested() {
        onSettingsDismissed()
        viewModelScope.launch { preferencesRepository.setKioskSuppressed(true) }
    }

    fun onLockKioskAgainRequested() {
        onSettingsDismissed()
        viewModelScope.launch { preferencesRepository.setKioskSuppressed(false) }
    }

    fun onRemoveDeviceOwnerRequested() {
        onSettingsDismissed()
        kioskManager.removeDeviceOwner()
    }

    // endregion

    // region Settings dialog

    /** While the kiosk is locked and has a PIN, the PIN comes first. */
    private fun openSettings() {
        val currentState = _viewState.value
        if (currentState.isKioskWanted == true && currentState.screenSettings.kioskPin != null) {
            _viewState.update { it.copy(isKioskPinDialogVisible = true) }
        } else {
            openSettingsDialog(isPinVerified = false)
        }
    }

    private fun openSettingsDialog(isPinVerified: Boolean) {
        _viewState.update { currentState ->
            val isKioskLocked = currentState.isKioskWanted == true
            val kioskAction = when {
                currentState.isKioskEnabled != true -> null
                currentState.isLocallyUnlocked -> KioskAction.LOCK_AGAIN
                isPinVerified -> KioskAction.UNLOCK
                else -> null
            }
            currentState.copy(
                settingsDialog = SettingsDialogState(
                    server = connectionRepository.server.value,
                    // Lock task blocks the system screens these buttons open.
                    canOfferSelfStart = !isKioskLocked && !deviceRepository.canSelfStart(),
                    canOfferScreenOff = !isKioskLocked &&
                        currentState.screenSettings.standbyScreenOff &&
                        !screenPowerRepository.canTurnScreenOff(),
                    canOfferUnrestrictedBattery = !isKioskLocked &&
                        currentState.screenSettings.standbyScreenOff &&
                        !deviceRepository.isIgnoringBatteryOptimizations(),
                    canOfferCameraPermission = !isKioskLocked &&
                        currentState.screenSettings.wakeOnFace &&
                        !faceWakeMonitor.hasCameraPermission(),
                    wakeOnFaceUnavailableNote = wakeOnFaceUnavailableNote(currentState.screenSettings.wakeOnFace),
                    kioskAction = kioskAction,
                    canRemoveDeviceOwner = !isKioskLocked && kioskManager.isDeviceOwner(),
                ),
            )
        }
    }

    fun onSettingsBrightnessPreview(brightnessPercent: Int) {
        _viewState.update { it.copy(previewBrightnessPercent = brightnessPercent) }
    }

    fun onSettingsConfirmed(brightnessPercent: Int, isDarkMode: Boolean, standbyMinutes: Int) {
        val currentSettings = _viewState.value.screenSettings
        val changedSettings = currentSettings.copy(
            brightness = brightnessPercent,
            darkMode = isDarkMode,
            standbyMinutes = standbyMinutes,
        )
        if (changedSettings != currentSettings) {
            applyScreenSettings(changedSettings)
            send(PlayerMessage.SettingsChanged(changedSettings))
        }
        onSettingsDismissed()
    }

    fun onSettingsDismissed() {
        _viewState.update { it.copy(settingsDialog = null, previewBrightnessPercent = null) }
        onUserInteraction()
    }

    fun onChangeServerRequested() {
        onSettingsDismissed()
        openAddressDialog()
    }

    fun onForgetRequested() {
        _viewState.update { state ->
            state.copy(settingsDialog = state.settingsDialog?.copy(isForgetConfirmationVisible = true))
        }
    }

    fun onForgetCancelled() {
        _viewState.update { state ->
            state.copy(settingsDialog = state.settingsDialog?.copy(isForgetConfirmationVisible = false))
        }
    }

    fun onForgetConfirmed() {
        onSettingsDismissed()
        viewModelScope.launch {
            preferencesRepository.forget()
            updateRemoteScreen { it.cleared() }
            connectionRepository.reconnect()
        }
    }

    fun onAllowScreenOffRequested() {
        onSettingsDismissed()
        viewModelScope.launch { _events.send(PlayerEvent.OpenScreenOffAdminSettings) }
    }

    fun onAllowUnrestrictedBatteryRequested() {
        onSettingsDismissed()
        viewModelScope.launch { _events.send(PlayerEvent.RequestUnrestrictedBattery) }
    }

    fun onAllowCameraPermissionRequested() {
        onSettingsDismissed()
        viewModelScope.launch { _events.send(PlayerEvent.RequestCameraPermission) }
    }

    @StringRes
    private fun wakeOnFaceUnavailableNote(wakeOnFace: Boolean): Int? = when {
        !wakeOnFace -> null
        faceWakeMonitor.isLowRamDevice() -> R.string.face_wake_low_ram
        !faceWakeMonitor.hasFrontCamera() -> R.string.face_wake_no_camera
        else -> null
    }

    fun onAllowSelfStartRequested() {
        onSettingsDismissed()
        viewModelScope.launch { _events.send(PlayerEvent.OpenOverlayPermissionSettings) }
    }

    // endregion

    // region Address dialog

    fun openAddressDialog() {
        viewModelScope.launch {
            val savedServer = preferencesRepository.server.first().orEmpty()
            _viewState.update { it.copy(addressDialog = AddressDialogState(initialAddress = savedServer)) }
        }
    }

    fun onAddressConfirmed(input: String) {
        onAddressDismissed()
        val server = normalizeServerAddress(input) ?: return
        viewModelScope.launch {
            preferencesRepository.setServer(server)
            updateRemoteScreen { it.cleared() }
            connect(server)
        }
    }

    fun onAddressDismissed() {
        _viewState.update { it.copy(addressDialog = null) }
    }

    // endregion

    // region Updates

    fun onInstallUpdateConfirmed() {
        val update = _viewState.value.availableUpdate ?: return
        onUpdateDismissed()
        viewModelScope.launch { installUpdate(update, silent = kioskManager.isDeviceOwner()) }
    }

    fun onUpdateDismissed() {
        _viewState.update { it.copy(availableUpdate = null) }
    }

    private suspend fun installUpdate(update: ServerMessage.UpdateAvailable, silent: Boolean) {
        val server = connectionRepository.server.value
        val token = preferencesRepository.token.first()
        if (server == null || token == null) return
        // Device owner may install packages without REQUEST_INSTALL_PACKAGES; others still need it.
        if (!silent && !deviceRepository.canInstallPackages()) {
            _events.send(PlayerEvent.ShowMessageResource(R.string.update_permission))
            _events.send(PlayerEvent.OpenInstallPermissionSettings)
            return
        }
        // Screen pinning would hide a confirm sheet on older Android; stop it before committing.
        if (kioskManager.lockTaskState() == KioskState.PINNED) _events.send(PlayerEvent.StopScreenPinning)
        val installed = updateRepository.install(server, update.path, token, silent = silent)
        if (!installed) {
            _events.send(PlayerEvent.ShowMessageResource(R.string.update_failed))
            _events.send(PlayerEvent.ReapplyKiosk)
        }
    }

    // endregion

    companion object {
        /** `host:port` of the add-on, for setting a screen up over adb: `am start -n … --es server 10.0.0.5:8100`. */
        const val EXTRA_SERVER = "server"
    }
}

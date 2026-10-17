package tech.shaffersoftworks.remotescreens.server.di

import tech.shaffersoftworks.remotescreens.server.config.ServerConfig
import tech.shaffersoftworks.remotescreens.server.data.apk.PlayerApkRepository
import tech.shaffersoftworks.remotescreens.server.data.homeassistant.HomeAssistant
import tech.shaffersoftworks.remotescreens.server.data.homeassistant.HomeAssistantClient
import tech.shaffersoftworks.remotescreens.server.data.homeassistant.demo.DemoHome
import tech.shaffersoftworks.remotescreens.server.data.homeassistant.demo.DemoLayout
import tech.shaffersoftworks.remotescreens.server.data.layout.LayoutRepository
import tech.shaffersoftworks.remotescreens.server.data.pictures.PictureRepository
import tech.shaffersoftworks.remotescreens.server.data.renderdata.RenderDataRepository
import tech.shaffersoftworks.remotescreens.server.data.security.ApiKeyRepository
import tech.shaffersoftworks.remotescreens.server.i18n.LanguageRepository
import tech.shaffersoftworks.remotescreens.server.network.siteLocalAddress
import tech.shaffersoftworks.remotescreens.server.player.PlayerHub
import tech.shaffersoftworks.remotescreens.server.player.PlayerSessionContext
import tech.shaffersoftworks.remotescreens.server.player.ScreenClock
import tech.shaffersoftworks.remotescreens.server.player.action.ActionRouter
import tech.shaffersoftworks.remotescreens.server.service.CameraStreamService
import tech.shaffersoftworks.remotescreens.server.service.EnrollmentService
import tech.shaffersoftworks.remotescreens.server.service.HomeAssistantCatalogService
import tech.shaffersoftworks.remotescreens.server.service.ScreenService
import tech.shaffersoftworks.remotescreens.server.service.ThemeService
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The composition root: every long-lived object of the server, built once and wired by hand. */
class ServerDependencies(
    val config: ServerConfig,
    httpClient: HttpClient,
    private val scope: CoroutineScope,
) {
    private val homeAssistantClient: HomeAssistantClient? = if (config.isDemo) {
        null
    } else {
        HomeAssistantClient(
            webSocketUrl = config.homeAssistantWebSocketUrl,
            restBaseUrl = config.homeAssistantRestUrl,
            accessToken = config.accessToken,
            scope = scope,
        )
    }
    val homeAssistant: HomeAssistant = homeAssistantClient ?: DemoHome()

    val layoutRepository = LayoutRepository(config.dataDir)
    val playerApkRepository = PlayerApkRepository(config.apkDir)
    val apiKeyRepository = ApiKeyRepository(config.dataDir)
    private val renderDataRepository = RenderDataRepository(homeAssistant)
    private val pictureRepository = PictureRepository(homeAssistant, httpClient, config.mapTileUrl)
    private val languageRepository = LanguageRepository(homeAssistant, layoutRepository)

    val screenService = ScreenService(layoutRepository)
    val themeService = ThemeService(layoutRepository)
    val homeAssistantCatalogService = HomeAssistantCatalogService(homeAssistant)
    val cameraStreamService = CameraStreamService(homeAssistant)
    val enrollmentService = EnrollmentService(
        playerApkRepository = playerApkRepository,
        playerPort = config.playerPort,
        defaultHost = { siteLocalAddress()?.hostAddress },
    )

    val playerHub = PlayerHub(
        PlayerSessionContext(
            serverVersion = config.version,
            homeAssistant = homeAssistant,
            layoutRepository = layoutRepository,
            screenService = screenService,
            renderDataRepository = renderDataRepository,
            pictureRepository = pictureRepository,
            cameraStreamService = cameraStreamService,
            languageRepository = languageRepository,
            playerApkRepository = playerApkRepository,
            actionRouter = ActionRouter(homeAssistant) { languageRepository.current() },
            screenClock = ScreenClock(homeAssistant, languageRepository),
            scope = scope,
            mapTileUrl = config.mapTileUrl,
        ),
    )

    fun start() {
        homeAssistantClient?.start()
        if (config.isDemo && layoutRepository.layout.value.screens.isEmpty()) {
            layoutRepository.update { it.copy(screens = listOf(DemoLayout.screen())) }
        }
        playerHub.start()
        scope.launch {
            homeAssistant.isConnected.collect { isConnected ->
                if (isConnected) languageRepository.loadHomeAssistantWords()
            }
        }
    }
}

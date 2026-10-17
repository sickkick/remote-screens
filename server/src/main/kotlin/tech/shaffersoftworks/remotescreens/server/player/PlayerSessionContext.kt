package tech.shaffersoftworks.remotescreens.server.player

import tech.shaffersoftworks.remotescreens.server.config.ServerConfig
import tech.shaffersoftworks.remotescreens.server.data.apk.PlayerApkRepository
import tech.shaffersoftworks.remotescreens.server.data.homeassistant.HomeAssistant
import tech.shaffersoftworks.remotescreens.server.data.layout.LayoutRepository
import tech.shaffersoftworks.remotescreens.server.data.pictures.PictureRepository
import tech.shaffersoftworks.remotescreens.server.data.renderdata.RenderDataRepository
import tech.shaffersoftworks.remotescreens.server.i18n.LanguageRepository
import tech.shaffersoftworks.remotescreens.server.player.action.ActionRouter
import tech.shaffersoftworks.remotescreens.server.service.CameraStreamService
import tech.shaffersoftworks.remotescreens.server.service.ScreenService
import kotlinx.coroutines.CoroutineScope

/** What every [PlayerSession] shares, handed down by the [PlayerHub]. */
class PlayerSessionContext(
    val serverVersion: String,
    val homeAssistant: HomeAssistant,
    val layoutRepository: LayoutRepository,
    val screenService: ScreenService,
    val renderDataRepository: RenderDataRepository,
    val pictureRepository: PictureRepository,
    val cameraStreamService: CameraStreamService,
    val languageRepository: LanguageRepository,
    val playerApkRepository: PlayerApkRepository,
    val actionRouter: ActionRouter,
    val screenClock: ScreenClock,
    val scope: CoroutineScope,
    /** OpenStreetMap-style tile URL with `{z}`, `{x}` and `{y}`, for interactive map cards. */
    val mapTileUrl: String = ServerConfig.OPENSTREETMAP_TILE_URL,
)

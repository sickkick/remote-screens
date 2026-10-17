package tech.shaffersoftworks.remotescreens.player.ui.screens.player.remote

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.utsman.osmandcompose.MapProperties
import com.utsman.osmandcompose.Marker
import com.utsman.osmandcompose.OpenStreetMap
import com.utsman.osmandcompose.ZoomButtonVisibility
import com.utsman.osmandcompose.rememberCameraState
import com.utsman.osmandcompose.rememberMarkerState
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import tech.shaffersoftworks.remotescreens.player.BuildConfig
import tech.shaffersoftworks.remotescreens.shared.MapMarker
import java.io.File
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

private const val CORNER_DP = 16f
private const val MARKER_SIZE_PX = 96
private const val SINGLE_MARKER_ZOOM = 18.0
private const val EMPTY_MAP_ZOOM = 3.0
private const val DEFAULT_LATITUDE = 52.37
private const val DEFAULT_LONGITUDE = 4.89
private const val MIN_TILE_ZOOM = 3
private const val MAX_TILE_ZOOM = 19
private const val TILE_SIZE = 256
private const val MULTI_MARKER_PADDING = 1.35
private const val MAX_FIT_ZOOM = 16.0
private const val MIN_SPAN_DEGREES = 0.01
private const val DARK_TILE_MULTIPLY = 0xFF888888.toInt()
private const val INITIALS_COUNT = 2
private const val PHOTO_INSET_PX = 2f
private const val MARKER_STROKE_DIVISOR = 16f
private const val MARKER_INITIALS_SIZE_SHARE = 0.4f
private val MARKER_COLOURS = intArrayOf(
    0xFF3D7FD6.toInt(),
    0xFFD65A3D.toInt(),
    0xFF3DA866.toInt(),
    0xFF9B5BD6.toInt(),
    0xFFD6A23D.toInt(),
)

/**
 * Interactive OpenStreetMap over a map card's component box. Pinch-zoom and pan stay on the device; taps on the
 * card chrome around this view still reach the Remote Compose document.
 */
@SuppressLint("ViewConstructor")
class LiveMapView(context: Context) : FrameLayout(context) {
    private val composeView = ComposeView(context)

    /** What is shown now; null when stopped. */
    var map: SlotMap? = null
        private set

    init {
        ensureOsmdroidConfigured(context)
        val cornerPx = CORNER_DP * resources.displayMetrics.density
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerPx)
            }
        }
        clipToOutline = true
        clipChildren = true
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        addView(composeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun show(map: SlotMap, pictures: Map<String, Bitmap>) {
        this.map = map
        composeView.setContent {
            InteractiveMap(
                tileUrl = map.tileUrl,
                dark = map.dark,
                markers = map.markers,
                pictures = pictures,
            )
        }
    }

    fun stop() {
        map = null
        composeView.setContent { }
    }

    companion object {
        @Volatile
        private var configured = false

        fun ensureOsmdroidConfigured(context: Context) {
            if (configured) return
            synchronized(this) {
                if (configured) return
                val appContext = context.applicationContext
                val base = File(appContext.cacheDir, "osmdroid")
                Configuration.getInstance().apply {
                    userAgentValue = "RemoteScreens/${BuildConfig.VERSION_NAME}"
                    osmdroidBasePath = base
                    osmdroidTileCache = File(base, "tiles")
                    load(appContext, appContext.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
                }
                configured = true
            }
        }
    }
}

@Composable
private fun InteractiveMap(
    tileUrl: String,
    dark: Boolean,
    markers: List<MapMarker>,
    pictures: Map<String, Bitmap>,
) {
    val (centre, zoom) = remember(markers) { viewOf(markers) }
    val cameraState = rememberCameraState {
        geoPoint = centre
        this.zoom = zoom
    }
    val tileSource = remember(tileUrl) { tileSourceFor(tileUrl) }
    val properties = remember(tileSource) {
        MapProperties(
            minZoomLevel = MIN_TILE_ZOOM.toDouble(),
            maxZoomLevel = MAX_TILE_ZOOM.toDouble(),
            tileSources = tileSource,
            zoomButtonVisibility = ZoomButtonVisibility.NEVER,
            isMultiTouchControls = true,
        )
    }
    val icons = remember(markers, pictures) {
        markers.mapIndexed { index, marker ->
            markerIcon(pictures[marker.picture], marker.label, index)
        }
    }

    val hostView = LocalView.current
    OpenStreetMap(
        modifier = Modifier.fillMaxSize(),
        cameraState = cameraState,
        properties = properties,
        onFirstLoadListener = {
            if (dark) hostView.post { applyDarkTileFilter(hostView) }
        },
    ) {
        markers.forEachIndexed { index, marker ->
            key(marker.label, marker.latitude, marker.longitude, index) {
                MapMarkerPin(
                    marker = marker,
                    icon = icons[index],
                    index = index,
                )
            }
        }
    }
    SideEffect {
        if (dark) hostView.post { applyDarkTileFilter(hostView) }
    }
}

@Composable
private fun MapMarkerPin(marker: MapMarker, icon: Drawable, index: Int) {
    val point = remember(marker.latitude, marker.longitude) {
        GeoPoint(marker.latitude, marker.longitude)
    }
    val markerState = rememberMarkerState(geoPoint = point)
    Marker(
        state = markerState,
        icon = icon,
        title = marker.label,
        id = "${marker.label}-$index",
    )
}

private fun viewOf(markers: List<MapMarker>): Pair<GeoPoint, Double> = when {
    markers.isEmpty() -> GeoPoint(DEFAULT_LATITUDE, DEFAULT_LONGITUDE) to EMPTY_MAP_ZOOM
    markers.size == 1 -> {
        val only = markers.first()
        GeoPoint(only.latitude, only.longitude) to SINGLE_MARKER_ZOOM
    }
    else -> {
        val latitudes = markers.map { it.latitude }
        val longitudes = markers.map { it.longitude }
        val centre = GeoPoint(
            (latitudes.min() + latitudes.max()) / 2.0,
            (longitudes.min() + longitudes.max()) / 2.0,
        )
        val latSpan = max((latitudes.max() - latitudes.min()) * MULTI_MARKER_PADDING, MIN_SPAN_DEGREES)
        val lonSpan = max((longitudes.max() - longitudes.min()) * MULTI_MARKER_PADDING, MIN_SPAN_DEGREES)
        val zoom = min(
            MAX_FIT_ZOOM,
            max(MIN_TILE_ZOOM.toDouble(), ln(360.0 / max(latSpan, lonSpan)) / ln(2.0)),
        )
        centre to zoom
    }
}

private fun tileSourceFor(tileUrl: String): OnlineTileSourceBase =
    object : OnlineTileSourceBase(
        "remotescreens",
        MIN_TILE_ZOOM,
        MAX_TILE_ZOOM,
        TILE_SIZE,
        ".png",
        arrayOf(tileUrl.substringBefore("{")),
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String =
            tileUrl
                .replace("{z}", MapTileIndex.getZoom(pMapTileIndex).toString())
                .replace("{x}", MapTileIndex.getX(pMapTileIndex).toString())
                .replace("{y}", MapTileIndex.getY(pMapTileIndex).toString())
    }

private fun markerIcon(picture: Bitmap?, label: String, colourIndex: Int): Drawable {
    val size = MARKER_SIZE_PX
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    if (picture != null) {
        val scaled = Bitmap.createScaledBitmap(picture, size, size, true)
        paint.color = Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        canvas.save()
        canvas.clipPath(
            Path().apply {
                addCircle(size / 2f, size / 2f, size / 2f - PHOTO_INSET_PX, Path.Direction.CW)
            },
        )
        canvas.drawBitmap(scaled, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
        canvas.restore()
    } else {
        paint.color = MARKER_COLOURS[colourIndex % MARKER_COLOURS.size]
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = Color.WHITE
        paint.strokeWidth = size / MARKER_STROKE_DIVISOR
        paint.style = Paint.Style.STROKE
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - paint.strokeWidth / 2f, paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = size * MARKER_INITIALS_SIZE_SHARE
        paint.typeface = Typeface.DEFAULT_BOLD
        val initials = label.split(' ')
            .mapNotNull { word -> word.firstOrNull()?.uppercaseChar()?.toString() }
            .take(INITIALS_COUNT)
            .joinToString("")
            .ifEmpty { "?" }
        val textY = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(initials, size / 2f, textY, paint)
    }
    return BitmapDrawable(null, bitmap)
}

private fun applyDarkTileFilter(root: View) {
    if (root is MapView) {
        root.overlayManager.tilesOverlay.setColorFilter(
            PorterDuffColorFilter(DARK_TILE_MULTIPLY, PorterDuff.Mode.MULTIPLY),
        )
        return
    }
    if (root is ViewGroup) {
        for (index in 0 until root.childCount) {
            applyDarkTileFilter(root.getChildAt(index))
        }
    }
}

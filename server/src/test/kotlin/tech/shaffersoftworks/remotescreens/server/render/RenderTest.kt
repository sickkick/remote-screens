package tech.shaffersoftworks.remotescreens.server.render

import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.core.operations.NamedVariable
import tech.shaffersoftworks.remotescreens.server.data.renderdata.RenderData
import tech.shaffersoftworks.remotescreens.server.data.renderdata.RenderDataRepository
import tech.shaffersoftworks.remotescreens.server.i18n.I18n
import tech.shaffersoftworks.remotescreens.server.data.homeassistant.demo.DemoHome
import tech.shaffersoftworks.remotescreens.server.data.homeassistant.demo.DemoLayout
import tech.shaffersoftworks.remotescreens.shared.Catalogue
import tech.shaffersoftworks.remotescreens.shared.NavBar
import tech.shaffersoftworks.remotescreens.shared.Reflow
import tech.shaffersoftworks.remotescreens.shared.VarType
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RenderTest {
    private val ha = DemoHome()
    private val i18n = I18n("en", I18n.load("en"), emptyMap(), true)
    private val tablet = Device(1280, 800, 1.5f)

    private fun input(data: RenderData = RenderData(), palette: Palette = Themes.RPI) = RenderInput(ha.states.value, i18n, ha.config.value, data, palette)
    private val Role.argb: Int get() = Themes.RPI[this]
    private val Pastel.argb: Int get() = Themes.RPI[this]

    private fun parse(bytes: ByteArray) = CoreDocument().also { it.initFromBuffer(RemoteComposeBuffer.fromInputStream(ByteArrayInputStream(bytes))) }

    @Test
    fun everyPageOfTheDemoScreenIsOneDocument() {
        val screen = DemoLayout.screen()
        val data = runBlocking { RenderDataRepository(ha).forScreen(screen) }
        val doc = ScreenRenderer.render(screen, tablet, input(data))
        val core = parse(doc.bytes)
        assertContains(core.getNamedVariables(NamedVariable.INT_TYPE).toList(), "USER:page")
        assertEquals(screen.pages.map { it.id }, doc.pageIds)

        val vars = doc.vars(ha.states.value)
        assertEquals("Ceiling light", vars["e.light.living_room.name"]?.value)
        assertEquals(VarType.COLOR, vars["t.t1.line"]?.type)
        val declared = core.getNamedVariables(NamedVariable.STRING_TYPE).toSet() +
            core.getNamedVariables(NamedVariable.INT_TYPE) + core.getNamedVariables(NamedVariable.FLOAT_TYPE) + core.getNamedVariables(NamedVariable.COLOR_TYPE)
        val unread = vars.keys.filter { "USER:$it" !in declared && !it.startsWith("sys.") }
        assertTrue(unread.isEmpty(), "values sent but never declared: $unread")
    }

    @Test
    fun aCompressedDocumentIsSmallerAndReadsTheSame() {
        val screen = DemoLayout.screen()
        val doc = ScreenRenderer.render(screen, tablet, input(runBlocking { RenderDataRepository(ha).forScreen(screen) }))
        val small = Rc.compress(doc.bytes)
        assertTrue(small.size * 2 < doc.bytes.size, "${small.size} of ${doc.bytes.size} bytes")
        val names = { b: ByteArray -> parse(b).getNamedVariables(NamedVariable.STRING_TYPE).toSet() }
        assertEquals(names(doc.bytes), names(small))
    }

    @Test
    fun everyDemoTileHasItsOwnCell() {
        val screen = DemoLayout.screen()
        for (page in screen.pages) {
            val placed = Reflow.place(page.tiles, screen.grid)
            assertEquals(page.tiles.map { it.id }.sorted(), placed.map { it.tile.id }.sorted(), "tiles dropped from ${page.id}")
            for (p in placed) assertEquals(p.tile.col to p.tile.row, p.col to p.row, "${p.tile.id} moved on ${page.id}")
        }
    }

    @Test
    fun aToggleIsForeseen() {
        val doc = ScreenRenderer.render(DemoLayout.screen(), tablet, input())
        val meta = "t2|toggle|"
        assertTrue(meta in doc.docContext.predicted, "the table lamp's toggle can be predicted")
    }

    @Test
    fun aTileLightsUpWhenItsEntityIsOn() {
        val doc = ScreenRenderer.render(DemoLayout.screen(), tablet, input())
        val states = ha.states.value
        val off = doc.vars(states)
        val on = doc.vars(states + ("light.table_lamp" to states.getValue("light.table_lamp").with("on")))
        // Mockup domain tiles keep a soft teal header line while off; accent when on.
        assertEquals(Role.ACCENT_SOFT.argb, off["t.t2.line"]?.asColor())
        assertEquals(Pastel.YELLOW.argb, on["t.t2.line"]?.asColor())
        assertEquals(Pastel.YELLOW.argb, on["t.t2.icon"]?.asColor())
    }

    @Test
    fun aThemeColoursTheDocumentAndItsLiveColours() {
        val doc = ScreenRenderer.render(DemoLayout.screen(), tablet, input(palette = Themes.LIGHT))
        val states = ha.states.value
        val off = doc.vars(states)
        val on = doc.vars(states + ("light.table_lamp" to states.getValue("light.table_lamp").with("on")))
        assertEquals(Themes.LIGHT[Role.ACCENT_SOFT], off["t.t2.line"]?.asColor())
        assertEquals(Themes.LIGHT[Pastel.YELLOW], on["t.t2.line"]?.asColor())
        val page = Themes.LIGHT[Role.PAGE]
        val bytes = byteArrayOf((page ushr 24).toByte(), (page ushr 16).toByte(), (page ushr 8).toByte(), page.toByte())
        assertTrue(doc.bytes.asList().windowed(4).any { it == bytes.asList() }, "the light page colour is in the document")
        val dark = ScreenRenderer.render(DemoLayout.screen(), tablet, input())
        assertFalse(dark.bytes.asList().windowed(4).any { it == bytes.asList() }, "the RPI document has no light page")
    }

    @Test
    fun theHomePanelShowsTheNextEventAndWhoIsHome() {
        val doc = ScreenRenderer.render(DemoLayout.screen(), tablet, input())
        val vars = doc.vars(ha.states.value)
        assertEquals("Dentist appointment", vars["t.h1.ev.title"]?.value)
        assertEquals("Family · Main St", vars["t.h1.ev.meta"]?.value)
        assertEquals("HOME", vars["t.h1.where.person.sam"]?.value)
        assertEquals("AWAY", vars["t.h1.where.person.alex"]?.value)
        assertEquals("14°", vars["t.h1.temp"]?.value)
    }

    @Test
    fun aFullWeatherTileIsTheRpiWeatherPage() {
        val screen = DemoLayout.screen()
        val data = runBlocking { RenderDataRepository(ha).forScreen(screen) }
        val doc = ScreenRenderer.render(screen, tablet, input(data))
        val vars = doc.vars(ha.states.value)
        assertEquals("Wind 12 km/h", vars["t.w1.wind_speed"]?.value)
        assertEquals("Humidity 71%", vars["t.w1.humidity"]?.value)
        assertEquals("Pressure 1,013 hPa", vars["t.w1.pressure"]?.value)
        assertEquals("14°", vars["e.weather.home.value"]?.value)
    }

    @Test
    fun aFullClimateTileIsTheRpiThermostatPage() {
        val doc = ScreenRenderer.render(DemoLayout.screen(), tablet, input())
        val states = ha.states.value
        val vars = doc.vars(states)
        assertEquals("21.0°", vars["t.c1.current"]?.value)
        assertEquals("45%", vars["t.c1.humidity"]?.value)
        assertEquals("Cooling", vars["t.c1.action"]?.value)
        assertEquals(Role.BLUE.argb, vars["t.c1.dial"]?.asColor())
        assertEquals("18°", vars["t.c1.low"]?.value)
        assertEquals("24°", vars["t.c1.high"]?.value)
        assertEquals(Role.ACCENT_DARK.argb, vars["o.c1.seg.0.bg"]?.asColor(), "Heat/Cool is lit")
        val heatOnly = doc.vars(states + ("climate.living_room" to states.getValue("climate.living_room").with("heat")))
        assertEquals(Role.ACCENT_DARK.argb, heatOnly["o.c1.seg.1.bg"]?.asColor())
        assertTrue("c1|temperature_low|+0.5" in doc.docContext.predicted)
        assertTrue("c1|temperature_high|-0.5" in doc.docContext.predicted)
        assertTrue("c1|fan|on" in doc.docContext.predicted)
        assertEquals("Auto", vars["t.c1.fan"]?.value)
    }

    @Test
    fun aDocumentIsStaleOnlyWhenWhatIsDrawnChanges() {
        val doc = ScreenRenderer.render(DemoLayout.screen(), tablet, input())
        val states = ha.states.value
        assertFalse(doc.stale(states))
        val lamp = states.getValue("light.table_lamp")
        assertFalse(doc.stale(states + ("light.table_lamp" to lamp.with("on"))), "a state change is a value, not a rebuild")
    }

    @Test
    fun everyCardBuilds() {
        val ids = ha.states.value.keys.filter { Catalogue.of(it.substringBefore('.'))?.card?.let { c -> c != "none" } == true }
        for (id in ids) {
            val data = runBlocking { RenderDataRepository(ha).forCard(id, null) }
            val doc = CardRenderer.render(id, null, tablet, input(data))
            assertTrue(parse(doc.bytes).getNamedVariables(NamedVariable.STRING_TYPE).isNotEmpty(), id)
        }
        val energy = runBlocking { RenderDataRepository(ha).forCard(null, "energy") }
        CardRenderer.render("card.energy", "energy", tablet, input(energy))
    }

    @Test
    fun aPortraitPhoneGetsTheSameScreen() {
        val doc = ScreenRenderer.render(DemoLayout.screen(), Device(400, 860, 2.75f), input())
        assertContains(parse(doc.bytes).getNamedVariables(NamedVariable.STRING_TYPE).toList(), "USER:e.light.living_room.name")
    }

    @Test
    fun barsTakeTheirOwnSizeLabelsLayoutAndColours() {
        val pink = Pastel.PINK.argb
        val bytes = listOf(24, 16, 8, 0).map { (pink ushr it).toByte() }
        val plain = ScreenRenderer.render(DemoLayout.screen(), tablet, input())
        assertFalse(plain.bytes.asList().windowed(4).any { it == bytes }, "the demo screen has no pink")
        for (labels in NavBar.LABELS) for (layout in NavBar.LAYOUTS) {
            val screen = DemoLayout.screen().let {
                it.copy(
                    topBar = it.topBar.copy(height = 72, clockWidth = 30, date = false, background = "pink"),
                    navBar = NavBar(height = 56, labels = labels, layout = layout, active = "pink"),
                )
            }
            val doc = ScreenRenderer.render(screen, Device(400, 860, 2.75f), input())
            assertTrue(doc.bytes.asList().windowed(4).any { it == bytes }, "$labels/$layout: the pink header is drawn")
        }
    }

    @Test
    fun theTopBarDeclaresWifiAndBatteryWhenEnabled() {
        val off = ScreenRenderer.render(DemoLayout.screen(), tablet, input())
        val offNames = parse(off.bytes).getNamedVariables(NamedVariable.STRING_TYPE).toSet()
        assertFalse("USER:sys.wifi" in offNames)
        assertFalse("USER:sys.battery" in offNames)

        val on = ScreenRenderer.render(
            DemoLayout.screen().let { it.copy(topBar = it.topBar.copy(wifi = true, battery = true)) },
            tablet,
            input(),
        )
        val strings = parse(on.bytes).getNamedVariables(NamedVariable.STRING_TYPE).toSet()
        val ints = parse(on.bytes).getNamedVariables(NamedVariable.INT_TYPE).toSet()
        assertContains(strings, "USER:sys.wifi")
        assertContains(strings, "USER:sys.battery")
        assertContains(ints, "USER:sys.battery_charging")
        assertContains(ints, "USER:sys.battery_idle")
    }

    @Test
    fun anAlertBuilds() {
        val doc = AlertRenderer.render(Alert("Doorbell", "Someone is at the door", camera = "camera.front_door", buttons = listOf(AlertButton("Open"))), tablet, input())
        assertEquals(1, doc.docContext.pictures.size)
    }

    @Test
    fun theDoorbellsVideoPlaysOverItsImageCroppedLikeIt() {
        val doc = AlertRenderer.render(Alert("Doorbell", camera = "camera.front_door"), tablet, input())

        val video = doc.docContext.videos.single()
        assertEquals(LiveVideo("camera.front_door", LiveVideo.FIRST_COMPONENT_ID, crop = true), video)
        assertVideoTargetFound(doc, video.componentId)
    }

    @Test
    fun aCameraCardsVideoPlaysOverItsSpinnerFittedLikeIt() {
        val doc = CardRenderer.render("camera.front_door", null, tablet, input())

        assertEquals(1, doc.docContext.pictures.size)
        assertTrue(doc.docContext.declares(Vars.entity("camera.front_door", Vars.KEY_CAM_WAITING)))
        assertTrue(doc.docContext.declares(Vars.entity("camera.front_door", Vars.KEY_CAM_STILL)))
        val video = doc.docContext.videos.single()
        assertEquals(LiveVideo("camera.front_door", LiveVideo.FIRST_COMPONENT_ID, crop = false), video)
        assertVideoTargetFound(doc, video.componentId)
    }

    @Test
    fun anAlertWithoutACameraHasNoVideo() {
        assertTrue(AlertRenderer.render(Alert("Washer done"), tablet, input()).docContext.videos.isEmpty())
    }

    @Test
    fun aPersonCardsMapTargetsItsBoxAndStillBitmap() {
        val doc = CardRenderer.render("person.sam", null, tablet, input())

        assertTrue(doc.docContext.pictures.any { it.name == "map.card" })
        val map = doc.docContext.maps.single()
        assertEquals(listOf("person.sam"), map.entityIds)
        assertEquals(LiveMap.FIRST_COMPONENT_ID, map.componentId)
        assertTrue(map.dark)
        assertVideoTargetFound(doc, map.componentId)
    }

    /** The player finds the target by its id once it has read the document, as it must to lay the video over it. */
    private fun assertVideoTargetFound(doc: RenderedDoc, componentId: Int) {
        val component = assertNotNull(parse(doc.bytes).getComponent(componentId), "component $componentId")
        assertEquals(componentId, component.componentId)
    }
}

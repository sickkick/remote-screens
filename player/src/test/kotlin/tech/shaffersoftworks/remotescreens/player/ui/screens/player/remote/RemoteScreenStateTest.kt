package tech.shaffersoftworks.remotescreens.player.ui.screens.player.remote

import tech.shaffersoftworks.remotescreens.shared.ServerMessage
import tech.shaffersoftworks.remotescreens.shared.Slot
import tech.shaffersoftworks.remotescreens.shared.VarValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteScreenStateTest {
    private val documentBytes = byteArrayOf(1, 2, 3)

    private fun mainDocument(
        pageCount: Int = 4,
        homePage: Int = 0,
        swipePages: List<Int> = emptyList(),
        vars: List<VarValue> = emptyList(),
    ) = ServerMessage.Document(
        slot = Slot.MAIN,
        rev = 1,
        data = "",
        vars = vars,
        homePage = homePage,
        pageCount = pageCount,
        swipePages = swipePages,
    )

    @Test
    fun `main document sets pages and keeps only valid swipe pages in order`() {
        val state = RemoteScreenState().withDocument(mainDocument(swipePages = listOf(3, 9, 1)), documentBytes)

        assertTrue(state.hasMainDocument)
        assertEquals(4, state.pageCount)
        assertEquals(listOf(1, 3), state.swipePages)
    }

    @Test
    fun `empty swipe pages means every page`() {
        val state = RemoteScreenState().withDocument(mainDocument(pageCount = 3), documentBytes)

        assertEquals(listOf(0, 1, 2), state.swipePages)
    }

    @Test
    fun `swipe skips to the nearest swipeable page from a hidden page`() {
        val state = RemoteScreenState()
            .withDocument(mainDocument(pageCount = 5, swipePages = listOf(0, 2, 4)), documentBytes)
            .withPage(3)

        assertEquals(4, state.nextSwipePage)
        assertEquals(2, state.previousSwipePage)
    }

    @Test
    fun `page is kept within the document`() {
        val state = RemoteScreenState().withDocument(mainDocument(pageCount = 2), documentBytes)

        assertEquals(1, state.withPage(7).page)
        assertEquals(0, state.withPage(-1).page)
    }

    @Test
    fun `a new main document with fewer pages goes home`() {
        val state = RemoteScreenState()
            .withDocument(mainDocument(pageCount = 4), documentBytes)
            .withPage(3)
            .withDocument(mainDocument(pageCount = 2, homePage = 1), documentBytes)

        assertEquals(1, state.pageAfterNewDocument)
    }

    @Test
    fun `back closes the alert before the card`() {
        val overlay = ServerMessage.Document(slot = Slot.OVERLAY, rev = 1, data = "")
        val alert = ServerMessage.Document(slot = Slot.ALERT, rev = 1, data = "")
        val state = RemoteScreenState().withDocument(overlay, documentBytes).withDocument(alert, documentBytes)

        assertEquals(Slot.ALERT, state.topOverlay)
        assertEquals(Slot.OVERLAY, state.closed(Slot.ALERT).topOverlay)
        assertNull(state.closed(Slot.ALERT).closed(Slot.OVERLAY).topOverlay)
    }

    @Test
    fun `the main document cannot be closed`() {
        val state = RemoteScreenState().withDocument(mainDocument(), documentBytes)

        assertSame(state, state.closed(Slot.MAIN))
    }

    @Test
    fun `a prediction applies the values the tap will change`() {
        val predicted = VarValue.string("light", "on")
        val document = ServerMessage.Document(
            slot = Slot.MAIN,
            rev = 1,
            data = "",
            vars = listOf(VarValue.string("light", "off")),
            predictions = mapOf("light|toggle|" to listOf(predicted)),
        )
        val state = RemoteScreenState().withDocument(document, documentBytes)

        assertEquals(predicted, state.withPrediction(Slot.MAIN, "light|toggle|")?.values?.get("light"))
        assertNull(state.withPrediction(Slot.MAIN, "other|toggle|"))
    }

    @Test
    fun `time zone is kept when documents are cleared`() {
        val state = RemoteScreenState().withTimeZone("America/New_York").cleared()

        assertEquals("America/New_York", state.timeZone)
    }

    @Test
    fun `vars add predictions to every shown document without replacing them`() {
        val withDocument = RemoteScreenState().withDocument(mainDocument(), documentBytes)
        val loadedDocument = withDocument.documents.getValue(Slot.MAIN)
        val state = withDocument.withVars(
            ServerMessage.Vars(
                vars = listOf(VarValue.int("count", 2)),
                predictions = mapOf("fan|toggle|" to listOf(VarValue.string("fan", "on"))),
            ),
        )

        assertEquals(VarValue.int("count", 2), state.values["count"])
        assertTrue("fan|toggle|" in state.documents.getValue(Slot.MAIN).predictions)
        assertSame(loadedDocument, state.documents.getValue(Slot.MAIN))
    }

    @Test
    fun `clearing hides every document and forgets values but keeps the page`() {
        val state = RemoteScreenState()
            .withDocument(mainDocument(vars = listOf(VarValue.int("count", 1))), documentBytes)
            .withPage(2)
            .cleared()

        assertFalse(state.hasMainDocument)
        assertTrue(state.values.isEmpty())
        assertEquals(2, state.page)
    }

    private fun overlay(slot: Slot, rev: Int) = ServerMessage.Document(slot = slot, rev = rev, data = "")

    private fun video(rev: Int, url: String = "http://addon:8100/stream/hls/abc/master_playlist.m3u8") =
        SlotVideo(rev, componentId = 0x5C_0000, url = url, token = "token", crop = false)

    @Test
    fun `a card's video plays`() {
        val state = RemoteScreenState()
            .withDocument(overlay(Slot.OVERLAY, rev = 2), documentBytes)
            .withVideo(Slot.OVERLAY, video(rev = 2))

        assertEquals(Slot.OVERLAY to video(rev = 2), state.playingVideo)
    }

    @Test
    fun `a video for a document that is gone is dropped`() {
        val state = RemoteScreenState()
            .withDocument(overlay(Slot.OVERLAY, rev = 3), documentBytes)
            .withVideo(Slot.OVERLAY, video(rev = 2))

        assertNull(state.playingVideo)
        assertTrue(state.videos.isEmpty())
    }

    @Test
    fun `only the alert's video plays over a card, and the card's comes back when it closes`() {
        val state = RemoteScreenState()
            .withDocument(overlay(Slot.OVERLAY, rev = 2), documentBytes)
            .withVideo(Slot.OVERLAY, video(rev = 2))
            .withDocument(overlay(Slot.ALERT, rev = 3), documentBytes)
            .withVideo(Slot.ALERT, video(rev = 3))

        assertEquals(Slot.ALERT to video(rev = 3), state.playingVideo)
        assertEquals(Slot.OVERLAY to video(rev = 2), state.closed(Slot.ALERT).playingVideo)
    }

    @Test
    fun `an alert without a camera hides the card's video`() {
        val state = RemoteScreenState()
            .withDocument(overlay(Slot.OVERLAY, rev = 2), documentBytes)
            .withVideo(Slot.OVERLAY, video(rev = 2))
            .withDocument(overlay(Slot.ALERT, rev = 3), documentBytes)

        assertNull(state.playingVideo)
    }

    @Test
    fun `a new document or closing the card ends its video`() {
        val playing = RemoteScreenState()
            .withDocument(overlay(Slot.OVERLAY, rev = 2), documentBytes)
            .withVideo(Slot.OVERLAY, video(rev = 2))

        assertTrue(playing.withDocument(overlay(Slot.OVERLAY, rev = 4), documentBytes).videos.isEmpty())
        assertTrue(playing.closed(Slot.OVERLAY).videos.isEmpty())
        assertTrue(playing.cleared().videos.isEmpty())
    }

    @Test
    fun `a stopped video is forgotten unless the add-on already sent a newer one`() {
        val stopped = video(rev = 2)
        val restarted = video(rev = 2, url = "http://addon:8100/stream/hls/def/master_playlist.m3u8")
        val state = RemoteScreenState()
            .withDocument(overlay(Slot.OVERLAY, rev = 2), documentBytes)
            .withVideo(Slot.OVERLAY, stopped)

        assertNull(state.withoutVideo(Slot.OVERLAY, stopped).playingVideo)
        assertEquals(
            Slot.OVERLAY to restarted,
            state.withVideo(Slot.OVERLAY, restarted).withoutVideo(Slot.OVERLAY, stopped).playingVideo,
        )
    }

    private fun map(rev: Int) = SlotMap(
        revision = rev,
        componentId = 0x5D_0000,
        tileUrl = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        dark = true,
        markers = emptyList(),
    )

    @Test
    fun `a card's map plays`() {
        val state = RemoteScreenState()
            .withDocument(overlay(Slot.OVERLAY, rev = 2), documentBytes)
            .withMap(Slot.OVERLAY, map(rev = 2))

        assertEquals(Slot.OVERLAY to map(rev = 2), state.playingMap)
    }

    @Test
    fun `a new document or closing the card ends its map`() {
        val playing = RemoteScreenState()
            .withDocument(overlay(Slot.OVERLAY, rev = 2), documentBytes)
            .withMap(Slot.OVERLAY, map(rev = 2))

        assertTrue(playing.withDocument(overlay(Slot.OVERLAY, rev = 4), documentBytes).maps.isEmpty())
        assertTrue(playing.closed(Slot.OVERLAY).maps.isEmpty())
        assertTrue(playing.cleared().maps.isEmpty())
    }

    @Test
    fun `an alert over a map card hides the map until it closes`() {
        val state = RemoteScreenState()
            .withDocument(overlay(Slot.OVERLAY, rev = 2), documentBytes)
            .withMap(Slot.OVERLAY, map(rev = 2))
            .withDocument(overlay(Slot.ALERT, rev = 3), documentBytes)

        assertNull(state.playingMap)
        assertEquals(Slot.OVERLAY to map(rev = 2), state.closed(Slot.ALERT).playingMap)
    }
}

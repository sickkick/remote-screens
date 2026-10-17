package tech.shaffersoftworks.remotescreens.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class MapProtocolTest {
    @Test
    fun aMapReadsBackAsSent() {
        val map = ServerMessage.LiveMap(
            slot = Slot.OVERLAY,
            rev = 4,
            componentId = 0x5D_0000,
            tileUrl = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            dark = true,
            markers = listOf(
                MapMarker(52.37, 4.89, "Sam", picture = "map.pic.person.sam"),
                MapMarker(52.35, 4.92, "Alex"),
            ),
        )

        assertEquals(map, Protocol.decodeServer(Protocol.encode(map)))
        assertEquals(
            """{"type":"map","slot":"overlay","rev":4,"componentId":6094848,""" +
                """"tileUrl":"https://tile.openstreetmap.org/{z}/{x}/{y}.png","dark":true,""" +
                """"markers":[{"latitude":52.37,"longitude":4.89,"label":"Sam","picture":"map.pic.person.sam"},""" +
                """{"latitude":52.35,"longitude":4.92,"label":"Alex"}]}""",
            Protocol.encode(map),
        )
    }

    @Test
    fun aMapWithoutMarkersOmitsTheListWhenEmptyDefaultsAreSkipped() {
        val map = ServerMessage.LiveMap(
            slot = Slot.OVERLAY,
            rev = 1,
            componentId = 0x5D_0000,
            tileUrl = "https://t.example/{z}/{x}/{y}.png",
            dark = false,
        )

        assertEquals(map, Protocol.decodeServer(Protocol.encode(map)))
    }
}

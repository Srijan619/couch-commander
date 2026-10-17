package app.couchcommander.data.iptv

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PopularChannelsTest {
    private fun ch(name: String, group: String = "", n: Int? = null) = IptvChannel(name, group, "http://x/${name.hashCode()}", number = n)

    @Test
    fun `brands by name, not lookalikes`() {
        assertEquals("Sky Sports", PopularChannels.brand(ch("UK| Sky Sports Main Event UHD")))
        assertEquals("V Sport", PopularChannels.brand(ch("FI: V Sport Premium FHD")))
        assertEquals("Canal+ Sport", PopularChannels.brand(ch("FR| Canal+ Sport 360 FHD")))
        assertNull(PopularChannels.brand(ch("TV Sport 1 FHD")), "\"tv sport\" isn't V Sport")
        assertEquals(listOf("FI| Elisa V Sport 1 FHD"), PopularChannels.keep(listOf(ch("FI| Elisa V Sport 1 FHD"))).map { it.name }, "a bare v isn't a match slot")
        assertNull(PopularChannels.brand(ch("UK| BBC One FHD")))
    }

    @Test
    fun `every brand channel is kept, sharpest first, the group's tag counts`() {
        val kept = PopularChannels.keep(
            listOf(ch("UK| Sky Sports Football HD"), ch("UK| Sky Sports Football FHD"), ch("TNT Sports 1", group = "UK| SPORTS 4K"), ch("UK| BBC One FHD")),
        )
        assertEquals(listOf("TNT Sports 1", "UK| Sky Sports Football FHD", "UK| Sky Sports Football HD"), kept.map { it.name })
    }

    @Test
    fun `HD and SD go in their own group, after the sharp ones`() {
        val cbs = PopularChannels.group(
            listOf(ch("SPT| CBS SPORTS HD"), ch("US| CBS Sports Golazo FHD"), ch("US| CBS SPORTS NETWORK")),
            home = null,
        ).single()
        assertEquals("CBS Sports", cbs.name)
        assertEquals(listOf("Golazo US · FHD"), cbs.sharp.map { it.label })
        assertEquals(listOf("CBS Sports SPT · HD", "Network US"), cbs.other.map { it.label })
        assertEquals("TNT Sports", PopularChannels.brand(ch("UK| TNT Ultimate 4K")))
    }

    @Test
    fun `shouting names are calmed, codes kept, quality said once, event slots left out`() {
        val brands = PopularChannels.group(
            PopularChannels.keep(
                listOf(
                    ch("UK| SKY SPORTS MAIN EVENT UHD"), ch("UK| SKY SPORTS F1 UHD/4K"), ch("NO| V SPORT PL 1 FHD"),
                    ch("NL| VIAPLAY XTRA 65 PPV FHD"), ch("IT| ZONA DAZN SERIE A 1 UHD"),
                ),
            ),
            home = null,
        )
        assertEquals(listOf("Main Event UK · UHD", "F1 UK · UHD"), brands.first { it.name == "Sky Sports" }.sharp.map { it.label })
        assertEquals(listOf("PL 1 NO · FHD"), brands.first { it.name == "V Sport" }.sharp.map { it.label })
        assertEquals(listOf("Zona DAZN Serie A 1 IT · UHD"), brands.first { it.name == "DAZN" }.sharp.map { it.label })
        assertEquals(null, brands.firstOrNull { it.name == "Viaplay" }, "PPV slots are dropped")
    }

    @Test
    fun `dividers, non-sport channels and per-match slots are left out, superscript tags read`() {
        val kept = PopularChannels.keep(
            listOf(
                ch("ES| ##### MOVISTAR #####"), ch("AR| beIN Entertainment HD"), ch("US| Paramount+ Arsenal v Chelsea"),
                ch("AR| beIN SPORTS 1ᵁᴴᴰ"), ch("NL| ZIGGO SPORT ² FHD"),
            ),
        )
        assertEquals(listOf("AR| beIN SPORTS 1ᵁᴴᴰ", "NL| ZIGGO SPORT ² FHD"), kept.map { it.name })
        val brands = PopularChannels.group(kept, home = null)
        assertEquals("1 AR · UHD", brands.first { it.name == "beIN Sports" }.sharp.single().label.removePrefix("Sports "))
        assertEquals("2 NL · FHD", brands.first { it.name == "Ziggo Sport" }.sharp.single().label)
    }

    @Test
    fun `event slots and per-match slots go, frame rates are dropped from names`() {
        val kept = PopularChannels.keep(
            listOf(
                ch("BE| DAZN ONLY EVENT 3"), ch("US| ESPN+ US | Macarthur vs Sydney FC"), ch("UK| Sky Sports Main Event FHD"),
                ch("US| ESPN NEWS 60ᶠᵖˢ HD"),
            ),
        )
        assertEquals(listOf("UK| Sky Sports Main Event FHD", "US| ESPN NEWS 60ᶠᵖˢ HD"), kept.map { it.name })
        assertEquals("News US · HD", PopularChannels.group(kept, home = null).first { it.name == "ESPN" }.other.single().label)
    }

    @Test
    fun `grouped in brand order, home first, the sharper feed once`() {
        val brands = PopularChannels.group(
            listOf(
                ch("ES| DAZN 1 FHD", n = 1),
                ch("UK| Sky Sports Main Event FHD", n = 2),
                ch("UK| Sky Sports Main Event UHD", n = 3),
                ch("FI: Viaplay Sport 1 FHD", n = 4),
                ch("NL: Viaplay Sport 1 FHD", n = 5),
            ),
            home = "FI",
        )
        assertEquals(listOf("Sky Sports", "Viaplay", "DAZN"), brands.map { it.name })
        assertEquals(listOf("Main Event UK · UHD"), brands[0].sharp.map { it.label })
        assertEquals(listOf("Sport 1 FI · FHD", "Sport 1 NL · FHD"), brands[1].sharp.map { it.label })
    }
}

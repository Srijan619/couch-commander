package app.couchcommander.data.iptv

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NamesTest {
    @Test
    fun `channel names lose prefixes and quality tags`() {
        assertEquals("zdf", Names.channel("WC| ZDF FHD"))
        assertEquals("zdf", Names.channel("ZDF HD"))
        assertEquals("v sport 1", Names.channel("FI: V Sport 1 [Backup]"))
        assertEquals("sky sports main event", Names.channel("UK| Sky Sports Main Event UHD"))
    }

    @Test
    fun `team forms`() {
        assertEquals(setOf("ff jaro", "jaro"), Names.teamForms("FF Jaro"))
        assertTrue("wolves" in Names.teamForms("Wolverhampton Wanderers"))
        assertTrue("wolverhampton" in Names.teamForms("Wolverhampton Wanderers"))
        assertTrue("bayern" in Names.teamForms("Bayern München"))
        assertFalse("manchester" in Names.teamForms("Manchester United"))
        assertTrue("man utd" in Names.teamForms("Manchester United"))
        assertEquals(setOf("hjk"), Names.teamForms("HJK"))
    }

    @Test
    fun `mentions match whole words`() {
        val arsenal = Names.teamForms("Arsenal")
        assertTrue(Names.mentions(Names.fold("Premier League: Arsenal v Chelsea"), arsenal))
        assertFalse(Names.mentions(Names.fold("Arsenalfans podcast"), arsenal))
        val city = Names.teamForms("Manchester City")
        assertFalse(Names.mentions(Names.fold("Man Utd v Liverpool"), city))
    }

    @Test
    fun `sports filter`() {
        fun ch(name: String, group: String, url: String = "http://h/u/p/1") = IptvChannel(name, group, url)
        assertTrue(Sports.isLiveSports(ch("ZDF", "WORLD CUP 2026 VIP")))
        assertTrue(Sports.isLiveSports(ch("beIN Sports 1", "AR| GENERAL")))
        assertTrue(Sports.isLiveSports(ch("ES| DAZN 1", "ES| DAZN MUNDIAL")))
        assertFalse(Sports.isLiveSports(ch("Yle TV1", "FI| GENERAL")))
        assertFalse(Sports.isLiveSports(ch("Sports Story", "VOD | Sports documentaries")))
        assertFalse(Sports.isLiveSports(ch("Football film", "Sport", "http://h/movie/u/p/1.mkv")))
        assertTrue(Sports.isSportsCategory("UK| SKY SPORTS"))
        assertFalse(Sports.isSportsCategory("Sport Series"))
    }

    @Test
    fun `provider names for chips`() {
        fun ch(name: String, group: String) = IptvChannel(name, group, "http://h/1")
        assertEquals("Viaplay NL", Names.provider(ch("Union Berlin vs Dortmund @ Oct 17 3:20 PM :Viaplay NL  17", "NL| VIAPLAY PPV BK"), event = true))
        assertEquals("DAZN ES", Names.provider(ch("Next | Union Berlin - Borussia Dortmund | ES: DAZN PPV 48", "ES| DAZN PPV"), event = true))
        assertEquals("Viaplay FI", Names.provider(ch("Union Berlin vs Dortmund @ Oct 17 4:20 PM :Viaplay FI  25", "FINLAND VIAPLAY PPV VIP"), event = true))
        assertEquals("Sport TV 1 SI", Names.provider(ch("SI| SPORT TV 1 ⱽᴵᴾ ᴿᴬᵂ", "SI| SPORTS"), event = false))
        assertEquals("Eleven Sports 2", Names.provider(ch("Eleven Sports 2", "PL| SPORT"), event = false))
        assertEquals("beIN Sports 1 FR", Names.provider(ch("FR| BEIN SPORTS 1 FHD", "FR| SPORTS"), event = false))
    }

    @Test
    fun `home country first`() {
        assertEquals("FI", Names.homeCountry("Europe/Helsinki", "US"))
        assertEquals(null, Names.homeCountry("America/New_York", "US"))
        assertEquals("GB", Names.homeCountry("Etc/UTC", "gb"))
        assertEquals(listOf("Viaplay FI", "Viaplay NL", "DAZN ES"), Names.homeFirst(listOf("Viaplay NL", "Viaplay FI", "DAZN ES"), "FI"))
    }
}

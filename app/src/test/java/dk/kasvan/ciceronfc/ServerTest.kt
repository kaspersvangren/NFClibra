package dk.kasvan.ciceronfc

import org.junit.Assert.assertEquals
import org.junit.Test

/** Billetnøglen: Cicero inde i app'en og serveren skal se den samme forespørgsel ens */
class ServerTest {
    @Test
    fun samme_noegle_uanset_kodning_og_slutskraastreg() {
        val a = Server.ticketKey("GET", "/alarmOn")
        assertEquals(a, Server.ticketKey("get", "/alarmOn/"))
        assertEquals(a, Server.ticketKey("GET", "/%61larmOn"))
        assertEquals("GET /alarmOn", a)
    }

    @Test
    fun parametre_er_med_i_noeglen() {
        assertEquals("GET /write?barcode=123", Server.ticketKey("GET", "/write?barcode=123"))
        assertEquals("GET /write?barcode=123", Server.ticketKey("GET", "/write?barcode=%31%32%33"))
        // Andre parametre giver en anden billet
        assertEquals(false, Server.ticketKey("GET", "/write?barcode=1") == Server.ticketKey("GET", "/write?barcode=2"))
    }

    @Test
    fun plus_bliver_staaende_og_ugyldig_kodning_knaekker_ikke() {
        assertEquals("a+b", Server.pctDecode("a+b"))
        assertEquals("a b", Server.pctDecode("a%20b"))
        assertEquals("%zz", Server.pctDecode("%zz"))
    }

    @Test
    fun rod_og_events() {
        assertEquals("/", Server.normalPath("/"))
        assertEquals("/", Server.normalPath(""))
        assertEquals("/events", Server.normalPath("/events/"))
        assertEquals("/.status", Server.normalPath("/.status"))
    }
}

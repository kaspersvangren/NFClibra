package dk.kasvan.ciceronfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Chip-formatet (dansk RFID-datamodel, som go-feig skriver det) */
class TagContentTest {
    @Test
    fun crc16_er_ccitt_false() {
        // Kendt svar: CRC-16/CCITT-FALSE("123456789") = 0x29B1, lagt som lav byte først
        val crc = TagContent.crc16("123456789".toByteArray(Charsets.US_ASCII))
        assertArrayEquals(byteArrayOf(0xB1.toByte(), 0x29), crc)
    }

    @Test
    fun build_og_parse_er_hinandens_modsaetning() {
        val bytes = TagContent.build("5342010112345", 2, 1, "DK", "DK-715900")
        assertEquals(32, bytes.size)
        assertEquals(0x11, bytes[0].toInt())
        val c = TagContent.parse(bytes)
        assertEquals("5342010112345", c.barcode)
        assertEquals(2, c.numItems)
        assertEquals(1, c.seqNum)
        assertEquals("DK", c.country)
        assertEquals("DK-715900", c.library)
    }

    @Test
    fun crc_daekker_de_32_bytes_uden_crc_feltet() {
        val bytes = TagContent.build("12345", 1, 1, "DK", "DK-715900")
        // Som go-feig: bytes 0-18 + 21-33 (de to sidste er nul-bytes uden for de 32)
        val cs = ByteArray(32)
        bytes.copyInto(cs, 0, 0, 19)
        bytes.copyInto(cs, 19, 21, 32)
        assertArrayEquals(TagContent.crc16(cs), bytes.copyOfRange(19, 21))
    }

    @Test
    fun felterne_er_nul_udfyldt_og_afgraenset() {
        val bytes = TagContent.build("1", 1, 1, "DK", "DK-1")
        // Materialenummer: 16 tegn fra byte 3; resten nul
        assertEquals('1'.code.toByte(), bytes[3])
        for (i in 4 until 19) assertEquals("byte $i", 0, bytes[i].toInt())
        assertEquals("DK", String(bytes, 21, 2, Charsets.US_ASCII))
        assertEquals("DK-1", TagContent.parse(bytes).library)
    }

    @Test
    fun for_langt_eller_tomt_materialenummer_afvises() {
        assertThrows(IllegalArgumentException::class.java) { TagContent.build("", 1, 1, "DK", "DK-1") }
        assertThrows(IllegalArgumentException::class.java) { TagContent.build("12345678901234567", 1, 1, "DK", "DK-1") }
        assertThrows(IllegalArgumentException::class.java) { TagContent.build("æøå", 1, 1, "DK", "DK-1") }
    }

    @Test
    fun parse_kraever_32_bytes() {
        assertThrows(IllegalArgumentException::class.java) { TagContent.parse(ByteArray(31)) }
    }

    @Test
    fun tom_chip_parses_som_tom() {
        val c = TagContent.parse(ByteArray(32))
        assertEquals("", c.barcode)
        assertEquals("", c.library)
        assertEquals(0, c.numItems)
    }
}

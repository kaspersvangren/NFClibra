package dk.kasvan.ciceronfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CqlTextTest {
    @Test
    fun ciceros_cql_linje_fra_skaermen_rettes() {
        // Sådan blev linjen læst fra et billede af Ciceros skærm: "=" som "-", anførselstegn væk
        val raw = "CQL: (ti-på flugt i sibirien* AND fo=rane willerslev* AND em= sibirien*) and (bai=715900)"
        assertTrue(CqlText.looksCql(raw))
        assertEquals("ti=\"på flugt i sibirien*\" AND fo=\"rane willerslev*\" AND em=sibirien*", CqlText.repair(raw))
    }

    @Test
    fun ukendte_koder_og_filtre_rettes_og_bai_tages_fra() {
        val want = "(sp=dan and usa and historie) and ((bhs=OnShelf OR bhs=NotForLoan) AND ((bfi=\"Hovedbiblioteket\")))"
        assertEquals(want, CqlText.repair("CQL: (sp=dan and usa and historie) and ((bhs=OnShelf OR bhs=NotForLoan) AND ((bfi=\"Hovedbiblioteket\")) and bai=715900)"))
        // "=" helt væk foran anførselstegn
        assertEquals(want, CqlText.repair("CQL: (sp=dan and usa and historie) and ((bhs=OnShelf OR bhs=NotForLoan) AND ((bfi\"Hovedbiblioteket\")) and bai=715900)"))
        // Samme linje læst med "-" i stedet for "="
        assertEquals(want, CqlText.repair("CQL: (sp-dan and usa and historie) and ((bhs-OnShelf OR bhs-NotForLoan) AND ((bfi-\"Hovedbiblioteket\")) and bai-715900)"))
    }

    @Test
    fun korrekt_linje_beholdes_uden_bai() {
        val raw = "CQL: (ti=\"*på flugt i sibirien*\" AND fo=\"rane willerslev*\") and (bai=715900)"
        assertEquals("ti=\"*på flugt i sibirien*\" AND fo=\"rane willerslev*\"", CqlText.repair(raw))
    }

    @Test
    fun grupper_og_numre_beholdes() {
        assertEquals("term.creator=\"Willerslev, Rane\" AND term.title=(\"a\" OR \"b\")",
            CqlText.repair("term.creator=\"Willerslev, Rane\" AND term.title=(\"a\" OR \"b\")"))
        assertEquals("faust=27813739 OR faust=24633594", CqlText.repair("faust=27813739 OR faust=24633594"))
        assertEquals("(ti=\"lille traktor*\" OR ti=\"stor traktor*\") AND fo=bo",
            CqlText.repair("(ti=lille traktor* OR ti=stor traktor*) AND fo=bo"))
        // Et kort ord sidst i en titel i anførselstegn er ikke en kode
        assertEquals("ti=\"lille bog\" AND fo=x", CqlText.repair("ti=\"lille bog\" AND fo=x"))
    }

    @Test
    fun almindelig_tekst_er_ikke_cql() {
        assertFalse(CqlText.looksCql("Tidsskrift for litteratur"))
        assertFalse(CqlText.looksCql("O Brother, Where Art Thou"))
        assertFalse(CqlText.looksCql("Willerslev, Rane"))
        assertFalse(CqlText.looksCql("Anne-Marie og ti-tallet"))
        assertFalse(CqlText.looksCql("CQL"))
    }
}

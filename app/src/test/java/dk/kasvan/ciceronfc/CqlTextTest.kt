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
    fun et_tabt_anfoerselstegn_giver_nye() {
        val raw = "CQL: (ti=\"*på flugt i sibirien* AND fo=\"rane willerslev*\") and (bai=715900)"
        assertEquals("ti=\"*på flugt i sibirien*\" AND fo=\"rane willerslev*\"", CqlText.repair(raw))
    }

    @Test
    fun grupper_og_numre_beholdes() {
        assertEquals("term.creator=\"Willerslev, Rane\" AND term.title=(\"a\" OR \"b\")",
            CqlText.repair("term.creator=\"Willerslev, Rane\" AND term.title=(\"a\" OR \"b\")"))
        assertEquals("faust=27813739 OR faust=24633594", CqlText.repair("faust=27813739 OR faust=24633594"))
        assertEquals("(ti=\"lille traktor*\" OR ti=\"stor traktor*\") AND fo=bo",
            CqlText.repair("(ti=lille traktor* OR ti=stor traktor*) AND fo=bo"))
        assertEquals("ti=\"lille bog\" AND fo=x", CqlText.repair("ti=\"lille bog\" AND fo=x"))
    }

    @Test
    fun tekst_i_anfoerselstegn_roeres_ikke() {
        // Fund fra gennemgangen: AND/OR/NOT, parenteser, koder og bindestreger inde i en titel er ikke CQL
        for (ok in listOf(
            "ti=\"harry potter and the*\" AND fo=rowling*",
            "ti=\"Krig AND fred\"",
            "ti=\"Why not me\" AND fo=x",
            "term.title=\"war and peace\" AND term.creator=tolstoj",
            "ti=\"Romeo og Julie (uddrag)\" AND sp=dan",
            "term.title=\"Alt det (jeg) ikke sagde\"",
            "ti=\"Sibirien: is-ørkenen\"",
            "ti=\"De ti - bud\"",
        )) assertEquals(ok, CqlText.repair(ok))
    }

    @Test
    fun almindelig_tekst_er_ikke_cql() {
        for (t in listOf(
            "Tidsskrift for litteratur", "O Brother, Where Art Thou", "Willerslev, Rane", "Anne-Marie og ti-tallet", "CQL",
            "Faust: 12345678 ISBN: 9788702123456", "fo: Nielsen and Hansen", "Søgeresultat (23)", "ti=\"Krig AND fred\"",
        )) assertFalse(t, CqlText.looksCql(t))
        assertTrue(CqlText.looksCql("ti=x AND fo=y"))
        assertTrue(CqlText.looksCql("ti-på flugt* AND fo=rane*"))
    }

    @Test
    fun cql_foran_laest_som_col() {
        // Fotograferet fra skærmen: "Q" læst som "O"
        val raw = "COL: (usa and historie) and ((bhs=OnShelf OR bhs=NotForLoan) AND ((bfi=\"Lager på HB\")) and bai=715900)"
        assertTrue(CqlText.looksCql(raw))
        assertEquals("(usa and historie) and ((bhs=OnShelf OR bhs=NotForLoan) AND ((bfi=\"Lager på HB\")))", CqlText.repair(raw))
        assertEquals("ti=x AND fo=y", CqlText.repair("C0L; ti=x AND fo=y"))
        // Et ord, der begynder sådan, uden kolon er ikke "CQL:"
        assertFalse(CqlText.looksCql("Colin Firth"))
    }

    @Test
    fun qr_kode_med_cql() {
        // Fra browserudvidelsen: præcis, så én kode er nok – men en titel eller et navn er stadig ikke CQL
        assertTrue(CqlText.isCqlCode("faust=12345678"))
        assertTrue(CqlText.isCqlCode("ti=\"Harry Potter\""))
        assertTrue(CqlText.isCqlCode("CQL: (ti=\"x\" AND fo=\"y\") and (bai=715900)"))
        assertFalse(CqlText.isCqlCode("Harry Potter og De Vises Sten"))
        assertFalse(CqlText.isCqlCode("Willerslev, Rane"))
        assertEquals("ti=\"x\" AND fo=\"y\"", CqlText.repair("CQL: (ti=\"x\" AND fo=\"y\") and (bai=715900)"))
    }

    @Test
    fun linjer_brudt_over_flere_linjer() {
        // Åbent anførselstegn eller åben parentes, eller linjen har selv en kode – ellers hører den ikke med
        assertTrue(CqlText.continues("CQL: (ti=\"*på flugt", "i sibirien*\" AND fo=x)"))
        assertTrue(CqlText.continues("(sp=dan and usa", "and bai=715900)"))
        assertTrue(CqlText.continues("ti=x", "AND fo=y"))
        assertFalse(CqlText.continues("ti=\"Alice\" AND fo=carroll", "Søgeresultat (23)"))
        assertFalse(CqlText.continues("ti=\"Alice\" AND fo=carroll", "Antal: 12 (hjemme)"))
    }
}

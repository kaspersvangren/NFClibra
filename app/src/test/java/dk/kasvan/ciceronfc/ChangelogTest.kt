package dk.kasvan.ciceronfc

import org.junit.Assert.assertEquals
import org.junit.Test

class ChangelogTest {
    @Test
    fun laeser_versionslinjer_og_springer_resten_over() {
        val e = Changelog.parse(listOf(
            "# Ændringer", "",
            "- **Næste version** – endnu ikke bygget",
            "- **0.1.84** – Anden gennemgang: miniature – og mere.",
            "  - en indrykket linje, der ikke er en version",
            "- **0.1.9** – Ni",
        ))
        assertEquals(2, e.size)
        assertEquals("0.1.84", e[0].name)
        assertEquals(84L, e[0].code)
        assertEquals("Anden gennemgang: miniature – og mere.", e[0].text)
        assertEquals(9L, e[1].code)
    }
}

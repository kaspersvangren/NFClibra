package dk.kasvan.ciceronfc

import android.content.Context

/**
 * Ændringslisten bygges ind i app'en (CHANGELOG.md fra repo'et, lagt i assets under bygningen),
 * så den kan vises uden net. Én linje pr. version: "- **0.2.0** (96) – tekst", hvor tallet i parentes er
 * byggenummeret (det Android sammenligner). Ældre linjer ("- **0.1.27** – tekst") har byggenummeret som sidste tal.
 */
object Changelog {
    class Entry(val name: String, val code: Long, val text: String)

    private val LINE = Regex("""^- \*\*(\d+\.\d+\.(\d+))\*\*(?: \((\d+)\))? – (.+)$""")

    /** Linjerne fra CHANGELOG.md → versioner; linjer, der ikke følger formen (fx "Næste version"), springes over */
    fun parse(lines: List<String>): List<Entry> = lines.mapNotNull { line ->
        LINE.find(line.trim())?.let { m ->
            val code = m.groupValues[3].ifEmpty { m.groupValues[2] }.toLong()
            Entry(m.groupValues[1], code, m.groupValues[4].trim())
        }
    }

    fun entries(ctx: Context): List<Entry> = try {
        ctx.assets.open("CHANGELOG.md").bufferedReader().use { r -> parse(r.readLines()) }
    } catch (_: Exception) {
        emptyList()
    }
}

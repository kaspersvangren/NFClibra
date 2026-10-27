package dk.kasvan.ciceronfc

/**
 * En CQL-linje læst med kameraet (fx Ciceros egen "CQL: (ti="…" AND fo="…") and (bai=…)" på en skærm):
 * genkendes og rettes for de typiske læsefejl, så den kan søges på, som den står.
 */
object CqlText {
    /** Kendte koder i Ciceros CQL – her rettes også "-" og ":" til "=" (typiske læsefejl) */
    private const val KNOWN = """term\.[a-z]+|phrase\.[a-z]+|ti|fo|em|dk5|faust|bmn|isbn|is|sp|ma|lti|lfo|lem|bai|bhs|bfi"""
    private val KNOWN_EQ = Regex("""(?i)(^|[\s(])($KNOWN)\s*[=\-–:]\s*""")
    /** Andre koder (små bogstaver): med "=", eller med "-" lige efter "(" eller AND/OR */
    private val ANY_EQ = Regex("""(^|[\s(])([a-zæøå]{2,5}\d?)\s*=\s*""")
    private val ANY_DASH = Regex("""(^|\(|\s(?:AND|OR|NOT|and|or|not)\s)([a-zæøå]{2,5}\d?)\s*[\-–]\s*""")
    private val PREFIX = Regex("""(?i)^\s*CQL\s*[:;.]?\s*""")
    private val BOOL = Regex("""\s(?:AND|OR|NOT)\s""", RegexOption.IGNORE_CASE)
    /** Bibliotekets eget filter, som Cicero selv sætter på: "and (bai=715900)" */
    private val BAI = Regex("""(?i)\s*(?:\b(?:and|or)\s+)?\(?\s*\bbai\s*[=\-–:]\s*"?[\w.]*"?\s*\)?""")
    private val VALUE = Regex("""(^|[\s(])(term\.[a-z]+|phrase\.[a-z]+|[a-zæøå]{2,5}\d?)=(?!\()(.+?)(?=\s+(?:AND|OR|NOT|and|or|not)\s|\)|$)""")

    /** Antal koder i teksten (samme sted tælles kun én gang) */
    private fun codeCount(t: String) =
        (KNOWN_EQ.findAll(t) + ANY_EQ.findAll(t)).map { it.groups[2]!!.range.first }.toSet().size

    /** Ligner teksten en CQL-søgning? ("CQL:" forrest, eller kode= sammen med AND/OR eller flere koder) */
    fun looksCql(s: String): Boolean {
        val t = s.trim()
        if (PREFIX.find(t)?.let { it.value.contains(':') || it.value.contains(';') } == true && t.length > 6) return true
        val codes = codeCount(t)
        return codes >= 2 || (codes == 1 && BOOL.containsMatchIn(t))
    }

    /** Ligner linjen fortsættelsen af en CQL-søgning, der er brudt over flere linjer? */
    fun continues(s: String): Boolean =
        KNOWN_EQ.containsMatchIn(s) || ANY_EQ.containsMatchIn(s) || BOOL.containsMatchIn(" $s ") || s.contains('"') || s.contains('*') || s.contains(')')

    /** Retter en læst CQL-linje: uden "CQL:" og bibliotekets bai-filter, med = efter koderne og værdierne i anførselstegn */
    fun repair(raw: String): String {
        var s = raw.replace(Regex("[“”„″«»]"), "\"").replace("''", "\"").replace(Regex("\\s+"), " ").trim()
        s = PREFIX.replace(s, "")
        s = BAI.replace(s, "")
        s = KNOWN_EQ.replace(s) { it.groupValues[1] + it.groupValues[2].lowercase() + "=" }
        s = ANY_DASH.replace(s) { it.groupValues[1] + it.groupValues[2] + "=" }
        s = ANY_EQ.replace(s) { it.groupValues[1] + it.groupValues[2] + "=" }
        s = VALUE.replace(s) { m ->
            val v = m.groupValues[3].replace("\"", "").trim()
            m.groupValues[1] + m.groupValues[2] + "=" + (if (v.isNotEmpty() && v.all { it.isDigit() }) v else "\"" + v + "\"")
        }
        // Parenteser i balance: overskydende ")" væk, manglende ")" sat på
        val out = StringBuilder()
        var depth = 0
        for (c in s) {
            if (c == ')') { if (depth == 0) continue; depth-- }
            if (c == '(') depth++
            out.append(c)
        }
        repeat(depth) { out.append(')') }
        // Tomme parenteser og løse AND/OR i enderne (efter at bai-filteret er taget fra)
        var r = out.toString().replace(Regex("""\(\s*\)"""), "").trim()
        r = r.replace(Regex("""(?i)^(?:AND|OR|NOT)\s+|\s+(?:AND|OR|NOT)$"""), "").trim()
        // Én parentes om det hele er unødvendig
        if (r.startsWith("(") && r.endsWith(")") && outerPair(r)) r = r.substring(1, r.length - 1).trim()
        return r
    }

    /** Hører første "(" sammen med sidste ")"? */
    private fun outerPair(s: String): Boolean {
        var d = 0
        for ((i, c) in s.withIndex()) {
            if (c == '(') d++ else if (c == ')') { d--; if (d == 0 && i < s.length - 1) return false }
        }
        return true
    }
}

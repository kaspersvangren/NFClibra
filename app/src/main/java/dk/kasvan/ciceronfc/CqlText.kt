package dk.kasvan.ciceronfc

/**
 * En CQL-linje læst med kameraet (fx Ciceros egen "CQL: (ti="…" AND fo="…") and (bai=…)" på en skærm):
 * genkendes og rettes for de typiske læsefejl, så den kan søges på, som den står.
 *
 * Alt, der står i anførselstegn, lades urørt – en titel som "harry potter and the…" eller "Romeo og Julie (uddrag)"
 * må ikke blive til søgekoder. Rettelserne sker kun i teksten uden om anførselstegnene.
 */
object CqlText {
    /** Kendte koder i Ciceros CQL – her rettes også "-" og ":" til "=" (typiske læsefejl) */
    private const val KNOWN = """term\.[a-z]+|phrase\.[a-z]+|ti|fo|em|dk5|faust|bmn|isbn|is|sp|ma|lti|lfo|lem|bai|bhs|bfi"""
    private const val BOOL_WORD = """(?:AND|OR|NOT|and|or|not)"""
    /** kode= (mellemrum omkring "=" er i orden) */
    private val ANY_EQ = Regex("""(^|[\s(])(term\.[a-z]+|phrase\.[a-z]+|[a-zæøå]{2,5}\d?)\s*=\s*""")
    /** Kendt kode med "-" klistret på (ti-på flugt) eller ":" (ti: på flugt) – læsefejl for "=" */
    private val KNOWN_DASH = Regex("""(?i)(^|[\s(])($KNOWN)[\-–](?=\S)""")
    private val KNOWN_COLON = Regex("""(?i)(^|[\s(])($KNOWN):\s*""")
    /** Kode uden "=" (eller med "-"/":" læst for "=") lige før et anførselstegn: (bfi"Hovedbiblioteket" */
    private val CODE_AT_END = Regex("""(^|\(|\s$BOOL_WORD\s)($KNOWN|[a-zæøå]{2,5}\d?)\s*[\-–:]?\s*$""")
    private val PREFIX = Regex("""(?i)^\s*CQL\s*[:;.]?\s*""")
    private val BOOL = Regex("""\s$BOOL_WORD\s""")
    /** Bibliotekets eget filter, som Cicero selv sætter på: "and (bai=715900)" */
    private val BAI = Regex("""(?i)\s*(?:\b(?:and|or)\s+)?\(?\s*\bbai\s*[=\-–:]\s*[\w.]*\s*\)?""")
    /** kode=værdi uden anførselstegn – værdien går til næste AND/OR, ")" eller linjens slutning */
    private val VALUE = Regex("""(^|[\s(])(term\.[a-z]+|phrase\.[a-z]+|[a-zæøå]{2,5}\d?)=(?![("])(.+?)(?=\s+$BOOL_WORD\s|\)|$)""")

    /** Ligner teksten en CQL-søgning? ("CQL:" forrest, eller kode= sammen med AND/OR eller flere koder) */
    fun looksCql(s: String): Boolean {
        val t = s.trim()
        if (PREFIX.find(t)?.let { it.value.contains(':') || it.value.contains(';') } == true && t.length > 6) return true
        // Kun koder uden om anførselstegn tæller, og kun med "=" (eller kendt kode med "-" klistret på)
        var codes = 0
        var bool = false
        for ((text, quoted) in segments(t)) {
            if (quoted) continue
            codes += ANY_EQ.findAll(text).count() + KNOWN_DASH.findAll(text).count()
            if (BOOL.containsMatchIn(text)) bool = true
        }
        return codes >= 2 || (codes == 1 && bool)
    }

    /**
     * Indholdet af en QR-kode: læst præcist, så én kode er nok (fx `faust=12345678` eller `ti="Harry Potter"`).
     * Almindelig tekst uden kode= (en titel, et navn) er stadig ikke CQL.
     */
    fun isCqlCode(s: String): Boolean {
        if (looksCql(s)) return true
        return segments(s.trim()).any { (text, quoted) -> !quoted && (ANY_EQ.containsMatchIn(text) || KNOWN_DASH.containsMatchIn(text)) }
    }

    /**
     * Hører [line] med til en CQL-søgning, der er brudt over flere linjer, og som indtil nu lyder [soFar]?
     * Ja, hvis der står et anførselstegn eller en parentes åben, eller linjen selv har en kode= eller begynder med AND/OR.
     */
    fun continues(soFar: String, line: String): Boolean {
        if (soFar.count { it == '"' } % 2 == 1) return true
        if (depthOutsideQuotes(soFar) > 0) return true
        val t = line.trim()
        return ANY_EQ.containsMatchIn(t) || KNOWN_DASH.containsMatchIn(t) || Regex("""^$BOOL_WORD\s""").containsMatchIn(t)
    }

    /** Retter en læst CQL-linje: uden "CQL:" og bibliotekets bai-filter, med = efter koderne og anførselstegn, hvor de mangler */
    fun repair(raw: String): String {
        var s = raw.replace(Regex("[“”„″«»]"), "\"").replace("''", "\"").replace(Regex("\\s+"), " ").trim()
        s = PREFIX.replace(s, "")
        // Et tabt anførselstegn (ulige antal): så kan de ikke bruges – alle tages fra, og værdier med mellemrum får nye
        if (s.count { it == '"' } % 2 == 1) s = s.replace("\"", "")
        val segs = segments(s).map { (text, quoted) -> if (quoted) text else fixOutside(text) }.toMutableList()
        // Kode lige før et anførselstegn uden "=": (bfi"Hovedbiblioteket"
        for (i in 0 until segs.size - 1) {
            if (!segs[i].startsWith("\"") && segs[i + 1].startsWith("\"")) {
                segs[i] = CODE_AT_END.replace(segs[i]) { it.groupValues[1] + it.groupValues[2].lowercase() + "=" }
            }
        }
        s = segs.joinToString("")
        s = balance(s)
        s = s.replace(Regex("""\(\s*\)"""), "").replace(Regex("\\s+"), " ").trim()
        s = s.replace(Regex("""^$BOOL_WORD\s+|\s+$BOOL_WORD$"""), "").trim()
        // Én parentes om det hele er unødvendig
        if (s.startsWith("(") && s.endsWith(")") && outerPair(s)) s = s.substring(1, s.length - 1).trim()
        return s
    }

    /** Rettelserne i tekst uden om anførselstegn */
    private fun fixOutside(text: String): String {
        var t = BAI.replace(text, "")
        t = KNOWN_DASH.replace(t) { it.groupValues[1] + it.groupValues[2].lowercase() + "=" }
        t = KNOWN_COLON.replace(t) { it.groupValues[1] + it.groupValues[2].lowercase() + "=" }
        t = ANY_EQ.replace(t) { it.groupValues[1] + it.groupValues[2] + "=" }
        // Værdi med mellemrum uden anførselstegn (fo=rane willerslev*): anførselstegn sættes på
        t = VALUE.replace(t) { m ->
            val v = m.groupValues[3].trim()
            m.groupValues[1] + m.groupValues[2] + "=" + (if (v.contains(' ')) "\"" + v + "\"" else v)
        }
        return t
    }

    /** Teksten delt i stykker: i anførselstegn (inkl. tegnene) og uden om. Lige antal anførselstegn forudsættes. */
    private fun segments(s: String): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        var i = 0
        while (i < s.length) {
            val q = s.indexOf('"', i)
            if (q < 0) { out += s.substring(i) to false; break }
            val end = s.indexOf('"', q + 1)
            if (end < 0) { out += s.substring(i) to false; break }
            if (q > i) out += s.substring(i, q) to false
            out += s.substring(q, end + 1) to true
            i = end + 1
        }
        return out
    }

    private fun depthOutsideQuotes(s: String): Int {
        var d = 0
        for ((text, quoted) in segments(s)) if (!quoted) for (c in text) { if (c == '(') d++ else if (c == ')') d-- }
        return d
    }

    /** Parenteser i balance (kun uden om anførselstegn): overskydende ")" væk, manglende ")" sat på */
    private fun balance(s: String): String {
        val out = StringBuilder()
        var depth = 0
        for ((text, quoted) in segments(s)) {
            if (quoted) { out.append(text); continue }
            for (c in text) {
                if (c == ')') { if (depth == 0) continue; depth-- }
                if (c == '(') depth++
                out.append(c)
            }
        }
        repeat(depth) { out.append(')') }
        return out.toString()
    }

    /** Hører første "(" sammen med sidste ")"? (anførselstegn tæller ikke) */
    private fun outerPair(s: String): Boolean {
        var d = 0
        var pos = 0
        for ((text, quoted) in segments(s)) {
            if (!quoted) for ((k, c) in text.withIndex()) {
                if (c == '(') d++ else if (c == ')') { d--; if (d == 0 && pos + k < s.length - 1) return false }
            }
            pos += text.length
        }
        return true
    }
}

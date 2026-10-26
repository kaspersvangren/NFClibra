package dk.kasvan.ciceronfc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Kamera, der læser materialenummeret – både stregkoder og trykte tal (fx på en etiket i bogen).
 * Viser de numre, den kan se; man trykker på det rigtige. Alt genkendes på telefonen; intet sendes nogen steder hen.
 */
class ScanActivity : LockedScreen(), NfcAdapter.ReaderCallback {
    companion object {
        const val EXTRA_VALUE = "value"
        const val EXTRA_DARK = "dark"
        /** "cql": søgning i Ciceros CQL-felt – tekstlinjer og numre, der kan søges på; resultatet er en CQL-streng */
        const val EXTRA_MODE = "mode"
        const val MODE_CQL = "cql"
        private val TEXT_TYPES = linkedMapOf("free" to "Fritekst", "title" to "Titel", "creator" to "Forfatter", "dk5" to "DK5", "subject" to "Emneord")
        private val NUM_TYPES = linkedMapOf("faust" to "Faust", "bmn" to "Materialenr.", "isbn" to "ISBN")
        /** Ord, der aldrig er en søgning, når de står alene: Ciceros feltnavne og materialetyper (sammenligningsnøgler) */
        private val STOP_WORDS = setOf(
            "titel", "forfatter", "emne", "emner", "serie", "status", "beholdning", "placering", "placeringer",
            "faust", "faustnr", "klassifikation", "materialenummer", "materialenr", "udlant", "hjemme", "reserveret",
            "reservationer", "isbn", "udgivelse", "sprog", "materialetype", "type", "antal", "materialer",
            "bog", "boger", "lydbog", "ebog", "netlydbog", "billedbog", "tegneserie", "tidsskrift", "avis", "artikel",
            "film", "dvd", "bluray", "cd", "musik", "node", "noder", "spil", "konsolspil", "braetspil", "kort", "saet",
        )
        private val DIGITS = Regex("""(?<![0-9])[0-9](?:[ \-]?[0-9]){7,15}(?![0-9])""")
    }

    private class Candidate(val value: String, val barcode: Boolean, var lastSeen: Long, var hits: Int) {
        var clean = 0      // set uden bindestreg/mellemrum
        var isbnHint = false
    }

    // ---------- CQL-søgning ----------
    private val cqlMode by lazy { intent.getStringExtra(EXTRA_MODE) == MODE_CQL }
    /** En tekstlinje; samme tekst læst lidt forskelligt (fx "på"/"pa") samles under én nøgle */
    private class Line(val key: String, val firstSeen: Long, var lastSeen: Long, var hits: Int, var text: String) {
        val keys = HashSet<String>().apply { add(key) }
        val variants = HashMap<String, Int>()
        /**
         * Den bedste udgave: blandt dem, der er læst mindst to gange, den længste (delvise læsninger er kortere) –
         * ved lighed den, der er læst flest gange, og så den med æ/ø/å
         */
        /**
         * Den udgave, flest læsninger støtter: en læsning støtter de udgaver, den er en del af (en delvis læsning
         * støtter hele linjen, og alle stykker støtter den sammensatte). En fejllæsning ("tralktor") støtter ikke den
         * rigtige ("traktor") og taber derfor, så snart den rigtige er læst oftere. Ved lighed: den længste, så den med æ/ø/å.
         */
        fun best(fold: (String) -> String): String {
            val all = (variants.keys + text).distinct()
            val folded = all.associateWith(fold)
            fun score(v: String) = variants.entries.sumOf { (o, n) -> if (folded[v]!!.contains(folded[o]!!)) n else 0 }
            return all.maxWithOrNull(
                compareBy<String> { score(it) }.thenBy { it.length }.thenBy { v -> v.count { it in "æøåÆØÅ" } },
            ) ?: key
        }
    }
    private class Pick(var text: String, var type: String)
    private val lines = LinkedHashMap<String, Line>()
    private val picks = ArrayList<Pick>()
    private val numTypeChoice = HashMap<String, String>()
    private lateinit var picksBox: LinearLayout
    private lateinit var picksScroll: android.widget.ScrollView
    private lateinit var pickHeader: TextView
    private lateinit var searchAll: TextView
    private lateinit var cqlPreview: TextView

    private lateinit var executor: ExecutorService
    // Lukkes skærmen midt i en genkendelse, må det sene svar ikke få app'en til at gå ned
    private val safe = java.util.concurrent.Executor { r -> try { executor.execute(r) } catch (_: Exception) {} }
    private lateinit var preview: PreviewView
    private var controller: LifecycleCameraController? = null
    // Sigte-rammen i billedets (skærmens) koordinater – kun det, der står inden for den, tæller
    @Volatile private var frameRect: Rect? = null
    private lateinit var list: LinearLayout
    private lateinit var hint: TextView
    private var torch = false
    private var dark = true
    private lateinit var tc: ToolColors
    private val candidates = LinkedHashMap<String, Candidate>()
    private var lastUiUpdate = 0L
    private var notReadyLogged = false
    private var zoomed = true
    // Den længde jeres materialenumre plejer at have (lært fra læste tags); null = ikke set endnu
    private val itemLen by lazy { Hub.itemLength() }
    private lateinit var camBox: FrameLayout
    private val nfc by lazy { NfcAdapter.getDefaultAdapter(this) }
    // Sigte-rammens størrelse på skærmen
    private val frameW get() = dp(if (cqlMode) 330 else 300)
    private val frameH get() = dp(if (cqlMode) 110 else 140)

    private val barcodes by lazy { BarcodeScanning.getClient() }
    private val texts by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else { hint.text = "Kameraet er ikke tilladt – giv lov i telefonens indstillinger"; }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = intent.getBooleanExtra(EXTRA_DARK, true)
        tc = ToolColors(dark)
        val bg = tc.bg
        val fg = tc.fg
        val sub = tc.sub
        executor = Executors.newSingleThreadExecutor()

        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        // Sigte-ramme midt i billedet
        val frame = View(this).apply {
            background = GradientDrawable().apply {
                setStroke(dp(3), Color.WHITE)
                cornerRadius = dp(12).toFloat()
            }
        }
        camBox = FrameLayout(this).apply {
            addView(preview, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(frame, FrameLayout.LayoutParams(frameW, frameH, Gravity.CENTER))
        }
        frame.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ -> frameRect = Rect(l, t, r, b) }
        hint = TextView(this).apply {
            text = if (cqlMode) "Sigt på én ting ad gangen – titel, forfatter, faust, ISBN … Tryk ＋ for at gemme en linje, eller Søg nu. Tryk på en gemt linje for at rette eller flette den."
            else "Hold materialenummeret inden for rammen – stregkode eller tal. Tryk på billedet for skarpt billede, og vælg så nummeret nedenfor."
            textSize = 15f
            setTextColor(sub)
            setPadding(dp(16), dp(10), dp(16), dp(6))
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(12))
        }
        fun smallButton(label: String, action: (TextView) -> Unit) = TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(fg)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setOnClickListener { action(this) }
        }
        bottom.addView(smallButton("Annuller") { finish() })
        bottom.addView(smallButton("Lys til") { b ->
            torch = !torch
            controller?.enableTorch(torch)
            b.text = if (torch) "Lys fra" else "Lys til"
        })
        // Til CQL-søgning starter kameraet uden zoom, så der er plads til lange titler
        zoomed = !cqlMode
        bottom.addView(smallButton(if (zoomed) "Zoom 1×" else "Zoom 2×") { b ->
            zoomed = !zoomed
            applyZoom()
            b.text = if (zoomed) "Zoom 1×" else "Zoom 2×"
        })
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            fitsSystemWindows = true
            if (cqlMode) {
                // Fast størrelse: kamerabilledet lidt større end rammen, og listen over det, kameraet ser, har fast
                // plads til fire linjer – så intet hopper, mens listen skifter
                addView(camBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, frameH + dp(70)))
                addView(hint)
                addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4 * 62)))
                buildCqlParts(this)
            } else {
                addView(camBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(hint)
                addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(230)))
            }
            addView(bottom)
        }
        setContentView(root)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * Kameraet styres af CameraX' egen controller: den sørger for tryk-for-skarpt, og genkendelsen får
     * placeringen af hvert nummer direkte i skærmens koordinater – så det kan afgøres præcist, om det står i rammen.
     */
    private fun startCamera() {
        try {
            val c = LifecycleCameraController(this)
            c.cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            c.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            c.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            // Større billede til genkendelsen = skarpere tal
            c.imageAnalysisResolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build()
            c.isTapToFocusEnabled = true
            c.setImageAnalysisAnalyzer(
                executor,
                MlKitAnalyzer(listOf(barcodes, texts), ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED, safe) { r -> handle(r) },
            )
            preview.controller = c
            c.bindToLifecycle(this)
            controller = c
            // 2× zoom fra start: telefonen holdes længere væk, hvor den kan stille skarpt (og fri af chippen)
            c.initializationFuture.addListener({ applyZoom() }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            LogBuf.add("Kamera kunne ikke starte: ${e.message}")
            hint.text = "Kameraet kunne ikke starte"
        }
    }

    private fun applyZoom() {
        val c = controller ?: return
        val max = c.zoomState.value?.maxZoomRatio ?: 1f
        c.setZoomRatio(if (zoomed) minOf(2f, max) else 1f)
    }

    private fun inFrame(r: Rect?): Boolean {
        val f = frameRect ?: return false
        return r != null && f.contains(r.centerX(), r.centerY())
    }

    private fun handle(r: MlKitAnalyzer.Result) {
        val now = SystemClock.elapsedRealtime()
        r.getValue(barcodes)?.forEach { b ->
            val v = b.rawValue?.trim() ?: return@forEach
            if (inFrame(b.boundingBox) && v.length in 1..16 && v.all { it.code in 0x21..0x7E }) add(v, true, now, true, false)
        }
        val text = r.getValue(texts)
        if (text != null) {
            for (block in text.textBlocks) for (line in block.lines) {
                if (cqlMode) {
                    // Kun linjer, der står helt inde i rammen – halve linjer i kanten giver skæve læsninger
                    if (wholeInFrame(line.boundingBox)) scanLineCql(line, now)
                    continue
                }
                if (!inFrame(line.boundingBox)) continue
                val isbnLine = line.text.contains("ISBN", ignoreCase = true)
                for (m in DIGITS.findAll(line.text)) {
                    val v = m.value.filter { it.isDigit() }
                    if (v.length in 8..16) add(v, false, now, m.value.all { it.isDigit() }, isbnLine)
                }
            }
        } else if (!notReadyLogged && r.getThrowable(texts) != null) {
            // Fx første gang, mens Play-tjenester stadig henter tekstgenkendelsen
            notReadyLogged = true
            LogBuf.add("Tekstgenkendelse ikke klar: ${r.getThrowable(texts)?.message}")
        }
        if (now - lastUiUpdate > 250) {
            lastUiUpdate = now
            runOnUiThread { showCandidates() }
        }
    }

    private fun add(v: String, barcode: Boolean, now: Long, clean: Boolean, isbnHint: Boolean) {
        synchronized(candidates) {
            val c = candidates.getOrPut(v) { Candidate(v, barcode, now, 0) }
            c.lastSeen = now; c.hits++
            if (clean) c.clean++
            if (isbnHint) c.isbnHint = true
        }
    }

    private fun isIsbn(c: Candidate) =
        (c.value.length == 13 && (c.value.startsWith("978") || c.value.startsWith("979"))) ||
            (c.isbnHint && c.value.length in setOf(10, 13))

    /**
     * 0 = ligner jeres materialenumre (rigtig længde, kun cifre), 1 = almindeligt nummer,
     * 2 = næppe et materialenummer (ISBN, dato eller tal med bindestreg/mellemrum). Alt vises – kun rækkefølgen ændres.
     */
    private fun tier(c: Candidate): Int = when {
        isIsbn(c) -> 2
        !c.barcode && c.clean == 0 -> 2
        itemLen != null && c.value.length == itemLen && c.value.all { it.isDigit() } -> 0
        else -> 1
    }

    private fun showCandidates() {
        if (cqlMode) { showCql(); return }
        val now = SystemClock.elapsedRealtime()
        val shown = synchronized(candidates) {
            // Listen følger billedet: et nummer, der ikke er set i rammen i 1½ sekund, forsvinder
            candidates.values.removeAll { now - it.lastSeen > 1500 }
            // Tal skal ses mindst 2 gange (stregkoder har kontrolciffer), og et tal, der er en del af
            // et længere nummer (fx uden første ciffer), vises ikke
            val sure = candidates.values.filter { it.barcode || it.hits >= 2 }
            sure.filter { c ->
                sure.none { o ->
                    o !== c && o.value.length > c.value.length && o.value.length - c.value.length <= 2 &&
                        o.value.contains(c.value) && tier(o) <= tier(c)
                }
            }
                .sortedWith(compareBy<Candidate> { tier(it) }.thenByDescending { it.barcode }.thenByDescending { it.hits })
                .take(3) // der er plads til tre knapper
        }
        // Knapperne bygges kun om, når listen ændrer sig – ellers kunne et tryk gå tabt midt i en ombygning
        val sig = shown.joinToString(";") { it.value + "|" + kindOf(it) }
        if (sig == lastSig) return
        lastSig = sig
        list.removeAllViews()
        if (shown.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Ingen numre fundet endnu"
                textSize = 15f
                setTextColor(tc.sub)
                setPadding(dp(4), dp(10), dp(4), dp(10))
            })
            return
        }
        shown.forEachIndexed { i, c ->
            // Det bedste bud er fyldt blåt; de andre har blå kant
            val best = i == 0
            val fg = if (best) Color.WHITE else tc.fg
            val accent = if (best) Color.WHITE else tc.blue
            val number = TextView(this).apply {
                text = c.value
                textSize = 22f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(fg)
            }
            val kind = TextView(this).apply {
                text = kindOf(c)
                textSize = 12f
                setTextColor(if (best) Color.argb(220, 255, 255, 255) else tc.sub)
            }
            val use = TextView(this).apply {
                text = "Brug  ✓"
                textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(accent)
            }
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(if (best) tc.blue else tc.card)
                    if (!best) setStroke(dp(2), tc.blue)
                    cornerRadius = dp(12).toFloat()
                }
                setPadding(dp(16), dp(10), dp(16), dp(10))
                isClickable = true
                contentDescription = "Brug ${c.value}"
                addView(LinearLayout(this@ScanActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(number)
                    addView(kind)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(use)
                setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> v.alpha = 0.7f
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.alpha = 1f
                    }
                    false
                }
                setOnClickListener {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_VALUE, c.value))
                    finish()
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(5), 0, dp(5))
            })
        }
    }

    private var lastSig: String? = null

    private fun kindOf(c: Candidate) = when {
        isIsbn(c) -> "ISBN"
        tier(c) == 2 -> "andet tal"
        c.barcode -> "stregkode"
        else -> "tal"
    }

    // ---------- CQL-søgning ----------

    private fun wholeInFrame(r: Rect?): Boolean {
        val f = frameRect ?: return false
        if (r == null) return false
        val m = dp(8)
        return r.left >= f.left - m && r.right <= f.right + m && r.top >= f.top - m && r.bottom <= f.bottom + m
    }

    /**
     * En linje deles, hvor der er et stort mellemrum mellem to ord (fx "9788711… Bog" i en tabel på en skærm).
     * Et enkelt tegn, der står langt fra resten, er typisk en tjekboks læst som "O" – det springes over.
     * Numre (faust, materialenummer, ISBN) tages fra; resten er en tekstlinje.
     */
    private fun scanLineCql(line: com.google.mlkit.vision.text.Text.Line, now: Long) {
        val els = line.elements.filter { it.boundingBox != null }.sortedBy { it.boundingBox!!.left }
        if (els.isEmpty()) return
        val h = els.map { it.boundingBox!!.height() }.sorted()[els.size / 2].coerceAtLeast(1)
        val segs = ArrayList<MutableList<com.google.mlkit.vision.text.Text.Element>>()
        var cur = mutableListOf(els[0])
        for (i in 1 until els.size) {
            val gap = els[i].boundingBox!!.left - els[i - 1].boundingBox!!.right
            if (gap > h * 1.3f) { segs += cur; cur = mutableListOf() }
            cur += els[i]
        }
        segs += cur
        for (seg in segs) {
            val t = seg.joinToString(" ") { it.text }.trim()
            if (seg.size == 1 && t.length == 1) continue
            val isbnHint = t.contains("ISBN", ignoreCase = true)
            var rest = t
            for (m in DIGITS.findAll(t)) {
                val v = m.value.filter { it.isDigit() }
                if (v.length in 8..16) {
                    add(v, false, now, m.value.all { it.isDigit() }, isbnHint)
                    rest = rest.replace(m.value, " ")
                }
            }
            addLine(rest.replace(Regex("(?i)isbn[:.]?"), " "), now)
        }
    }

    private fun addLine(raw: String, now: Long) {
        val t = raw.replace(Regex("\\s+"), " ").trim().trim(':', ';', ',', '-', '–', '•', '|', '*', '/', ' ')
        if (t.length !in 2..80) return
        val k = normKey(t)
        if (k.length < 2 || k in STOP_WORDS) return
        // Lange tal klares som numre (faust/materialenummer/ISBN); korte tal (fx titlen "1984") er tekst
        if (k.all { it.isDigit() } && k.length >= 8) return
        // Et nummer, der er læst forkert (fx "S49157e393"): mest cifre og intet mellemrum – ikke en tekstlinje
        if (k.length >= 6 && !t.contains(' ') && k.count { it.isDigit() } * 10 >= k.length * 6) return
        synchronized(lines) {
            // Samme linje læst delvist eller lidt forskelligt samles under én
            // ... eller stykker af en lang linje, der overlapper hinanden ("Yesteryea" + "steryear: en beretn" + …)
            val l = lines[k] ?: lines.values.firstOrNull { stitch(it.text, t) != null || similar(it.keys, k) }
                ?: Line(k, now, now, 0, t).also { lines[k] = it }
            stitch(l.text, t)?.let { l.text = it }
            l.keys += k
            l.lastSeen = now; l.hits++
            l.variants[t] = (l.variants[t] ?: 0) + 1
        }
    }

    /** Et tegn uden accent og med små bogstaver – samme længde som før, så positioner passer (å → a, ø → o, æ → a) */
    private fun fold(s: String): String = buildString(s.length) {
        for (c in s.lowercase()) append(
            when (c) {
                'ø' -> 'o'
                'æ' -> 'a'
                else -> java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD).firstOrNull() ?: c
            },
        )
    }

    /** Største overlap, hvor slutningen af a er begyndelsen af b */
    private fun overlap(a: String, b: String): Int {
        for (k in minOf(a.length, b.length) downTo 1) if (a.regionMatches(a.length - k, b, 0, k)) return k
        return 0
    }

    /**
     * Sætter to læsninger af samme linje sammen: er den ene indeholdt i den anden, bruges den længste; overlapper
     * slutningen af den ene med begyndelsen af den anden (mindst 6 tegn og en tredjedel af den korteste), lægges de
     * sammen. Ellers null (ikke samme linje).
     */
    private fun stitch(a: String, b: String): String? {
        val fa = fold(a)
        val fb = fold(b)
        if (fa == fb) return if (b.count { it in "æøåÆØÅ" } > a.count { it in "æøåÆØÅ" }) b else a
        if (fa.contains(fb)) return a
        if (fb.contains(fa)) return b
        val need = maxOf(6, minOf(fa.length, fb.length) / 3)
        val ab = overlap(fa, fb)
        if (ab >= need) return a + b.substring(ab)
        val ba = overlap(fb, fa)
        if (ba >= need) return b + a.substring(ba)
        return null
    }

    /** Ligner to læsninger hinanden nok til at være samme linje (den ene en del af den anden, eller stort overlap)? */
    private fun similar(keys: Collection<String>, k: String): Boolean = keys.any { o ->
        val short = if (o.length <= k.length) o else k
        val long = if (o.length <= k.length) k else o
        if (short.length < 4) return@any short == long
        (long.contains(short) && short.length * 2 >= long.length) || commonRun(short, long) >= maxOf(5, (short.length * 0.7).toInt())
    }

    /** Længste fælles stykke af to tekster */
    private fun commonRun(a: String, b: String): Int {
        var best = 0
        val prev = IntArray(b.length + 1)
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                cur[j] = if (a[i - 1] == b[j - 1]) prev[j - 1] + 1 else 0
                if (cur[j] > best) best = cur[j]
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return best
    }

    /** Sammenligningsnøgle: små bogstaver, uden accenter (å → a) og uden tegn/mellemrum */
    private fun normKey(t: String): String =
        java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD)
            .replace("ø", "o").replace("æ", "ae")
            .filter { it.isLetterOrDigit() }

    /** ISBN (978/979 + 13 cifre, eller 10 cifre ved "ISBN"), materialenummer (jeres længde) eller faust (8-9 cifre) */
    private fun numType(c: Candidate): String? {
        val v = c.value
        if (!v.all { it.isDigit() }) return null
        numTypeChoice[v]?.let { return it }
        return when {
            v.length == 13 && (v.startsWith("978") || v.startsWith("979")) -> "isbn"
            v.length == 10 && c.isbnHint -> "isbn"
            itemLen != null && v.length == itemLen -> "bmn"
            v.length in 8..9 -> "faust"
            else -> null
        }
    }

    private fun isNumType(type: String) = type == "faust" || type == "bmn" || type == "isbn"

    /** Værdien, som den skrives i søgningen: faust og materialenummer som tal, ISBN og tekst i anførselstegn */
    private fun valueOf(text: String, type: String): String = when (type) {
        "faust", "bmn" -> text.filter { it.isDigit() }
        "isbn" -> "\"" + text.filter { it.isDigit() } + "\""
        else -> "\"" + text.replace('"', ' ').replace(Regex("\\s+"), " ").trim() + "\""
    }

    private fun codeOf(type: String): String? = when (type) {
        "title" -> "term.title"
        "creator" -> "term.creator"
        "dk5" -> "dk5"
        "subject" -> "term.subject"
        "faust" -> "faust"
        "bmn" -> "bmn"
        "isbn" -> "term.isbn"
        else -> null // fritekst
    }

    private fun cqlOf(text: String, type: String): String =
        codeOf(type)?.let { it + "=" + valueOf(text, type) } ?: valueOf(text, type)

    /** Samme type samles med OR i parentes, forskellige typer med AND; fritekst-linjer hver for sig med AND */
    private fun cqlOfPicks(): String {
        val parts = ArrayList<String>()
        val groups = LinkedHashMap<String, MutableList<Pick>>()
        for (p in picks) groups.getOrPut(p.type) { ArrayList() } += p
        for ((type, ps) in groups) {
            val code = codeOf(type)
            if (code == null) { ps.forEach { parts += valueOf(it.text, type) }; continue }
            parts += if (ps.size == 1) code + "=" + valueOf(ps[0].text, type)
            else code + "=(" + ps.joinToString(" OR ") { valueOf(it.text, type) } + ")"
        }
        return parts.joinToString(" AND ")
    }

    private fun finishWith(cql: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_VALUE, cql))
        finish()
    }

    private fun pill(label: String, filled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setTextColor(if (filled) Color.WHITE else tc.blue)
        background = GradientDrawable().apply {
            setColor(if (filled) tc.blue else Color.TRANSPARENT)
            setStroke(dp(2), tc.blue)
            cornerRadius = dp(18).toFloat()
        }
        setPadding(dp(12), dp(7), dp(12), dp(7))
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View, first: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { setColor(tc.card); cornerRadius = dp(10).toFloat() }
        setPadding(dp(12), dp(6), dp(8), dp(6))
        addView(first, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        views.forEach { addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) }) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(3), 0, dp(3)) }
    }

    private fun label(t: String, size: Float = 16f) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(tc.fg)
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun buildCqlParts(root: LinearLayout) {
        list.setPadding(dp(12), 0, dp(12), dp(4))
        pickHeader = TextView(this).apply {
            text = "Valgt"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(tc.blue)
            setPadding(dp(16), dp(6), dp(16), dp(2))
            visibility = View.INVISIBLE
        }
        picksBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
        picksScroll = android.widget.ScrollView(this).apply { addView(picksBox); visibility = View.INVISIBLE }
        searchAll = pill("Søg", true) {
            if (picks.isNotEmpty()) finishWith(cqlOfPicks())
        }.apply { visibility = View.INVISIBLE }
        cqlPreview = TextView(this).apply {
            textSize = 12f
            setTextColor(tc.sub)
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(16), dp(4), dp(16), 0)
            visibility = View.INVISIBLE
        }
        root.addView(pickHeader)
        root.addView(picksScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(cqlPreview)
        root.addView(searchAll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12), dp(6), dp(12), 0) })
    }

    private var lastCqlSig: String? = null

    // Fire faste pladser på skærmen: en linje beholder sin plads, til den forsvinder; nye linjer tager en ledig plads
    private val slots = arrayOfNulls<String>(4)
    private val rowH get() = dp(56)

    private fun showCql() {
        val now = SystemClock.elapsedRealtime()
        val picked = picks.map { it.text }.toSet()
        val pickedKeys = picks.map { normKey(it.text) }
        // Numre og tekst blandes ikke i én søgning
        val canNum = picks.none { !isNumType(it.type) }
        val canText = picks.none { isNumType(it.type) }
        // Det, der kan vises nu: id -> (sorteringstid, visning)
        val alive = LinkedHashMap<String, Pair<Long, Pair<String, String?>>>()
        synchronized(candidates) {
            candidates.values.removeAll { now - it.lastSeen > 3000 }
            val sure = candidates.values.filter { (it.barcode || it.hits >= 2) && numType(it) != null && it.value !in picked }
            // Et nummer, der kun er en del af et andet (fx uden første ciffer), vises ikke
            sure.filter { c -> sure.none { o -> o !== c && o.value.length > c.value.length && o.value.length - c.value.length <= 2 && o.value.contains(c.value) } }
                .filter { c -> picked.none { it.length > c.value.length && it.length - c.value.length <= 2 && it.contains(c.value) } }
                .forEach { c -> alive["n:" + c.value] = 0L to (c.value to numType(c)) }
        }
        synchronized(lines) {
            lines.values.removeAll { now - it.lastSeen > 3000 }
            lines.values.filter { it.hits >= 3 && pickedKeys.none { k -> similar(it.keys, k) } }
                .forEach { l -> alive["t:" + l.key] = l.firstSeen to (l.best(::fold) to null) }
        }
        // Pladser, hvis linje er væk, bliver ledige; nye linjer fylder ledige pladser i den rækkefølge, de blev set
        for (i in slots.indices) { val sid = slots[i]; if (sid != null && !alive.containsKey(sid)) slots[i] = null }
        for ((id, _) in alive.entries.sortedBy { it.value.first }) {
            if (id in slots) continue
            val free = slots.indexOfFirst { it == null }
            if (free < 0) break
            slots[free] = id
        }
        val sig = slots.joinToString(";") { id -> id?.let { it + "=" + alive[it]?.second } ?: "-" } + "#" + canNum + canText
        if (sig == lastCqlSig) return
        lastCqlSig = sig
        list.removeAllViews()
        val empty = slots.all { it == null }
        for (i in slots.indices) {
            val id = slots[i]
            val shown = id?.let { alive[it]?.second }
            val v: View = when {
                shown == null && empty && i == 0 -> TextView(this).apply {
                    text = "Ingen tekst fundet endnu"
                    textSize = 15f
                    setTextColor(tc.sub)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), 0, dp(4), 0)
                }
                shown == null -> View(this)
                shown.second != null -> numRow(shown.first, shown.second!!, canNum)
                else -> textRow(shown.first, canText)
            }
            list.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowH).apply { setMargins(0, dp(3), 0, dp(3)) })
        }
    }

    private fun numRow(n: String, type: String, canNum: Boolean): View {
        val chip = pill(NUM_TYPES[type] ?: type, false) {}
        chip.setOnClickListener {
            // Forkert type: vælg en anden
            val m = android.widget.PopupMenu(this, chip)
            NUM_TYPES.entries.forEachIndexed { i, e -> m.menu.add(0, i, i, e.value) }
            m.setOnMenuItemClickListener { item ->
                numTypeChoice[n] = NUM_TYPES.keys.elementAt(item.itemId)
                lastCqlSig = null; showCql(); true
            }
            m.show()
        }
        return row(
            chip,
            pill("Søg nu", false) { finishWith(cqlOf(n, type)) },
            addPill(canNum) {
                picks += Pick(n, type)
                rebuildPicks()
                lastCqlSig = null; showCql()
            },
            first = label(n, 18f),
        )
    }

    private fun textRow(t: String, canText: Boolean): View = row(
        pill("Søg nu", false) { finishWith(cqlOf(t, "free")) },
        addPill(canText) {
            picks += Pick(t, "free")
            rebuildPicks()
            lastCqlSig = null; showCql()
        },
        first = label(t),
    )

    /** ＋ – grå og uden virkning, når linjen ikke kan kombineres med det, der allerede er valgt */
    private fun addPill(enabled: Boolean, onClick: () -> Unit) =
        pill("＋", true) { if (enabled) onClick() }.apply { alpha = if (enabled) 1f else 0.3f }

    private fun rebuildPicks() {
        picksBox.removeAllViews()
        val any = picks.isNotEmpty()
        pickHeader.visibility = if (any) View.VISIBLE else View.INVISIBLE
        picksScroll.visibility = if (any) View.VISIBLE else View.INVISIBLE
        searchAll.visibility = if (any) View.VISIBLE else View.INVISIBLE
        searchAll.text = "Søg (" + picks.size + " valgt" + (if (picks.size == 1) "" else "e") + ")"
        cqlPreview.visibility = if (any) View.VISIBLE else View.INVISIBLE
        cqlPreview.text = if (any) cqlOfPicks() else ""
        for (p in picks.toList()) {
            val text = label(p.text).apply { setOnClickListener { editPick(p) } }
            val types = if (isNumType(p.type)) NUM_TYPES else TEXT_TYPES
            val typeChip = pill(types[p.type] ?: p.type, false) {}
            typeChip.setOnClickListener {
                val m = android.widget.PopupMenu(this, typeChip)
                types.entries.forEachIndexed { i, e -> m.menu.add(0, i, i, e.value) }
                m.setOnMenuItemClickListener { item ->
                    p.type = types.keys.elementAt(item.itemId)
                    rebuildPicks(); true
                }
                m.show()
            }
            picksBox.addView(row(
                typeChip,
                pill("Søg nu", false) { finishWith(cqlOf(p.text, p.type)) },
                pill("✕", false) { picks.remove(p); rebuildPicks(); lastCqlSig = null; showCql() },
                first = text,
            ))
        }
    }

    /** Ret en linje, kameraet har læst lidt forkert – eller flet den med linjen over (fx en titel på to linjer) */
    private fun editPick(p: Pick) {
        val idx = picks.indexOf(p)
        val above = if (idx > 0 && isNumType(picks[idx - 1].type) == isNumType(p.type)) picks[idx - 1] else null
        val input = android.widget.EditText(this).apply {
            setText(p.text)
            setSelection(p.text.length)
            setSingleLine(true)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Ret teksten")
            .setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) })
            .setPositiveButton("OK") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) { p.text = t; rebuildPicks() }
            }
            .setNegativeButton("Annuller", null)
            .apply {
                if (above != null) setNeutralButton("Flet med linjen over") { _, _ ->
                    above.text = above.text + " " + input.text.toString().trim()
                    picks.remove(p)
                    rebuildPicks(); lastCqlSig = null; showCql()
                }
            }
            .show()
    }

    // Holder på NFC, mens kameraet er fremme – ellers melder Android "ingen understøttede apps", når bogen er tæt på
    override fun onTagDiscovered(tag: android.nfc.Tag?) {}

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        try {
            nfc?.enableReaderMode(this, this,
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                null)
        } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        try { nfc?.disableReaderMode(this) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        try { barcodes.close(); texts.close() } catch (_: Exception) {}
    }
}

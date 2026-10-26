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
        private val NUM_TYPES = linkedMapOf("faust" to "Faust", "bmn" to "Materialenr.")
        private val DIGITS = Regex("""(?<![0-9])[0-9](?:[ \-]?[0-9]){7,15}(?![0-9])""")
    }

    private class Candidate(val value: String, val barcode: Boolean, var lastSeen: Long, var hits: Int) {
        var clean = 0      // set uden bindestreg/mellemrum
        var isbnHint = false
    }

    // ---------- CQL-søgning ----------
    private val cqlMode by lazy { intent.getStringExtra(EXTRA_MODE) == MODE_CQL }
    private class Line(val text: String, var lastSeen: Long, var hits: Int)
    private class Pick(var text: String, var type: String)
    private val lines = LinkedHashMap<String, Line>()
    private val picks = ArrayList<Pick>()
    private val numTypeChoice = HashMap<String, String>()
    private lateinit var picksBox: LinearLayout
    private lateinit var picksScroll: android.widget.ScrollView
    private lateinit var pickHeader: TextView
    private lateinit var searchAll: TextView

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
    private val frameW get() = dp(300)
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
            text = if (cqlMode) "Sigt på én ting ad gangen – titel, forfatter, faust … Tryk ＋ for at gemme en linje, eller Søg nu."
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
        bottom.addView(smallButton("Zoom 1×") { b ->
            zoomed = !zoomed
            applyZoom()
            b.text = if (zoomed) "Zoom 1×" else "Zoom 2×"
        })
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            fitsSystemWindows = true
            addView(camBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(hint)
            if (cqlMode) {
                addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                buildCqlParts(this)
            } else {
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
                if (!inFrame(line.boundingBox)) continue
                if (cqlMode) addLine(line.text, now)
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

    private fun addLine(raw: String, now: Long) {
        val t = raw.replace(Regex("\\s+"), " ").trim()
        if (t.length !in 2..80) return
        // Rene tal klares som numre (faust/materialenummer)
        if (t.replace(Regex("[ -]"), "").all { it.isDigit() }) return
        synchronized(lines) {
            val l = lines.getOrPut(t) { Line(t, now, 0) }
            l.lastSeen = now; l.hits++
        }
    }

    /** Faust (8-9 cifre) eller materialenummer (jeres længde); andre tal tilbydes ikke som numre */
    private fun numType(v: String): String? {
        if (!v.all { it.isDigit() }) return null
        numTypeChoice[v]?.let { return it }
        return when {
            itemLen != null && v.length == itemLen -> "bmn"
            v.length in 8..9 -> "faust"
            else -> null
        }
    }

    private fun cqlOf(text: String, type: String): String {
        val t = text.replace('"', ' ').replace(Regex("\\s+"), " ").trim()
        return when (type) {
            "title" -> "term.title=\"" + t + "\""
            "creator" -> "term.creator=\"" + t + "\""
            "dk5" -> "dk5=\"" + t + "\""
            "subject" -> "term.subject=\"" + t + "\""
            "faust" -> "faust=" + t.filter { it.isDigit() }
            "bmn" -> "bmn=" + t.filter { it.isDigit() }
            else -> "\"" + t + "\""
        }
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
            visibility = View.GONE
        }
        picksBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
        picksScroll = android.widget.ScrollView(this).apply { addView(picksBox); visibility = View.GONE }
        searchAll = pill("Søg", true) {
            if (picks.isNotEmpty()) finishWith(picks.joinToString(" AND ") { cqlOf(it.text, it.type) })
        }.apply { visibility = View.GONE }
        root.addView(pickHeader)
        root.addView(picksScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150)))
        root.addView(searchAll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12), dp(6), dp(12), 0) })
    }

    private var lastCqlSig: String? = null

    private fun showCql() {
        val now = SystemClock.elapsedRealtime()
        val nums = synchronized(candidates) {
            candidates.values.removeAll { now - it.lastSeen > 1500 }
            candidates.values.filter { (it.barcode || it.hits >= 2) && numType(it.value) != null }
                .sortedByDescending { it.hits }.take(2).map { it.value }
        }
        val picked = picks.map { it.text }.toSet()
        val texts = synchronized(lines) {
            lines.values.removeAll { now - it.lastSeen > 1500 }
            lines.values.filter { it.hits >= 2 && it.text !in picked }.sortedByDescending { it.hits }.take(4 - nums.size).map { it.text }
        }
        val sig = nums.joinToString(";") { it + "|" + numType(it) } + "#" + texts.joinToString(";")
        if (sig == lastCqlSig) return
        lastCqlSig = sig
        list.removeAllViews()
        for (n in nums) {
            val type = numType(n) ?: continue
            list.addView(row(
                pill(NUM_TYPES[type] ?: type, false) {
                    // Forkert type: skift mellem Faust og Materialenr.
                    numTypeChoice[n] = if (type == "faust") "bmn" else "faust"
                    lastCqlSig = null; showCql()
                },
                pill("Søg nu", true) { finishWith(cqlOf(n, numType(n) ?: type)) },
                first = label(n, 18f),
            ))
        }
        for (t in texts) {
            list.addView(row(
                pill("Søg nu", false) { finishWith(cqlOf(t, "free")) },
                pill("＋", true) {
                    picks += Pick(t, "free")
                    rebuildPicks()
                    lastCqlSig = null; showCql()
                },
                first = label(t),
            ))
        }
        if (nums.isEmpty() && texts.isEmpty()) list.addView(TextView(this).apply {
            text = "Ingen tekst fundet endnu"
            textSize = 15f
            setTextColor(tc.sub)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        })
    }

    private fun rebuildPicks() {
        picksBox.removeAllViews()
        val any = picks.isNotEmpty()
        pickHeader.visibility = if (any) View.VISIBLE else View.GONE
        picksScroll.visibility = if (any) View.VISIBLE else View.GONE
        searchAll.visibility = if (any) View.VISIBLE else View.GONE
        searchAll.text = "Søg (" + picks.size + " valgt" + (if (picks.size == 1) "" else "e") + ")"
        for (p in picks.toList()) {
            val text = label(p.text).apply { setOnClickListener { editPick(p) } }
            val typeChip = pill(TEXT_TYPES[p.type] ?: p.type, false) {}
            typeChip.setOnClickListener {
                val m = android.widget.PopupMenu(this, typeChip)
                TEXT_TYPES.entries.forEachIndexed { i, e -> m.menu.add(0, i, i, e.value) }
                m.setOnMenuItemClickListener { item ->
                    p.type = TEXT_TYPES.keys.elementAt(item.itemId)
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

    /** Ret en linje, kameraet har læst lidt forkert */
    private fun editPick(p: Pick) {
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

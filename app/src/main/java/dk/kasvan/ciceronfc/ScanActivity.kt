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
        private val DIGITS = Regex("""(?<![0-9])[0-9](?:[ \-]?[0-9]){7,15}(?![0-9])""")
    }

    private class Candidate(val value: String, val barcode: Boolean, var lastSeen: Long, var hits: Int) {
        var clean = 0      // set uden bindestreg/mellemrum
        var isbnHint = false
    }

    private lateinit var executor: ExecutorService
    // Lukkes skærmen midt i en genkendelse, må det sene svar ikke få app'en til at gå ned
    private val safe = java.util.concurrent.Executor { r -> try { executor.execute(r) } catch (_: Exception) {} }
    private lateinit var preview: PreviewView
    private var controller: LifecycleCameraController? = null
    // Sigte-rammen i billedets (skærmens) koordinater – kun det, der står inden for den, tæller
    @Volatile private var frameRect: Rect? = null
    private lateinit var list: LinearLayout
    private lateinit var hint: TextView
    private var dark = true
    private lateinit var tc: ToolColors
    private val candidates = LinkedHashMap<String, Candidate>()
    private var lastUiUpdate = 0L
    private var notReadyLogged = false
    private lateinit var controls: CameraControls
    // Den længde jeres materialenumre plejer at have (lært fra læste tags); null = ikke set endnu
    private val itemLen by lazy { Hub.itemLength() }
    private lateinit var camBox: FrameLayout
    private val nfc by lazy { NfcAdapter.getDefaultAdapter(this) }
    // Sigte-rammens størrelse på skærmen
    private val frameW get() = dp(300)
    private val frameH get() = dp(140)

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
            text = "Hold materialenummeret inden for rammen – stregkode eller tal. Tryk på billedet for skarpt billede, og vælg så nummeret nedenfor."
            textSize = 15f
            setTextColor(sub)
            setPadding(dp(16), dp(10), dp(16), dp(6))
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        // ✕, lys og zoom oven på billedet – som i telefonens eget kamera (2× fra start: telefonen holdes længere
        // væk, hvor den kan stille skarpt, og fri af chippen)
        controls = CameraControls(this, camBox, 2f) { finish() }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            fitsSystemWindows = true
            addView(camBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(hint)
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(230)))
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
            controls.attach(c)
        } catch (e: Exception) {
            LogBuf.add("Kamera kunne ikke starte: ${e.message}")
            hint.text = "Kameraet kunne ikke starte"
        }
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

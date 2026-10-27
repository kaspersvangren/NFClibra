package dk.kasvan.ciceronfc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.nfc.NfcAdapter
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Kamera til Ciceros CQL-søgning – med stillbillede: man sigter og tager et billede, app'en læser det én gang og
 * viser det frosset med de fundne linjer og numre markeret. Man trykker på det, man vil søge på; det lægges under
 * "Valgt", hvor typen vælges (titel, forfatter …), teksten kan rettes eller flettes med linjen over, og der søges
 * på én eller dem alle. Flere billeder kan tages efter hinanden (fx titel på forsiden, ISBN på bagsiden).
 * Resultatet er en CQL-streng. Alt genkendes på telefonen; intet sendes nogen steder hen.
 */
class CqlScanActivity : LockedScreen(), NfcAdapter.ReaderCallback {
    companion object {
        const val EXTRA_DARK = "dark"
        const val EXTRA_CQL = "cql"
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
        /** Tegn, en tjekboks typisk læses som */
        private const val BOX_CHARS = "Oo0□☐■◻DQ"
        /** Tegn, der næsten aldrig står alene forrest i en titel eller et navn – kun tjekbokse læses sådan */
        private const val SURE_BOX = "□☐■◻DQ0"
        /** "Efternavn, Fornavn" – foreslås som Forfatter */
        private val NAME = Regex("""^\p{Lu}[\p{L}'’\-]+(?: \p{Lu}[\p{L}'’\-]+){0,3}, \p{Lu}[\p{L}.'’\- ]{0,40}$""")
    }

    /** Noget, der er fundet i billedet: en tekstlinje eller et nummer (numType sat) */
    private class Item(val text: String, val rect: RectF, val numType: String?) { var pick: Pick? = null }
    private class Pick(var text: String, var type: String, var item: Item?)

    private lateinit var tc: ToolColors
    private lateinit var preview: PreviewView
    private lateinit var still: ImageView
    private lateinit var marks: ItemsView
    private lateinit var hint: TextView
    private lateinit var shutter: FrameLayout
    private lateinit var shutterInner: FrameLayout
    private lateinit var retakeIcon: ImageView
    private lateinit var picksBox: LinearLayout
    private lateinit var pickHeader: TextView
    private lateinit var picksScroll: ScrollView
    private lateinit var cqlPreview: TextView
    private lateinit var searchAll: TextView
    private var controller: LifecycleCameraController? = null
    private lateinit var controls: CameraControls
    private var frozen = false
    private var busy = false
    private val picks = ArrayList<Pick>()
    private val itemLen by lazy { Hub.itemLength() }
    private val nfc by lazy { NfcAdapter.getDefaultAdapter(this) }
    private val barcodes by lazy { BarcodeScanning.getClient() }
    private val texts by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else hint.text = "Kameraet er ikke tilladt – giv lov i telefonens indstillinger"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tc = ToolColors(intent.getBooleanExtra(EXTRA_DARK, true))
        val match = LinearLayout.LayoutParams.MATCH_PARENT
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT

        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        still = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; visibility = View.GONE }
        marks = ItemsView(this)
        // Udløser som i telefonens kamera: rund knap nederst på skærmen, nem at nå med tommelfingeren. Når billedet er taget: ↺ = nyt billede
        // ↺ som ikon (ikke tegn), så den står præcis i midten
        retakeIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_menu_reload)
            imageTintList = android.content.res.ColorStateList.valueOf(tc.bg)
            visibility = View.INVISIBLE
        }
        shutterInner = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(tc.fg) }
            addView(retakeIcon, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        }
        shutter = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(dp(4), tc.fg) }
            addView(shutterInner, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
            contentDescription = "Tag billede"
            setOnClickListener { if (frozen) retake() else capture() }
        }
        val camBox = FrameLayout(this).apply {
            addView(preview, FrameLayout.LayoutParams(match, match))
            addView(still, FrameLayout.LayoutParams(match, match))
            addView(marks, FrameLayout.LayoutParams(match, match))
        }
        hint = TextView(this).apply {
            text = "Sigt på bogen eller skærmen, og tag et billede."
            textSize = 15f
            setTextColor(tc.sub)
        }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(4))
            addView(hint, LinearLayout.LayoutParams(0, wrap, 1f))
        }
        pickHeader = TextView(this).apply {
            text = "Valgt"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(tc.blue)
            setPadding(dp(16), dp(6), dp(16), dp(2))
            visibility = View.INVISIBLE
        }
        picksBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
        picksScroll = ScrollView(this).apply { addView(picksBox); visibility = View.INVISIBLE }
        cqlPreview = TextView(this).apply {
            textSize = 12f
            setTextColor(tc.sub)
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(16), dp(4), dp(16), 0)
            visibility = View.INVISIBLE
        }
        searchAll = pill("Søg", true) { if (picks.isNotEmpty()) finishWith(cqlOfPicks()) }.apply { visibility = View.INVISIBLE }
        // Lys, udløser og ✕ nederst ved tommelfingeren, zoom lige over – ens med kameraet til materialenumre
        controls = CameraControls(this, tc, 1f, shutter) { finish() }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(tc.bg)
            fitsSystemWindows = true
            // Billedet fylder halvdelen af skærmen, så linjerne er til at ramme med fingeren
            addView(camBox, LinearLayout.LayoutParams(match, (resources.displayMetrics.heightPixels * 0.48f).toInt()))
            addView(actionRow)
            addView(pickHeader)
            addView(picksScroll, LinearLayout.LayoutParams(match, 0, 1f))
            addView(cqlPreview)
            addView(searchAll, LinearLayout.LayoutParams(match, wrap).apply { setMargins(dp(12), dp(6), dp(12), 0) })
            addView(controls.panel, LinearLayout.LayoutParams(match, wrap))
        }
        setContentView(root)
        window.statusBarColor = tc.bg
        window.navigationBarColor = tc.bg

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        try {
            val c = LifecycleCameraController(this)
            c.cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            // Kun forhåndsvisning – billedet tages fra den, så markeringerne passer præcist med det, man så
            c.setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            c.isTapToFocusEnabled = true
            preview.controller = c
            c.bindToLifecycle(this)
            controller = c
            controls.attach(c)
        } catch (e: Exception) {
            LogBuf.add("Kamera kunne ikke starte: ${e.message}")
            hint.text = "Kameraet kunne ikke starte"
        }
    }

    // ---------- Billedet ----------

    private fun capture() {
        if (busy) return
        val bmp: Bitmap = preview.bitmap ?: run { hint.text = "Kameraet er ikke klar endnu – prøv igen"; return }
        busy = true
        frozen = true
        still.setImageBitmap(bmp)
        still.visibility = View.VISIBLE
        marks.items = emptyList()
        controls.liveControls(false)
        retakeIcon.visibility = View.VISIBLE
        shutter.contentDescription = "Nyt billede"
        hint.text = "Læser billedet …"
        val img = InputImage.fromBitmap(bmp, 0)
        val tText = texts.process(img)
        val tCodes = barcodes.process(img)
        Tasks.whenAllComplete(tText, tCodes).addOnCompleteListener(ContextCompat.getMainExecutor(this)) {
            busy = false
            if (!frozen || isFinishing) return@addOnCompleteListener
            val found = ArrayList<Item>()
            if (tCodes.isSuccessful) tCodes.result?.let { addBarcodes(it, found) }
            if (tText.isSuccessful) tText.result?.let { addText(it, found) }
            else LogBuf.add("Tekstgenkendelse fejlede: ${tText.exception?.message}")
            // Samme nummer fundet både som stregkode og som tal: kun én gang
            val seen = HashSet<String>()
            val items = found.filter { it.numType == null || seen.add(it.text) }
            // Det, der allerede er valgt fra et tidligere billede, vises som valgt
            for (it in items) picks.firstOrNull { p -> p.item == null && normKey(p.text) == normKey(it.text) }?.let { p -> it.pick = p; p.item = it }
            marks.items = items
            hint.text = if (items.isEmpty()) "Ingen tekst fundet – prøv et nyt billede, tættere på eller med lys"
            else "Tryk på det, du vil søge på."
        }
    }

    private fun retake() {
        frozen = false
        still.visibility = View.GONE
        still.setImageDrawable(null)
        picks.forEach { it.item = null }
        marks.items = emptyList()
        controls.liveControls(true)
        retakeIcon.visibility = View.INVISIBLE
        shutter.contentDescription = "Tag billede"
        hint.text = "Sigt på bogen eller skærmen, og tag et billede."
    }

    private fun addBarcodes(list: List<Barcode>, out: MutableList<Item>) {
        for (b in list) {
            val v = b.rawValue?.trim() ?: continue
            val box = b.boundingBox ?: continue
            if (!v.all { it.isDigit() }) continue
            val type = numType(v, false) ?: continue
            out += Item(v, RectF(box), type)
        }
    }

    /**
     * Hver linje deles, hvor der er et stort mellemrum mellem to ord (fx et nummer og "Bog" i en tabel på en skærm).
     * En tjekboks foran teksten (læst som "O") springes over, når der er tydelig luft mellem den og teksten –
     * "O brother" har almindelig ordafstand og bliver stående. Numre tages fra; resten er tekstlinjer.
     */
    private fun addText(t: Text, out: MutableList<Item>) {
        for (block in t.textBlocks) for (line in block.lines) {
            val els = line.elements.filter { it.boundingBox != null }.sortedBy { it.boundingBox!!.left }
            if (els.isEmpty()) continue
            val h = els.map { it.boundingBox!!.height() }.sorted()[els.size / 2].coerceAtLeast(1)
            val segs = ArrayList<MutableList<Pair<String, Rect>>>()
            var cur = ArrayList<Pair<String, Rect>>()
            var prevRight = Int.MIN_VALUE
            for ((i, e) in els.withIndex()) {
                var txt = e.text
                var box = Rect(e.boundingBox!!)
                // Tjekboks klistret på ordet ("OWillerslev"): første tegn står med tydelig luft til resten
                val sym = e.symbols
                if (txt.length > 1 && txt[0] in BOX_CHARS && sym.size >= 2) {
                    val a = sym[0].boundingBox
                    val b = sym[1].boundingBox
                    if (a != null && b != null && b.left - a.right > h * 0.35f) {
                        txt = txt.substring(1)
                        box = Rect(b.left, box.top, box.right, box.bottom)
                    }
                }
                // Tjekboks som eget "ord" foran teksten: sikre tegn (fx "D", "0") med bare lidt luft; "O" kun med tydelig
                // luft eller lav sikkerhed i læsningen ("O brother" har almindelig ordafstand og står stille)
                if (i == 0 && txt.length == 1 && txt[0] in BOX_CHARS && els.size > 1) {
                    val gap = els[1].boundingBox!!.left - box.right
                    if ((txt[0] in SURE_BOX && gap > h * 0.15f) || gap > h * 0.5f || e.confidence < 0.6f) continue
                }
                if (prevRight != Int.MIN_VALUE && box.left - prevRight > h * 1.3f) { segs += cur; cur = ArrayList() }
                cur += txt to box
                prevRight = box.right
            }
            if (cur.isNotEmpty()) segs += cur
            for (seg in segs) {
                val text = seg.joinToString(" ") { it.first }.trim()
                val r = RectF(seg.first().second)
                seg.forEach { r.union(RectF(it.second)) }
                if (seg.size == 1 && text.length == 1) continue
                val isbnHint = text.contains("ISBN", ignoreCase = true)
                var hadNumber = false
                for (m in DIGITS.findAll(text)) {
                    val v = m.value.filter { it.isDigit() }
                    val type = numType(v, isbnHint) ?: continue
                    out += Item(v, RectF(r), type)
                    hadNumber = true
                }
                if (hadNumber) continue
                cleanText(text)?.let { out += Item(it, r, null) }
            }
        }
    }

    /** Tekst, der kan søges på – eller null for feltnavne, materialetyper, støj og fejllæste numre */
    private fun cleanText(raw: String): String? {
        val t = raw.replace(Regex("\\s+"), " ").trim().trim(':', ';', ',', '-', '–', '•', '|', '*', '/', ' ')
        if (t.length !in 2..150) return null
        val k = normKey(t)
        if (k.length < 2 || k in STOP_WORDS) return null
        // Et nummer læst med bogstaver indimellem (fx "S49157e393"): mest cifre og intet mellemrum
        if (k.length >= 6 && !t.contains(' ') && k.count { it.isDigit() } * 10 >= k.length * 6) return null
        return t
    }

    /** Sammenligningsnøgle: små bogstaver, uden accenter (å → a) og uden tegn/mellemrum */
    private fun normKey(t: String): String =
        java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD)
            .replace("ø", "o").replace("æ", "ae")
            .filter { it.isLetterOrDigit() }

    /** ISBN (978/979 + 13 cifre, eller 10 cifre ved "ISBN"), materialenummer (jeres længde) eller faust (8-9 cifre) */
    private fun numType(v: String, isbnHint: Boolean): String? = when {
        v.length == 13 && (v.startsWith("978") || v.startsWith("979")) -> "isbn"
        v.length == 10 && isbnHint -> "isbn"
        itemLen != null && v.length == itemLen -> "bmn"
        v.length in 8..9 -> "faust"
        else -> null
    }

    // ---------- Valg ----------

    private fun isNumType(type: String) = type in NUM_TYPES

    private fun toggle(item: Item) {
        item.pick?.let { p ->
            picks.remove(p)
            item.pick = null
            rebuildPicks(); marks.invalidate()
            return
        }
        // Numre har deres type; "Efternavn, Fornavn" foreslås som Forfatter; resten er fritekst
        val type = item.numType ?: if (NAME.matches(item.text)) "creator" else "free"
        // Numre og tekst blandes ikke i én søgning
        if (picks.any { isNumType(it.type) != isNumType(type) }) {
            Toast.makeText(this, "Numre og tekst kan ikke søges sammen", Toast.LENGTH_SHORT).show()
            return
        }
        val p = Pick(item.text, type, item)
        item.pick = p
        picks += p
        rebuildPicks(); marks.invalidate()
        picksScroll.post { picksScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------- Søgningen ----------

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
        setResult(RESULT_OK, Intent().putExtra(EXTRA_CQL, cql))
        finish()
    }

    // ---------- "Valgt" ----------

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

    private fun rebuildPicks() {
        picksBox.removeAllViews()
        val any = picks.isNotEmpty()
        val vis = if (any) View.VISIBLE else View.INVISIBLE
        pickHeader.visibility = vis
        picksScroll.visibility = vis
        searchAll.visibility = vis
        cqlPreview.visibility = vis
        searchAll.text = "Søg (" + picks.size + " valgt" + (if (picks.size == 1) "" else "e") + ")"
        cqlPreview.text = if (any) cqlOfPicks() else ""
        for (p in picks.toList()) {
            val label = TextView(this).apply {
                text = p.text
                textSize = 16f
                setTextColor(tc.fg)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setOnClickListener { editPick(p) }
            }
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
            picksBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { setColor(tc.card); cornerRadius = dp(10).toFloat() }
                setPadding(dp(12), dp(6), dp(8), dp(6))
                addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                listOf(
                    typeChip,
                    pill("Søg nu", false) { finishWith(cqlOf(p.text, p.type)) },
                    pill("✕", false) {
                        picks.remove(p)
                        p.item?.pick = null
                        rebuildPicks(); marks.invalidate()
                    },
                ).forEach { addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) }) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(3), 0, dp(3)) })
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
                    p.item?.pick = above
                    rebuildPicks(); marks.invalidate()
                }
            }
            .show()
    }

    // ---------- Markeringerne i billedet ----------

    /** Tegner de fundne linjer oven på billedet; et tryk på en linje vælger den (eller fravælger den igen) */
    @SuppressLint("ViewConstructor")
    private inner class ItemsView(ctx: Context) : View(ctx) {
        var items: List<Item> = emptyList()
            set(v) { field = v; invalidate() }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val pad = dp(4).toFloat()
        private val corner = dp(6).toFloat()

        override fun onDraw(canvas: Canvas) {
            for (it in items) {
                val r = RectF(it.rect.left - pad, it.rect.top - pad, it.rect.right + pad, it.rect.bottom + pad)
                val on = it.pick != null
                fill.color = if (on) Color.argb(110, Color.red(tc.blue), Color.green(tc.blue), Color.blue(tc.blue)) else Color.argb(40, 255, 255, 255)
                stroke.color = if (on) tc.blue else Color.argb(220, 255, 255, 255)
                stroke.strokeWidth = dp(if (on) 3 else 2).toFloat()
                canvas.drawRoundRect(r, corner, corner, fill)
                canvas.drawRoundRect(r, corner, corner, stroke)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (items.isEmpty()) return false // ingen billede: tryk går videre til kameraet (skarpt billede)
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                val slack = dp(10).toFloat()
                items.filter { RectF(it.rect.left - slack, it.rect.top - slack, it.rect.right + slack, it.rect.bottom + slack).contains(e.x, e.y) }
                    .minByOrNull { it.rect.width() * it.rect.height() }
                    ?.let { toggle(it) }
            }
            return true
        }
    }

    // ---------- NFC og oprydning ----------

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
        try { barcodes.close(); texts.close() } catch (_: Exception) {}
    }
}

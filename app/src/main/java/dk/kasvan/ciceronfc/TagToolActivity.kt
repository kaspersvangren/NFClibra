package dk.kasvan.ciceronfc

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Værktøj til tags: se hvad der står på et tag, slå alarmen til/fra, nulstil det, eller programmér en ny chip
 * ("konvertér"). Mens værktøjet er åbent, får Cicero ikke besked om tags – intet kan lånes ud eller afleveres ved et uheld.
 * App'en sender aldrig lås-kommandoer til et tag (de kan ikke fortrydes).
 */
class TagToolActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    companion object {
        const val EXTRA_DARK = "dark"
        private const val FORGET_AFTER_MS = 10_000L // kortet forsvinder 10 sek. efter chippen er fjernet
    }

    private class Info(
        val mac: String,
        val maker: String,
        val afi: Int?,
        val content: TagContent?,
        val blank: Boolean,
        val danish: Boolean,
    )

    private class Pending(val barcode: String, var part: Int, val total: Int, val single: Boolean)

    // Farver som Cicero (lys/mørk) – fælles med kameraet
    private var dark = true
    private lateinit var tc: ToolColors
    private val bg get() = tc.bg
    private val card get() = tc.card
    private val fg get() = tc.fg
    private val sub get() = tc.sub
    private val keyBg get() = tc.key
    private val blue get() = tc.blue
    private val green get() = tc.green
    private val orange get() = tc.orange
    private val red get() = tc.red
    private val tileBg get() = tc.tile
    private val warnBg get() = tc.warnBg
    private val warnFg get() = tc.warnFg

    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var nfc: NfcAdapter? = null

    // Kun på exec-tråden
    private var tag: PhoneTag? = null
    private var pingFails = 0
    private var lastWrittenMac: String? = null
    private var confirmedMac: String? = null
    private var askingMac: String? = null

    @Volatile private var info: Info? = null
    @Volatile private var present = false
    @Volatile private var pending: Pending? = null
    @Volatile private var goneAt = 0L

    private lateinit var libraryText: TextView
    private lateinit var cardBox: LinearLayout
    private lateinit var stateText: TextView
    private lateinit var numberLabel: TextView
    private lateinit var statusTitle: TextView
    private lateinit var tiles: LinearLayout
    private lateinit var tagButtons: LinearLayout
    private lateinit var feedback: TextView
    private lateinit var input: EditText
    private lateinit var abcBtn: TextView
    private lateinit var totalRow: LinearLayout
    private lateinit var modeRow: LinearLayout
    private lateinit var partBox: LinearLayout
    private lateinit var partRow: LinearLayout
    private lateinit var writeBtn: TextView
    private lateinit var cancelBtn: TextView
    private var part = 1
    private var total = 1
    private var single = false // "Kun én del" i stedet for hele sættet
    // Nummeret, appen selv har sat i feltet fra en læst chip (null = brugeren har skrevet selv)
    private var autoFilled: String? = null
    private lateinit var copyBtn: TextView
    private lateinit var flashView: android.widget.ImageView

    private val scan = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra(ScanActivity.EXTRA_VALUE)?.let {
            autoFilled = null
            input.setSelectAllOnFocus(false)
            input.setText(it)
            input.setSelection(it.length)
            LogBuf.add("Værktøj: nummer læst med kamera")
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun prefs() = getSharedPreferences("ui", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = intent.getBooleanExtra(EXTRA_DARK, true)
        tc = ToolColors(dark)
        Hub.configuredLibrary = prefs().getString(Hub.PREF_LIBRARY, null)
        nfc = NfcAdapter.getDefaultAdapter(this)
        buildUi()
        showInfo()
        exec.scheduleWithFixedDelay({ presenceCheck() }, 400, 400, TimeUnit.MILLISECONDS)
        LogBuf.add("Værktøj til tags åbnet")
    }

    // ---------- Skærmen ----------

    private fun button(label: String, color: Int, textColor: Int = Color.WHITE, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 17f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setTextColor(textColor)
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat() }
        setPadding(dp(6), dp(12), dp(6), dp(12))
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(4), dp(4), dp(4), dp(4))
        })
    }

    private fun label(t: String, size: Float = 15f, color: Int = sub) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
    }

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(2), dp(14), dp(10))
        }

        val back = TextView(this).apply {
            text = "←"
            textSize = 26f
            setTextColor(fg)
            setPadding(dp(4), dp(4), dp(16), dp(4))
            setOnClickListener { finish() }
        }
        val title = label("Værktøj til tags", 22f, fg).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back)
            addView(title)
        })

        libraryText = label("", 15f)
        val editLib = TextView(this).apply {
            text = "Ret"
            textSize = 15f
            setTextColor(blue)
            setPadding(dp(16), dp(4), dp(4), dp(4))
            setOnClickListener { askLibrary(null) }
        }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(libraryText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(editLib)
        })
        updateLibraryText()

        // Kortet med tagget: status, materialenummer stort, og et felt for hver oplysning
        stateText = label("", 14f)
        numberLabel = label("Materialenummer", 13f)
        statusTitle = label("", 28f, fg).apply {
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.04f
        }
        tiles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Én linje med fast plads, så knapperne ikke flytter sig, når der kommer en besked
        feedback = label("", 15f, fg).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            minLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        cardBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(card); cornerRadius = dp(16).toFloat() }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(stateText)
            addView(numberLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            // Materialenummeret med en kopiér-knap ved siden af
            copyBtn = TextView(this@TagToolActivity).apply {
                text = "Kopiér"
                textSize = 15f
                setTextColor(blue)
                setPadding(dp(12), dp(8), dp(4), dp(8))
                setOnClickListener { copyNumber() }
            }
            addView(LinearLayout(this@TagToolActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(statusTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(copyBtn)
            })
            addView(tiles, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }
        col.addView(cardBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        col.addView(feedback, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6); marginStart = dp(4); marginEnd = dp(4)
        })

        // Knapperne til tagget: altid på én række
        tagButtons = row(
            button("Alarm til", orange) { alarm(true) },
            button("Alarm fra", green) { alarm(false) },
            button("Nulstil", keyBg, red) { confirmReset() },
        )
        col.addView(tagButtons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })

        // Programmér ny chip – sit eget kort, så det er tydeligt adskilt fra chippen ved telefonen
        val prog = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(card); cornerRadius = dp(16).toFloat() }
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }
        col.addView(prog, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        prog.addView(label("Programmér ny chip", 13f).apply { setPadding(0, 0, 0, dp(6)) })
        input = EditText(this).apply {
            textSize = 22f
            setTextColor(fg)
            setHintTextColor(sub)
            // Hjælpeteksten i almindelig størrelse, så den kan være der; tallene står stort
            hint = android.text.SpannableString("Materialenummer").apply {
                setSpan(android.text.style.AbsoluteSizeSpan(16, true), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            background = GradientDrawable().apply { setColor(card); setStroke(dp(1), sub); cornerRadius = dp(10).toFloat() }
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        // Skriver man selv, er det ikke længere chippens nummer, og det må ikke overskrives
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (autoFilled != null && s.toString() != autoFilled) {
                    autoFilled = null
                    input.setSelectAllOnFocus(false)
                }
            }
        })
        abcBtn = button("ABC", keyBg, fg) {
            val isNumber = (input.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER
            input.inputType = if (isNumber) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS else InputType.TYPE_CLASS_NUMBER
            abcBtn.text = if (isNumber) "123" else "ABC"
            input.setSelection(input.text.length)
        }
        val camBtn = button("Kamera", blue) {
            scan.launch(Intent(this, ScanActivity::class.java).putExtra(ScanActivity.EXTRA_DARK, dark))
        }
        prog.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(abcBtn, LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            addView(camBtn, LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        })

        // Antal dele og hele sættet / kun én del – ét tryk, ingen tællen op og ned
        fun rowBox() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        prog.addView(label("Antal dele", 13f).apply { setPadding(0, dp(10), 0, dp(4)) })
        totalRow = rowBox()
        prog.addView(totalRow)
        modeRow = rowBox()
        prog.addView(modeRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        partRow = rowBox()
        partBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Hvilken del mangler?", 13f).apply { setPadding(0, dp(8), 0, dp(4)) })
            addView(partRow)
        }
        prog.addView(partBox)
        updateParts()

        writeBtn = button("Programmér chip", blue) { arm() }
        cancelBtn = button("Stop", keyBg, fg) { disarm("Stoppet") }.apply { visibility = View.GONE }
        prog.addView(writeBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        prog.addView(cancelBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        // Altid på én skærm: er der ikke plads (lille skærm eller stor skrift), skaleres det hele ned, til det passer
        val holder = android.widget.FrameLayout(this).apply {
            setBackgroundColor(bg)
            fitsSystemWindows = true
            addView(col, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        holder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> holder.post { fitToScreen(holder, col) } }
        col.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot) holder.post { fitToScreen(holder, col) } }
        val scroll = holder
        // Stort flueben/kryds midt på skærmen, der toner ud – så man kan se resultatet på afstand
        flashView = android.widget.ImageView(this).apply {
            alpha = 0f
            visibility = View.GONE
            setPadding(dp(30), dp(30), dp(30), dp(30))
        }
        setContentView(android.widget.FrameLayout(this).apply {
            addView(scroll)
            addView(flashView, android.widget.FrameLayout.LayoutParams(dp(150), dp(150), Gravity.CENTER))
        })
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (!dark && Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }
    }

    /** Rund knap med et tal (eller "Flere…"); den valgte er blå */
    private fun chip(text: String, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 17f
        gravity = Gravity.CENTER
        maxLines = 1
        setTextColor(if (selected) Color.WHITE else fg)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = GradientDrawable().apply { setColor(if (selected) blue else keyBg); cornerRadius = dp(22).toFloat() }
        setPadding(dp(2), dp(10), dp(2), dp(10))
        setOnClickListener { if (pending == null) { onClick(); updateParts() } }
    }

    private fun addChip(row: LinearLayout, v: View, weight: Float = 1f) =
        row.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply { setMargins(dp(3), 0, dp(3), 0) })

    /** Tallene 1-6 og "Flere…" til større tal (op til [max]) */
    private fun numberRow(row: LinearLayout, value: Int, max: Int, title: String, set: (Int) -> Unit) {
        row.removeAllViews()
        for (n in 1..minOf(6, max)) addChip(row, chip("$n", value == n) { set(n) })
        if (max > 6) {
            addChip(row, chip(if (value > 6) "$value" else "Flere…", value > 6) { askNumber(title, 7, max, value) { set(it); updateParts() } }, 1.8f)
        }
    }

    private fun askNumber(title: String, min: Int, max: Int, current: Int, set: (Int) -> Unit) {
        val picker = android.widget.NumberPicker(this).apply {
            minValue = min
            maxValue = max
            value = current.coerceIn(min, max)
            wrapSelectorWheel = false
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(picker)
            .setPositiveButton("OK") { _, _ -> set(picker.value) }
            .setNegativeButton("Annuller", null)
            .show()
    }

    private var fitScale = 1f

    /** Skalér indholdet ned (aldrig op), så det kan være på skærmen uden at rulle */
    private fun fitToScreen(holder: android.view.View, col: android.view.View) {
        val availW = holder.width - holder.paddingLeft - holder.paddingRight
        val availH = holder.height - holder.paddingTop - holder.paddingBottom
        if (availW <= 0 || availH <= 0) return
        var s = 1f
        repeat(3) {
            val w = (availW / s).toInt()
            col.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            )
            val need = col.measuredHeight
            s = if (need * s > availH) (availH.toFloat() / need).coerceAtLeast(0.6f) else s
        }
        if (kotlin.math.abs(s - fitScale) < 0.005f && col.layoutParams.width == (if (s < 1f) (availW / s).toInt() else android.view.ViewGroup.LayoutParams.MATCH_PARENT)) return
        fitScale = s
        col.pivotX = 0f
        col.pivotY = 0f
        col.scaleX = s
        col.scaleY = s
        col.layoutParams = col.layoutParams.apply {
            width = if (s < 1f) (availW / s).toInt() else android.view.ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    private fun updateParts() {
        numberRow(totalRow, total, 99, "Antal dele") { n ->
            total = n
            if (part > total) part = 1
            if (total == 1) single = false
        }
        modeRow.removeAllViews()
        modeRow.visibility = if (total > 1) View.VISIBLE else View.GONE
        addChip(modeRow, chip("Hele sættet", !single) { single = false; part = 1 })
        addChip(modeRow, chip("Kun én del…", single) { single = true })
        partBox.visibility = if (single && total > 1) View.VISIBLE else View.GONE
        numberRow(partRow, part, total, "Hvilken del?") { part = it }
    }

    private fun updateLibraryText() {
        val lib = Hub.configuredLibrary
        libraryText.text = if (lib != null) "Bibliotek: DK-$lib" else "Bibliotek: ikke sat"
    }

    private fun say(text: String, color: Int) {
        runOnUiThread {
            feedback.text = text
            feedback.setTextColor(color)
        }
    }

    private enum class Kind { NORMAL, WARN, ALARM_ON, ALARM_OFF }
    private class Field(val label: String, val value: String, val kind: Kind = Kind.NORMAL)

    private fun tile(f: Field) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val warn = f.kind == Kind.WARN
        background = GradientDrawable().apply { setColor(if (warn) warnBg else tileBg); cornerRadius = dp(10).toFloat() }
        setPadding(dp(12), dp(8), dp(12), dp(10))
        addView(label(f.label, 13f, if (warn) warnFg else sub))
        val v = TextView(this@TagToolActivity).apply {
            text = f.value
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            when (f.kind) {
                Kind.ALARM_ON, Kind.ALARM_OFF -> {
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        setColor(if (f.kind == Kind.ALARM_ON) orange else green); cornerRadius = dp(20).toFloat()
                    }
                    setPadding(dp(12), dp(2), dp(12), dp(3))
                }
                Kind.WARN -> { setTextColor(warnFg); setPadding(0, dp(2), 0, dp(3)) }
                else -> { setTextColor(fg); setPadding(0, dp(2), 0, dp(3)) }
            }
        }
        addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
    }

    /** Altid de samme fire felter i samme rækkefølge, så intet hopper; "–" når der ikke er noget at vise */
    private fun fieldsFor(i: Info?): List<Field> {
        if (i == null) return listOf(Field("Del", "–"), Field("Bibliotek", "–"), Field("Alarm", "–"), Field("Chip", "–"))
        val c = i.content
        val danish = c != null && i.danish && !i.blank
        val del = when {
            !danish -> Field("Del", "–")
            c!!.seqNum in 1..c.numItems -> Field("Del", "${c.seqNum} af ${c.numItems}")
            else -> Field("Del", "uklart (${c.seqNum}/${c.numItems})", Kind.WARN)
        }
        val own = Hub.configuredLibrary
        val lib = when {
            i.blank -> Field("Bibliotek", "–")
            !danish -> Field("Bibliotek", "ikke dansk format", Kind.WARN)
            c!!.library.isEmpty() -> Field("Bibliotek", "ikke angivet", Kind.WARN)
            own != null && c.library != own -> Field("Bibliotek", "${c.country}-${c.library}", Kind.WARN)
            else -> Field("Bibliotek", "${c.country}-${c.library}")
        }
        val alarm = when (i.afi) {
            Hub.AFI_ON -> Field("Alarm", "Til", Kind.ALARM_ON)
            Hub.AFI_OFF -> Field("Alarm", "Fra", Kind.ALARM_OFF)
            null -> Field("Alarm", "ukendt", Kind.WARN)
            else -> Field("Alarm", "anden (0x%02X)".format(i.afi), Kind.WARN)
        }
        return listOf(del, lib, alarm, Field("Chip", i.maker))
    }

    private fun showInfo() {
        runOnUiThread {
            handler.removeCallbacks(forgetTick)
            val i = info
            tiles.removeAllViews()
            val c = i?.content
            val hasNumber = i != null && c != null && i.danish && !i.blank
            statusTitle.text = when {
                i == null -> "–"
                i.blank -> "Tom chip"
                hasNumber -> c!!.barcode.ifEmpty { "(intet nummer)" }
                else -> "Ukendt indhold"
            }
            // Kopiér beholder sin plads, så nummeret ikke flytter sig
            copyBtn.visibility = if (hasNumber && !c?.barcode.isNullOrEmpty()) View.VISIBLE else View.INVISIBLE
            copyBtn.text = "Kopiér"
            val fields = fieldsFor(i)
            for (k in fields.indices step 2) {
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (f in fields.subList(k, minOf(k + 2, fields.size))) {
                    r.addView(tile(f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
                }
                tiles.addView(r)
            }
            // Knapperne står der altid; uden chip ved telefonen er de nedtonede og gør ingenting
            setTagButtonsEnabled(i != null && present)
            when {
                i == null -> { cardBox.alpha = 1f; stateText.text = "Hold et tag mod telefonen" }
                present -> { cardBox.alpha = 1f; stateText.text = "Chip ved telefonen" }
                else -> { cardBox.alpha = 0.55f; forgetTick.run() }
            }
        }
    }

    private fun setTagButtonsEnabled(on: Boolean) {
        tagButtons.alpha = if (on) 1f else 0.35f
        fun walk(v: View) {
            v.isEnabled = on
            if (v is android.view.ViewGroup) for (k in 0 until v.childCount) walk(v.getChildAt(k))
        }
        walk(tagButtons)
    }

    /** Chippen er fjernet: vis "Sidst læst" med nedtælling, og glem den efter 10 sekunder */
    private val forgetTick = object : Runnable {
        override fun run() {
            if (present || info == null) return
            val left = FORGET_AFTER_MS - (SystemClock.elapsedRealtime() - goneAt)
            if (left <= 0) {
                val at = goneAt
                exec.execute {
                    if (!present && goneAt == at) {
                        info = null
                        tag?.close(); tag = null
                        if (pending == null) say("", fg)
                    }
                    showInfo()
                }
                return
            }
            stateText.text = "Sidst læst · forsvinder om ${(left + 999) / 1000} sek."
            handler.postDelayed(this, 250)
        }
    }

    // ---------- NFC ----------

    override fun onTagDiscovered(t: Tag) {
        exec.execute { handleTag(t) }
    }

    private fun handleTag(t: Tag) {
        val pt = try { PhoneTag(t) } catch (e: Exception) {
            say("Ikke et bibliotekstag", red)
            return
        }
        tag?.close()
        tag = pt
        present = true
        pingFails = 0
        info = readInfo(pt)
        if (pending == null) say("", fg)
        showInfo()
        if (pending == null) prefill(info)
        vibrate(longArrayOf(0, 50))
        maybeProgram()
    }

    private fun readInfo(pt: PhoneTag): Info {
        val afi = try { pt.systemInfo().afi } catch (_: Exception) { null }
        val raw = try { pt.readBlocks(0, 8) } catch (_: Exception) { null }
        val blank = raw != null && raw.all { it == 0.toByte() }
        val danish = raw != null && (raw[0].toInt() and 0xFF) == 0x11
        val content = if (danish) try { TagContent.parse(raw!!) } catch (_: Exception) { null } else null
        content?.let { Hub.noteItemNumber(it.barcode, it.library) }
        content?.library?.takeIf { it.isNotEmpty() }?.let { if (Hub.configuredLibrary == null) suggestLibrary(it) }
        val makerByte = pt.uidMsb.getOrNull(1)?.toInt()?.and(0xFF)
        val maker = when (makerByte) {
            0x04 -> "NXP (ICODE)"
            0x07 -> "Texas Instruments"
            0x02 -> "STMicroelectronics"
            0x05 -> "Infineon"
            0x16 -> "EM Microelectronic"
            null -> "ukendt"
            else -> "producent 0x%02X".format(makerByte)
        }
        return Info(pt.mac, maker, afi, content, blank, danish)
    }

    private fun presenceCheck() {
        val t = tag ?: return
        if (!present) return
        try {
            t.ping()
            pingFails = 0
        } catch (_: Exception) {
            if (++pingFails >= 2) {
                present = false
                goneAt = SystemClock.elapsedRealtime()
                showInfo()
            }
        }
    }

    /** Kør en handling på tagget ved telefonen; læs det igen bagefter, så kortet viser det rigtige */
    private fun copyNumber() {
        val nr = info?.content?.barcode?.takeIf { it.isNotEmpty() } ?: return
        getSystemService(android.content.ClipboardManager::class.java)
            ?.setPrimaryClip(android.content.ClipData.newPlainText("Materialenummer", nr))
        copyBtn.text = "Kopieret ✓"
        handler.postDelayed({ if (::copyBtn.isInitialized) copyBtn.text = "Kopiér" }, 1500)
    }

    /**
     * En læst chip med materialenummer fylder feltet "Programmér chip" ud (markeret, så det forsvinder, når man
     * skriver), og antal dele sættes. Har man selv skrevet noget, røres feltet ikke.
     */
    private fun prefill(i: Info?) {
        val c = i?.content ?: return
        if (!i.danish || i.blank || c.barcode.isEmpty()) return
        runOnUiThread {
            val current = input.text.toString()
            if (current.isNotEmpty() && current != autoFilled) return@runOnUiThread
            autoFilled = c.barcode
            input.setText(c.barcode)
            input.setSelectAllOnFocus(true)
            if (input.hasFocus()) input.selectAll()
            if (c.numItems in 1..99) {
                total = c.numItems
                part = if (c.seqNum in 1..total) c.seqNum else 1
                if (total == 1) single = false
                updateParts()
            }
        }
    }

    /** Stort grønt flueben eller rødt kryds, der toner ud */
    private fun flash(ok: Boolean) {
        runOnUiThread {
            flashView.animate().cancel()
            flashView.setImageResource(if (ok) R.drawable.ic_check else R.drawable.ic_close)
            flashView.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (ok) green else red)
            }
            flashView.visibility = View.VISIBLE
            flashView.alpha = 0f
            flashView.scaleX = 0.7f; flashView.scaleY = 0.7f
            flashView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).withEndAction {
                flashView.animate().alpha(0f).setStartDelay(700).setDuration(400)
                    .withEndAction { flashView.visibility = View.GONE }.start()
            }.start()
        }
    }

    private fun withTag(what: String, f: (PhoneTag, Info) -> String) {
        exec.execute {
            val t = tag
            val i = info
            if (t == null || i == null || !present) {
                say("Hold et tag mod telefonen først", orange)
                return@execute
            }
            try {
                val msg = f(t, i)
                info = readInfo(t)
                showInfo()
                say(msg, green)
                flash(true)
                vibrate(longArrayOf(0, 110, 80, 110))
            } catch (e: Exception) {
                LogBuf.add("Værktøj: $what fejlede: ${e.message}")
                say("$what fejlede – prøv igen", red)
                flash(false)
                vibrate(longArrayOf(0, 80, 70, 80, 70, 80))
            }
        }
    }

    private fun alarm(on: Boolean) {
        withTag(if (on) "Alarm til" else "Alarm fra") { t, i ->
            t.writeAfi(if (on) Hub.AFI_ON else Hub.AFI_OFF)
            LogBuf.add("Værktøj: alarm ${if (on) "til" else "fra"} på ${i.content?.barcode ?: i.mac}")
            if (on) "Alarm slået til ✓" else "Alarm slået fra ✓"
        }
    }

    private fun confirmReset() {
        val i = info
        if (i == null || !present) { say("Hold et tag mod telefonen først", orange); return }
        val name = i.content?.barcode?.takeIf { it.isNotEmpty() } ?: "dette tag"
        val msg = SpannableStringBuilder().apply {
            val b0 = length; append(name); setSpan(StyleSpan(Typeface.BOLD), b0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(" bliver helt tomt som en ny chip.\n\n")
            val r0 = length; append("Det kan ikke fortrydes.")
            setSpan(StyleSpan(Typeface.BOLD), r0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(red), r0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val d = AlertDialog.Builder(this)
            .setTitle("⚠  Nulstil tag?")
            .setMessage(msg)
            .setPositiveButton("Nulstil") { _, _ ->
                withTag("Nulstil") { t, _ ->
                    t.writeBlocks(ByteArray(32))
                    t.writeAfi(0x00)
                    if (!t.readBlocks(0, 8).all { it == 0.toByte() }) throw IllegalStateException("kontrol-læsning viste data")
                    LogBuf.add("Værktøj: nulstillet tag (var $name)")
                    "Nulstillet ✓ – chippen er tom"
                }
            }
            .setNegativeButton("Annuller", null)
            .show()
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(red)
    }

    // ---------- Programmering ----------

    private fun arm() {
        val barcode = input.text.toString().trim()
        if (barcode.isEmpty() || barcode.length > 16 || !barcode.all { it.code in 0x21..0x7E }) {
            say("Skriv et materialenummer (højst 16 tegn)", orange)
            return
        }
        if (Hub.configuredLibrary == null) {
            askLibrary { arm() }
            return
        }
        if (!single) part = 1
        val p = Pending(barcode, part, total, single && total > 1)
        exec.execute {
            pending = p
            lastWrittenMac = null
            confirmedMac = null
            say(if (total > 1) "Hold del $part af $total mod telefonen" else "Hold chippen mod telefonen", blue)
            runOnUiThread {
                writeBtn.visibility = View.GONE
                cancelBtn.visibility = View.VISIBLE
            }
            maybeProgram()
        }
    }

    private fun disarm(msg: String?) {
        exec.execute {
            pending = null
            askingMac = null
            runOnUiThread {
                writeBtn.visibility = View.VISIBLE
                cancelBtn.visibility = View.GONE
            }
            if (msg != null) say(msg, sub)
        }
    }

    /** Kører på exec: skriv til tagget ved telefonen, hvis der venter en programmering */
    private fun maybeProgram() {
        val p = pending ?: return
        val t = tag ?: return
        val i = info ?: return
        if (!present) return
        // Samme chip som lige er skrevet: vent på den næste (ellers ville del 2 lande oven i del 1)
        if (i.mac == lastWrittenMac) return
        val c = i.content
        val same = c != null && i.danish && c.barcode == p.barcode && c.seqNum == p.part && c.numItems == p.total
        if (!i.blank && !same && confirmedMac != i.mac) {
            if (askingMac == i.mac) return
            askingMac = i.mac
            val what = if (c != null && i.danish && c.barcode.isNotEmpty()) "materialenummer ${c.barcode}" else "andet indhold"
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("Overskriv?")
                    .setMessage("Chippen har allerede $what. Vil du overskrive den med ${p.barcode}?")
                    .setPositiveButton("Overskriv") { _, _ -> exec.execute { confirmedMac = i.mac; askingMac = null; maybeProgram() } }
                    .setNegativeButton("Annuller") { _, _ -> exec.execute { askingMac = null }; disarm("Ikke overskrevet") }
                    .setCancelable(false)
                    .show()
            }
            return
        }
        try {
            val lib = Hub.configuredLibrary ?: throw IllegalStateException("biblioteksnummer er ikke sat")
            // Samme feltrækkefølge som Ciceros egen skrivning (go-feig): antal dele, derefter delnummer
            val bytes = TagContent.build(p.barcode, p.total, p.part, Hub.DEFAULT_COUNTRY, lib)
            t.writeBlocks(bytes)
            val check = TagContent.parse(t.readBlocks(0, 8))
            if (check.barcode != p.barcode) throw IllegalStateException("kontrol-læsning viste '${check.barcode}'")
            t.writeAfi(Hub.AFI_ON)
            lastWrittenMac = i.mac
            info = readInfo(t)
            showInfo()
            LogBuf.add("Værktøj: programmeret ${p.barcode} del ${p.part}/${p.total}")
            vibrate(longArrayOf(0, 110, 80, 110))
            flash(true)
            if (!p.single && p.part < p.total) {
                p.part++
                runOnUiThread { part = p.part; updateParts() }
                say("Del ${p.part - 1} af ${p.total} ✓ – hold del ${p.part} mod telefonen", green)
            } else {
                say(
                    if (p.single) "Del ${p.part} af ${p.total} skrevet ✓ – alarm til"
                    else "Skrevet ✓ – alarm til",
                    green,
                )
                pending = null
                runOnUiThread {
                    writeBtn.visibility = View.VISIBLE
                    cancelBtn.visibility = View.GONE
                    part = 1
                    updateParts()
                }
            }
        } catch (e: Exception) {
            LogBuf.add("Værktøj: programmering fejlede: ${e.message}")
            say("Skrivning fejlede – løft chippen og prøv igen", red)
            flash(false)
            vibrate(longArrayOf(0, 80, 70, 80, 70, 80))
        }
    }

    // ---------- Biblioteksnummer ----------

    @Volatile private var suggested: String? = null

    private fun suggestLibrary(lib: String) {
        if (suggested == null) suggested = lib
    }

    private fun askLibrary(then: (() -> Unit)?) {
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Hub.configuredLibrary ?: suggested ?: Hub.lastLibrary ?: "")
            setSelection(text.length)
            textSize = 22f
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle("Biblioteksnummer")
            .setMessage("Skrives på nye chips sammen med landekoden DK. Forslaget er det nummer, app'en har set på jeres tags.")
            .setView(box)
            .setPositiveButton("Gem") { _, _ ->
                val v = field.text.toString().trim()
                if (v.isEmpty() || v.length > 9 || !v.all { it.isLetterOrDigit() }) {
                    say("Biblioteksnummer: 1-9 tal eller bogstaver", orange)
                } else {
                    prefs().edit().putString(Hub.PREF_LIBRARY, v).apply()
                    Hub.configuredLibrary = v
                    updateLibraryText()
                    LogBuf.add("Biblioteksnummer sat til DK-$v")
                    then?.invoke()
                }
            }
            .setNegativeButton("Annuller", null)
            .show()
    }

    // ---------- Livscyklus ----------

    // Samme vibration som resten af app'en (kommer også igennem i lydløs)
    private fun vibrate(pattern: LongArray) = Hub.vibrate(pattern)

    /**
     * Ethvert tryk tæller som brug, så app'en ikke låser midt i arbejdet. Er app'en låst eller
     * har den ikke været brugt i 2 minutter, lukkes værktøjet i stedet, og hovedskærmen låser.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (Hub.locked || Hub.idleTooLong()) { finish(); return true }
        Hub.lastActivity = SystemClock.elapsedRealtime()
        return super.dispatchTouchEvent(ev)
    }

    private val idleCheck = object : Runnable {
        override fun run() {
            // 2 minutter uden brug: luk værktøjet, så app'en låser som normalt
            if (Hub.locked || Hub.idleTooLong()) { finish(); return }
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onResume() {
        super.onResume()
        // Kommer man tilbage efter mere end 2 minutter, må værktøjet ikke stå åbent uden oplåsning
        if (Hub.locked || Hub.idleTooLong()) { finish(); return }
        val opts = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 500) }
        nfc?.enableReaderMode(
            this, this,
            NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            opts,
        )
        handler.removeCallbacks(idleCheck)
        handler.postDelayed(idleCheck, 10_000)
    }

    override fun onPause() {
        super.onPause()
        nfc?.disableReaderMode(this)
        handler.removeCallbacks(idleCheck)
    }

    override fun onDestroy() {
        super.onDestroy()
        LogBuf.add("Værktøj til tags lukket")
        exec.execute { tag?.close() }
        exec.shutdown()
    }
}

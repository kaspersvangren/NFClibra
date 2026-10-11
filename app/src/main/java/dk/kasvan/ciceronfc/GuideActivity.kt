package dk.kasvan.ciceronfc

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import kotlin.math.abs

/**
 * Guide til NFClibra: en række sider med en lille tegning (mockup) af det, der forklares, og et par linjer tekst.
 * Tegningerne er kun billeder – intet i app'en eller i Cicero bliver åbnet eller ændret.
 */
class GuideActivity : ComponentActivity() {
    companion object {
        const val EXTRA_DARK = "dark"
    }

    private class Page(val title: String, val text: String, val build: () -> View)

    private lateinit var tc: ToolColors
    private var dark = true
    private val handler = Handler(Looper.getMainLooper())
    private val loops = ArrayList<Runnable>()
    private val animators = ArrayList<ValueAnimator>()

    private lateinit var pages: List<Page>
    private var index = 0
    private lateinit var stage: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var textView: TextView
    private lateinit var dots: LinearLayout
    private lateinit var backBtn: TextView
    private lateinit var nextBtn: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = intent.getBooleanExtra(EXTRA_DARK, true)
        tc = ToolColors(dark)
        pages = buildPages()

        stage = FrameLayout(this)
        titleView = TextView(this).apply {
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(tc.fg)
            gravity = Gravity.CENTER
        }
        textView = TextView(this).apply {
            textSize = 16f
            setTextColor(tc.sub)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
        }
        dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        backBtn = TextView(this).apply {
            textSize = 16f
            setTextColor(tc.sub)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { if (index == 0) finish() else show(index - 1) }
        }
        nextBtn = TextView(this).apply {
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(tc.blue); cornerRadius = dp(24).toFloat() }
            setPadding(dp(24), dp(12), dp(24), dp(12))
            setOnClickListener { if (index == pages.size - 1) finish() else show(index + 1) }
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(16), dp(16))
            addView(backBtn)
            addView(dots, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(nextBtn)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(tc.bg)
            fitsSystemWindows = true
            addView(stage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.15f))
            addView(titleView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(24), dp(8), dp(24), 0)
            })
            addView(textView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.55f).apply {
                setMargins(dp(28), dp(10), dp(28), 0)
            })
            addView(bottom)
        }
        setContentView(root)
        window.statusBarColor = tc.bg
        window.navigationBarColor = tc.bg
        if (!dark && Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }
        // Swipe til siden for at bladre
        val swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) < abs(vy) || abs(vx) < 600) return false
                if (vx < 0 && index < pages.size - 1) show(index + 1)
                if (vx > 0 && index > 0) show(index - 1)
                return true
            }
        })
        root.setOnTouchListener { _, e -> swipe.onTouchEvent(e); true }
        stage.setOnTouchListener { _, e -> swipe.onTouchEvent(e); true }
        LogBuf.add("Guide åbnet")
        show(0)
    }

    private fun show(i: Int) {
        stopAnimations()
        index = i
        val p = pages[i]
        stage.removeAllViews()
        stage.addView(FitBox(this, dp(12)).apply { addView(p.build()) }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        stage.alpha = 0f
        stage.animate().alpha(1f).setDuration(200).start()
        titleView.text = p.title
        textView.text = p.text
        backBtn.text = if (i == 0) "Spring over" else "Tilbage"
        nextBtn.text = if (i == pages.size - 1) "Færdig" else "Næste"
        dots.removeAllViews()
        for (k in pages.indices) {
            dots.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (k == i) tc.blue else tc.key) }
            }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
    }

    // ---------- Animationer ----------

    /** Gentag [step] hvert [ms] millisekund, så længe siden vises */
    private fun loop(ms: Long, step: (Int) -> Unit) {
        var n = 0
        val r = object : Runnable {
            override fun run() { step(n++); handler.postDelayed(this, ms) }
        }
        loops += r
        handler.post(r)
    }

    private fun stopAnimations() {
        loops.forEach { handler.removeCallbacks(it) }
        loops.clear()
        animators.forEach { it.cancel() }
        animators.clear()
    }

    override fun onDestroy() {
        stopAnimations()
        super.onDestroy()
    }

    // ---------- Byggeklodser til tegningerne ----------

    /**
     * Holder én tegning: den får sin egen størrelse (eller den, den har bedt om), centreres,
     * og gøres mindre, hvis den ikke kan være der – så intet skæres af eller klemmes.
     */
    private class FitBox(ctx: Context, private val margin: Int) : FrameLayout(ctx) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val w = MeasureSpec.getSize(widthSpec)
            val h = MeasureSpec.getSize(heightSpec)
            getChildAt(0)?.let { c ->
                val lp = c.layoutParams
                c.measure(
                    if (lp.width > 0) MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY) else MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST),
                    if (lp.height > 0) MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY) else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                )
            }
            setMeasuredDimension(w, h)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val c = getChildAt(0) ?: return
            val cw = c.measuredWidth
            val ch = c.measuredHeight
            val x = (width - cw) / 2
            val y = (height - ch) / 2
            c.layout(x, y, x + cw, y + ch)
            val s = minOf(1f, (height - margin).toFloat() / ch.coerceAtLeast(1), width.toFloat() / cw.coerceAtLeast(1))
            c.pivotX = cw / 2f
            c.pivotY = ch / 2f
            c.scaleX = s
            c.scaleY = s
        }
    }

    /** En rullebar visning, der kun rulles af guiden selv (fingeren bladrer stadig mellem siderne) */
    private fun stillScroll(horizontal: Boolean): FrameLayout {
        val v: FrameLayout = if (horizontal) object : android.widget.HorizontalScrollView(this) {
            override fun onInterceptTouchEvent(ev: MotionEvent) = false
            override fun onTouchEvent(ev: MotionEvent) = false
        } else object : android.widget.ScrollView(this) {
            override fun onInterceptTouchEvent(ev: MotionEvent) = false
            override fun onTouchEvent(ev: MotionEvent) = false
        }
        v.isVerticalScrollBarEnabled = false
        v.isHorizontalScrollBarEnabled = false
        v.overScrollMode = View.OVER_SCROLL_NEVER
        return v
    }

    /** Rul [v] blødt til [to] (vandret eller lodret) */
    private fun glide(v: View, horizontal: Boolean, to: Int, ms: Long) {
        val from = if (horizontal) v.scrollX else v.scrollY
        animators += ValueAnimator.ofInt(from, to).apply {
            duration = ms
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { val p = it.animatedValue as Int; if (horizontal) v.scrollTo(p, 0) else v.scrollTo(0, p) }
            start()
        }
    }

    /** Et kort tryk på en knap */
    private fun press(v: View) {
        v.animate().scaleX(0.9f).scaleY(0.9f).setStartDelay(0).setDuration(90).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(140).start()
        }.start()
    }

    private fun rounded(color: Int, r: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    // Alle byggeklodser får "så stor som indholdet" som udgangspunkt – ellers kan en lodret boks skære teksten af
    private fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun text(t: String, size: Float, color: Int = tc.fg, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        maxLines = 1
        layoutParams = wrap()
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun icon(res: Int, color: Int, size: Int) = ImageView(this).apply {
        setImageResource(res)
        imageTintList = android.content.res.ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun card(vararg children: View, pad: Int = 12) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = wrap()
        background = rounded(tc.card, 14)
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        for (c in children) addView(c)
    }

    private fun hrow(vararg children: View, gap: Int = 8) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = wrap()
        children.forEachIndexed { k, c ->
            val lp = (c.layoutParams as? LinearLayout.LayoutParams)
                ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (k > 0) lp.marginStart = dp(gap)
            addView(c, lp)
        }
    }

    /** Pillen fra bjælken */
    private fun pill(color: Int, iconRes: Int, words: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = wrap()
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(color, 20)
        setPadding(dp(10), dp(5), dp(12), dp(5))
        addView(icon(iconRes, Color.WHITE, 16))
        addView(text(words, 14f, Color.WHITE, true).apply { setPadding(dp(6), 0, 0, 0) })
    }

    /** En kontakt (som Ciceros), der kan slås til og fra */
    private inner class Switch(on: Boolean) {
        private val thumb = View(this@GuideActivity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            elevation = dpf(2f)
        }
        private val track = GradientDrawable().apply { cornerRadius = dpf(12f) }
        val view = FrameLayout(this@GuideActivity).apply {
            background = track
            addView(thumb, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER_VERTICAL).apply { marginStart = dp(3) })
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(24))
        }
        fun set(on: Boolean) {
            track.setColor(if (on) tc.blue else tc.key)
            thumb.animate().translationX(if (on) dpf(20f) else 0f).setDuration(200).start()
        }
        init { set(on) }
    }

    // ---------- Siderne ----------

    private fun buildPages() = listOf(
        Page(
            "Velkommen til NFClibra",
            "NFClibra gør telefonens NFC til RFID-læser i Cicero Mobile. Swipe eller tryk Næste for en kort guide.",
        ) {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(FrameLayout(this@GuideActivity).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(tc.blue) }
                    addView(icon(R.drawable.ic_menu_nfc, Color.WHITE, 64), FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
                }, LinearLayout.LayoutParams(dp(130), dp(130)))
                addView(text("NFClibra", 28f, tc.fg, true).apply { setPadding(0, dp(16), 0, 0) })
            }
        },
        Page(
            "Hold bogen mod telefonen",
            "Ikonet til venstre viser, om Cicero lytter. Når en bog læses, vibrerer telefonen, og pillen viser, hvad der skete: grøn = alarm fra, orange = alarm til, rød = fejl, grå = læst.",
        ) {
            val slot = FrameLayout(this)
            val states = listOf(
                Triple(tc.green, R.drawable.ic_bell_off, "Alarm fra"),
                Triple(tc.orange, R.drawable.ic_bell, "Alarm til"),
                Triple(tc.red, R.drawable.ic_warn, "Fejl"),
                Triple(Color.parseColor("#718096"), R.drawable.ic_check, "Læst"),
            )
            val rfid = FrameLayout(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(tc.blue) }
                addView(icon(R.drawable.ic_rfid_on, Color.WHITE, 18), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
            }
            val bar = hrow(rfid, slot.apply { layoutParams = LinearLayout.LayoutParams(dp(130), dp(32)) }, View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), 1)
            }, icon(R.drawable.ic_menu_settings, tc.fg, 22)).apply {
                background = rounded(tc.card, 14)
                setPadding(dp(10), dp(8), dp(12), dp(8))
            }
            loop(1300) { n ->
                val (c, ic, w) = states[n % states.size]
                slot.removeAllViews()
                slot.addView(pill(c, ic, w).apply { alpha = 0f; animate().alpha(1f).setDuration(200).start() },
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
            }
            bar
        },
        Page(
            "Menuen",
            "Tandhjulet øverst til højre åbner menuen. Under Indstillinger kan du fx slå lyd og pinkode-tastaturet til og fra. Guiden finder du igen her.",
        ) {
            fun item(res: Int, t: String, hi: Boolean = false) = hrow(icon(res, if (hi) tc.blue else tc.sub, 18), text(t, 15f, if (hi) tc.blue else tc.fg, hi), gap = 12).apply {
                setPadding(dp(6), dp(6), dp(16), dp(6))
            }
            val menu = card(
                item(R.drawable.ic_menu_log, "Vis log"),
                item(R.drawable.ic_menu_reload, "Genindlæs Cicero"),
                item(R.drawable.ic_menu_lock, "Lås nu"),
                item(R.drawable.ic_menu_settings, "Indstillinger", true),
                item(R.drawable.ic_menu_update, "Søg efter opdatering"),
                item(R.drawable.ic_menu_help, "Guide", true),
                View(this).apply { setBackgroundColor(tc.key); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(0, dp(6), 0, dp(8)) } },
                hrow(icon(R.drawable.ic_menu_nfc, Color.WHITE, 18), text("Værktøj til tags", 15f, Color.WHITE, true)).apply {
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    background = rounded(tc.blue, 20)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                },
                pad = 8,
            )
            menu
        },
        Page(
            "Værktøj til tags",
            "Den blå knap nederst i menuen. Se hvad der står på en chip, slå alarmen til eller fra, nulstil den, eller programmér en ny – materialenummeret kan læses med kameraet, både som stregkode og som tal.",
        ) {
            val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
            val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
            fun tile(l: String, v: View) = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(tc.tile, 8)
                setPadding(dp(8), dp(5), dp(8), dp(6))
                addView(text(l, 11f, tc.sub))
                addView(v)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            fun row(vararg c: View, top: Int = 6) = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                c.forEachIndexed { k, v ->
                    if (k > 0) (v.layoutParams as LinearLayout.LayoutParams).marginStart = dp(6)
                    addView(v)
                }
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) }
            }
            fun btn(t: String, bg: Int, fg: Int, weight: Boolean = true) = text(t, 12f, fg, true).apply {
                gravity = Gravity.CENTER
                background = rounded(bg, 10)
                setPadding(dp(10), dp(7), dp(10), dp(7))
                layoutParams = if (weight) LinearLayout.LayoutParams(0, WRAP, 1f) else wrap()
            }
            fun cardBox(vararg c: View) = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(tc.card, 12)
                setPadding(dp(10), dp(8), dp(10), dp(10))
                for (v in c) addView(v)
            }

            val libRow = row(
                text("Bibliotek: DK-715900", 12f, tc.sub).apply { layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) },
                text("Ret", 12f, tc.blue, true),
                top = 0,
            ).apply { setPadding(dp(2), 0, dp(2), dp(6)) }

            val state = text("", 12f, tc.sub)
            val number = text("", 20f, tc.fg, true).apply {
                typeface = Typeface.create("monospace", Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            val copy = text("Kopiér", 12f, tc.blue, true)
            val vDel = text("", 13f)
            val vLib = text("", 13f)
            val vAlarm = text("", 13f)
            val vChip = text("", 13f)
            val chipCard = cardBox(
                state,
                text("Materialenummer", 11f, tc.sub).apply { setPadding(0, dp(2), 0, 0) },
                row(number, copy, top = 0),
                row(tile("Del", vDel), tile("Bibliotek", vLib)),
                row(tile("Alarm", vAlarm), tile("Chip", vChip)),
            )
            val check = FrameLayout(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(tc.green) }
                addView(icon(R.drawable.ic_check, Color.WHITE, 30), FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
                alpha = 0f
            }
            val chipBox = FrameLayout(this).apply {
                addView(chipCard)
                addView(check, FrameLayout.LayoutParams(dp(50), dp(50), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(8), dp(8), 0) })
            }

            val bOn = btn("Alarm til", tc.orange, Color.WHITE)
            val bOff = btn("Alarm fra", tc.green, Color.WHITE)
            val bReset = btn("Nulstil", tc.key, tc.red)
            val buttons = row(bOn, bOff, bReset, top = 10)

            val input = text("", 12f, tc.sub).apply {
                setPadding(dp(8), dp(7), dp(8), dp(7))
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            val cam = btn("Kamera", tc.blue, Color.WHITE, weight = false)
            val parts = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                for (k in 1..6) addView(text("$k", 12f, if (k == 1) Color.WHITE else tc.fg, true).apply {
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (k == 1) tc.blue else tc.key) }
                }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(5) })
                addView(text("Flere…", 12f, tc.fg, true).apply {
                    gravity = Gravity.CENTER
                    background = rounded(tc.key, 13)
                    setPadding(dp(10), 0, dp(10), 0)
                }, LinearLayout.LayoutParams(WRAP, dp(26)))
            }
            val progCard = cardBox(
                text("Programmér ny chip", 12f, tc.sub),
                row(input, btn("ABC", tc.key, tc.fg, weight = false), cam),
                text("Antal dele", 11f, tc.sub).apply { setPadding(0, dp(8), 0, dp(4)) },
                parts,
                text("Programmér chip", 13f, Color.WHITE, true).apply {
                    gravity = Gravity.CENTER
                    background = rounded(tc.blue, 14)
                    setPadding(0, dp(7), 0, dp(7))
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) }
                },
            ).apply { layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) } }

            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(libRow)
                addView(chipBox)
                addView(buttons)
                addView(progCard)
                layoutParams = FrameLayout.LayoutParams(dp(300), WRAP)
            }

            fun setInput(t: String?, picked: Boolean = false) {
                input.text = t ?: "Materialenummer"
                input.setTextColor(if (t == null) tc.sub else tc.fg)
                input.background = rounded(if (picked) Color.argb(60, Color.red(tc.blue), Color.green(tc.blue), Color.blue(tc.blue)) else tc.card, 8, if (picked) tc.blue else tc.sub)
            }
            fun setAlarm(on: Boolean?) {
                vAlarm.text = when (on) { null -> "–"; true -> "Til"; false -> "Fra" }
                if (on == null) {
                    vAlarm.background = null
                    vAlarm.setTextColor(tc.fg)
                    vAlarm.setPadding(0, 0, 0, 0)
                } else {
                    vAlarm.background = rounded(if (on) tc.orange else tc.green, 10)
                    vAlarm.setTextColor(Color.WHITE)
                    vAlarm.setPadding(dp(8), 0, dp(8), dp(1))
                }
            }
            fun empty() {
                state.text = "Hold et tag mod telefonen"
                number.text = "–"
                copy.visibility = View.INVISIBLE
                vDel.text = "–"; vLib.text = "–"; vChip.text = "–"
                setAlarm(null)
                buttons.alpha = 0.4f
                setInput(null)
            }
            empty()
            loop(1500) { n ->
                when (n % 6) {
                    0 -> empty()
                    1 -> {
                        // En chip læses: felterne udfyldes, og nummeret står klar i programmeringsfeltet
                        state.text = "Chip ved telefonen"
                        number.text = "5123456789"
                        copy.visibility = View.VISIBLE
                        vDel.text = "1 af 1"; vLib.text = "DK-715900"; vChip.text = "NXP"
                        setAlarm(true)
                        buttons.alpha = 1f
                        setInput("5123456789", picked = true)
                    }
                    2 -> {
                        press(bOff)
                        setAlarm(false)
                        check.scaleX = 0.7f; check.scaleY = 0.7f
                        check.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(150).setDuration(150).withEndAction {
                            check.animate().alpha(0f).setStartDelay(700).setDuration(300).start()
                        }.start()
                    }
                    4 -> {
                        // Kameraet læser et nyt nummer
                        press(cam)
                        setInput(null)
                        handler.postDelayed({ if (input.isAttachedToWindow) setInput("5198765432") }, 700)
                    }
                }
            }
            col
        },
        Page(
            "Pinkode ved udlån",
            "I Udlån sætter NFClibra en kontakt \"Pinkode\" ved siden af \"Send kvittering\". Den slår Ciceros krav om pinkode ved udlån til og fra med ét tryk.",
        ) {
            val send = Switch(true)
            val pin = Switch(false)
            loop(1500) { n -> pin.set(n % 2 == 1) }
            card(
                text("Udlån", 13f, tc.sub),
                hrow(send.view, text("Send kvittering", 15f), View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(16), 1) }, pin.view, text("Pinkode", 15f, tc.fg, true)).apply {
                    setPadding(0, dp(10), 0, dp(4))
                },
                pad = 14,
            )
        },
        Page(
            "Pinkode-tastaturet",
            "Når en låner skal taste pinkode, kommer et stort tastatur frem. Vend drejer cifrene mod låneren – dine knapper bliver stående. Stor giver ekstra store taster.",
        ) {
            val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
            val keys = ArrayList<TextView>()
            for (r in listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("←", "0", "OK"))) {
                grid.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    for (k in r) addView(text(k, 18f, if (k == "OK") Color.WHITE else tc.fg, true).apply {
                        gravity = Gravity.CENTER
                        background = rounded(if (k == "OK") tc.blue else tc.key, 8)
                        keys += this
                    }, LinearLayout.LayoutParams(dp(56), dp(40)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
                })
            }
            val staff = hrow(text("Annuller", 11f, tc.sub), text("Tastatur", 11f, tc.sub), text("Vend", 11f, tc.sub, true), text("Stor", 11f, tc.sub, true), gap = 14).apply {
                background = rounded(tc.card, 10)
                setPadding(dp(12), dp(6), dp(12), dp(6))
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(text("Indtast pinkode", 14f, tc.fg, true).apply { gravity = Gravity.CENTER })
                addView(grid)
            }
            val pad = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = rounded(tc.bg, 14, tc.key)
                setPadding(dp(8), dp(10), dp(8), dp(8))
                addView(col)
                addView(staff, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }
            val hc = Color.parseColor("#FFD400")
            loop(1800) { n ->
                when (n % 4) {
                    0 -> { col.animate().rotation(0f).setDuration(400).start(); setBig(keys, false, hc) }
                    1 -> col.animate().rotation(180f).setDuration(500).start()
                    2 -> { col.animate().rotation(0f).setDuration(500).start() }
                    3 -> setBig(keys, true, hc)
                }
            }
            pad
        },
        Page(
            "Faner kan swipes",
            "Fanerækken øverst i Cicero kan swipes. I stedet for at trykke på pilene skubber du bare fanerne til siden med fingeren.",
        ) {
            val strip = stillScroll(horizontal = true)
            val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            listOf("LÅNERE", "MELLEMVÆRENDE", "BESKEDER", "MEDLEMSKAB").forEachIndexed { k, t ->
                tabs.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(16), dp(12), dp(16), 0)
                    addView(text(t, 13f, if (k == 0) tc.blue else tc.sub, true))
                    addView(View(this@GuideActivity).apply { setBackgroundColor(if (k == 0) tc.blue else Color.TRANSPARENT) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).apply { topMargin = dp(10) })
                })
            }
            strip.addView(tabs)
            fun line(w: Int) = View(this).apply {
                background = rounded(tc.tile, 6)
                layoutParams = LinearLayout.LayoutParams(dp(w), dp(14)).apply { topMargin = dp(10) }
            }
            val screen = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(tc.card, 12)
                clipToOutline = true
                addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(View(this@GuideActivity).apply { setBackgroundColor(tc.key) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
                addView(LinearLayout(this@GuideActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(4), dp(16), dp(16))
                    addView(line(200)); addView(line(150)); addView(line(230)); addView(line(120))
                })
            }
            val finger = View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(tc.fg) }
                alpha = 0f
            }
            val box = FrameLayout(this).apply {
                addView(screen)
                addView(finger, FrameLayout.LayoutParams(dp(34), dp(34)).apply { leftMargin = dp(200); topMargin = dp(8) })
                layoutParams = FrameLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val dist = dp(160)
            loop(2600) { n ->
                // Fingeren lægges på, skubber fanerne, og løftes igen
                val toLeft = n % 2 == 0
                finger.translationX = if (toLeft) 0f else -dist.toFloat()
                finger.animate().alpha(0.35f).setStartDelay(0).setDuration(150).withEndAction {
                    finger.animate().translationX(if (toLeft) -dist.toFloat() else 0f).setStartDelay(0).setDuration(800).withEndAction {
                        finger.animate().alpha(0f).setStartDelay(0).setDuration(200).start()
                    }.start()
                    glide(strip, horizontal = true, to = if (toLeft) dist else 0, ms = 800)
                }.start()
            }
            box
        },
        Page(
            "Nulstil og Søg altid ved hånden",
            "Under søgninger ligger Nulstil og Søg som en lille pille nederst – også når du ruller ned i resultaterne. Til toppen-knappen er der stadig.",
        ) {
            val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
            val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
            fun field(label: String?, value: String) = FrameLayout(this).apply {
                addView(text(value, 13f, if (label != null) tc.fg else tc.sub).apply {
                    background = rounded(Color.TRANSPARENT, 6, tc.sub)
                    setPadding(dp(10), dp(9), dp(10), dp(9))
                }, FrameLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
                if (label != null) addView(text(label, 10f, tc.sub).apply { setBackgroundColor(tc.card); setPadding(dp(3), 0, dp(3), 0) },
                    FrameLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) }
            }
            val sw = Switch(true)
            val searchCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(tc.card, 10)
                setPadding(dp(12), dp(10), dp(12), dp(12))
                addView(hrow(sw.view, text("Beholdning", 13f)))
                addView(field("Titel", "Harry Potter"))
                addView(field(null, "Forfatter"))
                addView(field(null, "Emne"))
                addView(text("Flere filtre", 13f, tc.fg, true).apply { setPadding(dp(8), dp(12), 0, 0) })
            }
            val covers = listOf("#E05A7A", "#E8604C", "#7A5CC8", "#5C7FC8", "#B04A3A", "#3E9E6A", "#4A6FB0", "#C89A3A")
            val titles = listOf("Pocket Potters – Luna", "Potter mini – Luna", "Potter mini – Dumbledore", "Pocket Potters – Dumbledore",
                "Lyden af eventyr", "Pocket Potters – Hermione", "Pocket Potters – Ron", "Potter mini – Hagrid")
            val results = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(tc.card, 10)
                setPadding(dp(12), dp(10), dp(12), dp(6))
                addView(text("1 – 30 af 128 resultater", 12f, tc.sub).apply { setPadding(0, 0, 0, dp(6)) })
                titles.forEachIndexed { k, t ->
                    addView(LinearLayout(this@GuideActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(4), dp(7), dp(4), dp(7))
                        if (k % 2 == 0) background = rounded(tc.tile, 4)
                        addView(View(this@GuideActivity).apply { background = rounded(Color.parseColor(covers[k]), 2) }, LinearLayout.LayoutParams(dp(16), dp(22)))
                        addView(LinearLayout(this@GuideActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(text(t, 12f))
                            addView(text("Rowling, Joanne K.", 11f, tc.sub))
                        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })
                    }, LinearLayout.LayoutParams(MATCH, WRAP))
                }
            }
            val page = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(60))
                addView(searchCard, LinearLayout.LayoutParams(MATCH, WRAP))
                addView(results, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
            }
            val scroller = stillScroll(horizontal = false).apply { addView(page) }
            val pillRow = hrow(
                text("Nulstil", 13f, tc.fg, true),
                text("Søg", 13f, Color.WHITE, true).apply { background = rounded(tc.blue, 14); setPadding(dp(14), dp(5), dp(14), dp(5)) },
                gap = 14,
            ).apply {
                background = rounded(tc.card, 22, tc.key)
                elevation = dpf(4f)
                setPadding(dp(16), dp(6), dp(8), dp(6))
            }
            val up = FrameLayout(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(tc.key) }
                elevation = dpf(4f)
                addView(icon(R.drawable.ic_chevron_up, tc.fg, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                alpha = 0f
            }
            val box = FrameLayout(this).apply {
                background = rounded(tc.bg, 14, tc.key)
                clipToOutline = true
                addView(scroller, FrameLayout.LayoutParams(MATCH, MATCH))
                addView(pillRow, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(10) })
                addView(up, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(12), 0, 0, dp(12)) })
                layoutParams = FrameLayout.LayoutParams(dp(270), dp(340))
            }
            // Siden ruller ned og op igen – pillen bliver liggende, og "til toppen" kommer frem, når der er rullet
            loop(2800) { n ->
                val down = n % 2 == 1
                val max = (page.height - scroller.height).coerceAtLeast(0)
                glide(scroller, horizontal = false, to = if (down) max else 0, ms = 1300)
                up.animate().alpha(if (down) 1f else 0f).setStartDelay(if (down) 400 else 0).setDuration(250).start()
            }
            box
        },
        Page(
            "Kun NFC?",
            "Vil du kun bruge NFClibra som RFID-læser? Under Indstillinger kan du slå alle ændringerne af Cicero fra – swipe-faner, pinkode-kontakten og Nulstil/Søg-pillen. Så ser Cicero ud præcis som normalt.",
        ) {
            fun box(on: Boolean) = TextView(this).apply {
                text = if (on) "✓" else ""
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = if (on) rounded(tc.blue, 4) else rounded(Color.TRANSPARENT, 4, tc.sub)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            }
            fun row(t: String, b: View) = hrow(text(t, 15f).apply { layoutParams = LinearLayout.LayoutParams(dp(200), ViewGroup.LayoutParams.WRAP_CONTENT) }, b).apply {
                setPadding(0, dp(8), 0, dp(8))
            }
            val tweaks = box(true)
            loop(1600) { n ->
                val on = n % 2 == 0
                tweaks.text = if (on) "✓" else ""
                tweaks.background = if (on) rounded(tc.blue, 4) else rounded(Color.TRANSPARENT, 4, tc.sub)
            }
            card(
                text("Indstillinger", 17f, tc.fg, true).apply { setPadding(0, 0, 0, dp(6)) },
                row("Lyd ved alarmskift", box(true)),
                row("Pinkode-tastatur", box(true)),
                row("Vend pinkode-tastatur", box(false)),
                row("Tilpasninger af Cicero", tweaks),
                pad = 16,
            )
        },
        Page(
            "Låsen",
            "NFClibra låser sig efter 5 minutter uden brug og låses op med fingeraftryk eller pinkode. Mens den er låst, er Cicero skjult, og bøger læses ikke.",
        ) {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = rounded(tc.card, 16)
                setPadding(dp(40), dp(28), dp(40), dp(28))
                val logo = LockLogo(this@GuideActivity).apply { setColors(body = tc.fg, shackle = tc.sub, cutout = tc.card) }
                addView(logo, LinearLayout.LayoutParams(dp(78), dp(114)))
                loop(1800) { n -> if (n % 2 == 1) logo.animateOpen() else logo.animateClose() }
                addView(text("NFClibra er låst", 18f, tc.fg, true).apply { setPadding(0, dp(12), 0, 0) })
                addView(text("Tryk for at låse op", 14f, tc.sub).apply { setPadding(0, dp(4), 0, 0) })
            }
        },
        Page(
            "God fornøjelse",
            "Jeg håber, NFClibra bliver til glæde og gavn – ved skranken og ude mellem hylderne. Har du idéer, eller driller noget, så sig endelig til.\n\n– Kasper",
        ) {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = wrap()
                addView(FrameLayout(this@GuideActivity).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(tc.green) }
                    addView(icon(R.drawable.ic_check, Color.WHITE, 64), FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
                }, LinearLayout.LayoutParams(dp(120), dp(120)))
                addView(text("NFClibra", 24f, tc.fg, true).apply { setPadding(0, dp(14), 0, 0) })
            }
        },
    )

    private fun setBig(keys: List<TextView>, big: Boolean, hc: Int) {
        for (k in keys) {
            val ok = k.text == "OK"
            k.setTextColor(if (big) (if (ok) Color.BLACK else hc) else (if (ok) Color.WHITE else tc.fg))
            k.background = GradientDrawable().apply {
                cornerRadius = dpf(8f)
                setColor(if (big) (if (ok) hc else Color.BLACK) else (if (ok) tc.blue else tc.key))
                if (big) setStroke(dp(2), hc)
            }
            k.textSize = if (big) 22f else 18f
        }
    }

    /** Tryk tæller som brug; er app'en låst eller glemt i 5 minutter, lukkes guiden */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (Hub.locked || Hub.idleTooLong()) { finish(); return true }
        Hub.lastActivity = SystemClock.elapsedRealtime()
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        if (Hub.locked || Hub.idleTooLong()) finish()
    }
}

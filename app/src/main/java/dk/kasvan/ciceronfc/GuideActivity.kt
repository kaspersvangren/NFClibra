package dk.kasvan.ciceronfc

import android.animation.ValueAnimator
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
        stage.addView(p.build(), FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
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
            "Tandhjulet øverst til højre åbner menuen. Under Indstillinger slår du lyd, pinkode-tastaturet og tilpasningerne af Cicero til og fra. Guiden finder du igen her.",
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
            "Den blå knap nederst i menuen. Se hvad der står på en chip, slå alarmen til eller fra, nulstil den, eller programmér en ny – materialenummeret kan læses med kameraet.",
        ) {
            fun tile(l: String, v: View) = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(tc.tile, 8)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                addView(text(l, 11f, tc.sub))
                addView(v)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val alarm = text("Til", 13f, Color.WHITE, true).apply { background = rounded(tc.orange, 12); setPadding(dp(8), dp(1), dp(8), dp(2)) }
            val c = card(
                text("Chip ved telefonen", 12f, tc.sub),
                text("5123456789", 22f, tc.fg, true).apply { typeface = Typeface.create("monospace", Typeface.BOLD) },
                hrow(tile("Del", text("1 af 1", 14f)), tile("Alarm", alarm), gap = 6).apply {
                    setPadding(0, dp(6), 0, 0)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                },
                hrow(
                    text("Alarm til", 13f, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = rounded(tc.orange, 10); setPadding(dp(10), dp(8), dp(10), dp(8)) },
                    text("Alarm fra", 13f, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = rounded(tc.green, 10); setPadding(dp(10), dp(8), dp(10), dp(8)) },
                    text("Nulstil", 13f, tc.red, true).apply { gravity = Gravity.CENTER; background = rounded(tc.key, 10); setPadding(dp(10), dp(8), dp(10), dp(8)) },
                    gap = 6,
                ).apply { setPadding(0, dp(10), 0, 0) },
            ).apply { layoutParams = FrameLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT) }
            val check = FrameLayout(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(tc.green) }
                addView(icon(R.drawable.ic_check, Color.WHITE, 48), FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
                alpha = 0f
            }
            val box = FrameLayout(this).apply {
                addView(c)
                addView(check, FrameLayout.LayoutParams(dp(90), dp(90), Gravity.CENTER))
            }
            loop(2600) { n ->
                // Skiftevis: alarmen slås fra (grønt flueben), og til igen
                val on = n % 2 == 1
                alarm.text = if (on) "Til" else "Fra"
                alarm.background = rounded(if (on) tc.orange else tc.green, 12)
                check.scaleX = 0.7f; check.scaleY = 0.7f
                check.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).withEndAction {
                    check.animate().alpha(0f).setStartDelay(600).setDuration(350).start()
                }.start()
            }
            box
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
            "Søgninger i Cicero",
            "Fanerækken øverst kan swipes, så du når alle faner. Nulstil og Søg ligger altid som en lille pille nederst – også når du ruller ned i resultaterne.",
        ) {
            val tabs = hrow(
                text("LÅNERE", 13f, tc.blue, true), text("MELLEMVÆRENDE", 13f, tc.fg, true),
                text("BESKEDER", 13f, tc.fg, true), text("RESERVATIONER", 13f, tc.fg, true), gap = 22,
            )
            val tabClip = FrameLayout(this).apply {
                clipChildren = true
                addView(tabs, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
                layoutParams = LinearLayout.LayoutParams(dp(280), dp(36))
            }
            fun field(t: String) = text(t, 13f, tc.sub).apply {
                background = rounded(tc.card, 6, tc.key)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            }
            val pillRow = hrow(
                text("Nulstil", 13f, tc.fg, true),
                text("Søg", 13f, Color.WHITE, true).apply { background = rounded(tc.blue, 14); setPadding(dp(14), dp(5), dp(14), dp(5)) },
                gap = 14,
            ).apply {
                background = rounded(tc.card, 22)
                elevation = dpf(4f)
                setPadding(dp(16), dp(6), dp(8), dp(6))
            }
            val form = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = rounded(tc.tile, 12)
                setPadding(dp(10), dp(4), dp(10), dp(48))
                addView(tabClip)
                addView(field("Låner"))
                addView(field("Navn"))
                addView(field("Telefon"))
            }
            val box = FrameLayout(this).apply {
                addView(form)
                addView(pillRow, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin = dp(8)
                })
            }
            loop(2200) { n -> tabs.animate().translationX(if (n % 2 == 1) -dpf(150f) else 0f).setDuration(700).start() }
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
                addView(icon(R.drawable.ic_lock, tc.fg, 56))
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

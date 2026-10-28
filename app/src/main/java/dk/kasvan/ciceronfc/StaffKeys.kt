package dk.kasvan.ciceronfc

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Lille tastatur til personalet i felterne Låner, telefonnumre og Materialenummer: tal, slet, Enter,
 * ABC (telefonens tastatur), skjul og én tast, der skifter efter feltet: C, + eller kamera.
 * Ved Låner har nederste række desuden et kamera (fx til en QR-kode med lånernummeret fra pc'en).
 * Det ligger i bunden som telefonens eget tastatur. Tasterne sendes til Cicero som rigtige tastetryk,
 * så Cicero opfører sig, som hvis der blev tastet på et tastatur. Intet gemmes.
 */
class StaffKeys(private val ctx: Context, private val dp: (Int) -> Int) {
    /** Et tegn ('0'..'9', 'C') eller en af tasterne nedenfor */
    var onKey: ((Key) -> Unit)? = null

    sealed class Key {
        class Char(val c: kotlin.Char) : Key()
        object Delete : Key()
        object Enter : Key()
        object Abc : Key()
        object Hide : Key()
        /** C (Låner), + (telefon) eller kamera (Materialenummer) */
        object Special : Key()
        /** Kamera i nederste række – kun ved Låner */
        object Camera : Key()
    }

    enum class Mode { LAANER, PHONE, ITEM }

    var mode = Mode.LAANER
        private set
    private lateinit var specialText: TextView
    private lateinit var specialIcon: ImageView

    private val keyBgs = ArrayList<GradientDrawable>()
    private val labels = ArrayList<TextView>()
    private val icons = ArrayList<ImageView>()
    private val smallBgs = ArrayList<GradientDrawable>()
    private lateinit var enterBg: GradientDrawable
    private var cameraKey: View? = null
    private var enterKey: View? = null

    val view = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(8))
        visibility = View.GONE
        isClickable = true // tryk ved siden af tasterne må ikke gå igennem
    }

    val isShowing get() = view.visibility == View.VISIBLE

    init {
        row(Key.Char('1'), Key.Char('2'), Key.Char('3'), Key.Delete)
        row(Key.Char('4'), Key.Char('5'), Key.Char('6'), Key.Special)
        row(Key.Char('7'), Key.Char('8'), Key.Char('9'), Key.Abc)
        row(Key.Hide, Key.Camera, Key.Char('0'), Key.Enter)
    }

    private fun row(vararg ks: Key) {
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (k in ks) {
            val bg = GradientDrawable().apply { cornerRadius = dp(8).toFloat() }
            val small = k !is Key.Char
            val content: View = when (k) {
                is Key.Delete -> icon(R.drawable.ic_backspace, "Slet")
                is Key.Hide -> icon(R.drawable.ic_keyboard_hide, "Skjul tastaturet")
                is Key.Camera -> icon(R.drawable.ic_camera, "Kamera")
                is Key.Special -> FrameLayout(ctx).apply {
                    specialText = TextView(ctx).apply {
                        gravity = Gravity.CENTER
                        textSize = 18f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        labels += this
                    }
                    specialIcon = icon(R.drawable.ic_camera, "Kamera")
                    addView(specialText, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                    addView(specialIcon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                }
                else -> TextView(ctx).apply {
                    text = when (k) {
                        is Key.Char -> k.c.toString()
                        is Key.Abc -> "ABC"
                        else -> "Enter"
                    }
                    gravity = Gravity.CENTER
                    textSize = if (k is Key.Char) 22f else 16f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    labels += this
                }
            }
            val key = FrameLayout(ctx).apply {
                background = bg
                // Tasterne må ikke tage fokus fra Ciceros felt
                isFocusable = false
                isFocusableInTouchMode = false
                addView(content, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onKey?.invoke(k)
                }
                // Hold slet nede: slet hele feltet
                if (k is Key.Delete) setOnLongClickListener {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    repeat(40) { onKey?.invoke(Key.Delete) }
                    true
                }
            }
            if (k is Key.Camera) cameraKey = key
            if (k is Key.Enter) enterKey = key
            when {
                k is Key.Enter -> enterBg = bg
                small -> smallBgs += bg
                else -> keyBgs += bg
            }
            r.addView(key, LinearLayout.LayoutParams(0, dp(46), if (k is Key.Enter) 2f else 1f).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        view.addView(r, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun icon(res: Int, desc: String) = ImageView(ctx).apply {
        setImageResource(res)
        contentDescription = desc
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(12), dp(12), dp(12), dp(12))
        icons += this
    }

    fun setColors(bg: Int, text: Int, key: Int, blue: Int) {
        view.setBackgroundColor(ToolColors.darker(bg))
        keyBgs.forEach { it.setColor(key) }
        smallBgs.forEach { it.setColor(ToolColors.mix(key, ToolColors.darker(bg))) }
        enterBg.setColor(blue)
        labels.forEach { it.setTextColor(if (it.text.toString() == "Enter") Color.WHITE else text) }
        icons.forEach { it.imageTintList = ColorStateList.valueOf(text) }
    }

    fun show(m: Mode) {
        mode = m
        specialText.text = if (m == Mode.PHONE) "+" else "C"
        specialText.visibility = if (m == Mode.ITEM) View.GONE else View.VISIBLE
        specialIcon.visibility = if (m == Mode.ITEM) View.VISIBLE else View.GONE
        // Ved Låner: skjul · kamera · 0 · Enter (fire lige brede taster under rækkerne ovenover). Ellers: skjul · 0 · bred Enter
        val laaner = m == Mode.LAANER
        cameraKey?.visibility = if (laaner) View.VISIBLE else View.GONE
        (enterKey?.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.weight = if (laaner) 1f else 2f
            enterKey?.layoutParams = lp
        }
        view.visibility = View.VISIBLE
    }
    fun hide() { view.visibility = View.GONE }

}

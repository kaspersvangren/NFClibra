package dk.kasvan.ciceronfc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Rundvisning: mørk skærm med et "hul" om det, der forklares, og en boble med tekst og knapper.
 * Bruges både til rundvisningen i app'en og til de små tips inde i Cicero (ét trin ad gangen).
 */
class Tour(
    private val ctx: Context,
    private val host: FrameLayout,
    private val dp: (Int) -> Int,
    private val bubbleBg: Int,
    private val textColor: Int,
    private val subColor: Int,
    private val accent: Int,
) {
    /** Et trin. [target] giver det, der skal peges på, i [host]'s koordinater (null = boblen midt på skærmen). */
    class Step(
        val title: String,
        val text: String,
        val target: (() -> Rect?)? = null,
        val onEnter: (() -> Unit)? = null,
        val onLeave: (() -> Unit)? = null,
    )

    private var steps: List<Step> = emptyList()
    private var index = 0
    private var onDone: (() -> Unit)? = null
    private var dim = 0xB0000000.toInt()

    val isShowing get() = overlay.parent != null

    private val hole = RectF()
    private var hasHole = false
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = dp(2).toFloat()
    }

    private val overlay = object : FrameLayout(ctx) {
        init {
            setWillNotDraw(false)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isClickable = true // tryk må ikke gå igennem til det bagved
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(dim)
            if (hasHole) {
                val r = dp(14).toFloat()
                canvas.drawRoundRect(hole, r, r, clear)
                canvas.drawRoundRect(hole, r, r, ring)
            }
        }
    }

    private val title = TextView(ctx).apply {
        textSize = 18f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(textColor)
    }
    private val body = TextView(ctx).apply {
        textSize = 15f
        setTextColor(textColor)
        setLineSpacing(0f, 1.15f)
    }
    private val counter = TextView(ctx).apply {
        textSize = 13f
        setTextColor(subColor)
    }
    private val skip = TextView(ctx).apply {
        text = "Spring over"
        textSize = 15f
        setTextColor(subColor)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setOnClickListener { finish() }
    }
    private val next = TextView(ctx).apply {
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(18).toFloat() }
        setPadding(dp(18), dp(9), dp(18), dp(9))
        setOnClickListener { go(index + 1) }
    }
    private val bubble = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(bubbleBg); cornerRadius = dp(16).toFloat() }
        setPadding(dp(16), dp(14), dp(16), dp(10))
        elevation = dp(6).toFloat()
        addView(title)
        addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(counter, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(skip)
            addView(next)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    init {
        overlay.addView(bubble, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(16); rightMargin = dp(16)
        })
    }

    /** Start en rundvisning. [light] = svagere mørklægning (til små tips). */
    fun start(list: List<Step>, light: Boolean = false, done: (() -> Unit)? = null) {
        if (list.isEmpty()) return
        finish(callDone = false)
        steps = list
        onDone = done
        dim = if (light) 0x70000000 else 0xB0000000.toInt()
        skip.visibility = if (list.size > 1) View.VISIBLE else View.GONE
        host.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        go(0)
    }

    fun finish(callDone: Boolean = true) {
        if (!isShowing) return
        steps.getOrNull(index)?.onLeave?.invoke()
        host.removeView(overlay)
        val d = onDone
        onDone = null
        if (callDone) d?.invoke()
    }

    private fun go(i: Int) {
        steps.getOrNull(index)?.onLeave?.invoke()
        if (i >= steps.size) { finish(); return }
        index = i
        val s = steps[i]
        s.onEnter?.invoke()
        title.text = s.title
        body.text = s.text
        counter.text = if (steps.size > 1) "${i + 1} af ${steps.size}" else ""
        next.text = when {
            steps.size == 1 -> "OK"
            i == steps.size - 1 -> "Færdig"
            else -> "Næste"
        }
        // Vent et øjeblik, så fx et pinkode-tastatur, der lige er vist, har fået sin plads
        overlay.post { place(s) }
    }

    private fun place(s: Step) {
        val r = s.target?.invoke()
        val lp = bubble.layoutParams as FrameLayout.LayoutParams
        if (r == null || r.isEmpty) {
            hasHole = false
            lp.gravity = Gravity.CENTER_VERTICAL
            lp.topMargin = 0
        } else {
            val pad = dp(6).toFloat()
            hole.set(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad)
            hasHole = true
            bubble.measure(
                View.MeasureSpec.makeMeasureSpec(overlay.width - dp(32), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val h = bubble.measuredHeight
            lp.gravity = Gravity.TOP
            // Under det, der peges på, hvis der er plads – ellers over
            lp.topMargin = if (hole.bottom + dp(12) + h < overlay.height) (hole.bottom + dp(12)).toInt()
            else (hole.top - dp(12) - h).toInt().coerceAtLeast(dp(8))
        }
        bubble.layoutParams = lp
        overlay.invalidate()
    }
}

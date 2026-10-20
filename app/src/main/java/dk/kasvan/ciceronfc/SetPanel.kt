package dk.kasvan.ciceronfc

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Pop-op'en, mens et sæt i flere dele er i gang (se Hub.SetRun): én cirkel pr. del.
 * Omrids med tal = ikke læst · blå med flueben = læst · grøn/orange med flueben = alarm skiftet.
 * Tallet bliver stående svagt bag fluebenet. En ring om cirklen = delen ved telefonen lige nu.
 */
class SetPanel(private val ctx: Context, private val dp: (Int) -> Int) {
    var onStop: (() -> Unit)? = null
    var onForce: (() -> Unit)? = null
    var onSkip: (() -> Unit)? = null
    private var phase = -1

    private var cardColor = Color.WHITE
    private var textColor = Color.BLACK
    private var subColor = Color.GRAY
    private var blue = Color.BLUE
    private var green = Color.GREEN
    private var orange = Color.rgb(221, 107, 32)

    private val cardBg = GradientDrawable().apply { cornerRadius = dp(14).toFloat() }
    private val title = text(16f, bold = true)
    private val code = text(13f)
    private val msg = text(16f).apply { gravity = Gravity.CENTER }
    private val sub = text(14f).apply { gravity = Gravity.CENTER }
    private val parts = PartsView()
    private val stopBg = GradientDrawable().apply { cornerRadius = dp(20).toFloat() }
    private val forceBg = GradientDrawable().apply { cornerRadius = dp(20).toFloat() }
    private fun button(bg: GradientDrawable) = text(15f).apply {
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(8), dp(20), dp(8))
        background = bg
    }
    /** Venstre knap: Afbryd (samler) · Luk (venter) · Spring resten over (alarm) */
    private val stop = button(stopBg).apply {
        setOnClickListener { if (phase == Hub.SetView.ALARM) onSkip?.invoke() else onStop?.invoke() }
    }
    /** Højre knap, kun mens delene samles */
    private val force = button(forceBg).apply {
        text = "Gennemfør nu"
        setOnClickListener { onForce?.invoke() }
    }
    private val buttons = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(stop)
        addView(force, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
    }

    private fun text(size: Float, bold: Boolean = false) = TextView(ctx).apply {
        textSize = size
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    val view: FrameLayout = FrameLayout(ctx).apply {
        setBackgroundColor(Color.argb(115, 0, 0, 0))
        visibility = View.GONE
        isClickable = true // Cicero bagved må ikke reagere på tryk, mens sættet er i gang
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(code)
            })
            addView(parts, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14); bottomMargin = dp(12)
            })
            addView(msg)
            addView(sub, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
            addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14)
            })
        }
        addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins(dp(20), 0, dp(20), 0)
        })
    }

    fun setColors(card: Int, text: Int, sub: Int, blue: Int, green: Int, orange: Int, button: Int) {
        cardColor = card; textColor = text; subColor = sub
        this.blue = blue; this.green = green; this.orange = orange
        cardBg.setColor(card)
        stopBg.setColor(button)
        forceBg.setColor(blue)
        force.setTextColor(Color.WHITE)
        title.setTextColor(text); msg.setTextColor(text); stop.setTextColor(text)
        code.setTextColor(sub); this.sub.setTextColor(sub)
        parts.invalidate()
    }

    fun update(v: Hub.SetView?) {
        if (v == null) { view.visibility = View.GONE; phase = -1; return }
        phase = v.phase
        force.visibility = if (v.phase == Hub.SetView.COLLECT) View.VISIBLE else View.GONE
        val alarmColor = if (v.alarmOn) orange else green
        val alarmWord = if (v.alarmOn) "Alarm til" else "Alarm fra"
        code.text = v.barcode
        when (v.phase) {
            Hub.SetView.COLLECT -> {
                title.text = "Sæt i ${v.total} dele"; title.setTextColor(textColor)
                msg.text = "${v.read.size} af ${v.total} læst"
                sub.text = "Hold ${partList((1..v.total).filter { it !in v.read })} mod telefonen"
                stop.text = "Afbryd"; stop.visibility = View.VISIBLE
            }
            Hub.SetView.WAIT -> {
                title.text = "Sæt i ${v.total} dele"; title.setTextColor(textColor)
                msg.text = "Alle ${v.total} dele er meldt til Cicero"
                sub.text = "Venter på Cicero"
                stop.text = "Luk"; stop.visibility = View.VISIBLE
            }
            Hub.SetView.ALARM -> {
                title.text = alarmWord; title.setTextColor(alarmColor)
                msg.text = "${v.alarmDone.size} af ${v.total} klaret"
                sub.text = "Hold ${partList(v.missing.toList())} mod telefonen"
                stop.text = "Spring resten over"; stop.visibility = View.VISIBLE
            }
            else -> {
                title.text = alarmWord; title.setTextColor(alarmColor)
                if (v.virtual.isEmpty() && v.skipped.isEmpty()) {
                    msg.text = "$alarmWord på alle ${v.total} dele"
                    sub.text = ""
                } else {
                    msg.text = if (v.virtual.isNotEmpty()) "Gennemført med ${v.total - v.virtual.size} af ${v.total} dele"
                        else "$alarmWord på ${v.alarmDone.size} af ${v.total} dele"
                    sub.text = listOfNotNull(
                        v.skipped.takeIf { it.isNotEmpty() }?.let { "Ikke skiftet: ${partList(it.toList())}" },
                        v.virtual.takeIf { it.isNotEmpty() }?.let { "Ikke læst: ${partList(it.toList())}" },
                    ).joinToString(" · ")
                }
                stop.visibility = View.GONE
            }
        }
        buttons.visibility = if (stop.visibility == View.VISIBLE || force.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        sub.visibility = if (sub.text.isEmpty()) View.GONE else View.VISIBLE
        parts.state = v
        parts.requestLayout()
        parts.invalidate()
        view.visibility = View.VISIBLE
    }

    fun partList(l: List<Int>): String = when (l.size) {
        0 -> "delene"
        1 -> "del ${l[0]}"
        else -> "del " + l.dropLast(1).joinToString(", ") + " og " + l.last()
    }

    /** Cirklerne – over flere linjer og lidt mindre, når der er mange dele */
    private inner class PartsView : View(ctx) {
        var state: Hub.SetView? = null
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val check = Path()

        private fun diameter(n: Int) = dp(when { n <= 10 -> 46; n <= 20 -> 38; else -> 30 })
        private val gap get() = dp(8)
        private val ringRoom get() = dp(5)

        private fun perRow(w: Int, d: Int) = max(1, (w - 2 * ringRoom + gap) / (d + gap))

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val w = MeasureSpec.getSize(widthSpec)
            val n = state?.total ?: 1
            val d = diameter(n)
            val rows = ceil(n / perRow(w, d).toDouble()).toInt()
            setMeasuredDimension(w, rows * d + (rows - 1) * gap + 2 * ringRoom)
        }

        override fun onDraw(c: Canvas) {
            val v = state ?: return
            val n = v.total
            val d = diameter(n).toFloat()
            val r = d / 2f
            val per = perRow(width, d.toInt())
            val alarmColor = if (v.alarmOn) orange else green
            num.textSize = d * 0.35f
            for (i in 1..n) {
                val row = (i - 1) / per
                val col = (i - 1) % per
                val inRow = min(per, n - row * per)
                val rowW = inRow * d + (inRow - 1) * gap
                val cx = (width - rowW) / 2f + col * (d + gap) + r
                val cy = ringRoom + row * (d + gap) + r
                // ALARM/DONE: farvet = alarm skiftet; ellers farvet (blå) = læst
                val colored = when (v.phase) {
                    Hub.SetView.ALARM, Hub.SetView.DONE -> i in v.alarmDone
                    else -> i in v.read
                }
                val color = if (v.phase == Hub.SetView.ALARM || v.phase == Hub.SetView.DONE) alarmColor else blue
                if (colored) {
                    fill.color = color
                    c.drawCircle(cx, cy, r, fill)
                    // Tallet svagt bag fluebenet, samme sted og størrelse som før
                    num.color = Color.argb(90, 255, 255, 255)
                    c.drawText(i.toString(), cx, cy - (num.descent() + num.ascent()) / 2f, num)
                    stroke.color = Color.WHITE
                    stroke.strokeWidth = d * 0.08f
                    stroke.strokeCap = Paint.Cap.ROUND
                    stroke.strokeJoin = Paint.Join.ROUND
                    check.reset()
                    check.moveTo(cx - r * 0.38f, cy + r * 0.02f)
                    check.lineTo(cx - r * 0.1f, cy + r * 0.3f)
                    check.lineTo(cx + r * 0.42f, cy - r * 0.28f)
                    c.drawPath(check, stroke)
                } else {
                    stroke.color = subColor
                    stroke.strokeWidth = dp(2).toFloat()
                    // Stiplet = meldt uden at være læst ("Gennemfør nu")
                    stroke.pathEffect = if (i in v.virtual) DashPathEffect(floatArrayOf(dp(4).toFloat(), dp(4).toFloat()), 0f) else null
                    c.drawCircle(cx, cy, r - dp(1), stroke)
                    stroke.pathEffect = null
                    num.color = subColor
                    c.drawText(i.toString(), cx, cy - (num.descent() + num.ascent()) / 2f, num)
                }
                if (v.here == i && v.phase != Hub.SetView.DONE) {
                    stroke.color = blue
                    stroke.strokeWidth = dp(2).toFloat()
                    c.drawCircle(cx, cy, r + dp(4), stroke)
                }
            }
        }
    }
}

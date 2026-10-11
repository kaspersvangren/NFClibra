package dk.kasvan.ciceronfc

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.OvershootInterpolator

/**
 * Låseskærmens logo: app-ikonet (RFID-symbolet på Cicero-blå) med et boret hul i nederste højre hjørne,
 * hvor en hængelås hænger. [open] (0..1) trækker låsekassen ned, så bøjlen kommer fri.
 * Tegnes i et koordinatsystem på 26×38 enheder (logoet er 24×24).
 */
class LockLogo(context: Context) : View(context) {
    private var body = Color.WHITE
    private var shackle = Color.parseColor("#C9CED6")
    private var cut = Color.BLACK

    /** 0 = låst, 1 = bøjlen er sprunget fri */
    var open = 0f
        set(v) { field = v; invalidate() }

    private var anim: ValueAnimator? = null

    fun setColors(body: Int, shackle: Int, cutout: Int) {
        this.body = body; this.shackle = shackle; this.cut = cutout
        invalidate()
    }

    /** Lås op med et lille ryk; [done] kaldes, når bøjlen er fri */
    fun animateOpen(done: (() -> Unit)? = null) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(open, 1f).apply {
            duration = 240
            interpolator = OvershootInterpolator(3f)
            addUpdateListener { open = it.animatedValue as Float }
            if (done != null) addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) { if (!cancelled) done() }
            })
            start()
        }
    }

    fun animateClose() {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(open, 0f).apply {
            duration = 140
            addUpdateListener { open = it.animatedValue as Float }
            start()
        }
    }

    fun reset() {
        anim?.cancel()
        anim = null
        open = 0f
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    // Pladen med hullet
    private val plate = Path().apply {
        addRoundRect(RectF(0f, 0f, 24f, 24f), 5.5f, 5.5f, Path.Direction.CW)
        addCircle(19.6f, 19.6f, 1.7f, Path.Direction.CW)
        fillType = Path.FillType.EVEN_ODD
    }

    // Bøjlen: bageste ben (bag pladen) og forreste ben (foran). Den hænger i hullets underkant.
    private val shackleOval = RectF(17.1f, 20.7f, 22.1f, 25.7f)
    private val rear = Path().apply {
        arcTo(shackleOval, 270f, 90f, true)
        lineTo(22.1f, 31f)
    }
    private val front = Path().apply {
        moveTo(17.1f, 26.6f)
        lineTo(17.1f, 23.2f)
        arcTo(shackleOval, 180f, 90f)
    }

    // RFID-symbolet (som app-ikonet)
    private val inner = RectF(12f - 4.95f, 12f - 4.95f, 12f + 4.95f, 12f + 4.95f)
    private val outer = RectF(12f - 9.05f, 12f - 9.05f, 12f + 9.05f, 12f + 9.05f)
    private val waves = Path().apply {
        arcTo(inner, -45f, 90f, true)
        arcTo(inner, 135f, 90f, true)
        arcTo(outer, -45f, 90f, true)
        arcTo(outer, 135f, 90f, true)
    }

    private val bodyRect = RectF(16f, 26.1f, 23.2f, 34.1f)
    private val keySlot = Path().apply {
        moveTo(19.25f, 29.7f); lineTo(19.95f, 29.7f); lineTo(20.15f, 31.7f); lineTo(19.05f, 31.7f); close()
    }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width / 26f, height / 38f)
        canvas.save()
        canvas.translate((width - 26f * s) / 2f + s, (height - 38f * s) / 2f + s)
        canvas.scale(s, s)

        line.strokeWidth = 1.2f
        line.color = shackle
        canvas.drawPath(rear, line)

        fill.color = Color.parseColor("#0078D3")
        canvas.drawPath(plate, fill)

        canvas.save()
        canvas.translate(11.6f, 11.6f); canvas.scale(0.74f, 0.74f); canvas.translate(-12f, -12f)
        fill.color = Color.WHITE
        canvas.drawCircle(12f, 12f, 2.2f, fill)
        line.color = Color.WHITE
        line.strokeWidth = 2f
        canvas.drawPath(waves, line)
        canvas.restore()

        line.strokeWidth = 1.2f
        line.color = shackle
        canvas.drawPath(front, line)

        canvas.translate(0f, open * 2f)
        fill.color = body
        canvas.drawRoundRect(bodyRect, 1f, 1f, fill)
        fill.color = cut
        canvas.drawCircle(19.6f, 29.5f, 0.75f, fill)
        canvas.drawPath(keySlot, fill)
        canvas.restore()
    }
}

package dk.kasvan.ciceronfc

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.camera.view.LifecycleCameraController
import androidx.core.content.ContextCompat

/**
 * Knapperne under kamerabilledet, ens i alle app'ens kameraer og placeret ved tommelfingeren:
 *
 *            [ ,5 ]  [ 1× ]  [ 2 ]          zoom (0,5× kun hvis telefonen har vidvinkel)
 *       ⚡            ( ◯ )             ✕   lys · udløser (kun hvor der tages billeder) · luk
 *
 * Billedet selv er frit for knapper, så de ikke dækker det, man sigter på.
 */
class CameraControls(
    private val activity: Activity,
    private val tc: ToolColors,
    private val defaultZoom: Float,
    center: View?,
    onClose: () -> Unit,
) {
    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    private var controller: LifecycleCameraController? = null
    private var torch = false
    private var zoom = defaultZoom
    private val torchIcon = ImageView(activity)
    private val chips = LinkedHashMap<Float, TextView>()
    private val zoomRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { setColor(tc.card); cornerRadius = dp(28).toFloat() }
        setPadding(dp(4), dp(4), dp(4), dp(4))
        visibility = View.INVISIBLE
    }
    private val torchBtn: FrameLayout = roundButton("Lys").apply {
        addView(torchIcon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        setOnClickListener { setTorch(!torch) }
    }
    private val closeBtn: FrameLayout = roundButton("Luk").apply {
        addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(tc.fg)
        }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        setOnClickListener { onClose() }
    }

    private val centerView = center
    private val buttons = LinearLayout(activity)
    private var vertical = false

    /** Hele panelet – nederst på skærmen, eller i højre side når telefonen ligger ned (setVertical) */
    val panel: LinearLayout = LinearLayout(activity)

    init { arrange(); paintTorch() }

    /**
     * Liggende telefon: knapperne står i én lodret kolonne i højre side som i telefonens kamera (lys øverst, zoom,
     * udløser, ✕ nederst). Stående: vandret nederst.
     */
    fun setVertical(v: Boolean) {
        if (v == vertical) return
        vertical = v
        arrange()
    }

    private fun arrange() {
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        val match = LinearLayout.LayoutParams.MATCH_PARENT
        panel.removeAllViews()
        buttons.removeAllViews()
        zoomRow.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        for (i in 0 until zoomRow.childCount) placeChip(zoomRow.getChildAt(i))
        if (vertical) {
            // Én smal kolonne i højre side: lys øverst, zoom, udløser i midten, ✕ nederst – så billedet får bredden
            panel.orientation = LinearLayout.VERTICAL
            panel.gravity = Gravity.CENTER_HORIZONTAL
            panel.setPadding(dp(8), dp(10), dp(10), dp(10))
            fun gap() = panel.addView(Space(activity), LinearLayout.LayoutParams(1, 0, 1f))
            panel.addView(torchBtn, LinearLayout.LayoutParams(dp(52), dp(52)))
            gap()
            panel.addView(zoomRow, LinearLayout.LayoutParams(wrap, wrap))
            gap()
            if (centerView != null) { panel.addView(centerView, LinearLayout.LayoutParams(dp(72), dp(72))); gap() }
            panel.addView(closeBtn, LinearLayout.LayoutParams(dp(52), dp(52)))
            return
        }
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.gravity = Gravity.CENTER
        fun gap() = buttons.addView(Space(activity), LinearLayout.LayoutParams(0, 1, 1f))
        buttons.addView(torchBtn, LinearLayout.LayoutParams(dp(52), dp(52)))
        gap()
        if (centerView != null) buttons.addView(centerView, LinearLayout.LayoutParams(dp(72), dp(72)))
        else buttons.addView(Space(activity), LinearLayout.LayoutParams(dp(72), dp(52)))
        gap()
        buttons.addView(closeBtn, LinearLayout.LayoutParams(dp(52), dp(52)))
        panel.orientation = LinearLayout.VERTICAL
        panel.gravity = Gravity.CENTER_HORIZONTAL
        panel.setPadding(dp(16), dp(8), dp(16), dp(12))
        panel.addView(zoomRow, LinearLayout.LayoutParams(wrap, wrap))
        panel.addView(buttons, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(10) })
    }

    private fun placeChip(v: View) {
        v.layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply {
            if (vertical) setMargins(0, dp(2), 0, dp(2)) else setMargins(dp(2), 0, dp(2), 0)
        }
    }

    private fun roundButton(label: String) = FrameLayout(activity).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(tc.key) }
        contentDescription = label
    }

    /** Kaldes, når kameraet er startet: zoom-knapperne vises efter, hvad telefonens kamera kan */
    fun attach(c: LifecycleCameraController) {
        controller = c
        c.initializationFuture.addListener({
            val st = c.zoomState.value
            val min = st?.minZoomRatio ?: 1f
            val max = st?.maxZoomRatio ?: 1f
            val levels = buildList {
                if (min <= 0.6f) add(0.5f)
                add(1f)
                if (max >= 2f) add(2f)
            }
            zoomRow.removeAllViews()
            chips.clear()
            for (z in levels) {
                val t = TextView(activity).apply {
                    gravity = Gravity.CENTER
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setOnClickListener { setZoom(z) }
                }
                chips[z] = t
                zoomRow.addView(t)
                placeChip(t)
            }
            zoomRow.visibility = if (levels.size > 1) View.VISIBLE else View.INVISIBLE
            // Samme zoom som før, hvis kameraet startes igen (fx når telefonen vendes)
            setZoom(if (zoom in levels) zoom else 1f)
            if (torch) c.enableTorch(true)
        }, ContextCompat.getMainExecutor(activity))
    }

    fun setZoom(z: Float) {
        zoom = z
        controller?.setZoomRatio(z)
        for ((level, t) in chips) {
            val on = level == z
            t.text = (if (level == 0.5f) ",5" else level.toInt().toString()) + if (on) "×" else ""
            t.textSize = if (on) 16f else 13f
            t.setTextColor(if (on) tc.blue else tc.fg)
            t.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (on) tc.key else Color.TRANSPARENT)
            }
        }
    }

    private fun setTorch(on: Boolean) {
        torch = on
        controller?.enableTorch(on)
        paintTorch()
    }

    private fun paintTorch() {
        torchIcon.setImageResource(if (torch) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        torchIcon.imageTintList = ColorStateList.valueOf(if (torch) Color.parseColor("#F6B400") else tc.fg)
        torchBtn.contentDescription = if (torch) "Lys til – tryk for at slukke" else "Lys fra – tryk for at tænde"
    }

    /** Zoom og lys gemmes (uden at noget flytter sig), mens et taget billede står stille – de virker kun på det levende billede */
    fun liveControls(show: Boolean) {
        val v = if (show) View.VISIBLE else View.INVISIBLE
        torchBtn.visibility = v
        zoomRow.visibility = if (show && chips.size > 1) View.VISIBLE else View.INVISIBLE
    }
}

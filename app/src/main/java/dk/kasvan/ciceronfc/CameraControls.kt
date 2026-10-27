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
import android.widget.TextView
import androidx.camera.view.LifecycleCameraController
import androidx.core.content.ContextCompat

/**
 * Knapperne oven på kamerabilledet, ens i alle app'ens kameraer og som i telefonens eget kamera:
 * ✕ (luk) øverst til venstre, lyn (lommelygte til/fra) øverst til højre og zoom-knapper (0,5× · 1× · 2×) forneden.
 */
class CameraControls(
    private val activity: Activity,
    camBox: FrameLayout,
    private val defaultZoom: Float,
    onClose: () -> Unit,
) {
    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    private var controller: LifecycleCameraController? = null
    private var torch = false
    private var zoom = defaultZoom
    private val torchIcon = ImageView(activity)
    private val zoomRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { setColor(Color.argb(90, 0, 0, 0)); cornerRadius = dp(28).toFloat() }
        setPadding(dp(4), dp(4), dp(4), dp(4))
        visibility = View.GONE
    }
    private val chips = LinkedHashMap<Float, TextView>()
    private val closeBtn: View
    private val torchBtn: View

    init {
        closeBtn = round(R.drawable.ic_close, "Luk") { onClose() }
        torchBtn = FrameLayout(activity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(110, 0, 0, 0)) }
            contentDescription = "Lys"
            addView(torchIcon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            setOnClickListener { setTorch(!torch) }
        }
        paintTorch()
        camBox.addView(closeBtn, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), 0, 0) })
        camBox.addView(torchBtn, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(12), dp(12), 0) })
        camBox.addView(zoomRow, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(12) })
    }

    private fun round(icon: Int, label: String, click: () -> Unit) = FrameLayout(activity).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(110, 0, 0, 0)) }
        contentDescription = label
        addView(ImageView(activity).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
        }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        setOnClickListener { click() }
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
                zoomRow.addView(t, LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(dp(2), 0, dp(2), 0) })
            }
            zoomRow.visibility = if (levels.size > 1) View.VISIBLE else View.GONE
            setZoom(if (defaultZoom in levels) defaultZoom else 1f)
            if (torch) c.enableTorch(true)
        }, ContextCompat.getMainExecutor(activity))
    }

    fun setZoom(z: Float) {
        zoom = z
        controller?.setZoomRatio(z)
        for ((level, t) in chips) {
            val on = level == z
            t.text = (if (level == 0.5f) ",5" else level.toInt().toString()) + if (on) "×" else ""
            t.textSize = if (on) 15f else 13f
            t.setTextColor(if (on) Color.parseColor("#9CC8FF") else Color.WHITE)
            t.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (on) Color.argb(170, 0, 0, 0) else Color.argb(60, 0, 0, 0))
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
        torchIcon.imageTintList = ColorStateList.valueOf(if (torch) Color.parseColor("#FFD54F") else Color.WHITE)
        torchBtn.contentDescription = if (torch) "Lys til – tryk for at slukke" else "Lys fra – tryk for at tænde"
    }

    /** Zoom og lys skjules, mens et taget billede står stille (de virker kun på det levende billede) */
    fun liveControls(show: Boolean) {
        val v = if (show) View.VISIBLE else View.GONE
        torchBtn.visibility = v
        zoomRow.visibility = if (show && chips.size > 1) View.VISIBLE else View.GONE
    }
}

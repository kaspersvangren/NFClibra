package dk.kasvan.ciceronfc

import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity

/**
 * Fælles for app'ens egne skærme oven på Cicero (Værktøj til tags, kameraet, guiden): ethvert tryk tæller
 * som brug, og skærmen lukker sig, hvis app'en er låst, har stået ubrugt i 2 minutter, eller låses, mens
 * den er fremme. Så låser hovedskærmen som normalt bagved. Underklasser kalder super.onResume() først og
 * stopper, hvis [isFinishing].
 */
abstract class LockedScreen : ComponentActivity() {
    private val onLock: () -> Unit = { runOnUiThread { if (!isFinishing) finish() } }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (Hub.locked || Hub.idleTooLong()) { finish(); return true }
        Hub.lastActivity = SystemClock.elapsedRealtime()
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        // Kommer man tilbage efter mere end 2 minutter, må skærmen ikke stå åben uden oplåsning
        if (Hub.locked || Hub.idleTooLong()) { finish(); return }
        Hub.onLockListeners.addIfAbsent(onLock)
    }

    override fun onPause() {
        super.onPause()
        Hub.onLockListeners.remove(onLock)
    }

    protected fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

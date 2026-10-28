package dk.kasvan.ciceronfc

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity

/**
 * Fælles for app'ens egne skærme oven på Cicero (Værktøj til tags, kameraerne, guiden): ethvert tryk tæller
 * som brug, og skærmen lukker sig, hvis app'en er låst, har stået ubrugt i 2 minutter (tjekkes hvert 10. sekund,
 * også uden tryk), låses, mens den er fremme, eller mister fokus til en anden app (delt skærm, Android 10+).
 * Så låser hovedskærmen som normalt bagved. Underklasser kalder super.onResume() først og stopper, hvis [isFinishing].
 */
abstract class LockedScreen : ComponentActivity() {
    private val onLock: () -> Unit = { runOnUiThread { if (!isFinishing) finish() } }
    private val lockHandler = Handler(Looper.getMainLooper())
    private val idleCheck = object : Runnable {
        override fun run() {
            if (Hub.locked || Hub.idleTooLong()) { if (!isFinishing) finish(); return }
            lockHandler.postDelayed(this, 10_000)
        }
    }
    // En anden app har fået fokus (delt skærm): skærmen lukkes, og hovedskærmen låser. Kort frist, så et
    // systemvindue (fx tilladelser) ikke lukker skærmen
    private val focusLost = Runnable {
        // Som hovedskærmen: kun når telefonen har en skærmlås (ellers låser app'en slet ikke)
        val secure = getSystemService(android.app.KeyguardManager::class.java)?.isDeviceSecure == true
        if (secure && !isFinishing && !isChangingConfigurations) finish()
    }
    /** Sættes, lige før skærmen selv åbner noget oven på sig (tilladelse, kameraet) – det er ikke en anden app */
    protected var openingOwnWindow = false

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
        lockHandler.removeCallbacks(idleCheck)
        lockHandler.postDelayed(idleCheck, 10_000)
    }

    override fun onPause() {
        super.onPause()
        Hub.onLockListeners.remove(onLock)
        lockHandler.removeCallbacks(idleCheck)
        lockHandler.removeCallbacks(focusLost)
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        lockHandler.removeCallbacks(focusLost)
        if (isTopResumedActivity) { openingOwnWindow = false; return }
        if (!openingOwnWindow && !isFinishing && !isChangingConfigurations) lockHandler.postDelayed(focusLost, 1500)
    }

    protected fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

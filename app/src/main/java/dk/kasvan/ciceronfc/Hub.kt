package dk.kasvan.ciceronfc

import android.content.Context
import android.nfc.Tag
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Hjertet: holder styr på tagget ved telefonen og udfører det, Cicero beder om.
 * Alt der rører tagget eller lageret sker på én tråd (exec), så intet kolliderer.
 */
object Hub {
    const val DEFAULT_COUNTRY = "DK"

    /** Biblioteksnummer sat i Værktøj til tags (null = ikke sat) */
    @Volatile var configuredLibrary: String? = null

    class TagState(
        val phone: PhoneTag,
        var dsfid: Int?,
        var afi: Int?,
        var content: TagContent?,
    ) {
        val mac get() = phone.mac
        /** Del af et sæt, som samles op (se SetRun): meldes ikke "væk", når den fjernes fra telefonen */
        var inSet = false

        fun toJson(): JSONObject = JSONObject().apply {
            put("Trtype", 3)
            put("Dfsid", dsfid ?: 0)
            put("Id", TagContent.b64(phone.uidMsb))
            put("Mac", mac)
            put("Content", (content ?: TagContent.empty()).toJson())
        }

        fun label(): String {
            val c = content ?: return "ukendt bog ($mac)"
            val del = if (c.numItems > 1 || c.seqNum > 1) " del ${c.seqNum}/${c.numItems}" else ""
            return c.barcode.ifEmpty { "tomt tag" } + del
        }
    }

    private val exec = Executors.newSingleThreadScheduledExecutor()
    private lateinit var ctx: Context
    private var started = false
    private val startTime = SystemClock.elapsedRealtime()

    // Kun brugt på exec-tråden
    private val inventory = LinkedHashMap<String, TagState>()
    private var current: TagState? = null
    private var pingFails = 0
    /** Det biblioteksnummer, der senest er set på et tag – foreslås, hvis intet er sat */
    @Volatile var lastLibrary: String? = null
        private set

    const val AFI_ON = 0x07   // alarm til (sikret)
    const val AFI_OFF = 0xC2  // alarm fra (udlånt)
    const val PREF_LIBRARY = "library"

    /** Adresser, der betyder "denne telefon" – Ciceros RFID-opsætning og app'ens egen server */
    val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]")

    /** Ingen tryk i 2 minutter: så skal app'en låses, uanset hvilken skærm der er fremme */
    fun idleTooLong() = SystemClock.elapsedRealtime() - lastActivity >= MainActivity.LOCK_AFTER_MS

    /**
     * Husker længden på materialenumre fra eget bibliotek, så kameraet i værktøjet kan sætte
     * numre med den rigtige længde øverst. Kun længden gemmes – aldrig selve numrene.
     */
    fun noteItemNumber(barcode: String, library: String?) {
        if (!::ctx.isInitialized) return
        if (barcode.length !in 4..16 || !barcode.all { it.isDigit() }) return
        val own = configuredLibrary
        if (own != null && !library.isNullOrEmpty() && library != own) return
        try {
            val p = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
            val m = parseLens(p.getString("itemLens", null))
            m[barcode.length] = (m[barcode.length] ?: 0) + 1
            if (m.values.sum() > 1000) m.keys.toList().forEach { k -> m[k] = (m[k] ?: 0) / 2 }
            p.edit().putString("itemLens", m.filterValues { it > 0 }.entries.joinToString(",") { "${it.key}:${it.value}" }).apply()
        } catch (_: Exception) {}
    }

    /** Den mest almindelige længde på jeres materialenumre, eller null hvis app'en ikke har set nogen endnu */
    fun itemLength(): Int? {
        if (!::ctx.isInitialized) return null
        val m = parseLens(ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("itemLens", null))
        return m.maxByOrNull { it.value }?.key
    }

    private fun parseLens(s: String?): MutableMap<Int, Int> {
        val m = HashMap<Int, Int>()
        s?.split(',')?.forEach { e ->
            val kv = e.split(':')
            val k = kv.getOrNull(0)?.toIntOrNull(); val v = kv.getOrNull(1)?.toIntOrNull()
            if (k != null && v != null) m[k] = v
        }
        return m
    }

    /** Låst = Cicero er dækket og bøger læses ikke. Låst fra start, så login ikke kan bruges af andre. */
    @Volatile var locked = true
    @Volatile var lastActivity = 0L

    /** Antal af app'ens skærme, der er fremme. Falder den til 0 (skærmen slukket, anden app), låses der. */
    private var visibleScreens = 0
    private var lifecycleWatched = false
    var onBackground: (() -> Unit)? = null

    fun watchLifecycle(app: android.app.Application) {
        if (lifecycleWatched) return
        lifecycleWatched = true
        app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) { visibleScreens++ }
            override fun onActivityStopped(a: android.app.Activity) {
                visibleScreens = (visibleScreens - 1).coerceAtLeast(0)
                if (visibleScreens == 0 && !a.isChangingConfigurations) onBackground?.invoke()
            }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
    }

    /** Tryk og rul i en dialog tæller som brug, så app'en ikke låser midt i den */
    fun countTouches(d: android.app.Dialog) {
        val w = d.window ?: return
        val orig = w.callback
        w.callback = object : android.view.Window.Callback by orig {
            override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
                if (!locked) lastActivity = SystemClock.elapsedRealtime()
                return orig.dispatchTouchEvent(event)
            }
        }
    }

    @Volatile var mode = "IDLE"
    @Volatile var statusText = ""
        private set
    @Volatile var statusDetail = ""
        private set

    /**
     * Hvad brugeren skal se/høre/mærke lige nu.
     * READ = læst, venter på Cicero · SEEN = læst, Cicero skiftede ikke alarm (fx en ren opslagsside)
     */
    enum class Fb { IDLE, READ, SEEN, ALARM_OFF, ALARM_ON, WRITTEN, ERROR, SET, SET_ALARM }

    /** Ved SET_ALARM: er det alarm til (aflevering) eller fra (udlån), der mangler på resten af sættet */
    @Volatile var setAlarmOn = false
        private set

    @Volatile var feedback = Fb.IDLE
        private set
    @Volatile var feedbackSeq = 0
        private set
    @Volatile var statusListener: (() -> Unit)? = null

    private val counters = linkedMapOf(
        "ReadInvFail" to 0L, "ReadInvSucc" to 0L,
        "ReadTagFail" to 0L, "ReadTagSucc" to 0L,
        "WriteTagSucc" to 0L, "WriteTagFail" to 0L,
        "WriteAFISucc" to 0L, "WriteAFIFail" to 0L,
    )

    fun start(context: Context) {
        if (started) return
        started = true
        ctx = context.applicationContext
        configuredLibrary = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getString(PREF_LIBRARY, null)
        CrashLog.install(ctx)
        Server.start()
        exec.scheduleWithFixedDelay({ presenceCheck() }, 300, 300, TimeUnit.MILLISECONDS)
    }

    /** Vibrationsmønstre, som værktøjet også bruger: to korte = godt, tre hurtige = fejl */
    val VIB_DOUBLE = longArrayOf(0, 110, 80, 110)
    val VIB_ERROR = longArrayOf(0, 80, 70, 80, 70, 80)

    private fun signal(fb: Fb, text: String, detail: String = "") {
        feedback = fb
        feedbackSeq++
        statusText = text
        statusDetail = detail
        when (fb) {
            // Læst: kort tik (70 ms) i fuld styrke
            Fb.READ -> vibrate(longArrayOf(0, 70), intArrayOf(0, 255))
            // Alarm fra: "ka-pling" op (A4 → E5) + dobbelt-vibration
            Fb.ALARM_OFF -> {
                vibrate(VIB_DOUBLE)
                Beeper.chime(Beeper.Note(0, 440.0, 380), Beeper.Note(110, 659.3, 650))
            }
            // Alarm til: samme "ka-pling", bare ned (E5 → A4) + én lang vibration
            Fb.ALARM_ON -> {
                vibrate(longArrayOf(0, 450))
                Beeper.chime(Beeper.Note(0, 659.3, 380), Beeper.Note(110, 440.0, 650))
            }
            Fb.WRITTEN -> {
                vibrate(VIB_DOUBLE)
                Beeper.play(660 to 90, 0 to 40, 660 to 90)
            }
            Fb.ERROR -> {
                vibrate(VIB_ERROR)
                Beeper.play(220 to 550)
            }
            // Sæt: kort tik for hver del; alarm mangler på resten: to korte
            Fb.SET -> vibrate(longArrayOf(0, 70), intArrayOf(0, 255))
            Fb.SET_ALARM -> vibrate(VIB_DOUBLE)
            Fb.IDLE, Fb.SEEN -> {}
        }
        statusListener?.invoke()
    }

    /** Hvis Cicero ikke skifter alarm inden 1,5 s, er det en side der bare læser. */
    private fun settleToSeenLater() {
        val seq = feedbackSeq
        exec.schedule(Runnable {
            if (feedbackSeq == seq && feedback == Fb.READ) signal(Fb.SEEN, "Læst", statusDetail)
        }, 1500, TimeUnit.MILLISECONDS)
    }

    private fun count(k: String) {
        counters[k] = (counters[k] ?: 0L) + 1
    }

    private fun <T> onExec(f: () -> T): T = try {
        exec.submit(Callable(f)).get(10, TimeUnit.SECONDS)
    } catch (_: java.util.concurrent.TimeoutException) {
        throw IllegalStateException("NFC svarede ikke inden for 10 sekunder")
    }

    // ---------- NFC ----------

    fun onTagDiscovered(tag: Tag) {
        // Låst: ellers kunne man fx aflevere bøger uden at låse op
        if (locked) {
            LogBuf.add("Bog ignoreret – app'en er låst")
            vibrate(VIB_ERROR)
            return
        }
        lastActivity = SystemClock.elapsedRealtime()
        exec.execute { handleNewTag(tag) }
    }

    private fun handleNewTag(tag: Tag) {
        // Låst, siden tagget kom i kø (onLocked kan have kørt imellem): så skal det ikke ind bag låsen
        if (locked) return
        current?.let { drop(it, "erstattet af nyt tag") }
        val phone = try {
            PhoneTag(tag)
        } catch (e: Exception) {
            LogBuf.add("NFC: ${e.message}")
            return
        }
        val state = TagState(phone, null, null, null)
        try {
            val info = phone.systemInfo()
            state.dsfid = info.dsfid
            state.afi = info.afi
            count("ReadInvSucc")
        } catch (e: Exception) {
            count("ReadInvFail")
            LogBuf.add("NFC: kunne ikke læse systeminfo: ${e.message}")
        }
        try {
            state.content = TagContent.parse(phone.readBlocks(0, 8))
            state.content?.library?.takeIf { it.isNotEmpty() }?.let { lib ->
                lastLibrary = lib
                state.content?.barcode?.let { noteItemNumber(it, lib) }
            }
            count("ReadTagSucc")
        } catch (e: Exception) {
            count("ReadTagFail")
            LogBuf.add("NFC: kunne ikke læse indhold: ${e.message}")
        }
        // Del af et sæt (fx "del 2 af 5"), mens Cicero lytter: samles op, se SetRun
        val c = state.content
        if (c != null && c.barcode.isNotEmpty() && c.numItems in 2..MAX_SET && c.seqNum in 1..c.numItems && Server.clientCount() > 0) {
            handleSetPart(state, c)
            return
        }
        activeSet?.let { run ->
            if (run.reported && run.alarmValue != null && run.missingAlarm.isNotEmpty()) {
                // Resten af sættet har stadig forkert alarm – det skal gøres færdigt først
                state.phone.close()
                LogBuf.add("Ignoreret: ${state.label()} – alarm mangler stadig på ${run.barcode} del ${run.missingText()}")
                signal(Fb.ERROR, "Gør sættet færdigt", "Alarm mangler: del ${run.missingText()}")
                return
            }
            finishSet(if (run.reported) null else "andet materiale læst")
        }
        inventory[state.mac] = state
        current = state
        pingFails = 0
        Server.broadcast("addTag", state.toJson().toString())
        if (Server.clientCount() == 0) {
            LogBuf.add("Læst: ${state.label()} · alarm ${afiText(state.afi)} (Cicero lytter ikke)")
            signal(Fb.READ, "Aktiver RFID", state.label())
        } else {
            LogBuf.add("Læst: ${state.label()} · alarm ${afiText(state.afi)}")
            signal(Fb.READ, "Hold stille", state.label())
            settleToSeenLater()
        }
    }

    // ---------- Sæt i flere dele ----------

    /**
     * Telefonen læser kun én chip ad gangen, men Cicero vil se alle dele af et sæt samtidig (ellers: "Scan de
     * resterende materialer"). Delene samles derfor op én ad gangen og meldes først til Cicero, når alle er set –
     * som når hele sættet ligger på skrankens læser. Beder Cicero så om alarm til/fra, skiftes delen ved telefonen
     * med det samme, og resten krydses af, efterhånden som de holdes mod telefonen. Kun på exec-tråden.
     */
    private class SetRun(val barcode: String, val total: Int) {
        val parts = java.util.TreeMap<Int, TagState>()
        var reported = false
        /** Det Cicero bad om (AFI_ON/AFI_OFF), når sættet var meldt */
        var alarmValue: Int? = null
        val missingAlarm = java.util.TreeSet<Int>()
        var touched = SystemClock.elapsedRealtime()
        fun missingParts() = (1..total).filter { it !in parts }
        fun missingText() = missingAlarm.joinToString(", ")
    }

    private var activeSet: SetRun? = null

    /** Det pop-op'en viser (SetPanel). Kun læst af skærmen; skiftes ud som helhed. */
    class SetView(
        val barcode: String, val total: Int, val phase: Int,
        val read: Set<Int>, val alarmDone: Set<Int>, val here: Int?, val alarmOn: Boolean,
    ) {
        companion object { const val COLLECT = 0; const val WAIT = 1; const val ALARM = 2; const val DONE = 3 }
    }

    @Volatile var setView: SetView? = null
        private set

    private fun publishSet(run: SetRun?, done: Boolean = false) {
        setView = run?.let { r ->
            val phase = when {
                done -> SetView.DONE
                !r.reported -> SetView.COLLECT
                r.alarmValue == null -> SetView.WAIT
                else -> SetView.ALARM
            }
            val here = current?.takeIf { it.inSet }?.content?.takeIf { it.barcode == r.barcode }?.seqNum
            val alarmDone = if (r.alarmValue == null) emptySet() else
                (1..r.total).filter { it in r.parts && it !in r.missingAlarm }.toSet()
            SetView(r.barcode, r.total, phase, r.parts.keys.toSet(), alarmDone, here, r.alarmValue == AFI_ON)
        }
        statusListener?.invoke()
    }

    /** Afbryd/Luk/Stop i pop-op'en */
    fun stopSet() = exec.execute {
        val run = activeSet ?: return@execute
        val missing = if (run.alarmValue != null) run.missingText() else ""
        finishSet("stoppet i app'en")
        if (missing.isNotEmpty()) signal(Fb.ERROR, "Alarm mangler", "Del $missing – ret i Værktøj til tags")
        else signal(Fb.IDLE, "")
    }
    private const val MAX_SET = 50
    private const val SET_COLLECT_MS = 60_000L   // alle dele skal være læst inden for et minut fra sidste del
    private const val SET_WAIT_MS = 60_000L      // meldt, men Cicero skifter ikke alarm (fx en opslagsside)
    private const val SET_ALARM_MS = 120_000L    // alarm skiftet på én del – resten skal holdes mod telefonen

    private fun handleSetPart(state: TagState, c: TagContent) {
        state.inSet = true
        current = state
        pingFails = 0
        var run = activeSet
        if (run != null && (run.barcode != c.barcode || run.total != c.numItems)) {
            if (run.reported && run.alarmValue != null && run.missingAlarm.isNotEmpty()) {
                state.phone.close()
                current = null
                LogBuf.add("Ignoreret: ${state.label()} – alarm mangler stadig på ${run.barcode} del ${run.missingText()}")
                signal(Fb.ERROR, "Gør sættet færdigt", "Alarm mangler: del ${run.missingText()}")
                return
            }
            finishSet(if (run.reported) null else "andet sæt læst")
            run = null
        }
        if (run == null) {
            run = SetRun(c.barcode, c.numItems)
            activeSet = run
            LogBuf.add("Sæt: ${c.barcode} har ${c.numItems} dele – samler dem, før Cicero får besked")
        }
        run.touched = SystemClock.elapsedRealtime()
        LogBuf.add("Læst: ${state.label()} · alarm ${afiText(state.afi)}")

        if (!run.reported) {
            run.parts[c.seqNum]?.let { old -> if (old !== state) old.phone.close() }
            run.parts[c.seqNum] = state
            val missing = run.missingParts()
            if (missing.isNotEmpty()) {
                publishSet(run)
                signal(Fb.SET, "Sæt ${run.parts.size} af ${run.total}", "Hold næste del: ${missing.joinToString(", ")}")
            } else {
                reportSet(run)
            }
            return
        }

        // Sættet er meldt til Cicero: delen ved telefonen erstatter den gamle (lukkede) forbindelse
        run.parts[c.seqNum]?.let { old -> inventory.remove(old.mac); if (old !== state) old.phone.close() }
        run.parts[c.seqNum] = state
        inventory[state.mac] = state
        val want = run.alarmValue
        if (want == null) {
            publishSet(run)
            signal(Fb.SET, "Sæt meldt", "${run.barcode} – venter på Cicero")
            return
        }
        if (c.seqNum in run.missingAlarm) {
            if (!setAfi(state, want)) { publishSet(run); return }
            run.missingAlarm.remove(c.seqNum)
            LogBuf.add("${alarmWord(want)}: ${state.label()}")
        }
        if (run.missingAlarm.isEmpty()) alarmDone(run) else signalSetAlarm(run)
    }

    /** Alle dele er set: meld dem til Cicero på én gang */
    private fun reportSet(run: SetRun) {
        run.reported = true
        for (s in run.parts.values) {
            inventory[s.mac] = s
            Server.broadcast("addTag", s.toJson().toString())
        }
        LogBuf.add("Sæt komplet: ${run.barcode} – alle ${run.total} dele meldt til Cicero")
        publishSet(run)
        signal(Fb.READ, "Hold stille", "${run.barcode} – alle ${run.total} dele")
        settleToSeenLater()
    }

    private fun setAfi(s: TagState, value: Int): Boolean = try {
        s.phone.writeAfi(value)
        s.afi = value
        count("WriteAFISucc")
        true
    } catch (e: Exception) {
        count("WriteAFIFail")
        LogBuf.add("Alarm FEJLEDE på ${s.label()}: ${e.message}")
        signal(Fb.ERROR, "Alarm-fejl – hold delen stille", s.label())
        false
    }

    /** Cicero bad om alarm til/fra på et meldt sæt: delen ved telefonen nu, resten efterhånden */
    private fun setAlarm(run: SetRun, value: Int): Result {
        val word = if (value == AFI_ON) "TIL" else "FRA"
        run.alarmValue = value
        run.touched = SystemClock.elapsedRealtime()
        run.missingAlarm.clear()
        for ((seq, s) in run.parts) {
            if (s.afi == value) continue
            if (s === current && setAfi(s, value)) { LogBuf.add("${alarmWord(value)}: ${s.label()}"); continue }
            run.missingAlarm.add(seq)
        }
        if (run.missingAlarm.isEmpty()) {
            alarmDone(run)
        } else {
            LogBuf.add("Alarm $word: ${run.barcode} – mangler på del ${run.missingText()}")
            signalSetAlarm(run)
        }
        // Cicero får OK med det samme; resten af delene klares ved telefonen
        return Result(200, "OK")
    }

    private fun alarmWord(v: Int) = if (v == AFI_ON) "Alarm til" else "Alarm fra"

    private fun signalSetAlarm(run: SetRun) {
        publishSet(run)
        setAlarmOn = run.alarmValue == AFI_ON
        signal(Fb.SET_ALARM, "Alarm mangler", "Hold del ${run.missingText()} mod telefonen")
    }

    private fun alarmDone(run: SetRun) {
        val on = run.alarmValue == AFI_ON
        val word = if (on) "TIL" else "FRA"
        LogBuf.add("Alarm $word: ${run.barcode} – alle ${run.total} dele")
        val detail = "${run.barcode} – alle ${run.total} dele"
        if (on) signal(Fb.ALARM_ON, "Alarm til", detail) else signal(Fb.ALARM_OFF, "Alarm fra", detail)
        finishSet(null, done = true)
        // Pop-op'en viser "færdig" i 2 sekunder; derefter tilbage til "Klar"
        val seq = feedbackSeq
        exec.schedule(Runnable {
            if (activeSet == null) publishSet(null)
            if (feedbackSeq == seq) signal(Fb.IDLE, "")
        }, 2000, TimeUnit.MILLISECONDS)
    }

    /** Sættet er færdigt eller opgivet: Cicero får "væk" for delene, og alt glemmes */
    private fun finishSet(why: String?, done: Boolean = false) {
        val run = activeSet ?: return
        activeSet = null
        // Færdig: pop-op'en viser det kort (lukkes af alarmDone); ellers forsvinder den med det samme
        publishSet(if (done) run else null, done)
        if (why != null) {
            when {
                !run.reported -> LogBuf.add("Sæt afbrudt ($why): ${run.barcode} – del ${run.missingParts().joinToString(", ")} blev ikke læst; Cicero har intet fået")
                run.alarmValue != null && run.missingAlarm.isNotEmpty() ->
                    LogBuf.add("ADVARSEL: alarm ikke skiftet på ${run.barcode} del ${run.missingText()} ($why) – ret det i Værktøj til tags")
                else -> LogBuf.add("Sæt afsluttet ($why): ${run.barcode}")
            }
        }
        for (s in run.parts.values) {
            if (run.reported && inventory.remove(s.mac) != null) Server.broadcast("removeTag", s.toJson().toString())
            if (s !== current) s.phone.close()
        }
    }

    /** Hvert 0,3 s: et sæt, der står stille for længe, opgives */
    private fun checkSetTimeout() {
        val run = activeSet ?: return
        val idle = SystemClock.elapsedRealtime() - run.touched
        when {
            !run.reported && idle > SET_COLLECT_MS -> {
                val missing = run.missingParts().joinToString(", ")
                finishSet("ikke alle dele læst inden for et minut")
                signal(Fb.ERROR, "Sæt afbrudt", "Del $missing blev ikke læst – start forfra")
            }
            run.reported && run.alarmValue == null && idle > SET_WAIT_MS -> finishSet(null)
            run.reported && run.alarmValue != null && idle > SET_ALARM_MS -> {
                val missing = run.missingText()
                finishSet("tiden løb ud")
                signal(Fb.ERROR, "Alarm mangler", "Del $missing – ret i Værktøj til tags")
            }
        }
    }

    /** Cicero har åbnet eller lukket forbindelsen – opdatér RFID-ikonet. */
    fun onClientsChanged() {
        statusListener?.invoke()
    }

    /** App'ens andre skærme (værktøj, kamera, guide) lukker sig, når der låses – de melder sig her. */
    val onLockListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** App'en er låst: slip bøgerne ved telefonen, så intet kan ændres på dem bag låsen, og luk de andre skærme. */
    fun onLocked() {
        exec.execute {
            finishSet("app'en blev låst")
            current?.let { if (it.inSet) { it.phone.close(); current = null } }
            for (s in inventory.values.toList()) drop(s, "app'en blev låst")
        }
        for (l in onLockListeners) try { l() } catch (_: Exception) {}
    }

    /**
     * Cicero er begyndt at lytte (fx skiftet til Aflevering). En rigtig læser melder de
     * bøger, der allerede ligger på den – det gør vi også.
     */
    fun onClientConnected() {
        onClientsChanged()
        exec.schedule(Runnable {
            // En del af et sæt, der blev læst, før Cicero lyttede, samles op som et sæt i stedet for at blive meldt alene
            for (s in inventory.values.toList()) {
                val c = s.content ?: continue
                if (c.barcode.isNotEmpty() && c.numItems in 2..MAX_SET && c.seqNum in 1..c.numItems) {
                    inventory.remove(s.mac)
                    handleSetPart(s, c)
                }
            }
            if (inventory.isEmpty()) return@Runnable
            for (s in inventory.values) Server.broadcast("addTag", s.toJson().toString())
            val names = inventory.values.joinToString { it.label() }
            LogBuf.add("Cicero lytter nu – sendt bog der allerede lå ved telefonen: $names")
            signal(Fb.READ, "Hold stille", names)
            settleToSeenLater()
        }, 200, TimeUnit.MILLISECONDS)
    }

    private fun presenceCheck() {
        checkSetTimeout()
        val s = current ?: return
        try {
            s.phone.ping()
            pingFails = 0
        } catch (_: Exception) {
            pingFails++
            if (pingFails >= 2) drop(s, "fjernet fra telefonen")
        }
    }

    private fun drop(s: TagState, why: String) {
        if (s.inSet) {
            // Del af et sæt: Cicero skal stadig tro, at den ligger der, indtil sættet er færdigt
            if (current === s) current = null
            s.phone.close()
            activeSet?.let { publishSet(it) }
            return
        }
        inventory.remove(s.mac)
        if (current === s) current = null
        s.phone.close()
        Server.broadcast("removeTag", s.toJson().toString())
        LogBuf.add("Væk: ${s.label()} ($why)")
        if (feedback != Fb.ERROR) signal(Fb.IDLE, "")
    }

    // ---------- Det Cicero kalder (via Server) ----------

    fun inventoryJson(): String = onExec { inventoryJsonLocal() }

    private fun inventoryJsonLocal(): String {
        val o = JSONObject()
        for ((k, v) in inventory) o.put(k, v.toJson())
        return o.toString()
    }

    fun statusJson(): String = onExec {
        val reader = JSONObject().apply {
            put("StrHandle", JSONObject.NULL)
            put("PortHandle", 0)
            put("ReaderHandle", 0)
            put("Serial", "PHONE")
            put("IntSerial", 0)
            put("Name", "Telefon-NFC (NFClibra)")
            put("Family", "Android NFC")
            for ((k, v) in counters) put(k, v)
        }
        val last = JSONObject()
        for ((k, v) in inventory) last.put(k, v.toJson())
        JSONObject().apply {
            put("Uptime", uptime())
            put("Reader", reader)
            put("LastInventory", last)
            put("Client", "127.0.0.1")
            put("Mode", mode)
        }.toString()
    }

    class Result(val code: Int, val body: String, val json: Boolean = false)

    fun alarm(on: Boolean): Result = onExec {
        val value = if (on) AFI_ON else AFI_OFF
        val word = if (on) "TIL" else "FRA"
        if (locked) return@onExec Result(423, "Locked")
        activeSet?.let { run -> if (run.reported) return@onExec setAlarm(run, value) }
        if (inventory.isEmpty()) {
            LogBuf.add("Alarm $word: ingen bog ved telefonen")
            signal(Fb.ERROR, "For tidligt – alarm ikke skiftet")
            return@onExec Result(400, "Inventory empty")
        }
        for (s in inventory.values) {
            try {
                s.phone.writeAfi(value)
                s.afi = value
                count("WriteAFISucc")
            } catch (e: Exception) {
                count("WriteAFIFail")
                LogBuf.add("Alarm $word FEJLEDE på ${s.label()}: ${e.message}")
                signal(Fb.ERROR, "Alarm-fejl – prøv igen", s.label())
                return@onExec Result(500, "Failed activating alarm on id ${s.mac}, err: ${e.message} ")
            }
        }
        val names = inventory.values.joinToString { it.label() }
        LogBuf.add("Alarm $word: $names")
        if (on) signal(Fb.ALARM_ON, "Alarm til", names) else signal(Fb.ALARM_OFF, "Alarm fra", names)
        Result(200, "OK")
    }

    fun write(barcode: String): Result = onExec {
        if (locked) return@onExec Result(423, "Locked")
        if (activeSet != null) return@onExec Result(400, "Set in progress")
        if (inventory.isEmpty()) return@onExec Result(400, "Inventory empty")
        val n = inventory.size
        var i = 0
        for (s in inventory.values) {
            i++
            try {
                writeContent(s, barcode, numItems = n, seqNum = i)
            } catch (e: Exception) {
                LogBuf.add("Skriv $barcode FEJLEDE: ${e.message}")
                signal(Fb.ERROR, "Skrivning fejlede", barcode)
                return@onExec Result(400, "Error writing inventory: ${e.message}")
            }
        }
        LogBuf.add("Skrevet: $barcode til $n tag(s)")
        signal(Fb.WRITTEN, "Skrevet", barcode)
        Result(200, inventoryJsonLocal(), json = true)
    }

    fun writeTagBarcode(tagId: String, barcode: String): Result = onExec {
        if (locked) return@onExec Result(423, "Locked")
        if (activeSet != null) return@onExec Result(400, "Set in progress")
        if (inventory.isEmpty()) return@onExec Result(400, "Inventory empty")
        val s = inventory[tagId.uppercase()] ?: return@onExec Result(400, "Tag not in range: $tagId")
        val old = s.content
        try {
            writeContent(
                s, barcode,
                numItems = old?.numItems?.takeIf { it > 0 } ?: 1,
                seqNum = old?.seqNum?.takeIf { it > 0 } ?: 1,
            )
        } catch (e: Exception) {
            LogBuf.add("Skriv $barcode FEJLEDE: ${e.message}")
            signal(Fb.ERROR, "Skrivning fejlede", barcode)
            return@onExec Result(400, "Error writing tag: ${e.message}")
        }
        LogBuf.add("Skrevet: $barcode til ${s.mac}")
        signal(Fb.WRITTEN, "Skrevet", barcode)
        Result(200, s.toJson().toString(), json = true)
    }

    private fun writeContent(s: TagState, barcode: String, numItems: Int, seqNum: Int) {
        // Et tag, der allerede tilhører et bibliotek, beholder det; nye chips får det indstillede nummer
        val library = s.content?.library?.takeIf { it.isNotEmpty() } ?: configuredLibrary ?: lastLibrary
            ?: throw IllegalStateException("biblioteksnummer er ikke sat (Værktøj til tags)")
        val country = s.content?.country?.takeIf { it.isNotEmpty() } ?: DEFAULT_COUNTRY
        val bytes = TagContent.build(barcode, numItems, seqNum, country, library)
        try {
            s.phone.writeBlocks(bytes)
            val check = TagContent.parse(s.phone.readBlocks(0, 8))
            if (check.barcode != barcode) throw IllegalStateException("kontrol-læsning viste '${check.barcode}'")
            s.content = check
            count("WriteTagSucc")
        } catch (e: Exception) {
            count("WriteTagFail")
            throw e
        }
    }

    // ---------- Hjælpere ----------

    fun afiText(afi: Int?): String = when (afi) {
        AFI_ON -> "til"
        AFI_OFF -> "fra"
        null -> "ukendt"
        else -> "0x%02X".format(afi)
    }

    private fun uptime(): String {
        val ms = SystemClock.elapsedRealtime() - startTime
        val h = ms / 3_600_000
        val m = (ms / 60_000) % 60
        val s = (ms % 60_000) / 1000.0
        val sb = StringBuilder()
        if (h > 0) sb.append(h).append('h')
        if (h > 0 || m > 0) sb.append(m).append('m')
        sb.append(String.format(java.util.Locale.ROOT, "%.3fs", s))
        return sb.toString()
    }

    // Vibration mærket som "alarm", så den også kommer igennem i lydløs tilstand
    @Suppress("DEPRECATION")
    fun vibrate(pattern: LongArray, amplitudes: IntArray? = null) {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val effect = if (amplitudes != null && v.hasAmplitudeControl()) {
                VibrationEffect.createWaveform(pattern, amplitudes, -1)
            } else {
                VibrationEffect.createWaveform(pattern, -1)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            }
        } catch (e: Exception) {
            LogBuf.add("Vibration fejlede: ${e.message}")
        }
    }
}

object BuildConfigInfo {
    fun version(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }
}

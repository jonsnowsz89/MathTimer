package com.mathteacher.timer.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import androidx.core.app.NotificationCompat
import com.mathteacher.timer.MainActivity
import com.mathteacher.timer.R
import com.mathteacher.timer.alert.AlertPlayer
import com.mathteacher.timer.data.SessionStore
import com.mathteacher.timer.data.SettingsRepository
import com.mathteacher.timer.model.*
import com.mathteacher.timer.timer.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** State shared with the UI (service and ViewModel live in the same process). */
object TimerStateHolder { val state = MutableStateFlow(TimerState()) }

/**
 * Why alerts survive screen-off / Doze / process death:
 *  1. Every alert is backed by an exact AlarmManager alarm (setExactAndAllowWhileIdle) that wakes the
 *     device and re-enters this service, even if the process was killed (session is restored from SessionStore).
 *  2. A foreground service + partial WakeLock keep the process alive and the CPU running.
 *  3. Alert handling is idempotent (lastEvent), so the alarm and the 1-second loop never double-fire.
 */
class TimerService : Service() {
    companion object {
        const val ACTION_CLASS = "ACTION_CLASS"
        const val ACTION_EXAM = "ACTION_EXAM"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_NEXT = "ACTION_NEXT"
        const val ACTION_PREV = "ACTION_PREV"
        const val ACTION_UNDO = "ACTION_UNDO"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val ACTION_ADJUST = "ACTION_ADJUST"
        const val ACTION_ALERT = "ACTION_ALERT"
        const val EXTRA_MIN = "EXTRA_MIN"
        private const val CHANNEL = "timer_silent"
        private const val NOTIF_ID = 1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var loopJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var engine: Engine? = null
    @Volatile private var startAt = 0L
    @Volatile private var screenOn = true
    private var mode = Mode.CLASS
    private var minutes = 50
    private var reserve = false
    private var lastEvent = 0L
    private var paused = false
    private var pausedAt = 0L
    private var lastTitle = ""
    private var lastPaused = false
    private lateinit var player: AlertPlayer
    private lateinit var repo: SettingsRepository
    private lateinit var nm: NotificationManager

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            screenOn = i.action == Intent.ACTION_SCREEN_ON
            if (screenOn) wake.trySend(Unit)          // refresh the UI immediately when the screen turns on
        }
    }

    override fun onCreate() {
        super.onCreate()
        player = AlertPlayer(this); repo = SettingsRepository(this)
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "المؤقت", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false); setShowBadge(false)
        })
        screenOn = getSystemService(PowerManager::class.java).isInteractive
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) })
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val a = i?.action
        if (a == ACTION_STOP) { stopTimer(); return START_NOT_STICKY }
        val shown = TimerStateHolder.state.value
        try {   // must become foreground quickly (also when started by an alarm)
            startForeground(NOTIF_ID, notif(if (shown.mode != Mode.IDLE) shown
                else TimerState(mode = if (a == ACTION_EXAM) Mode.EXAM else Mode.CLASS, title = "…")))
        } catch (_: Exception) { }
        when (a) {
            ACTION_CLASS -> begin(Mode.CLASS, i?.getIntExtra(EXTRA_MIN, 50) ?: 50)
            ACTION_EXAM -> begin(Mode.EXAM, i?.getIntExtra(EXTRA_MIN, 60) ?: 60)
            else -> {
                if (!ready()) { stopTimer(); return START_NOT_STICKY }
                when (a) {
                    ACTION_NEXT -> navigate(+1)
                    ACTION_PREV -> navigate(-1)
                    ACTION_UNDO -> undoJump()
                    ACTION_PAUSE -> setPaused(true)
                    ACTION_RESUME -> setPaused(false)
                    ACTION_ADJUST -> adjust(i?.getIntExtra(EXTRA_MIN, 0) ?: 0)
                    else -> scope.launch { pulse() }   // ACTION_ALERT (alarm) or sticky restart
                }
            }
        }
        return START_STICKY
    }

    // ---------- session lifecycle ----------
    private fun begin(m: Mode, mins: Int) {
        loopJob?.cancel(); engine = null
        scope.launch {
            val s = repo.settings.first()
            val eng: Engine = if (m == Mode.CLASS) ClassEngine(ClassPlan.build(mins, s.reserveApp))
                              else ExamEngine(mins * 60_000L)
            synchronized(lock) {
                mode = m; minutes = mins; reserve = s.reserveApp; engine = eng
                startAt = SystemClock.elapsedRealtime(); lastEvent = 0; paused = false; pausedAt = 0
            }
            lastTitle = ""
            acquireWakeLock(eng.totalMs + 30 * 60_000L)
            persist(); startLoop()
        }
    }

    private fun ready() = engine != null || restore()

    private fun restore(): Boolean {
        val ss = SessionStore.load(this) ?: return false
        if (SystemClock.elapsedRealtime() < ss.startAt) { SessionStore.clear(this); return false }   // rebooted
        val eng: Engine = try {
            if (ss.mode == Mode.CLASS) ClassEngine(ClassPlan.build(ss.minutes, ss.reserve), ss.snap)
            else ExamEngine(ss.snap.toLong())
        } catch (e: Exception) { SessionStore.clear(this); return false }
        synchronized(lock) {
            mode = ss.mode; minutes = ss.minutes; reserve = ss.reserve; startAt = ss.startAt
            lastEvent = ss.lastEvent; paused = ss.paused; pausedAt = ss.pausedAt; engine = eng
        }
        acquireWakeLock(eng.totalMs + 30 * 60_000L)
        startLoop()
        return true
    }

    private fun persist() {
        val eng = engine ?: return
        synchronized(lock) {
            SessionStore.save(this, SessionStore.Session(mode, minutes, reserve, startAt, eng.snapshot(), lastEvent, paused, pausedAt))
        }
    }

    private fun stopTimer() {
        loopJob?.cancel(); engine = null
        alarmManager().cancel(alarmPi())
        SessionStore.clear(this)
        wakeLock?.takeIf { it.isHeld }?.release()
        lastTitle = ""; paused = false
        TimerStateHolder.state.value = TimerState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        loopJob?.cancel(); scope.cancel()
        runCatching { unregisterReceiver(screenReceiver) }
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()   // alarm is intentionally NOT cancelled: it restarts the service if we were killed
    }

    // ---------- time & events ----------
    private fun effElapsed(now: Long) = (if (paused) pausedAt else now) - startAt

    /** Must be called under [lock]. Returns the alert to play (END wins), consuming every due event once. */
    private fun processDue(eng: Engine, e: Long): AlertKind? {
        var fire: AlertKind? = null
        while (true) {
            val ev = eng.nextEvent(lastEvent) ?: break
            if (ev.atMs > e) break
            lastEvent = ev.atMs
            fire = if (fire == AlertKind.END || ev.kind == AlertKind.END) AlertKind.END else ev.kind
        }
        if (fire != null) persist()
        return fire
    }

    /** One tick: handle due alerts, publish state, re-arm the exact alarm. Safe to call from anywhere/anytime. */
    private suspend fun pulse(): TimerState? {
        val eng = engine ?: return null
        var fire: AlertKind? = null
        val st = synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            if (!paused) fire = processDue(eng, effElapsed(now))
            eng.stateAt(effElapsed(now)).copy(paused = paused)
        }
        publish(st)
        fire?.let { player.play(it, repo.settings.first()) }
        rescheduleAlarm()
        return st
    }

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            while (isActive) {
                val st = pulse() ?: break
                val wait = when {
                    screenOn -> if (paused) 1000L else 1000L - st.elapsedMs % 1000
                    paused -> 60_000L
                    else -> nextEventDelay().coerceIn(500L, 60_000L)   // screen off: no per-second wakeups
                }
                withTimeoutOrNull(wait) { wake.receive() }
            }
        }
    }

    private fun nextEventDelay(): Long = synchronized(lock) {
        val ev = engine?.nextEvent(lastEvent) ?: return 60_000L
        ev.atMs - effElapsed(SystemClock.elapsedRealtime())
    }

    private fun alarmManager() = getSystemService(AlarmManager::class.java)
    private fun alarmPi() = PendingIntent.getForegroundService(this, 2,
        Intent(this, TimerService::class.java).setAction(ACTION_ALERT),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun rescheduleAlarm() {
        val am = alarmManager(); val pi = alarmPi()
        am.cancel(pi)
        val eng = engine ?: return
        val at = synchronized(lock) { if (paused) null else eng.nextEvent(lastEvent)?.let { startAt + it.atMs } } ?: return
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms())
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)   // fallback: not exact
        else am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
    }

    // ---------- user actions ----------
    private fun afterChange() { persist(); scope.launch { pulse() }; wake.trySend(Unit) }

    private fun navigate(delta: Int) {
        val eng = engine as? ClassEngine ?: return
        val ok = synchronized(lock) {
            val e = effElapsed(SystemClock.elapsedRealtime())
            if (eng.jump(delta, e)) { lastEvent = maxOf(lastEvent, e); true } else false   // manual jump: no alert
        }
        if (ok) afterChange()
    }

    private fun undoJump() {
        val eng = engine as? ClassEngine ?: return
        val ok = synchronized(lock) { eng.undo(effElapsed(SystemClock.elapsedRealtime())) }
        if (ok) afterChange()
    }

    private fun adjust(min: Int) {
        val eng = engine as? ExamEngine ?: return
        synchronized(lock) { eng.extend(min * 60_000L, effElapsed(SystemClock.elapsedRealtime())) }
        acquireWakeLock(eng.totalMs + 30 * 60_000L)
        afterChange()
    }

    private fun setPaused(p: Boolean) {
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            if (p && !paused) { paused = true; pausedAt = now }
            else if (!p && paused) { startAt += now - pausedAt; paused = false }
        }
        afterChange()
    }

    private fun acquireWakeLock(timeoutMs: Long) {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MathTimer::Timer")
            .apply { setReferenceCounted(false); acquire(timeoutMs) }
    }

    // ---------- notification ----------
    private fun publish(st: TimerState) {
        TimerStateHolder.state.value = st
        if (st.title != lastTitle || st.paused != lastPaused) {
            lastTitle = st.title; lastPaused = st.paused
            nm.notify(NOTIF_ID, notif(st))
        }
    }

    private fun svcPi(code: Int, action: String) = PendingIntent.getService(this, code,
        Intent(this, TimerService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    private fun notif(s: TimerState): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val now = System.currentTimeMillis()
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(s.title)
            .setContentText(if (s.paused) "متوقف مؤقتًا" else if (s.mode == Mode.EXAM) "اختبار" else "حصة رياضيات")
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setUsesChronometer(!s.paused).setChronometerCountDown(!s.finished)
            .setWhen(if (s.finished) now - s.overtimeMs else now + s.phaseRemainingMs)   // system draws the countdown
            .setContentIntent(open)
            .addAction(0, if (s.paused) "استئناف" else "إيقاف مؤقت", svcPi(3, if (s.paused) ACTION_RESUME else ACTION_PAUSE))
            .addAction(0, "إنهاء", svcPi(1, ACTION_STOP))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}

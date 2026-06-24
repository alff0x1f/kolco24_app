package ru.kolco24.kolco24

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.kolco24.kolco24.data.track.LocationEngine
import ru.kolco24.kolco24.data.track.LocationEngineFactory
import ru.kolco24.kolco24.data.track.TrackProfile
import ru.kolco24.kolco24.data.track.TrackState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that records the team's GPS track with the screen off. Started from the UI
 * (Task 8) only after `ACCESS_FINE_LOCATION` is confirmed, so the `type=location` `startForeground`
 * is legal; it re-checks the permission on entry (TOCTOU guard) and bails cleanly if it was revoked.
 *
 * Time logic lives in `TrackRepository.insertAll` — the service merely forwards each delivered batch
 * of `RawFix`. Not unit-tested (an Android adapter, per repo convention); the engine choice and the
 * point mapping are covered by `LocationEngineFactoryTest`/`TrackRepositoryTest`.
 *
 * `START_NOT_STICKY`: a system kill does not silently resume recording (an unexpected battery drain
 * is worse than a missed tail) — the user restarts from the UI.
 */
class TrackRecordingService : Service() {

    private val container by lazy { (application as Kolco24App).container }
    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    /** Service-local scope for the count collector; cancelled on teardown and recreated on restart. */
    private var serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Posts the flush-timeout fallback; engine-field mutations + flush callbacks serialize on main. */
    private val mainHandler = Handler(Looper.getMainLooper())

    private var engine: LocationEngine? = null
    private var countJob: Job? = null
    private var raceId: Int = -1
    private var teamId: Int = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            teardown()
            return START_NOT_STICKY
        }

        // TOCTOU guard: the launcher confirmed permission before starting, but it could have been
        // revoked in the gap. Without coarse/fine, a `type=location` startForeground throws on 14+ —
        // so refuse to promote to foreground and just stop.
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission missing at service start; aborting.")
            stopSelf()
            return START_NOT_STICKY
        }

        raceId = intent?.getIntExtra(EXTRA_RACE_ID, -1) ?: -1
        teamId = intent?.getIntExtra(EXTRA_TEAM_ID, -1) ?: -1
        if (raceId < 0 || teamId < 0) {
            Log.w(TAG, "Missing raceId/teamId extras; aborting.")
            stopSelf()
            return START_NOT_STICKY
        }

        // A prior teardown() may have cancelled serviceScope (rapid stop→start on the same instance).
        // Recreate it so the count-collector launch below works on a fresh scope.
        if (!serviceScope.isActive) serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        createChannel()
        // Must be called within ~5 s of the start; do it first with a 0-count notification. The
        // 3-arg type overload exists from API 29; on 24–28 the 2-arg form is the only one.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(0), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, buildNotification(0))
        }

        container.trackRecordingState.value = TrackState.Recording(teamId, 0)

        // Reflect the DB truth in the state + notification as points land.
        // Defensive: stop any engine from a prior start before creating a new one.
        engine?.stop()
        engine = null
        countJob?.cancel()
        countJob = serviceScope.launch {
            container.trackRepository.countForTeam(teamId, raceId).collectLatest { count ->
                container.trackRecordingState.value = TrackState.Recording(teamId, count)
                notificationManager.notify(NOTIF_ID, buildNotification(count))
            }
        }

        // Stopgap (Task 7 replaces this with profile-aware startEngine): Precise == today's behavior.
        val locationEngine = LocationEngineFactory.create(this, TrackProfile.Precise).also { engine = it }
        locationEngine.start(
            onPoints = { fixes ->
                container.applicationScope.launch {
                    container.trackRepository.insertAll(fixes, raceId, teamId)
                }
            },
            onError = { e ->
                Log.e(TAG, "Location engine error; stopping recording.", e)
                teardown()
            },
        )

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // A system-initiated onDestroy() (task removed / reclaim) without an ACTION_STOP would drop the
        // Fused buffer via a bare stop(). Route it through a best-effort flush too — but onDestroy may
        // precede process death before the async flush lands, so this is best-effort only (a bare
        // onDestroy / hard kill may still lose up to maxDelay; «Стоп» and live-switch are lossless).
        val e = engine
        if (e != null && container.trackRecordingState.value is TrackState.Recording) {
            flushThen(e) {
                if (engine === e) e.stop()
                finishTeardown()
            }
        } else {
            finishTeardown()
        }
        super.onDestroy()
    }

    /** Stop updates, flip state back to Idle, drop the foreground notification, and stop the service. */
    private fun teardown() {
        val e = engine ?: return finishTeardown()
        // Flush the buffered batch (delivered/enqueued to applicationScope) before stopping — else the
        // last ≤maxDelay of fixes are lost (field-tested bug). Identity-guarded so a rapid stop→start
        // can't stop a newer engine.
        flushThen(e) {
            if (engine === e) {
                e.stop()
                engine = null
            }
            finishTeardown()
        }
    }

    /**
     * Flush [e]'s buffer, then run [after] **exactly once** — fired by the flush callback **or** a
     * [FLUSH_TIMEOUT_MS] fallback so a never-completing `flushLocations()` (rare GMS hiccup) can't leave
     * the «Стоп» service stuck foreground. Both paths run on the main thread.
     */
    private fun flushThen(e: LocationEngine, after: () -> Unit) {
        val done = AtomicBoolean(false)
        val complete = Runnable { if (done.compareAndSet(false, true)) after() }
        mainHandler.postDelayed(complete, FLUSH_TIMEOUT_MS) // stuck-flush fallback
        e.flush {
            mainHandler.removeCallbacks(complete)
            complete.run()
        }
    }

    /**
     * Final teardown: release the engine, drop pending flush timeouts, cancel jobs, flip to Idle, flush
     * uploads, and stop. Nulls [engine] defensively (so a later stale timeout's `engine !== e` is
     * guaranteed true) and `removeCallbacksAndMessages(null)` drops any *other* `flushThen` instance's
     * pending timeout `Runnable` — both **before** `serviceScope.cancel()`/`stopSelf()`. Runs on the
     * main thread (flush/timeout callback), not inside a serviceScope coroutine, so cancel is safe.
     */
    private fun finishTeardown() {
        engine?.stop()
        engine = null
        mainHandler.removeCallbacksAndMessages(null)
        countJob?.cancel()
        container.trackRecordingState.value = TrackState.Idle
        // Opportunistic flush of this scope on stop; outlives the service via applicationScope.
        // No-op (or quietly Offline) until the backend endpoint lands — points stay uploaded*=0.
        val r = raceId
        val t = teamId
        if (r >= 0 && t >= 0) {
            container.applicationScope.launch { container.trackRepository.uploadPending(r, t) }
        }
        serviceScope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Запись трека",
            NotificationManager.IMPORTANCE_LOW, // no sound/vibration — a quiet ongoing notification.
        ).apply { setShowBadge(false) }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(pointCount: Int): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TrackRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Идёт запись трека")
            .setContentText("$pointCount точек")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .addAction(0, "Стоп", stopIntent)
            .build()
    }

    companion object {
        private const val TAG = "TrackRecordingService"
        private const val CHANNEL_ID = "track_recording"
        private const val NOTIF_ID = 1001
        private const val FLUSH_TIMEOUT_MS = 4_000L
        private const val ACTION_STOP = "ru.kolco24.kolco24.action.STOP_TRACK"
        private const val EXTRA_RACE_ID = "race_id"
        private const val EXTRA_TEAM_ID = "team_id"

        /** Start recording for [raceId]/[teamId]. Caller must have confirmed location permission. */
        fun start(context: Context, raceId: Int, teamId: Int) {
            val intent = Intent(context, TrackRecordingService::class.java)
                .putExtra(EXTRA_RACE_ID, raceId)
                .putExtra(EXTRA_TEAM_ID, teamId)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Stop recording (notification «Стоп» button or a team switch). */
        fun stop(context: Context) {
            val intent = Intent(context, TrackRecordingService::class.java).setAction(ACTION_STOP)
            // startService (not foreground) is fine for a stop command; the service tears itself down.
            context.startService(intent)
        }
    }
}

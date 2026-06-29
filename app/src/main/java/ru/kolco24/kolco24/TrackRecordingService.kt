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
import android.os.SystemClock
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
import ru.kolco24.kolco24.data.track.pointsLabel
import ru.kolco24.kolco24.data.track.TrackState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decide the recording-session [segmentId] on a fresh-start path: mint a new one when there is no
 * current segment (first start or after a teardown reset it to null) or when a teardown was in flight
 * ([wasTearingDown] — the start is replacing a session being torn down, so it's logically a new one);
 * otherwise keep [current] so a duplicate/idempotent start intent stays one segment. Pure so the
 * stop→start / idempotent-re-entry matrix is JVM-tested (repo convention).
 */
fun nextSegmentId(current: String?, wasTearingDown: Boolean, mint: () -> String): String =
    if (wasTearingDown || current == null) mint() else current

/**
 * Minimum spacing between in-recording live uploads: 10 min. Applies to **both** profiles — Precise
 * (~60 s batches) fires ~every 10 min; Economy (~180 s batches) fires on the batch crossing 600 s,
 * ~every 12 min. One constant, no per-profile config (the GPS-delivery wake is reused, no extra
 * device wakeups).
 */
const val LIVE_UPLOAD_MIN_INTERVAL_MS = 600_000L

/**
 * Decide whether to fire a live upload for the current fix batch: never uploaded this session
 * ([lastUploadElapsed] == null) → always true (the first batch fires immediately, regardless of how
 * long the device has been booted), else true once the monotonic delta since the last upload reaches
 * [minIntervalMs]. The **nullable** sentinel (not `0L`) is reboot-safe: `elapsedRealtime()` is
 * time-since-boot, so a recording started within 10 min of a reboot would not fire on its first batch
 * with a `0L` baseline — and it's overflow-safe (no `now - Long.MIN_VALUE`). Pure so the
 * boundary/first-batch matrix is JVM-tested (repo convention).
 */
fun shouldLiveUpload(nowElapsed: Long, lastUploadElapsed: Long?, minIntervalMs: Long): Boolean =
    lastUploadElapsed == null || nowElapsed - lastUploadElapsed >= minIntervalMs

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
    /** Observes the profile preference for live-apply; re-launched per onStartCommand (like countJob). */
    private var profileJob: Job? = null
    /** The profile the running engine is currently on; updated only inside a completed flush callback. */
    private var activeProfile: TrackProfile = TrackProfile.Precise
    private var raceId: Int = -1
    private var teamId: Int = -1
    /** UUID minted once per recording session; snapshotted into each engine's onPoints batch. */
    private var segmentId: String? = null
    /**
     * Monotonic timestamp of the last in-recording live upload, or null until the first one. Drives the
     * [shouldLiveUpload] throttle so a recording flushes to the server ~every 10 min (first batch fires
     * immediately). **Persists across startEngine restarts** (a Precise↔Economy soft-restart must not
     * reset the throttle) and is reset to null on each new logical session — the same lifecycle as
     * [segmentId] (finishTeardown + the onStartCommand fresh-start mint predicate).
     */
    private var lastLiveUploadElapsed: Long? = null
    /**
     * Set to true when teardown() is in progress so the in-flight profile-switch flush callback
     * doesn't restart the engine after Stop wins the race — without this guard the profile callback
     * can restart the engine and teardown's identity check (engine !== e) then skips finishTeardown.
     */
    private var isTearingDown = false

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
        val wasTearingDown = isTearingDown
        isTearingDown = false // reset for new recording session
        // Reset the live-upload throttle on the same predicate nextSegmentId uses to mint — a genuinely
        // new logical session re-uploads its first batch immediately. Must be here too (not only in
        // finishTeardown): on a rapid stop→start the old session's teardown flush returns early at
        // `engine !== e` before finishTeardown runs, so reset there is skipped. Do NOT reset on an
        // idempotent re-entry (same logical session) — it keeps its 10-min throttle.
        if (wasTearingDown || segmentId == null) lastLiveUploadElapsed = null
        // Mint a fresh segment id only on a genuinely new session (first start, post-teardown, or one
        // replacing an in-flight teardown). An idempotent re-entry keeps the existing segment so a
        // duplicate start intent doesn't split one recording into two segments.
        segmentId = nextSegmentId(segmentId, wasTearingDown) { java.util.UUID.randomUUID().toString() }

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
        // Defensive: stop any engine from a prior start before creating a new one — but only if
        // teardown's flush isn't already in flight; the flush callback owns the stop in that case.
        if (!wasTearingDown) engine?.stop()
        engine = null
        countJob?.cancel()
        countJob = serviceScope.launch {
            container.trackRepository.countForTeam(teamId, raceId).collectLatest { count ->
                container.trackRecordingState.value = TrackState.Recording(teamId, count)
                notificationManager.notify(NOTIF_ID, buildNotification(count))
            }
        }

        // Start the engine on the persisted profile (Precise == today's behavior by default).
        activeProfile = container.trackProfilePreference.profile.value
        startEngine(activeProfile)

        // Live-apply: a mid-race profile toggle soft-restarts the engine without a track gap. Must be
        // (re)launched here, not once — teardown() cancels serviceScope and a later start recreates it.
        // No .drop(1): track activeProfile and skip emissions equal to it, so a change landing between
        // the initial .value read and the subscription isn't dropped. Runs on Dispatchers.Main.immediate
        // so engine writes serialize with the main-thread lifecycle + flush callbacks.
        profileJob?.cancel()
        profileJob = serviceScope.launch(Dispatchers.Main.immediate) {
            container.trackProfilePreference.profile.collectLatest { p ->
                if (p == activeProfile) return@collectLatest
                val e = engine ?: run {
                    activeProfile = p
                    startEngine(p)
                    return@collectLatest
                }
                flushThen(e) {
                    if (engine !== e) return@flushThen // a newer engine took over
                    // teardown() may have won the race: stop was requested while this flush was
                    // in flight. Don't restart the engine; teardown's own flushThen handles cleanup.
                    if (isTearingDown) return@flushThen
                    e.stop()
                    val latest = container.trackProfilePreference.profile.value // restart to LATEST
                    activeProfile = latest
                    startEngine(latest)
                }
            }
        }

        return START_NOT_STICKY
    }

    /**
     * Build + start a fresh engine on [profile], assigning the captured local to [engine] before
     * `start()` (NPE-safe: a concurrent `engine = null` can't null the local we start). Fully
     * synchronous and does **not** pre-stop — callers own the stop (onStartCommand's defensive
     * `engine?.stop()`, or the live-restart / teardown `flushThen` callbacks).
     */
    private fun startEngine(profile: TrackProfile) {
        val e = LocationEngineFactory.create(this, profile)
        // Snapshot raceId/teamId as locals so the onPoints lambda isn't affected by a later
        // onStartCommand (team switch + immediate new start) overwriting the instance fields before
        // the old engine finishes delivering its buffered batch.
        val r = raceId
        val t = teamId
        val s = segmentId ?: java.util.UUID.randomUUID().toString().also { segmentId = it }
        engine = e
        e.start(
            onPoints = { fixes ->
                // Throttled live upload: decide on the (serialized, main-thread) callback whether enough
                // time has elapsed since the last live upload, then piggyback on the batch-insert wake.
                // Guard on engine === e: a stale flush callback from a rapid stop→start must not
                // consume the new session's null sentinel (first-batch-fires slot).
                val now = SystemClock.elapsedRealtime()
                val doUpload = engine === e && shouldLiveUpload(now, lastLiveUploadElapsed, LIVE_UPLOAD_MIN_INTERVAL_MS)
                if (doUpload) lastLiveUploadElapsed = now
                container.applicationScope.launch {
                    container.trackRepository.insertAll(fixes, r, t, s)
                    // uploadPending is mutex-guarded (tryLock), dual-target, offline-tolerant — a stop /
                    // team-switch upload in flight just makes this a no-op; a failure leaves points pending.
                    if (doUpload) {
                        container.trackRepository.uploadPending(r, t)
                        // Piggyback the same throttled wake for КП takes (marks) so they reach the
                        // organizers in near-real-time alongside the track. Same scope/tryLock guard.
                        container.markRepository.uploadPending(r, t)
                    }
                }
            },
            onError = { err ->
                Log.e(TAG, "Location engine error; stopping recording.", err)
                mainHandler.post { teardown() }
            },
        )
    }

    override fun onDestroy() {
        // A system-initiated onDestroy() (task removed / reclaim) without an ACTION_STOP would drop the
        // Fused buffer via a bare stop(). Route it through a best-effort flush too — but onDestroy may
        // precede process death before the async flush lands, so this is best-effort only (a bare
        // onDestroy / hard kill may still lose up to maxDelay; «Стоп» and live-switch are lossless).
        isTearingDown = true // prevent an in-flight profile-switch flush from restarting the engine
        val e = engine
        if (e != null && container.trackRecordingState.value is TrackState.Recording) {
            flushThen(e) {
                if (engine === e) e.stop()
                finishTeardown()
            }
        } else if (container.trackRecordingState.value is TrackState.Recording) {
            // engine is null but state not yet cleaned up (e.g. stopped mid-start before engine set)
            finishTeardown()
        }
        // else: teardown() already ran — engine is null, state is Idle; nothing to do.
        super.onDestroy()
    }

    /** Stop updates, flip state back to Idle, drop the foreground notification, and stop the service. */
    private fun teardown() {
        isTearingDown = true
        val e = engine ?: return finishTeardown()
        // Flush the buffered batch (delivered/enqueued to applicationScope) before stopping — else the
        // last ≤maxDelay of fixes are lost (field-tested bug). Always stop the old engine after flush;
        // the identity guard only skips finishTeardown so a concurrent new session isn't torn down.
        flushThen(e) {
            e.stop() // flush done; stop old engine regardless of whether a new session started
            if (engine !== e) return@flushThen // newer session took over — don't finalize
            engine = null
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
        segmentId = null // next session mints a fresh segment — a stop→start gap is a new segment
        lastLiveUploadElapsed = null // next session re-uploads its first batch immediately
        mainHandler.removeCallbacksAndMessages(null)
        countJob?.cancel()
        profileJob?.cancel()
        container.trackRecordingState.value = TrackState.Idle
        // Opportunistic flush of both track points and checkpoint takes on stop; both outlive the
        // service via applicationScope so a slow upload finishes even after stopSelf().
        val r = raceId
        val t = teamId
        if (r >= 0 && t >= 0) {
            container.applicationScope.launch { container.trackRepository.uploadPending(r, t) }
            container.applicationScope.launch { container.markRepository.uploadPending(r, t) }
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
            .setContentText(pointsLabel(pointCount))
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

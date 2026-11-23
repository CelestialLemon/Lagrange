package com.celestiallemon.lagrange.friction

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.celestiallemon.lagrange.LagrangeApplication
import com.celestiallemon.lagrange.StartupFailedException
import com.celestiallemon.lagrange.data.FrictionControlCommand
import com.celestiallemon.lagrange.data.FrictionRepository.Companion.toAppPolicy
import com.celestiallemon.lagrange.data.IngestionKeys
import com.celestiallemon.lagrange.data.UserPrefs
import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.FrictionVocab
import com.celestiallemon.lagrange.data.db.IntentionTag
import com.celestiallemon.lagrange.data.db.ServiceKind
import com.celestiallemon.lagrange.domain.LogicalDay
import com.celestiallemon.lagrange.domain.friction.FrictionAppPolicy
import com.celestiallemon.lagrange.domain.friction.FrictionEffect
import com.celestiallemon.lagrange.domain.friction.FrictionEngine
import com.celestiallemon.lagrange.domain.friction.FrictionSchedule
import com.celestiallemon.lagrange.domain.friction.TodayUsageSnapshot
import com.celestiallemon.lagrange.harvest.HarvestTrigger
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Real-time Gentle Friction host (`AccessibilityService` with `TYPE_WINDOW_STATE_CHANGED` only,
 * `canRetrieveWindowContent="false"`, `isAccessibilityTool="false"`).
 *
 * Drives [FrictionEngine] on the main thread from cached policy snapshots for zero-latency overlays, while
 * serializing all database writes, coverage intervals, and monotonic runtime snapshots on IO behind the app's
 * startup gate.
 */
class LagrangeAccessibilityService : AccessibilityService() {
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serialized DB / DataStore work queue keeping event writes and coverage transitions in arrival order. */
    private val work = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    private val graph get() = (application as LagrangeApplication).graph

    private lateinit var classifier: AndroidWindowClassifier
    private lateinit var engine: FrictionEngine
    private lateinit var overlayController: FrictionOverlayController

    private var screenReceiverRegistered = false
    private var ready = false
    private var currentPrefs: UserPrefs = UserPrefs()
    private var currentSchedules: List<FrictionSchedule> = emptyList()
    private var appPoliciesByPackage: Map<String, FrictionAppPolicy> = emptyMap()

    // In-memory cache of today's materialized usage so launch-pause decisions never block the main thread.
    private var cachedLogicalDate: String = ""
    private var cachedRollupDistractingMs: Long = 0L
    private var cachedAppRealOpens: MutableMap<Long, Int> = HashMap()
    private var cachedPromptsShownToday: Int = 0
    private var sessionDistractingAtLastCacheRefreshMs: Long = 0L

    private var observersJob: Job? = null
    private var tickerJob: Job? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val nowWall = System.currentTimeMillis()
            val nowElapsed = SystemClock.elapsedRealtime()
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    val effects = engine.onScreenOff(nowWall, nowElapsed)
                    applyEffects(effects)
                    work.trySend { graph.coverage.heartbeat(ServiceKind.ACCESSIBILITY, nowWall) }
                    graph.harvestInBackground(HarvestTrigger.SCREEN_OFF)
                }
                Intent.ACTION_USER_PRESENT -> {
                    if (!ready) return
                    val effects = engine.onUserPresent(
                        nowWallMs = nowWall,
                        nowElapsedMs = nowElapsed,
                        zone = ZoneId.systemDefault(),
                        settings = currentPrefs.toFrictionSettings(),
                        schedules = currentSchedules,
                        resolveApp = ::resolvePolicyCached,
                        loadUsage = { app -> buildUsageSnapshot(app, nowWall) },
                        resolveRedirect = { pkg -> appPoliciesByPackage[pkg] },
                    )
                    applyEffects(effects)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        classifier = AndroidWindowClassifier(this)
        engine = FrictionEngine(classifier)
        overlayController = FrictionOverlayController(
            service = this,
            mainScope = mainScope,
            callbacks = overlayCallbacks,
            useDynamicColor = { currentPrefs.useDynamicColor },
        )

        ioScope.launch {
            try {
                graph.awaitReady()
            } catch (e: StartupFailedException) {
                Log.e(TAG, "startup failed; friction service stays idle until next start", e)
                work.cancel()
                return@launch
            }
            for (job in work) {
                try {
                    job()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "friction background write failed", e)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        registerScreenReceiver()
        work.trySend { graph.coverage.open(ServiceKind.ACCESSIBILITY, nowWall) }
        startObserversAndRestore(nowElapsed)
        startTicker()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!ready || event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()
        val cls = event.className?.toString()
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val effects = engine.onWindowStateChanged(
            packageName = pkg,
            className = cls,
            nowWallMs = nowWall,
            nowElapsedMs = nowElapsed,
            zone = ZoneId.systemDefault(),
            settings = currentPrefs.toFrictionSettings(),
            schedules = currentSchedules,
            resolveApp = ::resolvePolicyCached,
            loadUsage = { app -> buildUsageSnapshot(app, nowWall) },
            resolveRedirect = { redirectPkg -> appPoliciesByPackage[redirectPkg] },
        )
        applyEffects(effects)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Same-app activity recreation during rotation is automatically ignored by ForegroundAppStateMachine.
    }

    override fun onInterrupt() {
        val effects = engine.onServiceDisconnected(System.currentTimeMillis(), SystemClock.elapsedRealtime())
        applyEffects(effects)
        overlayController.dismiss()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardownConnection()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardownConnection()
        work.close()
        mainScope.cancel()
        super.onDestroy()
    }

    private fun teardownConnection() {
        unregisterScreenReceiver()
        observersJob?.cancel()
        observersJob = null
        tickerJob?.cancel()
        tickerJob = null
        if (::engine.isInitialized) {
            val effects = engine.onServiceDisconnected(System.currentTimeMillis(), SystemClock.elapsedRealtime())
            applyEffects(effects)
        }
        if (::overlayController.isInitialized) {
            overlayController.dismiss()
        }
        if (connected) {
            connected = false
            val nowWall = System.currentTimeMillis()
            work.trySend { graph.coverage.close(ServiceKind.ACCESSIBILITY, nowWall) }
        }
    }

    private fun startObserversAndRestore(connectedElapsedMs: Long) {
        observersJob?.cancel()
        observersJob = mainScope.launch {
            try {
                withContext(Dispatchers.IO) { graph.awaitReady() }
            } catch (_: StartupFailedException) {
                return@launch
            }

            // Initial snapshot load off main thread.
            val (prefs, schedules, policies, savedRuntime) = withContext(Dispatchers.IO) {
                val p = graph.preferences.current()
                val s = graph.friction.allSchedules()
                val a = graph.friction.allAppPolicies()
                val r = graph.friction.loadRuntimeSnapshot()
                Quad(p, s, a, r)
            }
            currentPrefs = prefs
            currentSchedules = schedules
            appPoliciesByPackage = policies
            engine.restore(savedRuntime, currentBootCount(), connectedElapsedMs)
            refreshUsageCache()
            publishStatus(connectedElapsedMs)
            ready = true

            launch {
                graph.preferences.prefs.collect { p ->
                    val rolloverChanged = p.rolloverHour != currentPrefs.rolloverHour
                    currentPrefs = p
                    if (rolloverChanged) refreshUsageCache()
                }
            }
            launch {
                graph.friction.observeDomainSchedules().collect { s ->
                    currentSchedules = s
                }
            }
            launch {
                graph.database.packageDao().observeAll().collect { rows ->
                    appPoliciesByPackage = rows.associate { it.packageName to it.toAppPolicy() }
                }
            }
            launch {
                graph.database.ingestionStateDao().observe(IngestionKeys.DERIVED_LAST_RUN_AT_MS).collect {
                    refreshUsageCache()
                }
            }
            launch {
                graph.friction.commands.collect { cmd ->
                    handleControlCommand(cmd)
                }
            }
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = mainScope.launch {
            var lastHeartbeatElapsed = SystemClock.elapsedRealtime()
            while (true) {
                delay(TICK_INTERVAL_MS)
                if (!ready) continue
                val nowWall = System.currentTimeMillis()
                val nowElapsed = SystemClock.elapsedRealtime()
                val effects = engine.onTick(
                    nowWallMs = nowWall,
                    nowElapsedMs = nowElapsed,
                    zone = ZoneId.systemDefault(),
                    settings = currentPrefs.toFrictionSettings(),
                    schedules = currentSchedules,
                    loadUsage = { app -> buildUsageSnapshot(app, nowWall) },
                )
                if (effects.isNotEmpty()) {
                    applyEffects(effects)
                } else {
                    publishStatus(nowElapsed)
                }
                if (nowElapsed - lastHeartbeatElapsed >= HEARTBEAT_INTERVAL_MS) {
                    lastHeartbeatElapsed = nowElapsed
                    work.trySend { graph.coverage.heartbeat(ServiceKind.ACCESSIBILITY, nowWall) }
                }
            }
        }
    }

    private suspend fun refreshUsageCache() {
        val nowWall = System.currentTimeMillis()
        val snapshot = withContext(Dispatchers.IO) {
            val logicalDate = graph.derived.dateOf(nowWall).toString()
            val dao = graph.database.derivedDao()
            val dayRollup = dao.dailyRollup(logicalDate)
            val derivedEndMs = graph.database.ingestionStateDao().get(IngestionKeys.DERIVED_DATA_END_MS) ?: 0L
            val allEventsToday = dao.frictionOn(logicalDate)
            val opensMap = HashMap<Long, Int>()
            for (row in dao.appRollupsOn(logicalDate)) {
                if (row.openCount > 0) opensMap[row.pkgId] = row.openCount
            }
            for (ev in allEventsToday) {
                if (ev.eventKind == FrictionVocab.LAUNCH_PAUSE && ev.outcome == FrictionVocab.OUTCOME_CONTINUED &&
                    ev.timestampUtcMs >= derivedEndMs
                ) {
                    opensMap[ev.pkgId] = (opensMap[ev.pkgId] ?: 0) + 1
                }
            }
            val prompts = allEventsToday.count { it.eventKind == FrictionVocab.LAUNCH_PAUSE }
            UsageCache(logicalDate, dayRollup?.distractingMs ?: 0L, opensMap, prompts)
        }
        cachedLogicalDate = snapshot.logicalDate
        cachedRollupDistractingMs = snapshot.distractingMs
        cachedAppRealOpens = snapshot.opensByPkg
        cachedPromptsShownToday = snapshot.promptsShown
        sessionDistractingAtLastCacheRefreshMs = engine.accumulatedSessionDistractingMs
    }

    private fun buildUsageSnapshot(app: FrictionAppPolicy, nowWallMs: Long): TodayUsageSnapshot {
        val logicalDate = LogicalDay.dateOf(nowWallMs, currentPrefs.rolloverHour, ZoneId.systemDefault()).toString()
        if (logicalDate != cachedLogicalDate) {
            cachedLogicalDate = logicalDate
            cachedRollupDistractingMs = 0L
            cachedAppRealOpens.clear()
            cachedPromptsShownToday = 0
            sessionDistractingAtLastCacheRefreshMs = engine.accumulatedSessionDistractingMs
        }
        val unsyncedSessionDelta = (engine.accumulatedSessionDistractingMs - sessionDistractingAtLastCacheRefreshMs).coerceAtLeast(0L)
        return TodayUsageSnapshot(
            logicalDate = logicalDate,
            appRealOpensToday = cachedAppRealOpens[app.pkgId] ?: 0,
            distractingMsToday = cachedRollupDistractingMs + unsyncedSessionDelta,
            promptsShownToday = cachedPromptsShownToday,
            budgetMs = currentPrefs.dailyBudgetMs,
        )
    }

    private fun resolvePolicyCached(packageName: String): FrictionAppPolicy {
        appPoliciesByPackage[packageName]?.let { return it }
        // Unknown package not yet interned: intern asynchronously for future events, treat as non-distracting NEUTRAL now.
        work.trySend {
            val resolved = graph.friction.resolveAppPolicy(packageName)
            mainScope.launch {
                appPoliciesByPackage = appPoliciesByPackage + (packageName to resolved)
            }
        }
        return FrictionAppPolicy(
            pkgId = 0L,
            packageName = packageName,
            displayName = packageName,
            category = AppCategory.NEUTRAL,
            override = null,
            isSystemSurface = false,
        )
    }

    private fun handleControlCommand(cmd: FrictionControlCommand) {
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val logicalDate = LogicalDay.dateOf(nowWall, currentPrefs.rolloverHour, ZoneId.systemDefault()).toString()
        val effects = when (cmd) {
            FrictionControlCommand.ActivateSnooze1h -> {
                val ownPkgId = appPoliciesByPackage[packageName]?.pkgId ?: 0L
                engine.activateSnooze1h(nowWall, nowElapsed, logicalDate, ownPkgId)
            }
            FrictionControlCommand.CancelSnooze -> engine.cancelSnooze(nowElapsed)
            FrictionControlCommand.ActivateFocusBoost1h -> engine.activateFocusBoost1h(nowElapsed)
            FrictionControlCommand.CancelFocusBoost -> engine.cancelFocusBoost(nowElapsed)
        }
        applyEffects(effects)
    }

    private fun applyEffects(effects: List<FrictionEffect>) {
        val nowElapsed = SystemClock.elapsedRealtime()
        for (effect in effects) {
            when (effect) {
                is FrictionEffect.ShowOverlay -> overlayController.show(effect.overlay)
                FrictionEffect.DismissOverlay -> overlayController.dismiss()
                FrictionEffect.PerformGoHome -> performGoHomeSafely()
                is FrictionEffect.LaunchRedirect -> launchRedirectSafely(effect.packageName)
                is FrictionEffect.LogEvent -> {
                    val rec = effect.record
                    if (rec.eventKind == FrictionVocab.LAUNCH_PAUSE) {
                        cachedPromptsShownToday += 1
                        if (rec.outcome == FrictionVocab.OUTCOME_CONTINUED && rec.pkgId > 0L) {
                            cachedAppRealOpens[rec.pkgId] = (cachedAppRealOpens[rec.pkgId] ?: 0) + 1
                        }
                    }
                    work.trySend {
                        val pkgId = if (rec.pkgId > 0L) rec.pkgId else graph.interner.packageId(packageName)
                        graph.friction.recordEvent(rec.copy(pkgId = pkgId))
                    }
                }
                is FrictionEffect.PersistState -> {
                    work.trySend { graph.friction.saveRuntimeSnapshot(effect.snapshot) }
                }
            }
        }
        publishStatus(nowElapsed)
    }

    private fun performGoHomeSafely() {
        val ok = runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }.getOrDefault(false)
        if (!ok) {
            Log.w(TAG, "GLOBAL_ACTION_HOME returned false; falling back to HOME intent")
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { Log.w(TAG, "HOME intent fallback failed", it) }
        }
    }

    private fun launchRedirectSafely(targetPackage: String) {
        val launch = packageManager.getLaunchIntentForPackage(targetPackage)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch != null) {
            runCatching { startActivity(launch) }
                .onFailure {
                    Log.w(TAG, "redirect launch failed for $targetPackage; going home", it)
                    performGoHomeSafely()
                }
        } else {
            Log.w(TAG, "no launch intent for redirect app $targetPackage; going home")
            performGoHomeSafely()
        }
    }

    private fun publishStatus(nowElapsedMs: Long) {
        if (ready) {
            graph.friction.publishLiveStatus(engine.liveStatus(nowElapsedMs))
        }
    }

    private val overlayCallbacks = object : OverlayCallbacks {
        override fun onSelectIntention(tag: IntentionTag) {
            engine.onPauseIntentionSelected(tag)
            engine.activeOverlay?.let(overlayController::update)
        }

        override fun onSelectTimer(minutes: Int?) {
            engine.onPauseTimerSelected(minutes)
            engine.activeOverlay?.let(overlayController::update)
        }

        override fun onPauseGoHome() {
            applyEffects(engine.onPauseGoHome(System.currentTimeMillis(), SystemClock.elapsedRealtime()))
        }

        override fun onPauseRedirect() {
            applyEffects(engine.onPauseRedirect(System.currentTimeMillis(), SystemClock.elapsedRealtime()))
        }

        override fun onPauseContinue() {
            applyEffects(engine.onPauseContinue(System.currentTimeMillis(), SystemClock.elapsedRealtime(), currentPrefs.toFrictionSettings()))
        }

        override fun onPauseSnooze1h() {
            val nowWall = System.currentTimeMillis()
            val nowElapsed = SystemClock.elapsedRealtime()
            val logicalDate = LogicalDay.dateOf(nowWall, currentPrefs.rolloverHour, ZoneId.systemDefault()).toString()
            val ownPkgId = appPoliciesByPackage[packageName]?.pkgId ?: 0L
            applyEffects(engine.activateSnooze1h(nowWall, nowElapsed, logicalDate, ownPkgId))
        }

        override fun onPauseBackPressed() {
            applyEffects(engine.onPauseBackPressed(System.currentTimeMillis(), SystemClock.elapsedRealtime()))
        }

        override fun onTimerGoHome() {
            applyEffects(engine.onTimerGoHome(System.currentTimeMillis(), SystemClock.elapsedRealtime()))
        }

        override fun onTimerBackPressed() {
            applyEffects(engine.onTimerBackPressed(System.currentTimeMillis(), SystemClock.elapsedRealtime()))
        }

        override fun onTimerExtend5m() {
            applyEffects(engine.onTimerExtend5m(System.currentTimeMillis(), SystemClock.elapsedRealtime()))
        }

        override fun onTimerDismissSession() {
            applyEffects(engine.onTimerDismissSession(System.currentTimeMillis(), SystemClock.elapsedRealtime()))
        }

        override fun onNudgeDismissed(userTapped: Boolean) {
            applyEffects(engine.onNudgeDismissed(userTapped))
        }
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        screenReceiverRegistered = true
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        runCatching { unregisterReceiver(screenReceiver) }
        screenReceiverRegistered = false
    }

    private fun currentBootCount(): Int =
        runCatching { Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, 0) }.getOrDefault(0)

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private data class UsageCache(
        val logicalDate: String,
        val distractingMs: Long,
        val opensByPkg: MutableMap<Long, Int>,
        val promptsShown: Int,
    )

    companion object {
        private const val TAG = "LagrangeFriction"
        private const val TICK_INTERVAL_MS = 1_000L
        private const val HEARTBEAT_INTERVAL_MS = 15 * 60_000L

        /** True while Android has this AccessibilityService bound and connected in this process. */
        @Volatile
        var connected: Boolean = false
            private set
    }
}

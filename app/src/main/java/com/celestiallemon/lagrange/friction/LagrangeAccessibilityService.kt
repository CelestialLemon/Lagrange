package com.celestiallemon.lagrange.friction

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.room3.useReaderConnection
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
import com.celestiallemon.lagrange.domain.friction.ActiveOverlay
import com.celestiallemon.lagrange.domain.friction.FrictionAppPolicy
import com.celestiallemon.lagrange.domain.friction.FrictionEffect
import com.celestiallemon.lagrange.domain.friction.FrictionEngine
import com.celestiallemon.lagrange.domain.friction.FrictionSchedule
import com.celestiallemon.lagrange.domain.friction.RedirectLaunchResult
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private var coverageOpened = false
    private var currentPrefs: UserPrefs = UserPrefs()
    private var currentSchedules: List<FrictionSchedule> = emptyList()
    private var appPoliciesByPackage: Map<String, FrictionAppPolicy> = emptyMap()

    // Live usage cache reconciled against DERIVED_DATA_END_MS so non-harvest derived recomputes never drop
    // accrued live minutes and newer Main-thread opens are never overwritten by an older IO refresh.
    private var cachedLogicalDate: String = ""
    private var cachedWindowStartMs: Long = Long.MIN_VALUE
    private var cachedWindowEndMs: Long = Long.MAX_VALUE
    private var cachedDerivedDataEndMs: Long = 0L
    private var cachedRollupDistractingMs: Long = 0L
    private var cachedAppRollupOpens: Map<Long, Int> = emptyMap()
    private var accruedAtLastDerivedAdvanceMs: Long = 0L
    private val recentLiveOpens = ArrayList<TimestampedOpen>()
    private val recentLivePrompts = ArrayList<Long>()

    private var observersJob: Job? = null
    private var tickerJob: Job? = null
    private var unlockFallbackJob: Job? = null

    /** True once [FrictionEngine.restore] ran for this connection; commands are applied from then on (before [ready]). */
    private var engineRestored = false

    private val liveCommandHandler: suspend (FrictionControlCommand) -> Boolean = { cmd ->
        withContext(Dispatchers.Main.immediate) {
            if (engineRestored) {
                handleControlCommand(cmd)
                true
            } else {
                false
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val nowWall = System.currentTimeMillis()
            val nowElapsed = SystemClock.elapsedRealtime()
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (ready) {
                        val effects = engine.onScreenOff(nowWall, nowElapsed)
                        applyEffects(effects)
                        work.trySend { graph.coverage.heartbeat(ServiceKind.ACCESSIBILITY, nowWall) }
                    } else if (::overlayController.isInitialized) {
                        overlayController.dismiss()
                    }
                    graph.harvestInBackground(HarvestTrigger.SCREEN_OFF)
                }
                Intent.ACTION_SCREEN_ON -> {
                    // When the device has no lockscreen (Screen Lock = None), Android never sends ACTION_USER_PRESENT.
                    val km = getSystemService(KeyguardManager::class.java)
                    if (km == null || !km.isKeyguardLocked) {
                        handleUnlock(nowWall, nowElapsed)
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    handleUnlock(nowWall, nowElapsed)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        classifier = AndroidWindowClassifier(this)
        engine = FrictionEngine(classifier)
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        engine.setInitialScreenInteractive(interactive)
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
        val nowElapsed = SystemClock.elapsedRealtime()
        registerScreenReceiver()
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
        // Same-app activity recreation during rotation is ignored by ForegroundAppStateMachine.
    }

    override fun onInterrupt() {
        unlockFallbackJob?.cancel()
        unlockFallbackJob = null
        if (ready && ::engine.isInitialized) {
            val effects = engine.onServiceDisconnected(System.currentTimeMillis(), SystemClock.elapsedRealtime())
            applyEffects(effects)
        }
        if (::overlayController.isInitialized) {
            overlayController.dismiss()
        }
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
        // Persist disconnect state whenever this connection restored the engine (even if teardown beat `ready`).
        val wasRestored = engineRestored
        ready = false
        engineRestored = false
        _readyState.value = false
        connected = false

        unregisterScreenReceiver()
        unlockFallbackJob?.cancel()
        unlockFallbackJob = null
        observersJob?.cancel()
        observersJob = null
        tickerJob?.cancel()
        tickerJob = null

        if (wasRestored && ::engine.isInitialized) {
            val effects = engine.onServiceDisconnected(System.currentTimeMillis(), SystemClock.elapsedRealtime())
            applyEffects(effects)
        }
        if (::overlayController.isInitialized) {
            overlayController.dismiss()
        }
        ioScope.launch {
            runCatching { graph.friction.detachLiveService(liveCommandHandler) }
        }
        if (coverageOpened) {
            coverageOpened = false
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

            // Load policies/settings and atomically restore persisted monotonic state + register command handler
            // under FrictionRepository's runtime mutex so no UI command can ever be dropped during startup.
            val (prefs, schedules, policies) = withContext(Dispatchers.IO) {
                val p = graph.preferences.current()
                val s = graph.friction.allSchedules()
                val a = graph.friction.allAppPolicies()
                Triple(p, s, a)
            }
            currentPrefs = prefs
            currentSchedules = schedules
            appPoliciesByPackage = policies

            graph.friction.attachLiveService(
                handler = liveCommandHandler,
                restoreFromDisk = { saved ->
                    withContext(Dispatchers.Main.immediate) {
                        engine.restore(saved, currentBootCount(), connectedElapsedMs)
                        engineRestored = true
                    }
                },
            )

            refreshUsageCache()

            // Open ACCESSIBILITY coverage only once the engine is genuinely ready.
            val nowWall = System.currentTimeMillis()
            coverageOpened = true
            work.trySend { graph.coverage.open(ServiceKind.ACCESSIBILITY, nowWall) }

            ready = true
            _readyState.value = true
            publishStatus(SystemClock.elapsedRealtime())

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

    private fun handleUnlock(nowWall: Long, nowElapsed: Long) {
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

    private suspend fun refreshUsageCache() {
        val nowWall = System.currentTimeMillis()
        val snapshot = withContext(Dispatchers.IO) {
            val date = graph.derived.dateOf(nowWall)
            val logicalDate = date.toString()
            val window = graph.derived.windowOf(date)
            // Read rollups, cutoff, and today's friction events in a single read transaction for snapshot consistency.
            graph.database.useReaderConnection {
                val dao = graph.database.derivedDao()
                val dayRollup = dao.dailyRollup(logicalDate)
                val derivedEndMs = graph.database.ingestionStateDao().get(IngestionKeys.DERIVED_DATA_END_MS) ?: 0L
                val rollupOpens = dao.appRollupsOn(logicalDate)
                    .filter { it.openCount > 0 }
                    .associate { it.pkgId to it.openCount }
                val eventsInWindow = dao.frictionBetween(window.startMs, window.endMs)
                val continuedAfterDerived = eventsInWindow
                    .filter { it.eventKind == FrictionVocab.LAUNCH_PAUSE && it.outcome == FrictionVocab.OUTCOME_CONTINUED && it.timestampUtcMs >= derivedEndMs }
                    .map { TimestampedOpen(it.pkgId, it.timestampUtcMs) }
                val promptsInWindow = eventsInWindow
                    .filter { it.eventKind == FrictionVocab.LAUNCH_PAUSE }
                    .map { it.timestampUtcMs }
                UsageCacheSnapshot(
                    logicalDate = logicalDate,
                    windowStartMs = window.startMs,
                    windowEndMs = window.endMs,
                    derivedDataEndMs = derivedEndMs,
                    rollupDistractingMs = dayRollup?.distractingMs ?: 0L,
                    rollupOpensByPkg = rollupOpens,
                    dbContinuedAfterDerived = continuedAfterDerived,
                    dbPromptTimestamps = promptsInWindow,
                )
            }
        }

        val dayChanged = snapshot.logicalDate != cachedLogicalDate
        val derivedAdvanced = snapshot.derivedDataEndMs > cachedDerivedDataEndMs
        if (dayChanged) {
            cachedLogicalDate = snapshot.logicalDate
            recentLiveOpens.clear()
            recentLivePrompts.clear()
            accruedAtLastDerivedAdvanceMs = engine.totalAccruedDistractingMs
        } else if (derivedAdvanced) {
            // Only rebase live distracting time when DERIVED_DATA_END_MS actually moved forward with new harvest data,
            // never on a non-harvest derived recompute triggered by logging a friction event!
            accruedAtLastDerivedAdvanceMs = engine.totalAccruedDistractingMs
        }

        cachedWindowStartMs = snapshot.windowStartMs
        cachedWindowEndMs = snapshot.windowEndMs
        cachedDerivedDataEndMs = snapshot.derivedDataEndMs
        cachedRollupDistractingMs = snapshot.rollupDistractingMs
        cachedAppRollupOpens = snapshot.rollupOpensByPkg

        // Reconcile timestamped opens/prompts: keep anything strictly newer than derivedDataEndMs, merging DB + local.
        recentLiveOpens.removeAll { it.wallMs < snapshot.windowStartMs || it.wallMs < snapshot.derivedDataEndMs }
        for (ev in snapshot.dbContinuedAfterDerived) {
            if (recentLiveOpens.none { it.pkgId == ev.pkgId && kotlin.math.abs(it.wallMs - ev.wallMs) < 1_000L }) {
                recentLiveOpens += ev
            }
        }
        recentLivePrompts.removeAll { it < snapshot.windowStartMs }
        for (ts in snapshot.dbPromptTimestamps) {
            if (recentLivePrompts.none { kotlin.math.abs(it - ts) < 1_000L }) {
                recentLivePrompts += ts
            }
        }
    }

    private fun buildUsageSnapshot(app: FrictionAppPolicy, nowWallMs: Long): TodayUsageSnapshot {
        val logicalDate = if (cachedLogicalDate.isNotEmpty() && nowWallMs in cachedWindowStartMs until cachedWindowEndMs) {
            cachedLogicalDate
        } else {
            val fresh = LogicalDay.dateOf(nowWallMs, currentPrefs.rolloverHour, ZoneId.systemDefault()).toString()
            if (fresh != cachedLogicalDate) {
                cachedLogicalDate = fresh
                cachedRollupDistractingMs = 0L
                cachedAppRollupOpens = emptyMap()
                recentLiveOpens.clear()
                recentLivePrompts.clear()
                accruedAtLastDerivedAdvanceMs = engine.totalAccruedDistractingMs
                mainScope.launch { refreshUsageCache() }
            }
            fresh
        }

        val unsyncedDistractingDelta = (engine.totalAccruedDistractingMs - accruedAtLastDerivedAdvanceMs).coerceAtLeast(0L)
        val liveOpensForApp = recentLiveOpens.count { it.pkgId == app.pkgId && it.wallMs >= cachedDerivedDataEndMs }
        return TodayUsageSnapshot(
            logicalDate = logicalDate,
            appRealOpensToday = (cachedAppRollupOpens[app.pkgId] ?: 0) + liveOpensForApp,
            distractingMsToday = cachedRollupDistractingMs + unsyncedDistractingDelta,
            promptsShownToday = recentLivePrompts.size,
            budgetMs = currentPrefs.dailyBudgetMs,
        )
    }

    private fun resolvePolicyCached(packageName: String): FrictionAppPolicy {
        appPoliciesByPackage[packageName]?.let { return it }
        // Unknown package not yet interned: intern asynchronously for future events, treat as unconfirmed NEUTRAL now.
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
            categoryConfirmed = false,
            isInstalled = true,
        )
    }

    private fun handleControlCommand(cmd: FrictionControlCommand) {
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val logicalDate = currentLogicalDate(nowWall)
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
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        for (effect in effects) {
            when (effect) {
                is FrictionEffect.ShowOverlay -> {
                    val shown = overlayController.show(effect.overlay)
                    if (!shown) {
                        val recovery = engine.onOverlayAttachFailed(nowWall, nowElapsed)
                        applyEffects(recovery)
                        return
                    }
                }
                FrictionEffect.DismissOverlay -> overlayController.dismiss()
                is FrictionEffect.ScheduleUnlockFallback -> {
                    unlockFallbackJob?.cancel()
                    unlockFallbackJob = mainScope.launch {
                        delay(effect.delayMs)
                        if (!ready) return@launch
                        val w = System.currentTimeMillis()
                        val e = SystemClock.elapsedRealtime()
                        val fallbackEffects = engine.onUnlockFallbackTimeout(
                            nowWallMs = w,
                            nowElapsedMs = e,
                            zone = ZoneId.systemDefault(),
                            settings = currentPrefs.toFrictionSettings(),
                            schedules = currentSchedules,
                            resolveApp = ::resolvePolicyCached,
                            loadUsage = { app -> buildUsageSnapshot(app, w) },
                            resolveRedirect = { pkg -> appPoliciesByPackage[pkg] },
                        )
                        applyEffects(fallbackEffects)
                    }
                }
                FrictionEffect.CancelUnlockFallback -> {
                    unlockFallbackJob?.cancel()
                    unlockFallbackJob = null
                }
                is FrictionEffect.RecordUnpromptedOpen -> {
                    recentLiveOpens += TimestampedOpen(effect.pkgId, effect.wallMs)
                }
                is FrictionEffect.LogEvent -> {
                    val rec = effect.record
                    if (rec.eventKind == FrictionVocab.LAUNCH_PAUSE) {
                        recentLivePrompts += rec.timestampUtcMs
                        if (rec.outcome == FrictionVocab.OUTCOME_CONTINUED && rec.pkgId > 0L) {
                            recentLiveOpens += TimestampedOpen(rec.pkgId, rec.timestampUtcMs)
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

    private fun performGoHomeSafely(): Boolean {
        val ok = runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }.getOrDefault(false)
        if (ok) return true
        Log.w(TAG, "GLOBAL_ACTION_HOME returned false; falling back to HOME intent")
        return runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.onFailure { Log.w(TAG, "HOME intent fallback failed", it) }.getOrDefault(false)
    }

    private fun launchRedirectSafely(targetPackage: String): RedirectLaunchResult {
        val launch = packageManager.getLaunchIntentForPackage(targetPackage)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch != null) {
            val launched = runCatching {
                startActivity(launch)
                true
            }.onFailure { Log.w(TAG, "redirect launch failed for $targetPackage; falling back to home", it) }.getOrDefault(false)
            if (launched) return RedirectLaunchResult.LAUNCHED_REDIRECT
        } else {
            Log.w(TAG, "no launch intent for redirect app $targetPackage; falling back to home")
        }
        return if (performGoHomeSafely()) RedirectLaunchResult.FELL_BACK_HOME else RedirectLaunchResult.FAILED
    }

    private fun currentLogicalDate(nowWallMs: Long): String =
        if (cachedLogicalDate.isNotEmpty() && nowWallMs in cachedWindowStartMs until cachedWindowEndMs) {
            cachedLogicalDate
        } else {
            LogicalDay.dateOf(nowWallMs, currentPrefs.rolloverHour, ZoneId.systemDefault()).toString()
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
            // Navigate BEFORE dismissing overlay/audio focus so media doesn't bleed through and failures aren't mislogged.
            val navigated = performGoHomeSafely()
            applyEffects(engine.onPauseGoHome(System.currentTimeMillis(), SystemClock.elapsedRealtime(), navigatedHome = navigated))
        }

        override fun onPauseRedirect() {
            val target = (engine.activeOverlay as? ActiveOverlay.LaunchPause)?.plan?.redirectPackageName
            val result = if (target != null) launchRedirectSafely(target) else {
                if (performGoHomeSafely()) RedirectLaunchResult.FELL_BACK_HOME else RedirectLaunchResult.FAILED
            }
            applyEffects(engine.onPauseRedirect(System.currentTimeMillis(), SystemClock.elapsedRealtime(), result))
        }

        override fun onPauseContinue() {
            applyEffects(engine.onPauseContinue(System.currentTimeMillis(), SystemClock.elapsedRealtime(), currentPrefs.toFrictionSettings()))
        }

        override fun onPauseSnooze1h() {
            val nowWall = System.currentTimeMillis()
            val nowElapsed = SystemClock.elapsedRealtime()
            val ownPkgId = appPoliciesByPackage[packageName]?.pkgId ?: 0L
            applyEffects(engine.activateSnooze1h(nowWall, nowElapsed, currentLogicalDate(nowWall), ownPkgId))
        }

        override fun onPauseBackPressed() {
            val navigated = performGoHomeSafely()
            applyEffects(engine.onPauseBackPressed(System.currentTimeMillis(), SystemClock.elapsedRealtime(), navigatedHome = navigated))
        }

        override fun onTimerGoHome() {
            val navigated = performGoHomeSafely()
            applyEffects(engine.onTimerGoHome(System.currentTimeMillis(), SystemClock.elapsedRealtime(), navigatedHome = navigated))
        }

        override fun onTimerBackPressed() {
            val navigated = performGoHomeSafely()
            applyEffects(engine.onTimerBackPressed(System.currentTimeMillis(), SystemClock.elapsedRealtime(), navigatedHome = navigated))
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

        override fun onOverlayUnexpectedlyDetached() {
            if (!ready) return
            applyEffects(engine.onOverlayAttachFailed(System.currentTimeMillis(), SystemClock.elapsedRealtime()))
        }
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
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

    private data class TimestampedOpen(val pkgId: Long, val wallMs: Long)

    private data class UsageCacheSnapshot(
        val logicalDate: String,
        val windowStartMs: Long,
        val windowEndMs: Long,
        val derivedDataEndMs: Long,
        val rollupDistractingMs: Long,
        val rollupOpensByPkg: Map<Long, Int>,
        val dbContinuedAfterDerived: List<TimestampedOpen>,
        val dbPromptTimestamps: List<Long>,
    )

    companion object {
        private const val TAG = "LagrangeFriction"
        private const val TICK_INTERVAL_MS = 1_000L
        private const val HEARTBEAT_INTERVAL_MS = 15 * 60_000L

        /** True while Android has this AccessibilityService bound in this process. */
        @Volatile
        var connected: Boolean = false
            private set

        private val _readyState = MutableStateFlow(false)

        /** True only once the service is bound, startup recovery passed, runtime state restored, and commands wired. */
        val readyState: StateFlow<Boolean> = _readyState.asStateFlow()
    }
}

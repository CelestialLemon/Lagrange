package com.celestiallemon.lagrange.digest

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.celestiallemon.lagrange.LagrangeApplication
import com.celestiallemon.lagrange.StartupFailedException
import com.celestiallemon.lagrange.data.UserPrefs
import com.celestiallemon.lagrange.domain.digest.DigestKind
import com.celestiallemon.lagrange.domain.digest.DigestSchedule
import com.celestiallemon.lagrange.harvest.HarvestTrigger
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Posts one reflection digest near its configured local time, then hands off to the next occurrence: under the
 * scheduler's lock it replaces itself in its unique chain with a run computed from the settings current at that
 * moment (the REPLACE also retires this finished worker). While a run is in flight [DigestScheduler.ensureScheduled]
 * leaves the chain alone, so the two never race. Posting is claimed per logical date / ISO week of the occurrence
 * this run was due for ([KEY_DUE_AT_MS]), so a retried or late run never double-posts or claims the next period.
 */
class DigestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val kind = inputData.getString(KEY_KIND)?.let { k -> DigestKind.entries.firstOrNull { it.name == k } } ?: return Result.failure()
        val graph = (applicationContext as LagrangeApplication).graph
        try {
            graph.awaitReady()
        } catch (_: StartupFailedException) {
            return Result.failure() // never read an unrecovered database
        }
        // Even when this kind was switched off meanwhile, the run ends through the locked hand-off: it re-reads the
        // settings there, so an off-then-on toggle while this ran (which ensureScheduled left alone) still queues the
        // next occurrence.
        if (DigestScheduler.enabled(graph.preferences.current(), kind)) {
            try {
                // Fresh numbers first; a failed harvest still leaves whatever was derived before.
                runCatching { graph.harvestAndDerive(HarvestTrigger.PERIODIC, recomputeInline = true) }
                    .onFailure { if (it is CancellationException) throw it }
                val dueAt = inputData.getLong(KEY_DUE_AT_MS, -1L).takeIf { it > 0 }
                val outcome = graph.digests.runScheduled(kind, dueAt)
                Log.i(TAG, "$kind digest: $outcome")
                runCatching { graph.digests.checkCoverage() }.onFailure { if (it is CancellationException) throw it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "$kind digest failed", e)
                if (runAttemptCount < MAX_RETRIES) return Result.retry()
            }
        }
        return try {
            DigestScheduler.scheduleNext(applicationContext, kind) { DigestScheduler.Settings.of(graph.preferences.current()) }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // This run still owns the chain: retrying it is the only way the schedule continues. Its digest is
            // already claimed, so the retry posts nothing twice.
            Log.e(TAG, "$kind hand-off failed", e)
            Result.retry()
        }
    }

    companion object {
        const val KEY_KIND = "kind"
        const val KEY_DUE_AT_MS = "due_at_ms"
        private const val TAG = "LagrangeDigest"
        private const val MAX_RETRIES = 3
    }
}

object DigestScheduler {
    const val DAILY_WORK = "digest-daily"
    const val WEEKLY_WORK = "digest-weekly"
    const val TAG = "lagrange-digest"
    private const val FINGERPRINT_PREFIX = "digest-fp:"

    /** The digest settings that decide when runs are due (the device zone is added per call). */
    data class Settings(
        val dailyEnabled: Boolean,
        val dailyMinuteOfDay: Int,
        val weeklyEnabled: Boolean,
        val weeklyDayOfWeek: Int,
        val weeklyMinuteOfDay: Int,
    ) {
        fun enabled(kind: DigestKind) = when (kind) {
            DigestKind.DAILY -> dailyEnabled
            DigestKind.WEEKLY -> weeklyEnabled
        }

        companion object {
            fun of(p: UserPrefs) = Settings(
                p.dailyDigestEnabled,
                p.dailyDigestMinuteOfDay,
                p.weeklyDigestEnabled,
                p.weeklyDigestDayOfWeek,
                p.weeklyDigestMinuteOfDay,
            )
        }
    }

    fun uniqueName(kind: DigestKind) = when (kind) {
        DigestKind.DAILY -> DAILY_WORK
        DigestKind.WEEKLY -> WEEKLY_WORK
    }

    fun enabled(prefs: UserPrefs, kind: DigestKind) = Settings.of(prefs).enabled(kind)

    fun delayMs(settings: Settings, kind: DigestKind, nowMs: Long, zone: ZoneId): Long = when (kind) {
        DigestKind.DAILY -> DigestSchedule.delayMs(kind, nowMs, zone, settings.dailyMinuteOfDay)
        DigestKind.WEEKLY -> DigestSchedule.delayMs(kind, nowMs, zone, settings.weeklyMinuteOfDay, settings.weeklyDayOfWeek)
    }

    /** Identifies the schedule a queued run was computed for: when this changes, the queued run is stale. */
    fun fingerprint(settings: Settings, kind: DigestKind, zone: ZoneId): String = FINGERPRINT_PREFIX + when (kind) {
        DigestKind.DAILY -> "daily:${settings.dailyMinuteOfDay}:${zone.id}"
        DigestKind.WEEKLY -> "weekly:${settings.weeklyDayOfWeek}:${settings.weeklyMinuteOfDay}:${zone.id}"
    }

    /** A queued or running digest job, reduced to what [plan] needs. */
    data class Job(val running: Boolean, val fingerprint: String?)

    sealed interface Plan {
        /** Disabled: cancel the whole chain. */
        data object CancelAll : Plan

        /** Leave the chain as it is: an up-to-date run is queued, or a run is in flight and will hand off itself. */
        data object Keep : Plan

        /** Replace whatever is queued with a fresh run. */
        data object Replace : Plan
    }

    /**
     * Decides what to do with the unfinished jobs of one digest. A running job is never touched: it re-reads the
     * settings and the clock when it hands off to its successor ([scheduleNext], under the same lock), so even a
     * forced re-plan (the wall clock jumped) is already covered. Otherwise a queued run is kept only when it was
     * computed for the current settings and zone and no re-plan is forced.
     */
    fun plan(enabled: Boolean, jobs: List<Job>, fingerprint: String, force: Boolean = false): Plan = when {
        !enabled -> Plan.CancelAll
        jobs.any { it.running } -> Plan.Keep
        !force && jobs.any { it.fingerprint == fingerprint } -> Plan.Keep
        else -> Plan.Replace
    }

    /** Serializes every change to the digest chains in this process (WorkManager runs workers in-process). */
    private val lock = Mutex()

    /**
     * Brings both digests' queued runs in line with [settings] and the device zone: cancels disabled ones, keeps an
     * up-to-date run, and replaces a stale one. Safe to call often (startup, restore, settings, zone/time change).
     */
    suspend fun ensureScheduled(
        context: Context,
        settings: Settings,
        force: Boolean = false,
        zone: ZoneId = ZoneId.systemDefault(),
    ) = lock.withLock {
        withContext(Dispatchers.IO) {
            val wm = WorkManager.getInstance(context)
            for (kind in DigestKind.entries) {
                val jobs = wm.getWorkInfosForUniqueWork(uniqueName(kind)).get()
                    .filter { !it.state.isFinished }
                    .map { info -> Job(info.state == WorkInfo.State.RUNNING, info.tags.firstOrNull { it.startsWith(FINGERPRINT_PREFIX) }) }
                when (plan(settings.enabled(kind), jobs, fingerprint(settings, kind, zone), force)) {
                    Plan.CancelAll -> wm.cancelUniqueWork(uniqueName(kind)).result.get()
                    Plan.Keep -> Unit
                    Plan.Replace -> enqueue(context, settings, kind, zone = zone)?.result?.get()
                }
            }
        }
    }

    /**
     * Called by a finishing worker: queues the next occurrence from the settings read under the lock (so a settings
     * change that arrived while the worker ran is honoured), replacing the finishing worker in its chain.
     */
    suspend fun scheduleNext(context: Context, kind: DigestKind, settings: suspend () -> Settings) = lock.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            val current = settings()
            val wm = WorkManager.getInstance(context)
            // Either way the finishing run is retired before the lock is released, so ensureScheduled never sees it
            // as RUNNING afterwards (an off-then-on toggle racing the end of a run can't be left with no successor).
            if (current.enabled(kind)) enqueue(context, current, kind)?.result?.get() else wm.cancelUniqueWork(uniqueName(kind)).result.get()
        }
    }

    /** Replaces the chain of [kind] with its next run (nothing when it is disabled). Call under [lock]. */
    private fun enqueue(
        context: Context,
        settings: Settings,
        kind: DigestKind,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Operation? {
        if (!settings.enabled(kind)) return null
        val request = request(settings, kind, nowMs, zone)
        return WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(kind), ExistingWorkPolicy.REPLACE, request)
    }

    /** The next run of [kind]: delayed to its due instant, which it carries so a late run still knows its occurrence. */
    fun request(settings: Settings, kind: DigestKind, nowMs: Long, zone: ZoneId): OneTimeWorkRequest {
        val delay = delayMs(settings, kind, nowMs, zone)
        return OneTimeWorkRequestBuilder<DigestWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(DigestWorker.KEY_KIND to kind.name, DigestWorker.KEY_DUE_AT_MS to nowMs + delay))
            .addTag(TAG)
            .addTag(fingerprint(settings, kind, zone))
            .build()
    }
}

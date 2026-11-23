package com.celestiallemon.lagrange.digest

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
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
import kotlinx.coroutines.withContext

/**
 * Posts one reflection digest near its configured local time, then queues the next occurrence behind itself
 * (APPEND_OR_REPLACE on the same unique name, so the next run's delay starts when this one finishes and a settings
 * change can replace the queued run). Posting is claimed per logical date / ISO week, so a retry never double-posts.
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
        val prefs = graph.preferences.current()
        if (!DigestScheduler.enabled(prefs, kind)) return Result.success()
        try {
            // Fresh numbers first; a failed harvest still leaves whatever was derived before.
            runCatching { graph.harvestAndDerive(HarvestTrigger.PERIODIC, recomputeInline = true) }
                .onFailure { if (it is CancellationException) throw it }
            val outcome = graph.digests.runScheduled(kind)
            Log.i(TAG, "$kind digest: $outcome")
            runCatching { graph.digests.checkCoverage() }.onFailure { if (it is CancellationException) throw it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "$kind digest failed", e)
            if (runAttemptCount < MAX_RETRIES) return Result.retry()
        }
        DigestScheduler.enqueue(
            applicationContext,
            DigestScheduler.Settings.of(graph.preferences.current()),
            kind,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
        )
        return Result.success()
    }

    companion object {
        const val KEY_KIND = "kind"
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

        /** An up-to-date run is already queued (or running and will queue the next itself). */
        data object Keep : Plan

        /** Cancel these stale queued (never running) jobs, then enqueue with [policy]. */
        data class Enqueue(val staleQueued: List<Int>, val policy: ExistingWorkPolicy) : Plan
    }

    /**
     * Decides what to do with the unfinished jobs of one digest. A running job is never cancelled (REPLACE would
     * kill a digest mid-post): stale queued jobs behind it are cancelled one by one and the fresh run is appended.
     * [force] re-plans even when fingerprints match (the wall clock jumped).
     */
    fun plan(enabled: Boolean, jobs: List<Job>, fingerprint: String, force: Boolean = false): Plan {
        if (!enabled) return Plan.CancelAll
        val running = jobs.any { it.running }
        val queued = jobs.withIndex().filter { !it.value.running }
        val fresh = queued.filter { it.value.fingerprint == fingerprint }
        if (!force && fresh.isNotEmpty()) return Plan.Keep
        // A running job with nothing queued schedules its own successor from the settings current when it ends.
        if (!force && running && queued.isEmpty()) return Plan.Keep
        val stale = queued.filter { force || it.value.fingerprint != fingerprint }.map { it.index }
        return Plan.Enqueue(stale, if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE)
    }

    /**
     * Brings both digests' queued runs in line with [settings] and the device zone: cancels disabled ones, keeps an
     * up-to-date run, and replaces a stale one. Safe to call often (startup, restore, settings, zone/time change).
     */
    suspend fun ensureScheduled(
        context: Context,
        settings: Settings,
        force: Boolean = false,
        zone: ZoneId = ZoneId.systemDefault(),
    ) = withContext(Dispatchers.IO) {
        val wm = WorkManager.getInstance(context)
        for (kind in DigestKind.entries) {
            val infos = wm.getWorkInfosForUniqueWork(uniqueName(kind)).get()
                .filter { !it.state.isFinished }
            val jobs = infos.map { info ->
                Job(info.state == WorkInfo.State.RUNNING, info.tags.firstOrNull { it.startsWith(FINGERPRINT_PREFIX) })
            }
            when (val plan = plan(settings.enabled(kind), jobs, fingerprint(settings, kind, zone), force)) {
                Plan.CancelAll -> wm.cancelUniqueWork(uniqueName(kind))
                Plan.Keep -> Unit
                is Plan.Enqueue -> {
                    plan.staleQueued.forEach { wm.cancelWorkById(infos[it].id) }
                    enqueue(context, settings, kind, plan.policy, zone = zone)
                }
            }
        }
    }

    /** Queues the next run of [kind] (nothing when it is disabled). */
    fun enqueue(
        context: Context,
        settings: Settings,
        kind: DigestKind,
        policy: ExistingWorkPolicy,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        if (!settings.enabled(kind)) return
        val request = OneTimeWorkRequestBuilder<DigestWorker>()
            .setInitialDelay(delayMs(settings, kind, nowMs, zone), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(DigestWorker.KEY_KIND to kind.name))
            .addTag(TAG)
            .addTag(fingerprint(settings, kind, zone))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(kind), policy, request)
    }
}

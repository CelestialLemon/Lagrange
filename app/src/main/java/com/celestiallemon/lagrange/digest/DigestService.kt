package com.celestiallemon.lagrange.digest

import com.celestiallemon.lagrange.domain.digest.Digest
import com.celestiallemon.lagrange.domain.digest.DigestKind
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Where digests go: the system notification shade in the app, a recorder in tests. */
interface DigestPoster {
    fun canPost(): Boolean
    fun post(digest: Digest): Boolean
    fun postCoverageAlert(silentHours: Long): Boolean
}

/**
 * Composes, deduplicates and posts reflection digests. Each digest is claimed (atomically, in preferences) under its
 * key before it is posted, so a retried or doubled worker never posts the same day or week twice; a test digest is
 * never claimed, so previewing never suppresses the real one.
 */
class DigestService(
    /** Composes [DigestKind] for the occurrence due at the given instant (null: as of now, for previews). */
    private val compose: suspend (DigestKind, Long?) -> Digest?,
    private val claim: suspend (DigestKind, String) -> Boolean,
    /** Gives a claim back when posting failed after it, so the next run can still deliver that day / week. */
    private val release: suspend (DigestKind, String) -> Unit = { _, _ -> },
    private val poster: DigestPoster,
    private val lastHarvestAtMs: suspend () -> Long?,
    private val claimCoverageAlert: suspend (Long) -> Boolean,
    /** Gives the coverage alert claim back when the alert couldn't be posted. */
    private val releaseCoverageAlert: suspend (Long) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val gapAlertMs: Long = COVERAGE_GAP_ALERT_MS,
) {
    enum class Outcome { POSTED, ALREADY_POSTED, NOTHING_TO_SAY, NOTIFICATIONS_OFF }

    /**
     * Posts the scheduled digest for the occurrence due at [dueAtMs] (so a run delayed past the logical rollover still
     * summarizes, and claims, the day or week it was meant for). A claim is given back if posting fails or the run is
     * cancelled before the notification went out.
     */
    suspend fun runScheduled(kind: DigestKind, dueAtMs: Long? = null): Outcome {
        if (!poster.canPost()) return Outcome.NOTIFICATIONS_OFF
        val digest = compose(kind, dueAtMs) ?: return Outcome.NOTHING_TO_SAY
        // Claim, post and give back as one non-cancellable step: a cancellation can't land between a committed
        // claim and the post (which would make the retry skip a digest that was never shown).
        return withContext(NonCancellable) {
            claimPostRelease(
                claim = { claim(kind, digest.key) },
                post = { poster.post(digest) },
                release = { release(kind, digest.key) },
                posted = Outcome.POSTED,
                claimed = Outcome.ALREADY_POSTED,
                failed = Outcome.NOTIFICATIONS_OFF,
            )
        }
    }

    private suspend fun <T> claimPostRelease(
        claim: suspend () -> Boolean,
        post: () -> Boolean,
        release: suspend () -> Unit,
        posted: T,
        claimed: T,
        failed: T,
    ): T {
        if (!claim()) return claimed
        val ok = try {
            post()
        } catch (e: Throwable) {
            release()
            throw e
        }
        if (ok) return posted
        release()
        return failed
    }

    /** "Preview / Send test digest": posts what the digest would say right now, without claiming it. */
    suspend fun sendTest(kind: DigestKind): Outcome {
        if (!poster.canPost()) return Outcome.NOTIFICATIONS_OFF
        val digest = compose(kind, null) ?: return Outcome.NOTHING_TO_SAY
        return if (poster.post(digest)) Outcome.POSTED else Outcome.NOTIFICATIONS_OFF
    }

    /**
     * Alerts once per silence when no harvest has succeeded for over [gapAlertMs] (usually Usage Access revoked or
     * the app restricted), before Android's roughly week-long event retention loses that history. Returns true when
     * an alert was posted.
     */
    suspend fun checkCoverage(): Boolean {
        val last = lastHarvestAtMs() ?: return false
        val silent = clock() - last
        if (silent <= gapAlertMs || !poster.canPost()) return false
        return withContext(NonCancellable) {
            claimPostRelease(
                claim = { claimCoverageAlert(last) },
                post = { poster.postCoverageAlert(silent / HOUR_MS) },
                release = { releaseCoverageAlert(last) },
                posted = true,
                claimed = false,
                failed = false,
            )
        }
    }

    companion object {
        private const val HOUR_MS = 60 * 60_000L
        const val COVERAGE_GAP_ALERT_MS = 48 * HOUR_MS
    }
}

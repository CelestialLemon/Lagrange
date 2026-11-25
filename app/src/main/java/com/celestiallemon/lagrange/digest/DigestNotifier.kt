package com.celestiallemon.lagrange.digest

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.celestiallemon.lagrange.Destination
import com.celestiallemon.lagrange.R
import com.celestiallemon.lagrange.domain.digest.Digest
import com.celestiallemon.lagrange.domain.digest.DigestKind

/**
 * Local notifications: the daily and weekly reflection digests and the coverage-gap alert, each on its own channel
 * so the user can silence any one of them in system settings. Nothing is posted without POST_NOTIFICATIONS.
 */
class DigestNotifier(private val context: Context) {
    private val manager get() = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_DAILY, "Daily reflection", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "A calm evening summary of the day: budget, rabbit holes, focus and pauses."
                },
                NotificationChannel(CHANNEL_WEEKLY, "Weekly reflection", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Your week against the week before, from fully covered days only."
                },
                NotificationChannel(CHANNEL_COVERAGE, "Collection gaps", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Warns when Lagrange hasn't been able to record for over two days, before Android forgets that history."
                },
            ),
        )
    }

    /** POST_NOTIFICATIONS is granted and notifications aren't blocked for the app. */
    fun canPost(): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            manager.areNotificationsEnabled()

    /** Posts [digest]; returns false (posting nothing) when notifications aren't allowed. */
    fun post(digest: Digest): Boolean {
        if (!canPost()) return false
        ensureChannels()
        val (channel, id, destination) = when (digest.kind) {
            DigestKind.DAILY -> Triple(CHANNEL_DAILY, ID_DAILY, Destination.TODAY)
            DigestKind.WEEKLY -> Triple(CHANNEL_WEEKLY, ID_WEEKLY, Destination.TRENDS)
        }
        val body = (digest.lines + listOfNotNull(digest.coverageNote)).joinToString("\n")
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_lagrange)
            .setContentTitle(digest.title)
            .setContentText(digest.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(destination.pendingIntent(context))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // Digests stay on this phone: not bridged to watches or other paired devices, and only a generic line
            // on a secure lock screen.
            .setLocalOnly(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_stat_lagrange)
                    .setContentTitle(if (digest.kind == DigestKind.DAILY) "Your daily digest is ready" else "Your weekly digest is ready")
                    .setLocalOnly(true)
                    .build(),
            )
            .build()
        return try {
            manager.notify(id, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /** "Lagrange hasn't recorded for N hours": tapping opens the Settings tab's Permission Health. */
    fun postCoverageAlert(silentHours: Long): Boolean {
        if (!canPost()) return false
        ensureChannels()
        val days = silentHours / 24
        val span = if (days >= 2) "$days days" else "$silentHours hours"
        val notification = NotificationCompat.Builder(context, CHANNEL_COVERAGE)
            .setSmallIcon(R.drawable.ic_stat_lagrange)
            .setContentTitle("Lagrange hasn't recorded for $span")
            .setContentText("Check Usage Access. Android keeps only about a week of history.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Nothing has been harvested for $span. Android keeps only about a week of usage events, so open Lagrange " +
                        "and check Permission Health (Usage Access, battery restrictions) before that history is gone.",
                ),
            )
            .setContentIntent(Destination.SETTINGS.pendingIntent(context))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setLocalOnly(true)
            .build()
        return try {
            manager.notify(ID_COVERAGE, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    companion object {
        const val CHANNEL_DAILY = "lagrange_daily_digest"
        const val CHANNEL_WEEKLY = "lagrange_weekly_digest"
        const val CHANNEL_COVERAGE = "lagrange_coverage_alerts"
        const val ID_DAILY = 6_101
        const val ID_WEEKLY = 6_102
        const val ID_COVERAGE = 6_103
    }
}

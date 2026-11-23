package com.celestiallemon.lagrange.harvest

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.os.UserManager

/** One usage event, decoupled from the framework type so the harvester is testable on the JVM. */
data class RawUsageEvent(
    val timestampMs: Long,
    val eventType: Int,
    val packageName: String,
    val className: String?,
)

interface UsageEventSource {
    fun hasUsageAccess(): Boolean

    /** False before the first unlock after a reboot (Direct Boot), when usage history is unavailable. */
    fun isUserUnlocked(): Boolean

    /**
     * Events with `beginMs <= timestamp < endMs`, oldest first, or null when the system returned nothing usable
     * (locked user, revoked access). The sequence is lazy so a window is never fully materialised.
     */
    fun query(beginMs: Long, endMs: Long): Sequence<RawUsageEvent>?
}

class AndroidUsageEventSource(private val context: Context) : UsageEventSource {
    private val usm = context.getSystemService(UsageStatsManager::class.java)

    override fun hasUsageAccess(): Boolean = hasUsageAccess(context)

    override fun isUserUnlocked(): Boolean = context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: false

    override fun query(beginMs: Long, endMs: Long): Sequence<RawUsageEvent>? {
        val events = try {
            usm?.queryEvents(beginMs, endMs)
        } catch (_: SecurityException) {
            null
        } ?: return null
        return sequence {
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                if (!events.getNextEvent(event)) continue
                yield(RawUsageEvent(event.timeStamp, event.eventType, event.packageName.orEmpty(), event.className))
            }
        }
    }

    companion object {
        // unsafeCheckOpNoThrow is deprecated on API 37 in favour of checkOpNoThrow(String…), which older releases lack.
        @Suppress("DEPRECATION")
        fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
            val mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            return if (mode == AppOpsManager.MODE_DEFAULT) {
                context.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                mode == AppOpsManager.MODE_ALLOWED
            }
        }
    }
}

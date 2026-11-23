package com.celestiallemon.lagrange.domain.friction

/** One UsageStats activity lifecycle step: RESUMED (`resumed = true`) or PAUSED/STOPPED. */
data class ActivityTransition(val packageName: String, val className: String?, val resumed: Boolean)

/** Pure helper for recovering the foreground app after a service restart. */
object ForegroundRecovery {
    /**
     * Package of the activity still resumed at the end of [transitions] (oldest first), or null. Tracked per activity:
     * when A opens B in the same app, A's PAUSED/STOPPED can arrive after B's RESUMED and must not clear B.
     */
    fun resumedPackage(transitions: List<ActivityTransition>): String? {
        var pkg: String? = null
        var cls: String? = null
        for (t in transitions) {
            if (t.resumed) {
                pkg = t.packageName
                cls = t.className
            } else if (t.packageName == pkg && t.className == cls) {
                pkg = null
                cls = null
            }
        }
        return pkg
    }
}

package com.celestiallemon.lagrange

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * A tab another surface (widget, Quick Settings tile, digest notification) can open Lagrange on. Carried as an
 * Intent extra to [MainActivity]; unknown or missing values open nothing in particular.
 */
enum class Destination {
    TODAY, TRENDS, FRICTION, SETTINGS;

    /** An explicit intent to [MainActivity] (singleTask: an open instance is brought forward and re-routed). */
    fun intent(context: Context): Intent = Intent(context, MainActivity::class.java)
        .setAction(ACTION_OPEN)
        .putExtra(EXTRA_DESTINATION, name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** An immutable activity PendingIntent; one request code per destination so extras never collide. */
    fun pendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_CODE_BASE + ordinal,
        intent(context),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val EXTRA_DESTINATION = "com.celestiallemon.lagrange.extra.DESTINATION"
        const val ACTION_OPEN = "com.celestiallemon.lagrange.action.OPEN"
        private const val REQUEST_CODE_BASE = 4_200

        fun fromIntent(intent: Intent?): Destination? =
            intent?.getStringExtra(EXTRA_DESTINATION)?.let { name ->
                // Coverage alerts posted by earlier builds targeted SYSTEM, now the Settings tab.
                if (name == "SYSTEM") SETTINGS else entries.firstOrNull { it.name == name }
            }
    }
}

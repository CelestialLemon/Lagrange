package com.celestiallemon.lagrange.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.celestiallemon.lagrange.domain.lab.NotificationSettingsTarget

/**
 * "Mute channel": opens the system page where the user can silence a notification channel (or, when only the app is
 * known, the app's notification settings). Lagrange never changes notification settings itself.
 */
object NotificationSettingsIntents {
    fun intentFor(target: NotificationSettingsTarget): Intent = when (target) {
        is NotificationSettingsTarget.Channel -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, target.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, target.channelId)
        is NotificationSettingsTarget.App -> appIntent(target.packageName)
    }

    fun appIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

    /**
     * Starts the channel page, falling back to the app's notification page if the channel page can't be opened (an
     * OEM without it, or a channel the app has since deleted). Returns false when neither could be opened.
     */
    fun open(context: Context, target: NotificationSettingsTarget): Boolean {
        val attempts = buildList {
            add(intentFor(target))
            if (target is NotificationSettingsTarget.Channel) add(appIntent(target.packageName))
        }
        for (intent in attempts) {
            try {
                if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                // try the next, broader page
            } catch (_: SecurityException) {
                // same
            }
        }
        return false
    }
}

package com.celestiallemon.lagrange.friction

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.LruCache
import android.view.inputmethod.InputMethodManager
import com.celestiallemon.lagrange.domain.ReconstructionRules
import com.celestiallemon.lagrange.domain.friction.WindowClassifier

/**
 * Android-backed [WindowClassifier] using bounded in-memory caches so `TYPE_WINDOW_STATE_CHANGED` filtering
 * stays fast and light on memory (spec §5.2).
 */
class AndroidWindowClassifier(
    private val context: Context,
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
) : WindowClassifier {
    override val ownPackage: String = context.packageName

    private val pm: PackageManager = context.packageManager
    private val activityCache = LruCache<String, Boolean>(ACTIVITY_CACHE_SIZE)

    private var cachedImePackages: Set<String> = emptySet()
    private var cachedLauncherPackages: Set<String> = emptySet()
    private var cachedCallPackages: Set<String> = DEFAULT_CALL_PACKAGES
    private var lastSystemRefreshElapsedMs: Long = Long.MIN_VALUE

    override fun isActivity(packageName: String, className: String): Boolean {
        val key = "$packageName/$className"
        activityCache.get(key)?.let { return it }
        val resolved = try {
            val component = ComponentName.createRelative(packageName, className)
            // Int-flags overload: identical semantics with flags = 0, and also supported by Robolectric.
            @Suppress("DEPRECATION")
            pm.getActivityInfo(component, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: RuntimeException) {
            false
        }
        activityCache.put(key, resolved)
        return resolved
    }

    override fun isImePackage(packageName: String): Boolean {
        refreshSystemPackagesIfStale()
        return packageName in cachedImePackages
    }

    override fun isSystemUiPackage(packageName: String): Boolean = packageName in SYSTEM_UI_PACKAGES

    override fun isLauncherPackage(packageName: String): Boolean {
        refreshSystemPackagesIfStale()
        return packageName in cachedLauncherPackages
    }

    override fun isCallOrDialerPackage(packageName: String): Boolean {
        refreshSystemPackagesIfStale()
        return packageName in cachedCallPackages
    }

    fun invalidateCaches() {
        activityCache.evictAll()
        lastSystemRefreshElapsedMs = Long.MIN_VALUE
    }

    private fun refreshSystemPackagesIfStale() {
        val now = elapsedClock()
        if (lastSystemRefreshElapsedMs != Long.MIN_VALUE && now - lastSystemRefreshElapsedMs < REFRESH_INTERVAL_MS) {
            return
        }
        lastSystemRefreshElapsedMs = now

        val imes = HashSet<String>()
        runCatching {
            val imm = context.getSystemService(InputMethodManager::class.java)
            imm?.enabledInputMethodList?.forEach { info ->
                info.packageName?.takeIf { it.isNotBlank() }?.let(imes::add)
            }
        }
        runCatching {
            val defaultIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            defaultIme?.substringBefore('/')?.takeIf { it.isNotBlank() }?.let(imes::add)
        }
        cachedImePackages = imes

        cachedLauncherPackages = runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            pm.queryIntentActivities(home, PackageManager.ResolveInfoFlags.of(0))
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        }.getOrDefault(emptySet())

        val calls = HashSet(DEFAULT_CALL_PACKAGES)
        runCatching {
            context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
                ?.takeIf { it.isNotBlank() }?.let(calls::add)
        }
        cachedCallPackages = calls
    }

    companion object {
        private const val ACTIVITY_CACHE_SIZE = 512
        private const val REFRESH_INTERVAL_MS = 60_000L

        private val DEFAULT_CALL_PACKAGES = ReconstructionRules().callPackages

        private val SYSTEM_UI_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.intentresolver",
            "com.google.android.intentresolver",
            "com.android.settings.intelligence",
            "com.google.android.settings.intelligence",
        )
    }
}

package com.celestiallemon.lagrange.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.celestiallemon.lagrange.data.db.AppCategory

/** What the collector knows about a package the first time it sees it. */
data class PackageMeta(
    val displayName: String,
    val suggestedCategory: AppCategory,
    val isLauncherOrSystemUi: Boolean,
    val isInstalled: Boolean,
    val installedAtMs: Long?,
)

fun interface PackageMetaResolver {
    fun resolve(packageName: String): PackageMeta

    /** Cheaper than [resolve] when only installation state is needed. */
    fun isInstalled(packageName: String): Boolean = resolve(packageName).isInstalled
}

/**
 * Suggests a starting category. These are only suggestions: the onboarding review asks the user to confirm them,
 * and Android's own `ApplicationInfo.category` is deliberately not trusted for SOCIAL (it lumps WhatsApp and Signal
 * in with feeds).
 */
object CategorySuggester {
    private val knownDistracting = setOf(
        "com.reddit.frontpage",
        "com.instagram.android",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.google.android.youtube",
        "com.twitter.android",
        "com.facebook.katana",
        "com.snapchat.android",
        "com.pinterest",
        "tv.twitch.android.app",
        "com.netflix.mediaclient",
    )

    fun suggest(packageName: String, ownPackage: String, isLauncherOrSystemUi: Boolean, androidCategory: Int?): AppCategory =
        when {
            packageName == ownPackage || packageName.startsWith("com.celestiallemon.lagrange") -> AppCategory.SELF_APP
            isLauncherOrSystemUi -> AppCategory.EXCLUDED
            packageName in knownDistracting -> AppCategory.DISTRACTING
            androidCategory == ApplicationInfo.CATEGORY_GAME -> AppCategory.DISTRACTING
            androidCategory == ApplicationInfo.CATEGORY_VIDEO -> AppCategory.DISTRACTING
            androidCategory == ApplicationInfo.CATEGORY_MAPS -> AppCategory.UTILITY
            androidCategory == ApplicationInfo.CATEGORY_PRODUCTIVITY -> AppCategory.UTILITY
            androidCategory == ApplicationInfo.CATEGORY_ACCESSIBILITY -> AppCategory.UTILITY
            else -> AppCategory.NEUTRAL
        }
}

/** Resolves labels, launcher status and install time through [PackageManager]. */
class AndroidPackageMetaResolver(private val context: Context) : PackageMetaResolver {
    private val pm: PackageManager = context.packageManager

    private val homePackages: Set<String> by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        pm.queryIntentActivities(home, PackageManager.ResolveInfoFlags.of(0))
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }

    override fun resolve(packageName: String): PackageMeta {
        val isSystemSurface = packageName.isEmpty() || packageName in SYSTEM_SURFACES || packageName in homePackages
        return try {
            val info = pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            val app = info.applicationInfo
            val label = app?.let { pm.getApplicationLabel(it).toString() }?.takeIf { it.isNotBlank() } ?: packageName
            PackageMeta(
                displayName = label,
                suggestedCategory = CategorySuggester.suggest(packageName, context.packageName, isSystemSurface, app?.category),
                isLauncherOrSystemUi = isSystemSurface,
                isInstalled = true,
                installedAtMs = info.firstInstallTime.takeIf { it > 0 },
            )
        } catch (_: PackageManager.NameNotFoundException) {
            PackageMeta(
                displayName = packageName.ifEmpty { "(system)" },
                suggestedCategory = CategorySuggester.suggest(packageName, context.packageName, isSystemSurface, null),
                isLauncherOrSystemUi = isSystemSurface,
                isInstalled = false,
                installedAtMs = null,
            )
        }
    }

    override fun isInstalled(packageName: String): Boolean = try {
        pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private companion object {
        val SYSTEM_SURFACES = setOf(
            "android",
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        )
    }
}

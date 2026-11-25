package com.celestiallemon.lagrange.ui.icons

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.min
import kotlin.math.roundToInt

/** Reads launcher icons through [PackageManager]. Holds the application context only. */
class PackageManagerIconSource(context: Context) : AppIconSource {
    private val pm: PackageManager = context.applicationContext.packageManager

    override fun load(packageName: String, sizePx: Int): AppIconResult.Icon? {
        if (packageName.isBlank()) return null
        // The icon the launcher shows is its launcher activity's (or activity-alias's): it may have one of its own,
        // even when the application has none, or a different one. A package with no launcher entry (a widget host, a
        // service) uses its application icon.
        val launcher = launcherEntry(packageName)
        val drawable = when {
            launcher == null -> applicationIcon(packageName)
            // Neither the intent filter, the activity nor the application has an icon: PackageManager would hand back
            // the generic robot, which says less than the letter.
            launcher.iconResource == 0 -> null
            else -> launcher.loadIcon(pm)
        } ?: return null
        return renderAppIcon(drawable, sizePx)
    }

    private fun launcherEntry(packageName: String): ResolveInfo? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
        return pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0)).firstOrNull()
    }

    /** The application's own icon, or null when it isn't installed or has none (same robot as above). */
    private fun applicationIcon(packageName: String): Drawable? {
        val info = try {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        if (info.icon == 0) return null
        return info.loadIcon(pm)
    }
}

/**
 * Renders a launcher icon into a [sizePx] square bitmap.
 *
 * Adaptive icons are drawn full bleed: both layers are 108 units with the visible 72 in the middle, so they're drawn
 * 1.5× the tile, centred, which is what a launcher shows before applying its mask. The UI then clips that square to
 * its own shape, so every adaptive icon matches the Orbit tile instead of the device's mask. Legacy icons keep their
 * own shape and transparency and are fitted, centred, without distortion, into the middle [LEGACY_SCALE] of the
 * square: the UI sets them on a light plate, which shows around them (and keeps a dark icon visible on a dark
 * surface).
 */
internal fun renderAppIcon(drawable: Drawable, sizePx: Int): AppIconResult.Icon {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val adaptive = drawable as? AdaptiveIconDrawable
    if (adaptive != null) {
        val bleed = (sizePx * ADAPTIVE_BLEED).roundToInt()
        listOfNotNull(adaptive.background, adaptive.foreground).forEach { layer ->
            layer.setBounds(-bleed, -bleed, sizePx + bleed, sizePx + bleed)
            layer.draw(canvas)
        }
    } else {
        val box = (sizePx * LEGACY_SCALE).roundToInt()
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: box
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: box
        val scale = min(box.toFloat() / w, box.toFloat() / h)
        val dw = (w * scale).roundToInt()
        val dh = (h * scale).roundToInt()
        val left = (sizePx - dw) / 2
        val top = (sizePx - dh) / 2
        drawable.setBounds(left, top, left + dw, top + dh)
        drawable.draw(canvas)
    }
    bitmap.prepareToDraw()
    return AppIconResult.Icon(bitmap.asImageBitmap(), fullBleed = adaptive != null)
}

/** (108 − 72) / 2 / 72: how far each adaptive layer reaches past the visible square on every side. */
private const val ADAPTIVE_BLEED = 0.25f

/** A legacy icon fills 32dp of the 40dp tile, leaving a 4dp margin of tile around it. */
internal const val LEGACY_SCALE = 0.8f

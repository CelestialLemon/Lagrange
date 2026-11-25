package com.celestiallemon.lagrange.ui.icons

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class PackageManagerIconSourceTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `adaptive icons are drawn full bleed, with the safe zone filling the tile`() {
        // Foreground covers the middle half of its 108-unit layer; the background fills the layer.
        val drawable = AdaptiveIconDrawable(ColorDrawable(Color.RED), InsetDrawable(ColorDrawable(Color.BLUE), 0.25f))

        val icon = renderAppIcon(drawable, 48)
        val px = icon.bitmap.asAndroidBitmap()

        assertTrue(icon.fullBleed)
        assertEquals(48, px.width)
        assertEquals(48, px.height)
        // Opaque to the very corner, so the UI's own shape decides the outline (not the device's mask).
        assertEquals(Color.RED, px.getPixel(0, 0))
        assertEquals(Color.RED, px.getPixel(47, 47))
        // The layer is drawn 1.5× the tile: its middle half lands on the middle 3/4 of the tile, [6, 42).
        assertEquals(Color.RED, px.getPixel(4, 24))
        assertEquals(Color.BLUE, px.getPixel(8, 24))
        assertEquals(Color.BLUE, px.getPixel(24, 24))
        assertEquals(Color.BLUE, px.getPixel(40, 24))
        assertEquals(Color.RED, px.getPixel(44, 24))
    }

    @Test
    fun `legacy icons keep their transparency and aspect ratio, inset and centred`() {
        val wide = GradientDrawable().apply {
            setColor(Color.GREEN)
            setSize(20, 10)
        }

        val icon = renderAppIcon(wide, 48)
        val px = icon.bitmap.asAndroidBitmap()

        assertFalse(icon.fullBleed)
        // 20×10 fitted into the middle 40 of 48 (the tile shows around it): 40×20, centred (cols 4–44, rows 14–34).
        assertEquals(0, Color.alpha(px.getPixel(24, 10)))
        assertEquals(0, Color.alpha(px.getPixel(2, 24)))
        assertEquals(Color.GREEN, px.getPixel(24, 24))
        assertEquals(Color.GREEN, px.getPixel(6, 24))
        assertEquals(0, Color.alpha(px.getPixel(24, 38)))
    }

    @Test
    fun `an app that isn't installed has no icon`() {
        assertNull(PackageManagerIconSource(context).load("com.example.not.installed", 48))
        assertNull(PackageManagerIconSource(context).load("", 48))
    }

    @Test
    fun `an app without an icon of its own falls back instead of showing the generic robot`() {
        val pkg = "com.example.noicon"
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                packageName = pkg
                applicationInfo = ApplicationInfo().apply {
                    packageName = pkg
                    icon = 0
                }
            },
        )
        assertNull(PackageManagerIconSource(context).load(pkg, 48))
    }

    @Test
    fun `a launcher activity's own icon is used even when the application has none`() {
        val pkg = "com.example.activityicon"
        installWithLauncher(pkg, applicationIcon = 0, activityIcon = ACTIVITY_ICON)
        shadowOf(context.packageManager).addDrawableResolution(pkg, ACTIVITY_ICON, solid(Color.GREEN))

        val icon = PackageManagerIconSource(context).load(pkg, 48)

        assertNotNull(icon)
        assertEquals(Color.GREEN, icon!!.bitmap.asAndroidBitmap().getPixel(24, 24))
    }

    @Test
    fun `a launcher activity's icon wins over the application's, as on the launcher`() {
        val pkg = "com.example.aliasicon"
        installWithLauncher(pkg, applicationIcon = APPLICATION_ICON, activityIcon = ACTIVITY_ICON)
        shadowOf(context.packageManager).apply {
            addDrawableResolution(pkg, APPLICATION_ICON, solid(Color.RED))
            addDrawableResolution(pkg, ACTIVITY_ICON, solid(Color.GREEN))
        }

        val icon = PackageManagerIconSource(context).load(pkg, 48)

        assertEquals(Color.GREEN, icon!!.bitmap.asAndroidBitmap().getPixel(24, 24))
    }

    @Test
    fun `a launcher activity without an icon uses the application's`() {
        val pkg = "com.example.appicon"
        installWithLauncher(pkg, applicationIcon = APPLICATION_ICON, activityIcon = 0)
        shadowOf(context.packageManager).addDrawableResolution(pkg, APPLICATION_ICON, solid(Color.RED))

        val icon = PackageManagerIconSource(context).load(pkg, 48)

        assertEquals(Color.RED, icon!!.bitmap.asAndroidBitmap().getPixel(24, 24))
    }

    @Test
    fun `a launcher entry with no icon anywhere falls back instead of showing the generic robot`() {
        val pkg = "com.example.noicons"
        installWithLauncher(pkg, applicationIcon = 0, activityIcon = 0)
        assertNull(PackageManagerIconSource(context).load(pkg, 48))
    }

    @Test
    fun `an installed app's adaptive launcher icon is loaded at the requested size`() {
        // Lagrange's own launcher icon is an adaptive icon.
        val icon = PackageManagerIconSource(context).load(context.packageName, 64)

        assertNotNull(icon)
        assertTrue(icon!!.fullBleed)
        assertEquals(64, icon.bitmap.width)
        assertEquals(64, icon.bitmap.height)
    }

    /** Installs [pkg] with one launcher activity, each with the given icon resource (0: none). */
    private fun installWithLauncher(pkg: String, applicationIcon: Int, activityIcon: Int) {
        val app = ApplicationInfo().apply {
            packageName = pkg
            icon = applicationIcon
        }
        val launcher = ComponentName(pkg, "$pkg.Launcher")
        shadowOf(context.packageManager).apply {
            installPackage(
                PackageInfo().apply {
                    packageName = pkg
                    applicationInfo = app
                },
            )
            addOrUpdateActivity(
                ActivityInfo().apply {
                    packageName = pkg
                    name = launcher.className
                    icon = activityIcon
                    applicationInfo = app
                    exported = true
                },
            )
            addIntentFilterForActivity(
                launcher,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
            )
        }
    }

    private fun solid(color: Int) = GradientDrawable().apply {
        setColor(color)
        setSize(48, 48)
    }

    private companion object {
        const val APPLICATION_ICON = 0x7f0a1001
        const val ACTIVITY_ICON = 0x7f0a1002
    }
}

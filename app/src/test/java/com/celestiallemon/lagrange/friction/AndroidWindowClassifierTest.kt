package com.celestiallemon.lagrange.friction

import android.app.Application
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AndroidWindowClassifierTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `resolves full and relative activity names, rejects non-activities, and classifies system and IME packages`() {
        val shadowPm = Shadows.shadowOf(context.packageManager)
        val appInfo = ApplicationInfo().apply { packageName = "com.example.reddit" }
        val actInfo = ActivityInfo().apply {
            packageName = "com.example.reddit"
            name = "com.example.reddit.MainActivity"
            applicationInfo = appInfo
        }
        shadowPm.installPackage(
            PackageInfo().apply {
                packageName = "com.example.reddit"
                applicationInfo = appInfo
                activities = arrayOf(actInfo)
            },
        )

        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
            "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME",
        )

        val classifier = AndroidWindowClassifier(context)
        assertTrue(classifier.isActivity("com.example.reddit", "com.example.reddit.MainActivity"))
        assertTrue("Relative shorthand .MainActivity must resolve", classifier.isActivity("com.example.reddit", ".MainActivity"))
        assertFalse(classifier.isActivity("com.example.reddit", "android.widget.FrameLayout"))
        assertTrue(classifier.isImePackage("com.google.android.inputmethod.latin"))
        assertTrue(classifier.isSystemUiPackage("com.android.systemui"))
        assertTrue(classifier.isSystemUiPackage("com.google.android.settings.intelligence"))
        assertTrue(classifier.isCallOrDialerPackage("com.google.android.dialer"))
        assertFalse(classifier.isSystemUiPackage("com.example.reddit"))
    }
}

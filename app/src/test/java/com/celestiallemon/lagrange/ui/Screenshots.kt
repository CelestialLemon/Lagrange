package com.celestiallemon.lagrange.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import com.celestiallemon.lagrange.ui.theme.LagrangeTheme
import java.io.File

/**
 * Renders a composable in the Lagrange theme and writes it to `app/build/screenshots/<name>.png`.
 * The clock is paused and advanced past every entrance animation, so the image shows the settled design.
 */
fun ComposeContentTestRule.snapshot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
    mainClock.autoAdvance = false
    setContent {
        LagrangeTheme(darkTheme = dark) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) { content() }
        }
    }
    mainClock.advanceTimeBy(3_000)
    waitForIdle()
    val bitmap = onRoot().captureToImage().asAndroidBitmap()
    val dir = File(System.getProperty("user.dir"), "build/screenshots").apply { mkdirs() }
    File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

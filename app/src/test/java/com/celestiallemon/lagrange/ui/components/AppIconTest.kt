package com.celestiallemon.lagrange.ui.components

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.celestiallemon.lagrange.ui.icons.AppIconCache
import com.celestiallemon.lagrange.ui.icons.AppIconLoader
import com.celestiallemon.lagrange.ui.icons.AppIconResult
import com.celestiallemon.lagrange.ui.icons.AppIconRevisions
import com.celestiallemon.lagrange.ui.theme.LagrangeTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class AppIconTest {
    @get:Rule val compose = createComposeRule()

    /** Icons for [installed] packages: in memory already for [warm] ones, otherwise only after [load]. */
    private class FakeLoader(installed: Set<String>, private val warm: Set<String> = emptySet()) : AppIconLoader {
        val icons = installed.associateWith { icon() }.toMutableMap()
        val loads = mutableListOf<String>()
        override val revisions = MutableStateFlow(AppIconRevisions.Initial)

        override fun cached(packageName: String, sizePx: Int): AppIconResult? =
            if (packageName in warm) icons[packageName] else null

        override suspend fun load(packageName: String, sizePx: Int): AppIconResult {
            loads += packageName
            return icons[packageName] ?: AppIconResult.Missing
        }

        /** [packageName] was installed or updated: it now has [newIcon]. */
        fun update(packageName: String, newIcon: AppIconResult.Icon) {
            icons[packageName] = newIcon
            revisions.value = revisions.value.changed(packageName)
        }
    }

    private fun avatars() = compose.onAllNodesWithTag(AppAvatarTestTag)

    @Test
    fun `without a loader, apps get their letter`() {
        compose.setContent { LagrangeTheme { AppIcon("com.google.android.youtube", "YouTube", Color.Red) } }
        avatars().assertCountEquals(1)
    }

    @Test
    fun `an icon already in memory shows on the first frame, with no letter in between`() {
        val loader = FakeLoader(installed = setOf("com.example.a"), warm = setOf("com.example.a"))
        val seen = mutableListOf<AppIconResult?>()
        compose.setContent {
            CompositionLocalProvider(LocalAppIconLoader provides loader) {
                LagrangeTheme {
                    seen += rememberAppIcon("com.example.a", 48)
                    AppIcon("com.example.a", "Alpha", Color.Red)
                }
            }
        }
        compose.waitForIdle()

        assertSame(loader.icons["com.example.a"], seen.first())
        assertEquals(emptyList<String>(), loader.loads)
        avatars().assertCountEquals(0)
    }

    @Test
    fun `an icon not in memory shows the letter until it has loaded`() {
        val loader = FakeLoader(installed = setOf("com.example.a"))
        val seen = mutableListOf<AppIconResult?>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalAppIconLoader provides loader) {
                LagrangeTheme {
                    seen += rememberAppIcon("com.example.a", 48)
                    AppIcon("com.example.a", "Alpha", Color.Red)
                }
            }
        }
        assertNull(seen.first())
        avatars().assertCountEquals(1)

        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        assertSame(loader.icons["com.example.a"], seen.last())
        avatars().assertCountEquals(0)
    }

    @Test
    fun `an uninstalled or synthetic app keeps its letter`() {
        val loader = FakeLoader(installed = emptySet())
        compose.setContent {
            CompositionLocalProvider(LocalAppIconLoader provides loader) {
                LagrangeTheme { AppIcon("com.example.gone", "Gone", Color.Red) }
            }
        }
        compose.waitForIdle()
        avatars().assertCountEquals(1)
        assertEquals(listOf("com.example.gone"), loader.loads)
    }

    @Test
    fun `no package name means the letter, without asking the loader`() {
        val loader = FakeLoader(installed = emptySet())
        compose.setContent {
            CompositionLocalProvider(LocalAppIconLoader provides loader) {
                LagrangeTheme {
                    AppIcon(null, "Unknown app", Color.Red)
                    AppIcon(" ", "Blank", Color.Red)
                }
            }
        }
        compose.waitForIdle()
        avatars().assertCountEquals(2)
        assertEquals(emptyList<String>(), loader.loads)
    }

    @Test
    fun `a slot reused for another app never shows the previous app's icon`() {
        val loader = FakeLoader(installed = setOf("com.example.a", "com.example.b"), warm = setOf("com.example.a"))
        var pkg by mutableStateOf("com.example.a")
        val seen = mutableListOf<Pair<String, AppIconResult?>>()
        compose.setContent {
            CompositionLocalProvider(LocalAppIconLoader provides loader) {
                seen += pkg to rememberAppIcon(pkg, 48)
            }
        }
        compose.waitForIdle()
        pkg = "com.example.b"
        compose.waitForIdle()

        val forB = seen.filter { it.first == "com.example.b" }.map { it.second }
        assertNull(forB.first())
        assertSame(loader.icons["com.example.b"], forB.last())
    }

    @Test
    fun `the letter is decorative, so a screen reader only hears the app's name beside it`() {
        compose.setContent { LagrangeTheme { AppIcon("com.example.a", "Alpha", Color.Red) } }
        compose.onNodeWithText("A").assertDoesNotExist()
        compose.onNodeWithTag(AppAvatarTestTag).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
    }

    @Test
    fun `an app installed while its row is on screen swaps its letter for its icon`() {
        val loader = FakeLoader(installed = emptySet())
        compose.setContent {
            CompositionLocalProvider(LocalAppIconLoader provides loader) {
                LagrangeTheme { AppIcon("com.example.new", "New", Color.Red) }
            }
        }
        compose.waitForIdle()
        avatars().assertCountEquals(1)

        loader.update("com.example.new", icon())
        compose.waitForIdle()

        avatars().assertCountEquals(0)
        assertEquals(listOf("com.example.new", "com.example.new"), loader.loads)
    }

    @Test
    fun `an app updated while its row is on screen shows its new icon, and other rows don't reload`() {
        val loader = FakeLoader(installed = setOf("com.example.a", "com.example.b"))
        val old = loader.icons.getValue("com.example.a")
        val seen = mutableListOf<AppIconResult?>()
        compose.setContent {
            CompositionLocalProvider(LocalAppIconLoader provides loader) {
                seen += rememberAppIcon("com.example.a", 48)
                rememberAppIcon("com.example.b", 48)
            }
        }
        compose.waitForIdle()
        assertSame(old, seen.last())

        val new = icon()
        loader.update("com.example.a", new)
        compose.waitForIdle()

        assertSame(new, seen.last())
        // The old icon stays until the new one is ready: no letter in between.
        assertTrue(seen.dropWhile { it !== old }.all { it is AppIconResult.Icon })
        assertEquals(listOf("com.example.a", "com.example.a"), loader.loads.filter { it == "com.example.a" })
        assertEquals(listOf("com.example.b"), loader.loads.filter { it == "com.example.b" })
    }

    @Test
    fun `trimming memory while a row is on screen doesn't decode its icon again`() {
        val decodes = AtomicInteger()
        // Unconfined: a decode runs inside load(), so it has finished by the time the UI is idle.
        val cache = AppIconCache(
            source = { _, _ -> decodes.incrementAndGet(); icon() },
            dispatcher = Dispatchers.Unconfined,
        )
        val seen = mutableListOf<AppIconResult?>()
        compose.setContent {
            CompositionLocalProvider(LocalAppIconLoader provides cache) {
                LagrangeTheme {
                    seen += rememberAppIcon("com.example.a", 48)
                    AppIcon("com.example.a", "Alpha", Color.Red)
                }
            }
        }
        compose.waitForIdle()
        assertTrue(decodes.get() > 0)
        val shown = seen.last()
        val decodesBefore = decodes.get()

        compose.runOnIdle { cache.trimMemory() }
        compose.waitForIdle()

        assertNull(cache.cached("com.example.a", 48))
        assertEquals(decodesBefore, decodes.get())
        assertSame(shown, seen.last())
        avatars().assertCountEquals(0)

        // Whereas clearing the cache (icons may have changed) does look them up again.
        compose.runOnIdle { cache.clear() }
        compose.waitForIdle()
        assertTrue(decodes.get() > decodesBefore)
    }
}

private fun icon() = AppIconResult.Icon(ImageBitmap(4, 4), fullBleed = true)

package com.celestiallemon.lagrange.ui.icons

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class AppIconCacheTest {
    /** Hands out a fresh 10×10 icon (400 bytes) per call, or null for [missing] packages, and counts the calls. */
    private class FakeSource(
        private val missing: Set<String> = emptySet(),
        private val fail: Set<String> = emptySet(),
        private val onLoad: (String) -> Unit = {},
    ) : AppIconSource {
        val calls = mutableListOf<Pair<String, Int>>()

        override fun load(packageName: String, sizePx: Int): AppIconResult.Icon? {
            calls += packageName to sizePx
            onLoad(packageName)
            if (packageName in fail) error("broken resources")
            if (packageName in missing) return null
            return AppIconResult.Icon(ImageBitmap(10, 10), fullBleed = true)
        }
    }

    private fun TestScope.cache(source: AppIconSource, maxBytes: Int = 1_000_000) =
        AppIconCache(source, maxBytes, StandardTestDispatcher(testScheduler))

    @Test
    fun `a loaded icon is then served from memory synchronously, without decoding again`() = runTest {
        val source = FakeSource()
        val cache = cache(source)
        assertNull(cache.cached("com.example.a", 48))

        val loaded = cache.load("com.example.a", 48)

        assertTrue(loaded is AppIconResult.Icon)
        assertSame(loaded, cache.cached("com.example.a", 48))
        assertSame(loaded, cache.load("com.example.a", 48))
        assertEquals(1, source.calls.size)
    }

    @Test
    fun `the pixel size is part of the key`() = runTest {
        val source = FakeSource()
        val cache = cache(source)
        cache.load("com.example.a", 48)
        cache.load("com.example.a", 96)
        assertEquals(listOf("com.example.a" to 48, "com.example.a" to 96), source.calls)
        assertNull(cache.cached("com.example.a", 120))
    }

    @Test
    fun `an app without an icon is remembered as missing, so it isn't looked up again`() = runTest {
        val source = FakeSource(missing = setOf("com.example.gone"))
        val cache = cache(source)

        assertSame(AppIconResult.Missing, cache.load("com.example.gone", 48))
        assertSame(AppIconResult.Missing, cache.cached("com.example.gone", 48))
        assertSame(AppIconResult.Missing, cache.load("com.example.gone", 48))
        assertEquals(1, source.calls.size)
    }

    @Test
    fun `an unexpected failure falls back to the letter but is retried next time`() = runTest {
        val source = FakeSource(fail = setOf("com.example.broken"))
        val cache = cache(source)

        assertSame(AppIconResult.Missing, cache.load("com.example.broken", 48))
        assertNull(cache.cached("com.example.broken", 48))
        cache.load("com.example.broken", 48)
        assertEquals(2, source.calls.size)
    }

    @Test
    fun `concurrent requests for the same icon share one decode`() = runTest {
        val source = FakeSource()
        val cache = cache(source)

        val a = async { cache.load("com.example.a", 48) }
        val b = async { cache.load("com.example.a", 48) }

        assertSame(a.await(), b.await())
        assertEquals(1, source.calls.size)
    }

    @Test
    fun `memory is bounded by bytes and evicts the least recently used icon`() = runTest {
        val source = FakeSource()
        // Room for two 400-byte icons.
        val cache = cache(source, maxBytes = 1_000)
        cache.load("com.example.a", 48)
        cache.load("com.example.b", 48)
        cache.cached("com.example.a", 48) // touch a, so b is now the oldest

        cache.load("com.example.c", 48)

        assertTrue(cache.cached("com.example.a", 48) is AppIconResult.Icon)
        assertNull(cache.cached("com.example.b", 48))
        assertTrue(cache.cached("com.example.c", 48) is AppIconResult.Icon)
    }

    @Test
    fun `invalidate drops every size of that package only`() = runTest {
        val source = FakeSource()
        val cache = cache(source)
        cache.load("com.example.a", 48)
        cache.load("com.example.a", 96)
        cache.load("com.example.b", 48)

        cache.invalidate("com.example.a")

        assertNull(cache.cached("com.example.a", 48))
        assertNull(cache.cached("com.example.a", 96))
        assertTrue(cache.cached("com.example.b", 48) is AppIconResult.Icon)
    }

    @Test
    fun `an icon decoded across an invalidate is returned but not kept`() = runTest {
        lateinit var cache: AppIconCache
        // The package is updated while its old icon is still being decoded.
        val source = FakeSource(onLoad = { if (it == "com.example.a") cache.invalidate(it) })
        cache = cache(source)

        assertTrue(cache.load("com.example.a", 48) is AppIconResult.Icon)
        assertNull(cache.cached("com.example.a", 48))
    }

    @Test
    fun `clear empties the cache`() = runTest {
        val cache = cache(FakeSource())
        cache.load("com.example.a", 48)
        cache.clear()
        assertNull(cache.cached("com.example.a", 48))
    }

    @Test
    fun `invalidating one package while another decodes still keeps the other`() = runTest {
        lateinit var cache: AppIconCache
        val source = FakeSource(onLoad = { if (it == "com.example.b") cache.invalidate("com.example.a") })
        cache = cache(source)

        cache.load("com.example.b", 48)

        assertTrue(cache.cached("com.example.b", 48) is AppIconResult.Icon)
    }

    @Test
    fun `an icon decoded across a clear is returned but doesn't refill the cache`() = runTest {
        lateinit var cache: AppIconCache
        val source = FakeSource(onLoad = { if (it == "com.example.a") cache.clear() })
        cache = cache(source)

        assertTrue(cache.load("com.example.a", 48) is AppIconResult.Icon)
        assertNull(cache.cached("com.example.a", 48))
    }

    @Test
    fun `trimming memory empties the cache without moving any revision`() = runTest {
        val source = FakeSource()
        val cache = cache(source)
        cache.load("com.example.a", 48)
        cache.load("com.example.b", 96)
        val before = cache.revisions.value

        cache.trimMemory()

        assertNull(cache.cached("com.example.a", 48))
        assertNull(cache.cached("com.example.b", 96))
        // The same instance: nothing observing revisions is told to reload.
        assertSame(before, cache.revisions.value)
        // Asked for again, it decodes again.
        assertTrue(cache.load("com.example.a", 48) is AppIconResult.Icon)
        assertEquals(3, source.calls.size)
    }

    @Test
    fun `an icon decoded across a trim is returned but doesn't refill the cache, and revisions don't move`() = runTest {
        lateinit var cache: AppIconCache
        val source = FakeSource(onLoad = { if (it == "com.example.a") cache.trimMemory() })
        cache = cache(source)
        val before = cache.revisions.value

        assertTrue(cache.load("com.example.a", 48) is AppIconResult.Icon)
        assertNull(cache.cached("com.example.a", 48))
        assertSame(before, cache.revisions.value)
    }

    @Test
    fun `revisions move for the invalidated package only, and for every package on clear`() = runTest {
        val cache = cache(FakeSource())
        val before = cache.revisions.value

        cache.invalidate("com.example.a")
        val afterInvalidate = cache.revisions.value
        assertNotEquals(before.of("com.example.a"), afterInvalidate.of("com.example.a"))
        assertEquals(before.of("com.example.b"), afterInvalidate.of("com.example.b"))

        cache.clear()
        val afterClear = cache.revisions.value
        assertNotEquals(afterInvalidate.of("com.example.a"), afterClear.of("com.example.a"))
        assertNotEquals(afterInvalidate.of("com.example.b"), afterClear.of("com.example.b"))

        // And forward again for a package invalidated after the clear.
        cache.invalidate("com.example.b")
        assertNotEquals(afterClear.of("com.example.b"), cache.revisions.value.of("com.example.b"))
    }

    @Test
    fun `running out of memory while decoding falls back to the letter, uncached, and is retried`() = runTest {
        var attempts = 0
        val source = AppIconSource { _, _ ->
            if (++attempts == 1) throw OutOfMemoryError("a 4096px icon")
            AppIconResult.Icon(ImageBitmap(10, 10), fullBleed = true)
        }
        val cache = cache(source)

        assertSame(AppIconResult.Missing, cache.load("com.example.huge", 48))
        assertNull(cache.cached("com.example.huge", 48))
        assertTrue(cache.load("com.example.huge", 48) is AppIconResult.Icon)
        assertEquals(2, attempts)
    }

    @Test
    fun `the byte budget counts what each bitmap really allocates`() = runTest {
        // A 10×10 icon in a buffer that was allocated for 20×20: 1,600 bytes, over a 1,000-byte budget.
        val padded = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply {
            reconfigure(10, 10, Bitmap.Config.ARGB_8888)
        }
        assertEquals(400, padded.byteCount)
        val cache = cache(AppIconSource { _, _ -> AppIconResult.Icon(padded.asImageBitmap(), fullBleed = true) }, 1_000)

        assertTrue(cache.load("com.example.a", 48) is AppIconResult.Icon)
        assertNull(cache.cached("com.example.a", 48))
    }

    @OptIn(ExperimentalCoroutinesApi::class) // runCurrent
    @Test
    fun `a waiter that is cancelled doesn't stop the decode for the others`() = runTest {
        val release = CountDownLatch(1)
        val decoder = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val cache = AppIconCache(
                source = { _, _ ->
                    release.await()
                    AppIconResult.Icon(ImageBitmap(10, 10), fullBleed = true)
                },
                dispatcher = decoder,
            )
            val a = async { cache.load("com.example.a", 48) }
            val b = async { cache.load("com.example.a", 48) }
            runCurrent() // both are now waiting on the one decode

            a.cancel()
            release.countDown()

            assertTrue(b.await() is AppIconResult.Icon)
            assertTrue(a.isCancelled)
            assertSame(b.await(), cache.cached("com.example.a", 48))
        } finally {
            decoder.close()
        }
    }

    @Test
    fun `a caller arriving just as a decode is published never decodes the icon again`() = runBlocking {
        // Races real threads against the moment a finished decode moves from in flight to memory.
        val icon = AppIconResult.Icon(ImageBitmap(1, 1), fullBleed = true)
        repeat(300) {
            val decodes = AtomicInteger()
            val cache = AppIconCache({ _, _ -> decodes.incrementAndGet(); icon }, dispatcher = Dispatchers.Default)
            val start = CountDownLatch(1)
            coroutineScope {
                repeat(8) {
                    launch(Dispatchers.Default) {
                        start.await()
                        cache.load("com.example.a", 48)
                    }
                }
                start.countDown()
            }
            assertEquals(1, decodes.get())
        }
    }

    @Test
    fun `the no-op loader always answers missing, synchronously`() = runTest {
        assertSame(AppIconResult.Missing, AppIconLoader.None.cached("com.example.a", 48))
        assertSame(AppIconResult.Missing, AppIconLoader.None.load("com.example.a", 48))
    }
}

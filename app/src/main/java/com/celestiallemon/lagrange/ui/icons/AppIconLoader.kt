package com.celestiallemon.lagrange.ui.icons

import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What an app's launcher icon resolved to. */
sealed interface AppIconResult {
    /**
     * A decoded icon, exactly [ImageBitmap.width] pixels square. [fullBleed] icons (adaptive icons) are opaque
     * squares that the caller clips to its own shape; the others (legacy icons) carry their own shape and
     * transparency and are drawn as they are.
     */
    class Icon(val bitmap: ImageBitmap, val fullBleed: Boolean) : AppIconResult

    /** Not installed, no icon of its own, or a synthetic package: show the letter avatar instead. */
    data object Missing : AppIconResult
}

/** Decodes one launcher icon. Blocking (PackageManager IPC, drawable inflation): never call it on the main thread. */
fun interface AppIconSource {
    /** The icon rendered [sizePx] square, or null when the package isn't installed or has no icon of its own. */
    fun load(packageName: String, sizePx: Int): AppIconResult.Icon?
}

/**
 * When each package's icon last changed, as far as an [AppIconLoader] knows: [of] moves forward whenever that
 * package was installed, updated or removed, or the whole cache was dropped. Immutable; a change is a new instance.
 */
class AppIconRevisions private constructor(private val clearedAt: Long, private val changedAt: Map<String, Long>) {
    /** A number that changes whenever [packageName]'s icon may have changed, and only then. */
    fun of(packageName: String): Long = maxOf(clearedAt, changedAt[packageName] ?: 0L)

    internal fun changed(packageName: String): AppIconRevisions =
        AppIconRevisions(clearedAt, changedAt + (packageName to next()))

    // Every package's revision now comes from clearedAt, so the per-package entries can go: the map stays small.
    internal fun cleared(): AppIconRevisions = AppIconRevisions(next(), emptyMap())

    private fun next(): Long = maxOf(clearedAt, changedAt.values.maxOrNull() ?: 0L) + 1

    companion object {
        val Initial = AppIconRevisions(0L, emptyMap())
    }
}

/**
 * Hands out launcher icons for the UI. [cached] is cheap enough to call during composition, so an icon already in
 * memory shows on the first frame; [load] does the work off the main thread; [revisions] tells a row on screen when
 * its icon has to be looked up again.
 */
interface AppIconLoader {
    /** Moves forward for a package when its icon may have changed, so rows showing it reload. */
    val revisions: StateFlow<AppIconRevisions>

    /** The icon from memory, or null when it hasn't been loaded yet. Never blocks. */
    fun cached(packageName: String, sizePx: Int): AppIconResult?

    /** Loads (or reuses) the icon. Safe to call from the main thread: the decode runs on a background dispatcher. */
    suspend fun load(packageName: String, sizePx: Int): AppIconResult

    /** Loads nothing: every app gets its letter avatar. The default for previews, screenshots and tests. */
    object None : AppIconLoader {
        override val revisions: StateFlow<AppIconRevisions> = MutableStateFlow(AppIconRevisions.Initial)
        override fun cached(packageName: String, sizePx: Int): AppIconResult = AppIconResult.Missing
        override suspend fun load(packageName: String, sizePx: Int): AppIconResult = AppIconResult.Missing
    }
}

/**
 * An [AppIconLoader] over a bounded, in-memory LRU of decoded bitmaps, keyed by package and pixel size.
 *
 * Only bitmaps are kept, never a `Drawable` or a `Context`. Packages with no icon are remembered too, as a tiny
 * [AppIconResult.Missing] entry, so a long list of uninstalled apps doesn't hit PackageManager on every scroll.
 * Concurrent requests for the same icon share one decode. [invalidate] drops a package after it is installed,
 * updated or removed; [clear] drops everything. Either way, a decode that was already running is still handed to
 * whoever waited for it, but never lands in memory, and [revisions] tells the rows on screen to load again.
 *
 * [trimMemory] also drops everything and keeps running decodes out of memory, but leaves [revisions] alone: the icons
 * didn't change, so the rows on screen keep the bitmaps they already hold instead of decoding them all again (which,
 * in the background, would refill the memory just given back). Rows composed later load on demand.
 */
class AppIconCache(
    private val source: AppIconSource,
    maxBytes: Int = defaultMaxBytes(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(DECODE_PARALLELISM),
) : AppIconLoader {
    private data class Key(val packageName: String, val sizePx: Int)

    private val memory = object : LruCache<Key, AppIconResult>(maxBytes) {
        override fun sizeOf(key: Key, value: AppIconResult): Int = when (value) {
            // What the bitmap really holds on to, row padding and all.
            is AppIconResult.Icon -> value.bitmap.asAndroidBitmap().allocationByteCount.coerceAtLeast(1)
            AppIconResult.Missing -> MISSING_ENTRY_BYTES
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Guards [inFlight], [revisionState] and every write to [memory], so a check-then-act on them is atomic. */
    private val lock = Any()
    private val inFlight = HashMap<Key, Deferred<AppIconResult>>()
    private val revisionState = MutableStateFlow(AppIconRevisions.Initial)

    override val revisions: StateFlow<AppIconRevisions> = revisionState.asStateFlow()

    override fun cached(packageName: String, sizePx: Int): AppIconResult? = memory.get(Key(packageName, sizePx))

    override suspend fun load(packageName: String, sizePx: Int): AppIconResult {
        val key = Key(packageName, sizePx)
        val pending = synchronized(lock) {
            // Checked under the lock: a decode that just finished has put its icon here and left inFlight in one step,
            // so a caller can't miss both and decode the same icon a second time.
            memory.get(key)?.let { return it }
            inFlight[key] ?: CompletableDeferred<AppIconResult>().also { deferred ->
                inFlight[key] = deferred
                val startedAt = revisionState.value.of(packageName)
                scope.launch {
                    try {
                        deferred.complete(decode(key, startedAt, deferred))
                    } catch (e: Throwable) {
                        // Only cancellation gets here (decode handles the rest): pass it on to the waiters.
                        deferred.completeExceptionally(e)
                    } finally {
                        // Only this decode's own entry: after an invalidate a newer one may have taken the key.
                        synchronized(lock) { inFlight.remove(key, deferred) }
                    }
                }
            }
        }
        // Cancelling the caller (a row scrolled away) leaves the decode running, so the icon is ready on the way back.
        return pending.await()
    }

    /** Forgets every size of [packageName], e.g. after it was installed, updated or removed. */
    fun invalidate(packageName: String) {
        synchronized(lock) {
            inFlight.keys.removeAll { it.packageName == packageName }
            memory.snapshot().keys.filter { it.packageName == packageName }.forEach(memory::remove)
            revisionState.value = revisionState.value.changed(packageName)
        }
    }

    /** Drops everything and has every row on screen look its icon up again, e.g. when any icon may have changed. */
    fun clear() {
        synchronized(lock) {
            evictAllLocked()
            revisionState.value = revisionState.value.cleared()
        }
    }

    /**
     * Gives the memory back, e.g. once the app is in the background and the system is short of memory. Unlike [clear],
     * [revisions] doesn't move, so rows on screen don't reload: they keep showing what they have, and an icon is only
     * decoded again when a row asks for one that isn't in memory.
     */
    fun trimMemory() {
        synchronized(lock) { evictAllLocked() }
    }

    /**
     * Empties memory and forgets the running decodes. That last part is the guard that keeps them from refilling it:
     * [decode] only keeps a result while its own entry is still in [inFlight]. Their waiters still get the icon.
     */
    private fun evictAllLocked() {
        inFlight.clear()
        memory.evictAll()
    }

    /**
     * Decodes [key] and keeps the result, unless its package changed (or the cache was cleared) since [startedAt], or
     * the cache was trimmed or cleared meanwhile (so [deferred] is no longer the decode in flight for [key]).
     * Never throws except for cancellation: whatever goes wrong while decoding one icon falls back to the letter.
     */
    private fun decode(key: Key, startedAt: Long, deferred: Deferred<AppIconResult>): AppIconResult {
        val result = try {
            source.load(key.packageName, key.sizePx) ?: AppIconResult.Missing
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            // A huge icon (a full-size bitmap where a launcher icon should be). Not remembered, so it's retried once
            // memory is back; the rows already showing their icons keep them.
            Log.w(TAG, "icon for ${key.packageName} ran out of memory", e)
            return AppIconResult.Missing
        } catch (e: Exception) {
            // Something unexpected (a broken resource, a dying package): fall back to the letter, but don't remember
            // the failure, so the next request tries again.
            Log.w(TAG, "icon for ${key.packageName} failed to load", e)
            return AppIconResult.Missing
        } catch (e: LinkageError) {
            // A launcher icon whose drawable class this process can't load (some OEM icon packs).
            Log.w(TAG, "icon for ${key.packageName} failed to load", e)
            return AppIconResult.Missing
        }
        synchronized(lock) {
            // Kept only if nothing changed for this package while it decoded, and this is still the decode for its key.
            if (revisionState.value.of(key.packageName) == startedAt && inFlight[key] === deferred) {
                memory.put(key, result)
            }
        }
        return result
    }

    companion object {
        private const val TAG = "AppIconCache"
        private const val MISSING_ENTRY_BYTES = 64
        private const val DECODE_PARALLELISM = 2

        /** 1/32 of the heap, between 2 and 8 MB: about 100 row icons on a high-density phone. */
        fun defaultMaxBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 32).coerceIn(2L * 1024 * 1024, 8L * 1024 * 1024).toInt()
    }
}

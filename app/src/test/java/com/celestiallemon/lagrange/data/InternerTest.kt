package com.celestiallemon.lagrange.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celestiallemon.lagrange.FakeResolver
import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class InternerTest {
    private val db = LagrangeDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val resolver = FakeResolver()
    private val interner = Interner(db, resolver)

    @After
    fun close() = db.close()

    @Test
    fun `ids are stable and the resolver runs once per new package`() = runBlocking {
        val a = interner.packageId("com.a")
        val b = interner.packageId("com.b")
        assertNotEquals(a, b)
        assertEquals(a, interner.packageId("com.a"))
        interner.clearCaches()
        assertEquals(a, interner.packageId("com.a"))
        assertEquals(listOf("com.a", "com.b"), resolver.calls)
    }

    @Test
    fun `suggestions are unconfirmed and never trust SOCIAL`() = runBlocking {
        interner.packageIds(listOf("com.reddit.frontpage", "org.thoughtcrime.securesms", "com.android.systemui", "com.celestiallemon.lagrange"))
        val rows = db.packageDao().all().associateBy { it.packageName }
        assertEquals(AppCategory.DISTRACTING.name, rows.getValue("com.reddit.frontpage").category)
        assertEquals(AppCategory.NEUTRAL.name, rows.getValue("org.thoughtcrime.securesms").category)
        assertEquals(AppCategory.EXCLUDED.name, rows.getValue("com.android.systemui").category)
        assertTrue(rows.getValue("com.android.systemui").isLauncherOrSystemUi)
        assertEquals(AppCategory.SELF_APP.name, rows.getValue("com.celestiallemon.lagrange").category)
        assertTrue(rows.values.none { it.categoryConfirmed })
    }

    @Test
    fun `catalog confirm and recategorize`() = runBlocking {
        val catalog = AppCatalogRepository(db, interner, { listOf("com.a", "com.b") }, FakeResolver())
        catalog.syncLaunchable()
        catalog.setCategory("com.a", AppCategory.DISTRACTING)
        val a = db.packageDao().byName("com.a")!!
        assertEquals(AppCategory.DISTRACTING.name, a.category)
        assertTrue(a.categoryConfirmed)
        assertFalse(db.packageDao().byName("com.b")!!.categoryConfirmed)
        catalog.confirmAll(listOf("com.b"))
        assertTrue(db.packageDao().byName("com.b")!!.categoryConfirmed)
    }
}

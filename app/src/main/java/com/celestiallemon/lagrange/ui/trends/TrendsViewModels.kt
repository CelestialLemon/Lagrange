package com.celestiallemon.lagrange.ui.trends

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.celestiallemon.lagrange.data.AppDeepDive
import com.celestiallemon.lagrange.data.TrendDataset
import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.PackageEntity
import com.celestiallemon.lagrange.domain.AppRow
import com.celestiallemon.lagrange.domain.AppSort
import com.celestiallemon.lagrange.domain.AppsBreakdown
import com.celestiallemon.lagrange.domain.TrendCalculator
import com.celestiallemon.lagrange.domain.TrendMetric
import com.celestiallemon.lagrange.domain.TrendReport
import com.celestiallemon.lagrange.domain.TrendWindow
import com.celestiallemon.lagrange.ui.afterReady
import com.celestiallemon.lagrange.ui.graph
import com.celestiallemon.lagrange.ui.minuteTickFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A trends window loaded from the database, with the apps breakdown for the same window. */
data class TrendsLoaded(val data: TrendDataset, val apps: List<AppRow>)

/**
 * Anything that changes what Trends shows: a rebuild rewriting rollups or ratings, an app's category / goal / name,
 * or the logical day rolling over while the screen is open.
 */
@OptIn(FlowPreview::class)
private fun com.celestiallemon.lagrange.AppGraph.trendsRefresh(): Flow<Any> = afterReady {
    val day = minuteTickFlow().map { derived.dateOf(it) }.distinctUntilChanged()
    combine(
        database.analyticsDao().observeDerivedSignature().distinctUntilChanged(),
        database.packageDao().observeAll().map { rows -> rows.map { listOf(it.id, it.category, it.dailyGoalMs, it.displayName) } }.distinctUntilChanged(),
        day,
    ) { a, b, c -> Triple(a, b, c) }.debounce(REFRESH_DEBOUNCE_MS)
}

private const val REFRESH_DEBOUNCE_MS = 400L

@OptIn(ExperimentalCoroutinesApi::class)
class TrendsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph

    val window = MutableStateFlow(TrendWindow.DEFAULT)
    val metric = MutableStateFlow(TrendMetric.DISTRACTING)
    val query = MutableStateFlow("")
    val category = MutableStateFlow<AppCategory?>(null)
    val sort = MutableStateFlow(AppSort.TIME)

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    val loaded: StateFlow<TrendsLoaded?> = combine(window, graph.trendsRefresh()) { w, _ -> w }
        .onEach { _loading.value = true }
        .mapLatest { w ->
            try {
                val data = graph.trends.trendData(w)
                TrendsLoaded(data, graph.trends.apps(data)).also { _error.value = null }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "trends load failed", e)
                _error.value = e.message ?: e.javaClass.simpleName
                null
            } finally {
                _loading.value = false
            }
        }
        .filterNotNull()
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val report: StateFlow<TrendReport?> = combine(loaded.filterNotNull(), metric) { l, m ->
        TrendCalculator.report(m, l.data.window, l.data.range, l.data.days)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val apps: StateFlow<List<AppRow>?> = combine(loaded.filterNotNull(), query, category, sort) { l, q, c, s ->
        AppsBreakdown.filter(l.apps, q, c, s)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setWindow(w: TrendWindow) {
        window.value = w
    }

    fun setMetric(m: TrendMetric) {
        metric.value = m
    }

    fun setQuery(q: String) {
        query.value = q.take(MAX_QUERY)
    }

    fun setCategory(c: AppCategory?) {
        category.value = c
    }

    fun setSort(s: AppSort) {
        sort.value = s
    }

    private companion object {
        const val TAG = "LagrangeTrends"
        const val MAX_QUERY = 60
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppDeepDiveViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph

    private val pkg = MutableStateFlow<Long?>(null)
    val window = MutableStateFlow(TrendWindow.DEFAULT)

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    /** Opens [pkgId]; the window starts as the one Trends was showing. */
    fun bind(pkgId: Long, initialWindow: TrendWindow) {
        if (pkg.value != pkgId) {
            window.value = initialWindow
            pkg.value = pkgId
        }
    }

    fun setWindow(w: TrendWindow) {
        window.value = w
    }

    val state: StateFlow<AppDeepDive?> = combine(pkg.filterNotNull(), window, graph.trendsRefresh()) { p, w, _ -> p to w }
        .onEach { _loading.value = true }
        .mapLatest { (p, w) ->
            try {
                graph.trends.deepDive(p, w)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "deep dive load failed", e)
                null
            } finally {
                _loading.value = false
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The live package row: the goal and category update the moment they are saved. */
    val pkgRow: StateFlow<PackageEntity?> = pkg.flatMapLatest { id ->
        if (id == null) flowOf(null) else graph.afterReady { database.analyticsDao().observePackage(id) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setGoal(goalMs: Long?) {
        val id = pkg.value ?: return
        launchIo { graph.trends.setDailyGoal(id, goalMs) }
    }

    fun setCategory(category: AppCategory) {
        val name = pkgRow.value?.packageName ?: return
        if (category == AppCategory.SELF_APP) return
        launchIo { graph.catalog.setCategory(name, category) }
    }

    private fun launchIo(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            graph.awaitReady()
            withContext(Dispatchers.IO) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "deep dive update failed", e)
        }
    }

    private companion object {
        const val TAG = "LagrangeDeepDive"
    }
}

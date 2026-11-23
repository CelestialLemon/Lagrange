package com.celestiallemon.lagrange.ui.trends

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.celestiallemon.lagrange.AppGraph
import com.celestiallemon.lagrange.data.AppDeepDive
import com.celestiallemon.lagrange.data.IngestionKeys
import com.celestiallemon.lagrange.data.TrendsOverview
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
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Anything that changes what Trends shows: a rebuild landing (`derived_last_run_at_ms`), a harvest (which moves how far
 * days are settled), a rating or friction event, the rollover hour or budget, an app's category or name, and the
 * logical day rolling over while the screen is open. Per-app goals refresh the apps list ([includeGoals]) but not the
 * deep dive, which draws the live goal itself.
 */
@OptIn(FlowPreview::class)
private fun AppGraph.trendsRefresh(includeGoals: Boolean): Flow<Any> = afterReady {
    val state = database.ingestionStateDao()
    combine(
        listOf(
            minuteTickFlow().map { derived.dateOf(it) }.distinctUntilChanged(),
            preferences.prefs.map { it.rolloverHour to it.dailyBudgetMs }.distinctUntilChanged(),
            state.observe(IngestionKeys.DERIVED_LAST_RUN_AT_MS).distinctUntilChanged(),
            state.observe(IngestionKeys.LAST_HARVEST_AT_MS).distinctUntilChanged(),
            database.analyticsDao().observeUserSignals().distinctUntilChanged(),
            database.packageDao().observeAll().map { rows ->
                rows.map { listOf(it.id, it.category, it.displayName, it.isLauncherOrSystemUi, if (includeGoals) it.dailyGoalMs else null) }
            }.distinctUntilChanged(),
        ),
    ) { it.toList() }.debounce(REFRESH_DEBOUNCE_MS)
}

private const val REFRESH_DEBOUNCE_MS = 400L

/** One step of a load: started for a request, finished with a value, or failed. */
private sealed interface Load<out T> {
    data object Started : Load<Nothing>
    data class Done<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

/**
 * Loads the latest request, cancelling an older one. Every request emits [Load.Started] first, so a cancelled load
 * can never clear the loading state of the one that replaced it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun <K, T> Flow<K>.loadLatest(tag: String, load: suspend (K) -> T): Flow<Pair<K, Load<T>>> = transformLatest { k ->
    emit(k to Load.Started)
    val result: Load<T> = try {
        Load.Done(load(k))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(tag, "load failed", e)
        Load.Failed(e.message ?: e.javaClass.simpleName)
    }
    emit(k to result)
}

/** What the Trends tab shows: the window asked for, and the last overview that loaded (possibly for another window). */
data class TrendsUiState(
    val requested: TrendWindow,
    val overview: TrendsOverview? = null,
    val loading: Boolean = true,
    val error: String? = null,
) {
    /** The figures on screen are for another window than the one selected (a load is under way or failed). */
    val stale: Boolean get() = overview != null && overview.data.window != requested
}

@OptIn(ExperimentalCoroutinesApi::class)
class TrendsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph

    val window = MutableStateFlow(TrendWindow.DEFAULT)
    val metric = MutableStateFlow(TrendMetric.DISTRACTING)
    val query = MutableStateFlow("")
    val category = MutableStateFlow<AppCategory?>(null)
    val sort = MutableStateFlow(AppSort.TIME)

    private val retries = MutableStateFlow(0)

    val ui: StateFlow<TrendsUiState> = combine(window, retries, graph.trendsRefresh(includeGoals = true)) { w, _, _ -> w }
        .loadLatest(TAG) { w -> graph.trends.overview(w) }
        .scan(TrendsUiState(TrendWindow.DEFAULT)) { acc, (w, ev) ->
            when (ev) {
                Load.Started -> acc.copy(requested = w, loading = true)
                is Load.Done -> TrendsUiState(w, ev.value, loading = false, error = null)
                is Load.Failed -> acc.copy(requested = w, loading = false, error = ev.message)
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrendsUiState(TrendWindow.DEFAULT))

    private val overview = ui.mapNotNull { it.overview }.distinctUntilChanged()

    /** The report is always built from the loaded data's own window, never the one merely requested. */
    val report: StateFlow<TrendReport?> = combine(overview, metric) { o, m ->
        TrendCalculator.report(m, o.data.window, o.data.range, o.data.days)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val apps: StateFlow<List<AppRow>?> = combine(overview, query, category, sort) { o, q, c, s ->
        AppsBreakdown.filter(o.apps, q, c, s)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setWindow(w: TrendWindow) {
        window.value = w
    }

    fun retry() {
        retries.value++
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

/** The deep dive for [pkgId]: [dive] may still be the previous load's (or null) while [loading]. */
data class DeepDiveUiState(
    val pkgId: Long? = null,
    val window: TrendWindow = TrendWindow.DEFAULT,
    val dive: AppDeepDive? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class AppDeepDiveViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph

    private val request = MutableStateFlow<Pair<Long, TrendWindow>?>(null)

    /** Shows [pkgId] over [window] (the screen owns the window, so each visit starts from Trends' own). */
    fun show(pkgId: Long, window: TrendWindow) {
        request.value = pkgId to window
    }

    private val retries = MutableStateFlow(0)

    fun retry() {
        retries.value++
    }

    val ui: StateFlow<DeepDiveUiState> = combine(request.filterNotNull(), retries, graph.trendsRefresh(includeGoals = false)) { r, _, _ -> r }
        .loadLatest(TAG) { (p, w) -> graph.trends.deepDive(p, w) }
        .scan(DeepDiveUiState()) { acc, (r, ev) ->
            val (p, w) = r
            when (ev) {
                // Never keep another app's figures around under this one's name.
                Load.Started -> acc.copy(pkgId = p, window = w, loading = true, error = null, dive = acc.dive?.takeIf { it.meta.pkgId == p })
                is Load.Done -> DeepDiveUiState(p, w, ev.value, loading = false)
                is Load.Failed -> acc.copy(pkgId = p, window = w, loading = false, error = ev.message, dive = acc.dive?.takeIf { it.meta.pkgId == p })
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeepDiveUiState())

    /** The live package row: the goal and category update the moment they are saved. */
    val pkgRow: StateFlow<PackageEntity?> = request.map { it?.first }.distinctUntilChanged().flatMapLatest { id ->
        if (id == null) flowOf(null) else graph.afterReady { database.analyticsDao().observePackage(id) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setGoal(goalMs: Long?) {
        val id = request.value?.first ?: return
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

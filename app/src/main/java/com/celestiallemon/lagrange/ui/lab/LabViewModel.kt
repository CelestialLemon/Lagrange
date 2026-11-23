package com.celestiallemon.lagrange.ui.lab

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.celestiallemon.lagrange.data.CheckInCard
import com.celestiallemon.lagrange.data.FrictionData
import com.celestiallemon.lagrange.data.GatewayData
import com.celestiallemon.lagrange.data.NotificationCostData
import com.celestiallemon.lagrange.data.RabbitHoleData
import com.celestiallemon.lagrange.data.RhythmData
import com.celestiallemon.lagrange.data.SpikesReport
import com.celestiallemon.lagrange.data.db.PackageEntity
import com.celestiallemon.lagrange.domain.TrendWindow
import com.celestiallemon.lagrange.domain.lab.LabSection
import com.celestiallemon.lagrange.domain.lab.LabWindows
import com.celestiallemon.lagrange.domain.lab.NotificationSort
import com.celestiallemon.lagrange.domain.lab.RabbitFilter
import com.celestiallemon.lagrange.domain.lab.RabbitHoleExplorer
import com.celestiallemon.lagrange.domain.lab.RabbitHoleFeed
import com.celestiallemon.lagrange.domain.lab.SpikeAutopsy
import com.celestiallemon.lagrange.ui.EpisodeUi
import com.celestiallemon.lagrange.ui.SessionDetail
import com.celestiallemon.lagrange.ui.checkInFlow
import com.celestiallemon.lagrange.ui.graph
import com.celestiallemon.lagrange.ui.loadSessionDetail
import com.celestiallemon.lagrange.ui.trends.Load
import com.celestiallemon.lagrange.ui.trends.loadLatest
import com.celestiallemon.lagrange.ui.trends.scanRetaining
import com.celestiallemon.lagrange.ui.trends.trendsRefresh
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One section's data for one window. */
sealed interface LabData {
    val window: TrendWindow

    data class Spikes(val report: SpikesReport) : LabData {
        override val window get() = report.scope.window
    }
    data class RabbitHoles(val data: RabbitHoleData) : LabData {
        override val window get() = data.scope.window
    }
    data class Gateways(val data: GatewayData) : LabData {
        override val window get() = data.scope.window
    }
    data class Alerts(val data: NotificationCostData, val sort: NotificationSort) : LabData {
        override val window get() = data.scope.window
    }
    data class Rhythm(val data: RhythmData) : LabData {
        override val window get() = data.scope.window
    }
    data class Friction(val data: FrictionData) : LabData {
        override val window get() = data.scope.window
    }
}

data class LabRequest(val window: TrendWindow, val section: LabSection, val sort: NotificationSort)

data class LabUiState(
    val request: LabRequest,
    /** Last loaded data per section (possibly for another window while a load runs). */
    val data: Map<LabSection, LabData> = emptyMap(),
    val loading: Boolean = true,
    val error: String? = null,
) {
    val current: LabData? get() = data[request.section]
    val stale: Boolean get() = current != null && current?.window != request.window
}

/** The session sheet opened from a Lab card. */
data class LabSheet(val episode: EpisodeUi, val packages: Map<Long, PackageEntity>, val detail: SessionDetail?)

data class AutopsyState(val date: LocalDate, val autopsy: SpikeAutopsy?, val loading: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
class LabViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph

    val window = MutableStateFlow(LabWindows.DEFAULT)
    val section = MutableStateFlow(LabSection.SPIKES)
    val notificationSort = MutableStateFlow(NotificationSort.VOLUME)
    val rabbitFilter = MutableStateFlow(RabbitFilter())
    private val retries = MutableStateFlow(0)
    private val refresh = graph.trendsRefresh(includeGoals = false)
    private val initial = LabRequest(LabWindows.DEFAULT, LabSection.SPIKES, NotificationSort.VOLUME)

    val ui: StateFlow<LabUiState> = combine(window, section, notificationSort, retries, refresh) { w, s, sort, _, _ -> LabRequest(w, s, sort) }
        .loadLatest(TAG) { r -> load(r) }
        .scanRetaining(LabUiState(initial)) { acc, (r, ev) ->
            when (ev) {
                Load.Started -> acc.copy(request = r, loading = true, error = null)
                is Load.Done -> acc.copy(request = r, data = acc.data + (r.section to ev.value), loading = false, error = null)
                is Load.Failed -> acc.copy(request = r, loading = false, error = ev.message)
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LabUiState(initial))

    private suspend fun load(r: LabRequest): LabData = when (r.section) {
        LabSection.SPIKES -> LabData.Spikes(graph.lab.spikes(r.window))
        LabSection.RABBIT_HOLES -> LabData.RabbitHoles(graph.lab.rabbitHoles(r.window))
        LabSection.GATEWAYS -> LabData.Gateways(graph.lab.gateways(r.window))
        LabSection.ALERTS -> LabData.Alerts(graph.lab.notificationCost(r.window, r.sort), r.sort)
        LabSection.RHYTHM -> LabData.Rhythm(graph.lab.rhythm(r.window))
        LabSection.FRICTION -> LabData.Friction(graph.lab.friction(r.window))
    }

    /** The filtered rabbit-hole feed (filtering is in memory: no reload per chip). */
    val rabbitFeed: StateFlow<RabbitHoleFeed?> = combine(ui.map { it.data[LabSection.RABBIT_HOLES] }.distinctUntilChanged(), rabbitFilter) { d, f ->
        (d as? LabData.RabbitHoles)?.let { RabbitHoleExplorer.feed(it.data.episodes, f, it.data.apps) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val autopsyDate = MutableStateFlow<LocalDate?>(null)

    /** The expanded spike autopsy; reloads with the rest of the Lab (a rating or rebuild can change it) and on note edits. */
    val autopsy: StateFlow<AutopsyState?> = combine(
        autopsyDate,
        ui.map { it.data[LabSection.SPIKES] }.distinctUntilChanged(),
        graph.database.analyticsDao().observeNotesSignal().distinctUntilChanged(),
    ) { d, s, n -> Triple(d, s, n) }
        .loadLatest(TAG) { (d, _, _) -> d?.let { graph.lab.autopsy(it) } }
        .map { (key, ev) ->
            val date = key.first ?: return@map null
            when (ev) {
                Load.Started -> AutopsyState(date, null, loading = true)
                is Load.Done -> AutopsyState(date, ev.value, loading = false)
                is Load.Failed -> AutopsyState(date, null, loading = false)
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val openKey = MutableStateFlow<String?>(null)

    val sheet: StateFlow<LabSheet?> = combine(openKey, refresh) { k, _ -> k }
        .mapLatest { key ->
            if (key == null) return@mapLatest null
            try {
                withContext(Dispatchers.IO) {
                    val (session, segments) = graph.lab.session(key) ?: return@withContext null
                    val ep = EpisodeUi(session, segments)
                    LabSheet(ep, graph.lab.packages(), loadSessionDetail(graph.database, ep))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "sheet load failed", e)
                null
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val checkIn: StateFlow<CheckInCard?> = graph.checkInFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setWindow(w: TrendWindow) {
        window.value = w
    }

    fun setSection(s: LabSection) {
        section.value = s
    }

    fun setSort(s: NotificationSort) {
        notificationSort.value = s
    }

    fun setFilter(f: RabbitFilter) {
        rabbitFilter.value = f
    }

    fun retry() {
        retries.value++
    }

    fun toggleAutopsy(date: LocalDate) {
        autopsyDate.value = if (autopsyDate.value == date) null else date
    }

    fun open(episodeKey: String?) {
        openKey.value = episodeKey
    }

    fun rate(episodeKey: String, startMs: Long, rating: Int?) = launchIo {
        if (!graph.derived.setWorthIt(episodeKey, startMs, rating)) {
            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), "That session changed while rebuilding. Please rate it again.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun dismissCheckIn(episodeKey: String) = launchIo { graph.lab.dismissCheckIn(episodeKey) }

    private fun launchIo(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            graph.awaitReady()
            withContext(Dispatchers.IO) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "lab update failed", e)
        }
    }

    private companion object {
        const val TAG = "LagrangeLab"
    }
}

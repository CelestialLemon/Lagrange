package com.celestiallemon.lagrange.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.celestiallemon.lagrange.data.RecomputeProgress
import com.celestiallemon.lagrange.data.TodayComparison
import com.celestiallemon.lagrange.data.UserPrefs
import com.celestiallemon.lagrange.data.db.DailyAppRollupEntity
import com.celestiallemon.lagrange.data.db.DailyNoteEntity
import com.celestiallemon.lagrange.data.db.DailyRollupEntity
import com.celestiallemon.lagrange.data.db.FrictionEventEntity
import com.celestiallemon.lagrange.data.db.PackageEntity
import com.celestiallemon.lagrange.data.db.RawNotificationEntity
import com.celestiallemon.lagrange.data.db.SessionEntity
import com.celestiallemon.lagrange.data.db.SessionSegmentEntity
import com.celestiallemon.lagrange.data.db.FrictionVocab
import com.celestiallemon.lagrange.data.db.NamedUsageEvent
import com.celestiallemon.lagrange.domain.DayWindow
import com.celestiallemon.lagrange.domain.LogicalDay
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One reconstructed episode with its app segments, ready for the timeline. */
data class EpisodeUi(val session: SessionEntity, val segments: List<SessionSegmentEntity>) {
    val id get() = session.id
    val isInProgress get() = session.contextFlags?.contains("IN_PROGRESS") == true
    val hasUncertainTail get() = session.contextFlags?.contains("UNCERTAIN_TAIL") == true
}

/** Everything the Today screen shows, for one logical day. */
data class TodayState(
    val date: LocalDate,
    val rollup: DailyRollupEntity?,
    val episodes: List<EpisodeUi>,
    val apps: List<DailyAppRollupEntity>,
    val packages: Map<Long, PackageEntity>,
    val note: DailyNoteEntity?,
    val budgetMs: Long,
    val comparison: TodayComparison?,
)

/** Blocked attempts and the trigger behind one session, loaded when its sheet opens. */
data class SessionDetail(
    val sessionId: Long,
    val triggerNotification: RawNotificationEntity?,
    val blockedAttempts: List<FrictionEventEntity>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph

    val progress: StateFlow<RecomputeProgress> = graph.afterReady { derived.progress }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecomputeProgress())

    /** The current logical day; rolls over on its own while the screen is open. */
    private val today: Flow<LocalDate> = graph.afterReady {
        combine(preferences.prefs.map { it.rolloverHour }.distinctUntilChanged(), minuteTickFlow()) { hour, now ->
            LogicalDay.dateOf(now, hour, ZoneId.systemDefault())
        }.distinctUntilChanged()
    }

    val state: StateFlow<TodayState?> = today.flatMapLatest { date ->
        val key = date.toString()
        val dao = graph.database.derivedDao()
        val rollup = dao.observeDailyRollup(key)
        val sessions = combine(dao.observeSessions(key), dao.observeSegments(key)) { s, g ->
            val bySession = g.groupBy { it.sessionId }
            s.map { EpisodeUi(it, bySession[it.id].orEmpty()) }
        }
        val packages = graph.database.packageDao().observeAll().map { rows -> rows.associateBy { it.id } }
        // Re-read the slice whenever the rollup changes (each rebuild) or the clock moves the cutoff.
        val comparison = combine(rollup, minuteTickFlow()) { r, now -> r?.activeUsageMs to now / COMPARISON_REFRESH_MS }
            .distinctUntilChanged()
            .mapLatest { runCatching { graph.derived.todayComparison() }.getOrNull() }
        val first = combine(rollup, sessions, dao.observeAppRollups(key), packages) { r, s, a, p -> Partial(r, s, a, p) }
        combine(first, dao.observeNote(key), graph.preferences.prefs.map { it.dailyBudgetMs }, comparison) { f, note, budget, cmp ->
            TodayState(date, f.rollup, f.episodes, f.apps, f.packages, note, budget, cmp?.takeIf { it.date == date })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private data class Partial(
        val rollup: DailyRollupEntity?,
        val episodes: List<EpisodeUi>,
        val apps: List<DailyAppRollupEntity>,
        val packages: Map<Long, PackageEntity>,
    )

    private val openDetail = MutableStateFlow<Long?>(null)

    val detail: StateFlow<SessionDetail?> = openDetail.mapLatest { id ->
        if (id == null) return@mapLatest null
        withContext(Dispatchers.IO) {
            val session = state.value?.episodes?.firstOrNull { it.id == id } ?: return@withContext null
            val notif = session.session.triggerNotificationId?.let { graph.database.rawNotificationDao().byId(it) }
            val blocked = session.segments.filter { it.isBlockedAttempt }.flatMap { seg ->
                graph.database.derivedDao().frictionFor(seg.pkgId, seg.startMs - FRICTION_SLACK_MS, seg.endMs + FRICTION_SLACK_MS)
                    .filter { it.outcome in FrictionVocab.BLOCKING_OUTCOMES }
            }.distinctBy { it.id }
            SessionDetail(id, notif, blocked)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun loadDetail(sessionId: Long?) {
        openDetail.value = sessionId
    }

    fun rate(sessionId: Long, rating: Int?) = launchIo { graph.derived.setWorthIt(sessionId, rating) }

    fun saveNote(date: LocalDate, text: String) = launchIo {
        val dao = graph.database.derivedDao()
        val trimmed = text.trim().take(NOTE_MAX_CHARS)
        if (trimmed.isEmpty()) dao.deleteNote(date.toString())
        else dao.upsertNote(DailyNoteEntity(date.toString(), trimmed, System.currentTimeMillis()))
    }

    fun setBudget(budgetMs: Long) = launchIo {
        graph.preferences.setDailyBudgetMs(budgetMs.coerceIn(UserPrefs.BUDGET_RANGE_MS))
    }

    private fun launchIo(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            graph.awaitReady()
            withContext(Dispatchers.IO) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "today update failed", e)
        }
    }

    companion object {
        const val NOTE_MAX_CHARS = 280
        private const val COMPARISON_REFRESH_MS = 5 * 60_000L
        private const val FRICTION_SLACK_MS = 5_000L
        private const val TAG = "LagrangeToday"
    }
}

/** One row of the Debug Timeline Inspector: raw facts on the left, what Lagrange made of them on the right. */
sealed interface InspectorRow {
    val atMs: Long

    data class RawEvent(val event: NamedUsageEvent) : InspectorRow {
        override val atMs get() = event.timestampMs
    }

    data class Notification(val n: RawNotificationEntity, val packageName: String) : InspectorRow {
        override val atMs get() = n.postedAtMs
    }

    data class SessionStart(val episode: EpisodeUi) : InspectorRow {
        override val atMs get() = episode.session.startMs
    }

    data class SegmentStart(val segment: SessionSegmentEntity, val packageName: String) : InspectorRow {
        override val atMs get() = segment.startMs
    }
}

data class InspectorState(
    val date: LocalDate,
    val window: DayWindow?,
    val rows: List<InspectorRow> = emptyList(),
    val rawEventCount: Int = 0,
    val notificationCount: Int = 0,
    val sessionCount: Int = 0,
    val segmentCount: Int = 0,
    val loading: Boolean = false,
)

class InspectorViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph

    private val _state = MutableStateFlow(InspectorState(LocalDate.now(), null, loading = true))
    val state: StateFlow<InspectorState> = _state

    private val _op = MutableStateFlow(DataOpState())
    val op: StateFlow<DataOpState> = _op

    /** Only the event types reconstruction reads, unless the user asks for everything. */
    private val _allTypes = MutableStateFlow(false)
    val allTypes: StateFlow<Boolean> = _allTypes

    init {
        viewModelScope.launch {
            try {
                graph.awaitReady()
                val today = withContext(Dispatchers.IO) { graph.derived.dateOf(System.currentTimeMillis()) }
                load(today)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "inspector init failed", e)
            }
        }
    }

    fun shiftDay(days: Long) = load(_state.value.date.plusDays(days))

    fun setAllTypes(all: Boolean) {
        _allTypes.value = all
        load(_state.value.date)
    }

    fun load(date: LocalDate) {
        _state.value = _state.value.copy(date = date, loading = true)
        viewModelScope.launch {
            val next = try {
                withContext(Dispatchers.IO) { build(date, _allTypes.value) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "inspector load failed", e)
                InspectorState(date, null)
            }
            if (_state.value.date == date) _state.value = next
        }
    }

    private suspend fun build(date: LocalDate, allTypes: Boolean): InspectorState {
        val w = graph.derived.windowOf(date)
        val db = graph.database
        val names = db.packageDao().all().associate { it.id to it.packageName }
        val events = db.rawUsageEventDao().namedBetween(w.startMs, w.endMs)
            .filter { allTypes || it.eventType in RECONSTRUCTION_TYPES }
        val notifications = db.rawNotificationDao().touching(w.startMs, w.endMs).filter { it.postedAtMs >= w.startMs && it.postedAtMs < w.endMs }
        val sessions = db.derivedDao().sessionsFor(date.toString())
        val segments = db.derivedDao().segmentsFor(date.toString())
        val bySession = segments.groupBy { it.sessionId }
        val episodes = sessions.map { EpisodeUi(it, bySession[it.id].orEmpty()) }
        val rows = buildList {
            events.forEach { add(InspectorRow.RawEvent(it)) }
            notifications.forEach { add(InspectorRow.Notification(it, names[it.pkgId] ?: "#${it.pkgId}")) }
            episodes.forEach { ep ->
                add(InspectorRow.SessionStart(ep))
                ep.segments.forEach { add(InspectorRow.SegmentStart(it, names[it.pkgId] ?: "#${it.pkgId}")) }
            }
        }.sortedWith(compareBy<InspectorRow>({ it.atMs }, { rowOrder(it) }))
        return InspectorState(date, w, rows, events.size, notifications.size, sessions.size, segments.size)
    }

    private fun rowOrder(r: InspectorRow) = when (r) {
        is InspectorRow.RawEvent -> 0
        is InspectorRow.Notification -> 1
        is InspectorRow.SessionStart -> 2
        is InspectorRow.SegmentStart -> 3
    }

    fun suggestedFileName(): String = "lagrange-day-${_state.value.date}.json"

    /** Writes the selected day as a replayable JSON fixture to a document the user picked. */
    fun exportFixture(uri: Uri) {
        if (_op.value.busy) return
        val date = _state.value.date
        _op.value = DataOpState(busy = true, message = "Exporting $date…")
        viewModelScope.launch {
            _op.value = try {
                graph.awaitReady()
                val fixture = graph.derived.dayFixture(date)
                withContext(Dispatchers.IO) {
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri, "wt") ?: error("Could not open the file")
                    out.use { it.write(fixture.encode().toByteArray()) }
                }
                DataOpState(message = "Exported ${fixture.events.size} events · ${fixture.expected.size} episodes")
            } catch (e: CancellationException) {
                _op.value = DataOpState()
                throw e
            } catch (e: Exception) {
                DataOpState(message = e.message ?: e.javaClass.simpleName, isError = true)
            }
        }
    }

    fun clearMessage() {
        if (!_op.value.busy) _op.value = DataOpState()
    }

    private companion object {
        const val TAG = "LagrangeInspector"
        val RECONSTRUCTION_TYPES = setOf(1, 2, 12, 15, 16, 17, 18, 23, 26, 27)
    }
}

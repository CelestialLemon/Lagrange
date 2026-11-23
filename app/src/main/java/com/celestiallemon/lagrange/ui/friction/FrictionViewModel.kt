package com.celestiallemon.lagrange.ui.friction

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.celestiallemon.lagrange.data.UserPrefs
import com.celestiallemon.lagrange.data.db.FrictionScheduleEntity
import com.celestiallemon.lagrange.digest.DigestService
import com.celestiallemon.lagrange.domain.digest.DigestKind
import com.celestiallemon.lagrange.harvest.HarvestTrigger
import com.celestiallemon.lagrange.domain.friction.FrictionLiveStatus
import com.celestiallemon.lagrange.domain.friction.FrictionSchedule
import com.celestiallemon.lagrange.ui.afterReady
import com.celestiallemon.lagrange.ui.graph
import com.celestiallemon.lagrange.ui.minuteTickFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class FrictionViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val prefs: StateFlow<UserPrefs?> = graph.afterReady { preferences.prefs }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val schedules: StateFlow<List<FrictionScheduleEntity>> = graph.afterReady { friction.observeSchedules() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val liveStatus: StateFlow<FrictionLiveStatus> = graph.afterReady { friction.liveStatus }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FrictionLiveStatus())

    /** Blocked launch attempts inside the current logical day's UTC window (matches DerivedRepository across rollover changes). */
    val blockedToday: StateFlow<Int> = graph.afterReady {
        combine(preferences.prefs.map { it.rolloverHour }.distinctUntilChanged(), minuteTickFlow()) { _, now ->
            withContext(Dispatchers.IO) {
                val date = derived.dateOf(now)
                derived.windowOf(date)
            }
        }.distinctUntilChanged().flatMapLatest { window ->
            database.derivedDao().observeBlockedCountBetween(window.startMs, window.endMs)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun clearError() {
        _errorMessage.value = null
    }

    fun setFrictionEnabled(enabled: Boolean) = launchIo { graph.preferences.setFrictionEnabled(enabled) }

    fun setEscalationEnabled(enabled: Boolean) = launchIo { graph.preferences.setEscalationEnabled(enabled) }

    fun setTierPauses(t0: Int, t1: Int, t2: Int, t3: Int) = launchIo {
        graph.preferences.setTierPauseSeconds(t0, t1, t2, t3)
    }

    fun setGraceWindowSec(sec: Int) = launchIo { graph.preferences.setGraceWindowSec(sec) }

    fun setNudgeIntervalMin(min: Int) = launchIo { graph.preferences.setNudgeIntervalMin(min) }

    fun toggleTimerPreset(minutes: Int) = launchIo {
        graph.preferences.toggleTimerPreset(minutes)
    }

    fun setDailyPromptCap(cap: Int?) = launchIo { graph.preferences.setDailyPromptCap(cap) }

    fun setRedirectPackage(pkg: String?) = launchIo { graph.preferences.setRedirectPackageName(pkg) }

    fun setDailyBudgetMs(budgetMs: Long) = launchIo {
        graph.preferences.setDailyBudgetMs(budgetMs.coerceIn(UserPrefs.BUDGET_RANGE_MS))
    }

    fun activateSnooze1h() = launchIo { graph.friction.activateSnooze1h() }

    fun cancelSnooze() = launchIo { graph.friction.cancelSnooze() }

    fun activateFocusBoost1h() = launchIo { graph.friction.activateFocusBoost1h() }

    fun cancelFocusBoost() = launchIo { graph.friction.cancelFocusBoost() }

    // -- Reflection digests (the app graph reschedules the workers whenever these settings change) -----------------

    private val _digestMessage = MutableStateFlow<String?>(null)

    /** Result of the last "Send preview digest", shown under the buttons. */
    val digestMessage: StateFlow<String?> = _digestMessage.asStateFlow()

    fun setDailyDigestEnabled(enabled: Boolean) = launchIo { graph.preferences.setDailyDigest(enabled = enabled) }

    fun setDailyDigestMinute(minuteOfDay: Int) = launchIo { graph.preferences.setDailyDigest(minuteOfDay = minuteOfDay) }

    fun setWeeklyDigestEnabled(enabled: Boolean) = launchIo { graph.preferences.setWeeklyDigest(enabled = enabled) }

    fun setWeeklyDigestDay(dayOfWeek: Int) = launchIo { graph.preferences.setWeeklyDigest(dayOfWeek = dayOfWeek) }

    fun setWeeklyDigestMinute(minuteOfDay: Int) = launchIo { graph.preferences.setWeeklyDigest(minuteOfDay = minuteOfDay) }

    /** Posts what the digest would say right now; never counts as the real one for that day or week. */
    fun sendPreviewDigest(kind: DigestKind) = launchIo {
        _digestMessage.value = "Composing ${kind.label()} preview…"
        // Fresh numbers first, as the scheduled digest does.
        runCatching { graph.harvestAndDerive(HarvestTrigger.MANUAL, recomputeInline = true) }
            .onFailure { if (it is CancellationException) throw it }
        _digestMessage.value = when (graph.digests.sendTest(kind)) {
            DigestService.Outcome.POSTED -> "Preview ${kind.label()} digest posted."
            DigestService.Outcome.NOTIFICATIONS_OFF -> "Notifications are off for Lagrange, so nothing was posted."
            DigestService.Outcome.NOTHING_TO_SAY -> when (kind) {
                DigestKind.DAILY -> "Nothing to summarize yet: no recorded data for that day."
                DigestKind.WEEKLY -> "Nothing to summarize yet: no fully covered day in the last week."
            }
            DigestService.Outcome.ALREADY_POSTED -> "Already posted."
        }
    }

    fun clearDigestMessage() {
        _digestMessage.value = null
    }

    private fun DigestKind.label() = if (this == DigestKind.DAILY) "daily" else "weekly"

    fun saveSchedule(schedule: FrictionScheduleEntity, onSaved: () -> Unit = {}) = viewModelScope.launch {
        try {
            graph.awaitReady()
            withContext(Dispatchers.IO) {
                if (schedule.id == 0L) graph.friction.createSchedule(schedule)
                else graph.friction.updateSchedule(schedule)
            }
            _errorMessage.value = null
            onSaved()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "schedule save failed", e)
            _errorMessage.value = e.message ?: "Could not save schedule."
        }
    }

    fun toggleScheduleEnabled(id: Long, enabled: Boolean) = launchIo {
        graph.friction.setScheduleEnabled(id, enabled)
    }

    fun deleteSchedule(id: Long) = launchIo {
        graph.friction.deleteSchedule(id)
    }

    fun addWorkHoursPreset() = saveSchedule(
        FrictionScheduleEntity(
            name = "Work Hours",
            enabled = true,
            daysOfWeekMask = FrictionSchedule.MASK_WEEKDAYS,
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 17 * 60 + 30,
            escalationBoost = 1,
            requireTimerPrompt = false,
            nudgeIntervalMin = null,
        ),
    )

    fun addWindDownPreset() = saveSchedule(
        FrictionScheduleEntity(
            name = "Wind-Down",
            enabled = true,
            daysOfWeekMask = FrictionSchedule.MASK_EVERY_DAY,
            startMinuteOfDay = 22 * 60 + 30,
            endMinuteOfDay = 4 * 60,
            escalationBoost = 1,
            requireTimerPrompt = true,
            nudgeIntervalMin = 10,
        ),
    )

    private fun launchIo(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            graph.awaitReady()
            withContext(Dispatchers.IO) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "friction update failed", e)
            _errorMessage.value = e.message ?: "Could not save change."
        }
    }

    private companion object {
        const val TAG = "LagrangeFrictionUi"
    }
}

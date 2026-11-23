package com.celestiallemon.lagrange.ui.friction

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.celestiallemon.lagrange.data.UserPrefs
import com.celestiallemon.lagrange.data.db.FrictionScheduleEntity
import com.celestiallemon.lagrange.domain.friction.FrictionLiveStatus
import com.celestiallemon.lagrange.domain.friction.FrictionSchedule
import com.celestiallemon.lagrange.friction.LagrangeAccessibilityService
import com.celestiallemon.lagrange.ui.afterReady
import com.celestiallemon.lagrange.ui.graph
import com.celestiallemon.lagrange.ui.minuteTickFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    val prefs: StateFlow<UserPrefs?> = graph.afterReady { preferences.prefs }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val schedules: StateFlow<List<FrictionScheduleEntity>> = graph.afterReady { friction.observeSchedules() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val liveStatus: StateFlow<FrictionLiveStatus> = graph.afterReady { friction.liveStatus }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FrictionLiveStatus())

    val blockedToday: StateFlow<Int> = graph.afterReady {
        combine(preferences.prefs.map { it.rolloverHour }.distinctUntilChanged(), minuteTickFlow()) { _, now ->
            withContext(Dispatchers.IO) { derived.dateOf(now).toString() }
        }.distinctUntilChanged().flatMapLatest { date ->
            database.derivedDao().observeBlockedCountOn(date)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setFrictionEnabled(enabled: Boolean) = launchIo { graph.preferences.setFrictionEnabled(enabled) }

    fun setEscalationEnabled(enabled: Boolean) = launchIo { graph.preferences.setEscalationEnabled(enabled) }

    fun setTierPauses(t0: Int, t1: Int, t2: Int, t3: Int) = launchIo {
        graph.preferences.setTierPauseSeconds(t0, t1, t2, t3)
    }

    fun setGraceWindowSec(sec: Int) = launchIo { graph.preferences.setGraceWindowSec(sec) }

    fun setNudgeIntervalMin(min: Int) = launchIo { graph.preferences.setNudgeIntervalMin(min) }

    fun toggleTimerPreset(minutes: Int) = launchIo {
        val current = graph.preferences.current().timerPresetsMin.toMutableSet()
        if (!current.add(minutes)) current.remove(minutes)
        graph.preferences.setTimerPresetsMin(current.toList())
    }

    fun setDailyPromptCap(cap: Int?) = launchIo { graph.preferences.setDailyPromptCap(cap) }

    fun setRedirectPackage(pkg: String?) = launchIo { graph.preferences.setRedirectPackageName(pkg) }

    fun setDailyBudgetMs(budgetMs: Long) = launchIo {
        graph.preferences.setDailyBudgetMs(budgetMs.coerceIn(UserPrefs.BUDGET_RANGE_MS))
    }

    fun activateSnooze1h() = launchIo {
        graph.friction.activateSnooze1h(LagrangeAccessibilityService.connected)
    }

    fun cancelSnooze() = launchIo {
        graph.friction.cancelSnooze(LagrangeAccessibilityService.connected)
    }

    fun activateFocusBoost1h() = launchIo {
        graph.friction.activateFocusBoost1h(LagrangeAccessibilityService.connected)
    }

    fun cancelFocusBoost() = launchIo {
        graph.friction.cancelFocusBoost(LagrangeAccessibilityService.connected)
    }

    fun saveSchedule(schedule: FrictionScheduleEntity) = launchIo {
        if (schedule.id == 0L) graph.friction.createSchedule(schedule)
        else graph.friction.updateSchedule(schedule)
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
        }
    }

    private companion object {
        const val TAG = "LagrangeFrictionUi"
    }
}

package com.celestiallemon.lagrange.ui.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.celestiallemon.lagrange.domain.lab.LabApps
import com.celestiallemon.lagrange.domain.lab.LabSection
import com.celestiallemon.lagrange.domain.lab.LabWindows
import com.celestiallemon.lagrange.ui.components.LagrangeScreen
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.ui.unit.dp
import com.celestiallemon.lagrange.ui.components.Banner
import com.celestiallemon.lagrange.ui.components.Choice
import com.celestiallemon.lagrange.ui.theme.Spacing
import com.celestiallemon.lagrange.ui.theme.Tone
import com.celestiallemon.lagrange.ui.theme.Transitions
import com.celestiallemon.lagrange.ui.components.BarRow
import com.celestiallemon.lagrange.ui.today.WorthCheckInCard
import com.celestiallemon.lagrange.ui.today.SessionDetailSheet

/** App names (and categories) for whatever section is on screen. */
internal val LocalLabApps = compositionLocalOf { LabApps.EMPTY }

/** The rollover hour the loaded data was built with (labels for time-of-day blocks). */
internal val LocalLabRollover = compositionLocalOf { 4 }

/** Each Lab section answers one question; the question is its heading. */
private val LabSection.question: String
    get() = when (this) {
        LabSection.SPIKES -> "Which days were unusual, and why?"
        LabSection.RABBIT_HOLES -> "Where did the long sessions go?"
        LabSection.GATEWAYS -> "Which apps start the spirals?"
        LabSection.ALERTS -> "What do notifications cost?"
        LabSection.RHYTHM -> "When in the week do you reach for it?"
        LabSection.FRICTION -> "Is the pause actually helping?"
    }

/**
 * The Behavior Lab (spec §4.2 C): why the numbers on Today and Trends happen. One window for every section, one
 * section at a time, every figure computed only over recorded time and shown with its sample.
 */
@Composable
fun LabScreen(padding: PaddingValues, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}) {
    val vm: LabViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val feed by vm.rabbitFeed.collectAsStateWithLifecycle()
    val filter by vm.rabbitFilter.collectAsStateWithLifecycle()
    val autopsy by vm.autopsy.collectAsStateWithLifecycle()
    val sheet by vm.sheet.collectAsStateWithLifecycle()
    val checkIn by vm.checkIn.collectAsStateWithLifecycle()
    var heatMetric by rememberSaveable { mutableStateOf(HeatMetric.DISTRACTING) }
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    val open: (String) -> Unit = { key -> openKey = key }
    // The open session survives rotation and process death here; the view model's copy doesn't, so follow this one.
    LaunchedEffect(openKey) { vm.open(openKey) }

    val current = ui.current
    val apps = when (current) {
        is LabData.Spikes -> current.report.apps
        is LabData.RabbitHoles -> current.data.apps
        is LabData.Gateways -> current.data.apps
        is LabData.Alerts -> current.data.apps
        is LabData.Friction -> current.data.apps
        is LabData.Rhythm, null -> LabApps.EMPTY
    }
    val rollover = when (current) {
        is LabData.Spikes -> current.report.scope.rolloverHour
        is LabData.Rhythm -> current.data.scope.rolloverHour
        else -> 4
    }

    CompositionLocalProvider(LocalLabApps provides apps, LocalLabRollover provides rollover) {
        LagrangeScreen(
            title = "Lab",
            contentPadding = padding,
            actions = actions,
            arrivalKey = ui.request.section,
        ) {
            item("windows") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    Choice(LabWindows.entries, ui.request.window, vm::setWindow, { it.kind.label })
                    PrimaryScrollableTabRow(
                        selectedTabIndex = ui.request.section.ordinal,
                        edgePadding = 0.dp,
                        containerColor = MaterialTheme.colorScheme.surface,
                        divider = {},
                    ) {
                        LabSection.entries.forEach { s ->
                            Tab(
                                selected = ui.request.section == s,
                                onClick = { vm.setSection(s) },
                                text = { Text(s.label) },
                            )
                        }
                    }
                    Text(
                        ui.request.section.question,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(top = Spacing.s),
                    )
                }
            }
            item("status") {
                Column {
                    AnimatedVisibility(ui.loading, enter = Transitions.reveal, exit = Transitions.conceal) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (ui.stale) Muted("Showing the earlier results while these load.")
                        }
                    }
                    if (!ui.loading && ui.error != null) {
                        Banner(
                            if (ui.stale) "Couldn't load this selection" else "Couldn't load this section",
                            Tone.Critical,
                            Icons.Outlined.ErrorOutline,
                            body = if (ui.stale) "Below are the earlier results, not these." else null,
                            action = "Retry" to vm::retry,
                        )
                    }
                }
            }
            checkIn?.let { card ->
                item("checkin") {
                    WorthCheckInCard(
                        card,
                        onRate = { vm.rate(card.episode.episodeKey, card.episode.startMs, it) },
                        onDismiss = { vm.dismissCheckIn(card.episode.episodeKey) },
                        onOpen = { open(card.episode.episodeKey) },
                    )
                }
            }
            when (current) {
                null -> Unit
                is LabData.Spikes -> spikesSection(current.report, autopsy, onToggle = { vm.toggleAutopsy(it.date) }, onOpenSession = open)
                is LabData.RabbitHoles -> rabbitHolesSection(
                    feed,
                    current.data.apps,
                    current.data.scope,
                    filter,
                    onFilter = vm::setFilter,
                    onOpen = open,
                    onRate = { key, start, rating -> vm.rate(key, start, rating) },
                )
                is LabData.Gateways -> gatewaysSection(current.data)
                is LabData.Alerts -> alertsSection(current.data, ui.request.sort, vm::setSort)
                is LabData.Rhythm -> rhythmSection(current.data, heatMetric) { heatMetric = it }
                is LabData.Friction -> frictionSection(current.data)
            }
        }
    }

    val s = sheet
    if (openKey != null && s != null && s.episode.session.episodeKey == openKey) {
        SessionDetailSheet(
            episode = s.episode,
            packages = s.packages,
            detail = s.detail,
            onRate = { vm.rate(s.episode.session.episodeKey, s.episode.session.startMs, it) },
            onDismiss = {
                openKey = null
            },
        )
    }
}

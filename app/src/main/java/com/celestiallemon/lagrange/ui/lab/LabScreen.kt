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
import com.celestiallemon.lagrange.ui.theme.LocalStatusColors
import com.celestiallemon.lagrange.ui.theme.Space
import com.celestiallemon.lagrange.ui.today.SessionDetailSheet

/** App names (and categories) for whatever section is on screen. */
internal val LocalLabApps = compositionLocalOf { LabApps.EMPTY }

/** The rollover hour the loaded data was built with (labels for time-of-day blocks). */
internal val LocalLabRollover = compositionLocalOf { 4 }

/**
 * The Behavior Lab (spec §4.2 C): why the numbers on Today and Trends happen. One window for every section, one
 * section at a time, every figure computed only over recorded time and shown with its sample.
 */
@Composable
fun LabScreen(padding: PaddingValues) {
    val vm: LabViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val feed by vm.rabbitFeed.collectAsStateWithLifecycle()
    val filter by vm.rabbitFilter.collectAsStateWithLifecycle()
    val autopsy by vm.autopsy.collectAsStateWithLifecycle()
    val sheet by vm.sheet.collectAsStateWithLifecycle()
    val checkIn by vm.checkIn.collectAsStateWithLifecycle()
    var heatMetric by rememberSaveable { mutableStateOf(HeatMetric.DISTRACTING) }
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    val open: (String) -> Unit = { key ->
        openKey = key
        vm.open(key)
    }

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
            eyebrow = "Behavior Lab",
            title = "Why it happens",
            subtitle = "What pulls you in, what keeps you there, and what actually helps. Only recorded time counts.",
            contentPadding = padding,
        ) {
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
            item("windows") {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        LabWindows.entries.forEach { w ->
                            FilterChip(selected = ui.request.window == w, onClick = { vm.setWindow(w) }, label = { Text(w.kind.label) })
                        }
                    }
                    ChipRow {
                        LabSection.entries.forEach { s ->
                            FilterChip(
                                selected = ui.request.section == s,
                                onClick = { vm.setSection(s) },
                                label = { Text(s.label) },
                                modifier = Modifier.semantics { contentDescription = "${s.label} section" },
                            )
                        }
                    }
                }
            }
            item("status") {
                when {
                    ui.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    ui.error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Couldn't load this section.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalStatusColors.current.bad,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = vm::retry) { Text("Retry") }
                    }
                    ui.stale -> Muted("Showing the previous window while this one loads.", Modifier.padding(horizontal = Space.s))
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
                vm.open(null)
            },
        )
    }
}

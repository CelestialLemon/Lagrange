package com.celestiallemon.lagrange.widget

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.celestiallemon.lagrange.Destination
import com.celestiallemon.lagrange.LagrangeApplication
import com.celestiallemon.lagrange.domain.widget.WidgetState
import com.celestiallemon.lagrange.domain.widget.WidgetStatus
import kotlinx.coroutines.CancellationException

/**
 * "Today at a glance": distracting time against the budget with a progress bar and status, the current phone-free
 * stretch and today's longest, and a friction badge (Focus Boost, snooze, or setup needed). Material You colors via
 * [GlanceTheme]; tapping anywhere opens Lagrange on Today.
 */
class LagrangeWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, TALL, TALL_WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val graph = (context.applicationContext as LagrangeApplication).graph
        val initial = try {
            graph.awaitReady()
            // A launcher-driven update (placement, resize, APPWIDGET_UPDATE) also asks for a fresh computation.
            graph.surfaces.requestRefreshIfOlderThan(60_000L)
            graph.surfaces.current()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("LagrangeWidget", "widget state unavailable", e)
            WidgetState.LOADING.copy(statusLabel = "Open Lagrange")
        }
        provideContent {
            val live by graph.surfaces.state.collectAsState()
            GlanceTheme {
                WidgetContent(live ?: initial, context)
            }
        }
    }

    companion object {
        // Two cells high is a compact layout (no progress bar, the badge as plain text) that fits in the 110 dp
        // minimum; from TALL's height on, the full layout has room for the bar, both stats and the badge chip.
        val SMALL = DpSize(120.dp, 110.dp)
        val WIDE = DpSize(250.dp, 110.dp)
        val TALL = DpSize(120.dp, 170.dp)
        val TALL_WIDE = DpSize(250.dp, 170.dp)
    }
}

class LagrangeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LagrangeWidget()
}

@Composable
private fun WidgetContent(s: WidgetState, context: Context) {
    val size = LocalSize.current
    val wide = size.width >= LagrangeWidget.WIDE.width
    val compact = size.height < LagrangeWidget.TALL.height
    val colors = GlanceTheme.colors
    val accent = when (s.status) {
        WidgetStatus.OVER_BUDGET -> colors.error
        WidgetStatus.NEAR_BUDGET -> colors.tertiary
        WidgetStatus.GRANT_ACCESS, WidgetStatus.COVERAGE_GAP -> colors.secondary
        else -> colors.primary
    }
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(24.dp)
            .background(colors.widgetBackground)
            .padding(if (compact) 12.dp else 14.dp)
            .clickable(actionStartActivity(Destination.TODAY.intent(context)))
            .semantics { contentDescription = "Lagrange today: ${s.usedText} distracting ${s.budgetText}. ${s.statusLabel}." },
        verticalAlignment = Alignment.Top,
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(s.usedText, style = TextStyle(color = colors.onSurface, fontSize = if (compact) 22.sp else 26.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            Spacer(GlanceModifier.width(6.dp))
            // Takes only the width left, ellipsized, so a long "≥ 1h 35m" never pushes the row off a narrow widget.
            Text(s.budgetText, modifier = GlanceModifier.defaultWeight(), style = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
        }
        if (!compact) {
            Spacer(GlanceModifier.height(6.dp))
            LinearProgressIndicator(
                progress = s.progress,
                modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                color = accent,
                backgroundColor = colors.surfaceVariant,
            )
            Spacer(GlanceModifier.height(6.dp))
        }
        Text(s.statusLabel, style = TextStyle(color = accent, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
        Spacer(GlanceModifier.defaultWeight())
        if (compact) {
            // One line each, so the streak and the badge both stay visible two cells high.
            Text(
                "${s.streakLabel} ${s.streakText}",
                style = TextStyle(color = colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            s.badge?.let { badge ->
                Text(badge.text, style = TextStyle(color = colors.secondary, fontSize = 11.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            }
        } else {
            if (wide) {
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    Stat(s.streakLabel, s.streakText, GlanceModifier.defaultWeight())
                    Stat("Longest today", s.longestText, GlanceModifier.defaultWeight())
                }
            } else {
                Stat(s.streakLabel, s.streakText, GlanceModifier)
            }
            s.badge?.let { badge ->
                Spacer(GlanceModifier.height(6.dp))
                Text(
                    badge.text,
                    modifier = GlanceModifier.background(colors.secondaryContainer).cornerRadius(10.dp).padding(horizontal = 8.dp, vertical = 2.dp),
                    style = TextStyle(color = colors.onSecondaryContainer, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: GlanceModifier) {
    val colors = GlanceTheme.colors
    Column(modifier = modifier) {
        Text(label, style = TextStyle(color = colors.onSurfaceVariant, fontSize = 11.sp), maxLines = 1)
        Text(value, style = TextStyle(color = colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium), maxLines = 1)
    }
}

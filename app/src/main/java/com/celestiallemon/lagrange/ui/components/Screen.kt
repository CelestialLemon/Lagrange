package com.celestiallemon.lagrange.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.celestiallemon.lagrange.ui.theme.ProvideScreenArrival
import com.celestiallemon.lagrange.ui.theme.Spacing

/**
 * Every screen in Lagrange: a large title that collapses as you scroll, then one scrolling column.
 *
 * The title is a plain noun ("Today", "Trends"). What the screen *says* belongs in its hero, not its header.
 * [contentPadding] is the padding the shell reserves (the navigation bar); system bar insets are handled here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LagrangeScreen(
    title: String,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    /** When this changes the content counts as newly arrived, so staggered entrances play again (e.g. a new tab). */
    arrivalKey: Any? = Unit,
    /** Gap between items. Dense tools (the inspector) use less. */
    itemSpacing: androidx.compose.ui.unit.Dp = Spacing.s,
    content: LazyListScope.() -> Unit,
) {
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    ProvideScreenArrival(arrivalKey) {
        Scaffold(
            modifier = modifier
                .fillMaxSize()
                .nestedScroll(behavior.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.surface,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                LargeTopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                        }
                    },
                    actions = actions,
                    scrollBehavior = behavior,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
            },
        ) { inner ->
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    start = Spacing.gutter,
                    end = Spacing.gutter,
                    top = inner.calculateTopPadding() + Spacing.xs,
                    bottom = contentPadding.calculateBottomPadding() + Spacing.xxxl,
                ),
                verticalArrangement = Arrangement.spacedBy(itemSpacing),
            ) {
                content()
            }
        }
    }
}

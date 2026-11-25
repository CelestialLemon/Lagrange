package com.celestiallemon.lagrange.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.celestiallemon.lagrange.ui.theme.Spacing

/**
 * Every editor in Lagrange is a bottom sheet with the same anatomy: a serif title, an optional one-line
 * explanation, the control, and Cancel / Save at the bottom within thumb reach.
 * Sheets slide from where the thumb already is and keep the screen behind them visible, so editing a budget never
 * feels like leaving Today.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSheet(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (() -> Unit)?,
    supporting: String? = null,
    confirmLabel: String = "Save",
    confirmEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl)
                .padding(bottom = Spacing.l)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                if (supporting != null) {
                    Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
            if (onConfirm != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.End)) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmLabel) }
                }
            }
        }
    }
}

/** A sheet that only shows information (a session, a day): same anatomy, no buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl)
                .padding(bottom = Spacing.xl)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
            content = content,
        )
    }
}

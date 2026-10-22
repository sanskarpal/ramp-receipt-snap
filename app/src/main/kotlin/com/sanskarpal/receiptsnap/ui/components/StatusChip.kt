package com.sanskarpal.receiptsnap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sanskarpal.receiptsnap.core.model.MatchStatus
import com.sanskarpal.receiptsnap.core.model.ReceiptState
import com.sanskarpal.receiptsnap.ui.theme.Amber
import com.sanskarpal.receiptsnap.ui.theme.Ink
import com.sanskarpal.receiptsnap.ui.theme.Lime
import com.sanskarpal.receiptsnap.ui.theme.Negative

enum class ReceiptBadge(val label: String) { MISSING("Missing"), READING("Reading"), REVIEW("Needs review"), SYNCING("Syncing"), MATCHED("Matched"), NO_CHARGE("No charge"), FAILED("Failed") }

/** One rule for every screen: review trumps sync, sync trumps done. */
fun badgeFor(state: ReceiptState?, match: MatchStatus?): ReceiptBadge = when {
    state == null -> ReceiptBadge.MISSING
    state == ReceiptState.FAILED_PERMANENT -> ReceiptBadge.FAILED
    state in setOf(ReceiptState.CAPTURED, ReceiptState.EXTRACTING, ReceiptState.EXTRACTED) -> ReceiptBadge.READING
    match == MatchStatus.SUGGESTED || state == ReceiptState.NEEDS_REVIEW -> ReceiptBadge.REVIEW
    state in setOf(ReceiptState.UPLOAD_PENDING, ReceiptState.UPLOADING, ReceiptState.MATCHED, ReceiptState.UNMATCHED) -> ReceiptBadge.SYNCING
    match == MatchStatus.NONE || match == MatchStatus.REJECTED -> ReceiptBadge.NO_CHARGE
    else -> ReceiptBadge.MATCHED
}

@Composable
fun StatusChip(badge: ReceiptBadge, modifier: Modifier = Modifier) {
    val (bg, fg) = when (badge) {
        ReceiptBadge.MISSING, ReceiptBadge.NO_CHARGE -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        ReceiptBadge.READING, ReceiptBadge.SYNCING -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        ReceiptBadge.REVIEW -> Amber.copy(alpha = 0.15f) to Amber
        ReceiptBadge.MATCHED -> Lime to Ink
        ReceiptBadge.FAILED -> Negative.copy(alpha = 0.12f) to Negative
    }
    Text(
        badge.label,
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        modifier = modifier
            .semantics { contentDescription = "Receipt status: ${badge.label}" }
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun Pill(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = fg, modifier = modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp))
}

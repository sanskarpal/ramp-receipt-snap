package com.sanskarpal.receiptsnap.ui.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.sanskarpal.receiptsnap.core.data.repo.ReceiptRepository
import com.sanskarpal.receiptsnap.core.data.repo.ReviewState
import com.sanskarpal.receiptsnap.core.model.Field
import com.sanskarpal.receiptsnap.core.model.MatchStatus
import com.sanskarpal.receiptsnap.core.model.ReceiptState
import com.sanskarpal.receiptsnap.ui.components.StatusChip
import com.sanskarpal.receiptsnap.ui.components.badgeFor
import com.sanskarpal.receiptsnap.ui.components.money
import com.sanskarpal.receiptsnap.ui.components.parseMoneyInput
import com.sanskarpal.receiptsnap.ui.components.shortDate
import com.sanskarpal.receiptsnap.ui.theme.Amber
import com.sanskarpal.receiptsnap.ui.theme.Ink
import com.sanskarpal.receiptsnap.ui.theme.Lime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class ReviewViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val receipts: ReceiptRepository,
) : ViewModel() {
    val id: String = checkNotNull(savedState["id"])
    val state = receipts.observeReview(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun save(merchant: String, total: String, date: String) = viewModelScope.launch {
        receipts.updateFields(id, merchant.ifBlank { null }, parseMoneyInput(total), runCatching { LocalDate.parse(date.trim()) }.getOrNull())
    }

    fun confirm(transactionId: String?) = viewModelScope.launch { receipts.confirmMatch(id, transactionId) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(onBack: () -> Unit, vm: ReviewViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receipt") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { state?.let { StatusChip(badgeFor(it.receipt.state, it.match?.status), Modifier.padding(end = 12.dp)) } },
            )
        },
    ) { padding ->
        val s = state
        if (s == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AsyncImage(
                        model = (s.receipt.localImagePath ?: s.receipt.thumbnailPath)?.let(::File),
                        contentDescription = "Receipt photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(110.dp).height(150.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
                    )
                    SyncLine(s)
                }
                if (s.receipt.state in setOf(ReceiptState.CAPTURED, ReceiptState.EXTRACTING)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Reading the receipt on this phone…")
                    }
                } else {
                    Fields(s, onSave = vm::save)
                    MatchCard(s, onConfirm = vm::confirm)
                }
            }
        }
    }
}

@Composable
private fun SyncLine(s: ReviewState) {
    val (icon, text) = when (s.receipt.state) {
        ReceiptState.SYNCED -> Icons.Filled.CloudDone to "Uploaded to the server"
        ReceiptState.FAILED_PERMANENT -> Icons.Filled.CloudUpload to "Upload failed: ${s.receipt.failureReason}"
        else -> Icons.Filled.CloudUpload to if (s.receipt.attemptCount > 0) "Waiting to upload (attempt ${s.receipt.attemptCount + 1}). Safe offline." else "Saved on this phone. Uploads when online."
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        s.receipt.failureReason?.takeIf { s.receipt.state == ReceiptState.NEEDS_REVIEW }?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Amber, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Fields(s: ReviewState, onSave: (String, String, String) -> Unit) {
    val e = s.extraction
    var merchant by rememberSaveable(e) { mutableStateOf(e?.merchant.orEmpty()) }
    var total by rememberSaveable(e) { mutableStateOf(e?.totalCents?.let { "%.2f".format(it / 100.0) }.orEmpty()) }
    var date by rememberSaveable(e) { mutableStateOf(e?.date?.toString().orEmpty()) }
    val dirty = merchant != e?.merchant.orEmpty() || total != e?.totalCents?.let { "%.2f".format(it / 100.0) }.orEmpty() || date != e?.date?.toString().orEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (e?.userEdited == true) "Edited by you" else "Read on device", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ConfidenceField("Merchant", merchant, { merchant = it }, e?.merchantConfidence, e?.userEdited == true)
        ConfidenceField("Total", total, { total = it }, e?.totalConfidence, e?.userEdited == true, KeyboardType.Decimal)
        ConfidenceField("Date (YYYY-MM-DD)", date, { date = it }, e?.dateConfidence, e?.userEdited == true)
        if (dirty) {
            Button(onClick = { onSave(merchant, total, date) }) { Text("Save and re-match") }
        }
    }
}

@Composable
private fun ConfidenceField(label: String, value: String, onChange: (String) -> Unit, confidence: Double?, edited: Boolean, keyboard: KeyboardType = KeyboardType.Text) {
    val low = !edited && (confidence == null || confidence < Field.LOW_CONFIDENCE)
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        supportingText = { if (low) Text("Check this: low confidence", color = Amber) },
        colors = if (low) OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Amber, focusedBorderColor = Amber) else OutlinedTextFieldDefaults.colors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MatchCard(s: ReviewState, onConfirm: (String?) -> Unit) {
    val m = s.match
    val status = m?.status ?: MatchStatus.NONE
    val matched = s.candidates.firstOrNull { it.second.id == m?.transactionId }
    // only a real suggestion is preselected; weak candidates are offered, never assumed
    var choice by remember(m) { mutableStateOf(if (status == MatchStatus.SUGGESTED) m?.transactionId else null) }
    var picking by remember(m) { mutableStateOf(status == MatchStatus.SUGGESTED) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                when (status) {
                    MatchStatus.AUTO -> "Matched automatically"
                    MatchStatus.CONFIRMED -> "Matched"
                    MatchStatus.SUGGESTED -> "Which charge is this?"
                    MatchStatus.REJECTED -> "Kept without a charge"
                    MatchStatus.NONE -> "No matching charge found"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (!picking && matched != null && (status == MatchStatus.AUTO || status == MatchStatus.CONFIRMED)) {
                Row(
                    Modifier.fillMaxWidth().background(Lime.copy(alpha = 0.35f), RoundedCornerShape(10.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Ink)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(matched.second.merchantDescriptor, fontWeight = FontWeight.Medium)
                        Text("${money(matched.second.amountCents)} · ${shortDate(matched.second.authorizedAt)}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("${(matched.first.score * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                }
                ScoreBreakdown(matched.first)
                TextButton(onClick = { picking = true }) { Text("Not this one?") }
            } else if (s.candidates.isNotEmpty() && picking) {
                s.candidates.forEach { (c, tx) ->
                    Row(Modifier.fillMaxWidth().clickable { choice = tx.id }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = choice == tx.id, onClick = { choice = tx.id })
                        Column(Modifier.weight(1f)) {
                            Text(tx.merchantDescriptor, fontWeight = FontWeight.Medium)
                            Text("${money(tx.amountCents)} · ${shortDate(tx.authorizedAt)} · ${(c.score * 100).toInt()}% match", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onConfirm(choice) }, enabled = choice != null,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                    ) { Text("Confirm match") }
                    TextButton(onClick = { onConfirm(null) }) { Text("None of these") }
                }
            } else {
                Text("No card charge within a day before or five days after this receipt fits. It's saved and uploads either way.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.candidates.isNotEmpty()) TextButton(onClick = { picking = true }) { Text("Pick a charge manually") }
            }
        }
    }
}

@Composable
private fun ScoreBreakdown(c: com.sanskarpal.receiptsnap.core.model.MatchCandidate) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("Amount" to c.amountScore, "Merchant" to c.merchantScore, "Date" to c.dateScore).forEach { (label, v) ->
            Text("$label ${(v * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

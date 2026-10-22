package com.sanskarpal.receiptsnap.ui.approvals

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sanskarpal.receiptsnap.core.data.db.ApprovalRequestEntity
import com.sanskarpal.receiptsnap.core.data.repo.ApprovalRepository
import com.sanskarpal.receiptsnap.core.model.ApprovalStatus
import com.sanskarpal.receiptsnap.core.model.Decision
import com.sanskarpal.receiptsnap.ui.components.Pill
import com.sanskarpal.receiptsnap.ui.components.money
import com.sanskarpal.receiptsnap.ui.theme.Amber
import com.sanskarpal.receiptsnap.ui.theme.Ink
import com.sanskarpal.receiptsnap.ui.theme.Lime
import com.sanskarpal.receiptsnap.ui.theme.Negative
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ApprovalsViewModel @Inject constructor(private val repo: ApprovalRepository) : ViewModel() {
    val approvals = repo.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val refreshing = MutableStateFlow(false)

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        refreshing.value = true
        runCatching { repo.refresh() }
        refreshing.value = false
    }

    fun decide(id: String, decision: Decision) = viewModelScope.launch { repo.decide(id, decision) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalsScreen(vm: ApprovalsViewModel = hiltViewModel()) {
    val approvals by vm.approvals.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("Approvals", style = MaterialTheme.typography.headlineMedium) }) }) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.padding(padding)) {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                if (approvals.isEmpty()) item { Text("No approval requests.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(approvals, key = { it.id }) { a -> ApprovalCard(a, onDecide = { vm.decide(a.id, it) }) }
            }
        }
    }
}

@Composable
private fun ApprovalCard(a: ApprovalRequestEntity, onDecide: (Decision) -> Unit) {
    val decided = a.localDecision != null || a.status != ApprovalStatus.PENDING
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.merchant, style = MaterialTheme.typography.titleMedium)
                    Text("${a.requesterName} · ${a.memo}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(money(a.amountCents), style = MaterialTheme.typography.titleLarge)
            }
            a.conflictMessage?.let { Text(it, color = Amber, style = MaterialTheme.typography.bodyMedium) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    a.localDecision != null -> {
                        val approved = a.localDecision == Decision.APPROVE
                        Pill(if (approved) "Approved" else "Denied", if (approved) Lime else Negative.copy(alpha = 0.12f), if (approved) Ink else Negative)
                        Spacer(Modifier.width(8.dp))
                        Pill("Pending sync", Amber.copy(alpha = 0.15f), Amber)
                    }
                    a.status == ApprovalStatus.APPROVED -> Pill("Approved", Lime, Ink)
                    a.status == ApprovalStatus.DENIED -> Pill("Denied", Negative.copy(alpha = 0.12f), Negative)
                }
            }
            if (!decided) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onDecide(Decision.APPROVE) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                    ) { Text("Approve") }
                    OutlinedButton(onClick = { onDecide(Decision.DENY) }) { Text("Deny") }
                }
            }
        }
    }
}

package com.sanskarpal.receiptsnap.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sanskarpal.receiptsnap.core.data.db.TransactionRow
import com.sanskarpal.receiptsnap.core.data.repo.TransactionRepository
import com.sanskarpal.receiptsnap.ui.components.StatusChip
import com.sanskarpal.receiptsnap.ui.components.badgeFor
import com.sanskarpal.receiptsnap.ui.components.money
import com.sanskarpal.receiptsnap.ui.components.shortDate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TransactionsViewModel @Inject constructor(private val repo: TransactionRepository) : ViewModel() {
    val rows = repo.observeRows().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val refreshing = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        refreshing.value = true
        error.value = runCatching { repo.refresh() }.exceptionOrNull()?.let { "Offline: showing saved transactions" }
        refreshing.value = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(onSnap: () -> Unit, onOpenReceipt: (String) -> Unit, vm: TransactionsViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val error by vm.error.collectAsState()
    var missingOnly by remember { mutableStateOf(false) }
    val missing = rows.count { it.receiptId == null }
    val shown = if (missingOnly) rows.filter { it.receiptId == null } else rows

    Scaffold(
        topBar = { TopAppBar(title = { Text("Transactions", style = MaterialTheme.typography.headlineMedium) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onSnap,
                icon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                text = { Text("Snap receipt") },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                item {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !missingOnly, onClick = { missingOnly = false }, label = { Text("All ${rows.size}") })
                        FilterChip(selected = missingOnly, onClick = { missingOnly = true }, label = { Text("Missing receipts $missing") })
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp)) }
                }
                if (shown.isEmpty()) {
                    item {
                        Text(
                            if (rows.isEmpty()) "No transactions yet. Pull to refresh once the mock server is running." else "Every transaction has a receipt.",
                            modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(shown, key = { it.id }) { row ->
                    TransactionItem(row, onClick = { row.receiptId?.let(onOpenReceipt) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun TransactionItem(row: TransactionRow, onClick: () -> Unit) {
    val badge = badgeFor(row.receiptState, row.matchStatus)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = row.receiptId != null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.merchantDescriptor, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text("${shortDate(row.authorizedAt)} · •••• ${row.cardLast4}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(money(row.amountCents), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            StatusChip(badge)
        }
    }
}

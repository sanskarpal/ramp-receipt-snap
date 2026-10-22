package com.sanskarpal.receiptsnap.ui.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sanskarpal.receiptsnap.core.data.db.OutboxOperationEntity
import com.sanskarpal.receiptsnap.core.data.repo.SyncStatusRepository
import com.sanskarpal.receiptsnap.core.model.OutboxState
import com.sanskarpal.receiptsnap.ui.components.Pill
import com.sanskarpal.receiptsnap.ui.theme.Amber
import com.sanskarpal.receiptsnap.ui.theme.Ink
import com.sanskarpal.receiptsnap.ui.theme.Lime
import com.sanskarpal.receiptsnap.ui.theme.Negative
import com.sanskarpal.receiptsnap.ui.theme.Positive
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val repo: SyncStatusRepository,
    @ApplicationContext context: Context,
) : ViewModel() {
    val outbox = repo.observeOutbox().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pending = repo.observePendingCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val online = connectivity(context).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun syncNow() = repo.syncNow()
    fun retryFailed() = viewModelScope.launch { repo.retryFailed() }

    private fun connectivity(context: Context) = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        fun hasInternet(caps: NetworkCapabilities?) = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        trySend(hasInternet(cm.getNetworkCapabilities(cm.activeNetwork)))
        // the default-network callback reports the network the app actually uses; onLost means it's gone
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { trySend(hasInternet(caps)) }
            override fun onLost(network: Network) { trySend(false) }
        }
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }
}

private val time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(vm: SyncViewModel = hiltViewModel()) {
    val outbox by vm.outbox.collectAsState()
    val pending by vm.pending.collectAsState()
    val online by vm.online.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("Sync", style = MaterialTheme.typography.headlineMedium) }) }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(if (online) Positive else Negative, CircleShape).semantics { contentDescription = if (online) "Online" else "Offline" })
                        Spacer(Modifier.width(8.dp))
                        Text(if (online) "Online" else "Offline: changes are saved and queued", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        if (pending == 0) "Everything is synced." else "$pending change${if (pending == 1) "" else "s"} waiting. Each keeps its idempotency key, so retries never duplicate.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = vm::syncNow,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                        ) { Text("Sync now") }
                        if (outbox.any { it.state == OutboxState.FAILED_PERMANENT }) OutlinedButton(onClick = { vm.retryFailed() }) { Text("Retry failed") }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (outbox.isEmpty()) item { Text("No changes yet. Snap a receipt or decide an approval.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(outbox, key = { it.id }) { op ->
                OutboxRow(op)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun OutboxRow(op: OutboxOperationEntity) {
    val (label, bg, fg) = when (op.state) {
        OutboxState.PENDING -> Triple(if (op.attemptCount > 0) "Retrying" else "Queued", Amber.copy(alpha = 0.15f), Amber)
        OutboxState.IN_FLIGHT -> Triple("Sending", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        OutboxState.DONE -> Triple("Done", Lime, Ink)
        OutboxState.FAILED_PERMANENT -> Triple("Failed", Negative.copy(alpha = 0.12f), Negative)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(op.type.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
            Text(
                "${time.format(op.createdAt)} · key ${op.id.take(8)} · ${op.attemptCount} ${if (op.attemptCount == 1) "retry" else "retries"}",
                style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            op.lastError?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = if (op.state == OutboxState.FAILED_PERMANENT) Negative else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Pill(label, bg, fg)
    }
}

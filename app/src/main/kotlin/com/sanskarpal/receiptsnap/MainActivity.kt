package com.sanskarpal.receiptsnap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sanskarpal.receiptsnap.ui.approvals.ApprovalsScreen
import com.sanskarpal.receiptsnap.ui.capture.CaptureScreen
import com.sanskarpal.receiptsnap.ui.review.ReviewScreen
import com.sanskarpal.receiptsnap.ui.sync.SyncScreen
import com.sanskarpal.receiptsnap.ui.sync.SyncViewModel
import com.sanskarpal.receiptsnap.ui.theme.ReceiptSnapTheme
import com.sanskarpal.receiptsnap.ui.transactions.TransactionsScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ReceiptSnapTheme { AppNav() } }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("transactions", "Transactions", Icons.AutoMirrored.Filled.ReceiptLong),
    Tab("approvals", "Approvals", Icons.Filled.TaskAlt),
    Tab("sync", "Sync", Icons.Filled.CloudSync),
)

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val syncVm: SyncViewModel = hiltViewModel()
    val pending by syncVm.pending.collectAsState()

    Scaffold(
        bottomBar = {
            if (TABS.any { it.route == route }) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                BadgedBox(badge = { if (tab.route == "sync" && pending > 0) Badge { Text("$pending") } }) { Icon(tab.icon, contentDescription = null) }
                            },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "transactions", modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
            composable("transactions") { TransactionsScreen(onSnap = { nav.navigate("capture") }, onOpenReceipt = { nav.navigate("review/$it") }) }
            composable("approvals") { ApprovalsScreen() }
            composable("sync") { SyncScreen() }
            composable("capture") {
                CaptureScreen(onBack = { nav.popBackStack() }, onCaptured = { id -> nav.navigate("review/$id") { popUpTo("capture") { inclusive = true } } })
            }
            composable("review/{id}") { ReviewScreen(onBack = { nav.popBackStack() }) }
        }
    }
}

package com.uniomo.wafce.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.uniomo.wafce.data.store.AccountProfile
import com.uniomo.wafce.ui.screens.AccountsScreen
import com.uniomo.wafce.ui.screens.DashboardScreen
import com.uniomo.wafce.ui.screens.LoginScreen
import com.uniomo.wafce.ui.screens.LogsScreen
import com.uniomo.wafce.ui.screens.ProtectScreen
import com.uniomo.wafce.ui.screens.SiteDetailScreen
import com.uniomo.wafce.ui.screens.SitesScreen
import com.uniomo.wafce.ui.screens.SystemScreen
import com.uniomo.wafce.ui.theme.SafeLineTheme
import com.uniomo.wafce.vm.AppViewModel
import com.uniomo.wafce.vm.SessionState

object Route {
    const val Dashboard = "dashboard"
    const val Sites = "sites"
    const val Protect = "protect"
    const val Logs = "logs"
    const val System = "system"
    const val SiteDetail = "site/{id}"
}

private data class NavItem(val route: String, val label: String, val icon: ImageVector)

private val navItems = listOf(
    NavItem(Route.Dashboard, "概览", Icons.Default.Dashboard),
    NavItem(Route.Sites, "站点", Icons.Default.Dns),
    NavItem(Route.Protect, "防护", Icons.Default.Security),
    NavItem(Route.Logs, "日志", Icons.Default.Article),
    NavItem(Route.System, "系统", Icons.Default.Settings)
)

@Composable
fun AppRoot(vm: AppViewModel) {
    SafeLineTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LaunchedEffect(Unit) {
                vm.reload()
                vm.ensureCurrentSession()
            }
            when {
                vm.current == null -> AccountsScreen(vm, firstLaunch = true)
                vm.sessionState != SessionState.AUTHENTICATED -> LoginScreen(vm, vm.current!!)
                else -> MainScaffold(vm, vm.current!!)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(vm: AppViewModel, account: AccountProfile) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    var sheetOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text(
                            titleOf(currentRoute),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "${account.name.ifBlank { account.username }} · ${shortUrl(account.baseUrl)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { sheetOpen = true }) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = "切换账号")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.reload(); vm.ensureCurrentSession() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                navItems.forEach { item ->
                    NavigationBarItem(
                        selected = currentRoute == item.route,
                        onClick = {
                            nav.navigate(item.route) {
                                popUpTo(Route.Dashboard) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(item.icon, contentDescription = item.label, modifier = Modifier.size(22.dp)) },
                        label = { Text(item.label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Route.Dashboard,
            modifier = Modifier.padding(padding)
        ) {
            composable(Route.Dashboard) { DashboardScreen(account) }
            composable(Route.Sites) { SitesScreen(account, nav) }
            composable(Route.Protect) { ProtectScreen(account) }
            composable(Route.Logs) { LogsScreen(account) }
            composable(Route.System) { SystemScreen(account, vm) }
            composable(
                route = Route.SiteDetail,
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { entry ->
                SiteDetailScreen(account, entry.arguments?.getLong("id") ?: 0L) { nav.popBackStack() }
            }
        }
    }

    if (sheetOpen) {
        AccountSwitcherSheet(
            vm = vm,
            onDismiss = { sheetOpen = false },
            onManage = { sheetOpen = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountSwitcherSheet(vm: AppViewModel, onDismiss: () -> Unit, onManage: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("切换 WAF 实例", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            vm.accounts.forEach { acc ->
                val selected = acc.id == vm.current?.id
                Surface(
                    onClick = { vm.switchTo(acc.id); onDismiss() },
                    shape = com.uniomo.wafce.ui.components.CardShape,
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                acc.name.ifBlank { acc.username },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            )
                            Text(
                                acc.baseUrl,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (selected) {
                            Text(
                                "当前",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                androidx.compose.material3.TextButton(onClick = { vm.logout(); onDismiss() }) {
                    Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("退出登录")
                }
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = { vm.beginManageAccounts(); onDismiss() }) {
                    Text("管理实例")
                }
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    }
}

private fun titleOf(route: String?): String = when (route) {
    Route.Sites -> "防护站点"
    Route.Protect -> "防护配置"
    Route.Logs -> "日志中心"
    Route.System -> "系统管理"
    Route.SiteDetail -> "站点详情"
    else -> "总览"
}

private fun shortUrl(url: String): String =
    url.removePrefix("https://").removePrefix("http://").trimEnd('/')

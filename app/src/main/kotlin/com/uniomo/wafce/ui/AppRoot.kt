package com.uniomo.wafce.ui

import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.lifecycle.viewmodel.compose.viewModel
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.uniomo.wafce.data.store.AccountProfile
import com.uniomo.wafce.ui.screens.AboutScreen
import com.uniomo.wafce.ui.screens.AccountsScreen
import com.uniomo.wafce.ui.screens.DashTarget
import com.uniomo.wafce.ui.screens.DashboardScreen
import com.uniomo.wafce.ui.screens.LoginScreen
import com.uniomo.wafce.ui.screens.LogsScreen
import com.uniomo.wafce.ui.screens.ProtectScreen
import com.uniomo.wafce.ui.screens.SiteDetailScreen
import com.uniomo.wafce.ui.screens.SitesScreen
import com.uniomo.wafce.ui.screens.SystemScreen
import com.uniomo.wafce.ui.theme.SafeLineTheme
import com.uniomo.wafce.ui.components.UpdateDialog
import com.uniomo.wafce.vm.AppViewModel
import com.uniomo.wafce.vm.SessionState
import com.uniomo.wafce.vm.UpdateVm

object Route {
    const val Dashboard = "dashboard"
    const val Sites = "sites"
    const val Protect = "protect"
    const val Logs = "logs"
    const val System = "system"
    const val About = "about"
    const val SiteDetail = "site/{id}"

    /** 携带跳转上下文的路由变体，参数在 composable 上解析 */
    const val LogsAt = "logs?tab={tab}"
    const val ProtectAt = "protect?tab={tab}"

    fun logsAt(tab: Int) = "logs?tab=$tab"
    fun protectAt(tab: String) = "protect?tab=$tab"

    const val ARG_TAB = "tab"
}

private data class NavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    /** 带参数的等价路由，用于高亮匹配（logs?tab=1 也应点亮「日志」） */
    val aliases: List<String> = emptyList()
)

private val navItems = listOf(
    NavItem(Route.Dashboard, "概览", Icons.Default.Dashboard),
    NavItem(Route.Sites, "站点", Icons.Default.Dns),
    NavItem(Route.Protect, "防护", Icons.Default.Security, listOf(Route.ProtectAt)),
    NavItem(Route.Logs, "日志", Icons.Default.Article, listOf(Route.LogsAt)),
    NavItem(Route.System, "系统", Icons.Default.Settings)
)

/**
 * 切换主页面。带参数的路由（如 logs?tab=1）也要先 pop 回顶层再切，
 * 否则底栏会不断堆叠同名页面，返回键需要按很多次。
 */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun AppRoot(vm: AppViewModel) {
    SafeLineTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LaunchedEffect(Unit) {
                vm.reload()
                vm.ensureCurrentSession()
            }
            val cur = vm.current
            when {
                cur == null -> AccountsScreen(vm, firstLaunch = true)
                // UNKNOWN 表示「尚未校验完成」，不能当登录态处理，
                // 否则每次冷启动都会先闪一下登录页
                vm.sessionState == SessionState.UNKNOWN -> SessionChecking()
                vm.sessionState != SessionState.AUTHENTICATED ->
                    LoginScreen(vm, cur, onCancel = { vm.beginManageAccounts() })
                else -> MainScaffold(vm, cur)
            }
        }
    }
}

@Composable
private fun SessionChecking() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            strokeWidth = 2.5.dp
        )
    }
}

/**
 * @param sessionKey 参与 NavController 的 key。
 *   rememberNavController 内部是 rememberSaveable，会写进 SavedStateRegistry；
 *   退出登录后 MainScaffold 离开组合、重新登录时同一 slot 可能被还原成
 *   上一个账号的深栈（返回键能回到已登录页面）。用会话序号做 key 强制重建。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(vm: AppViewModel, account: AccountProfile, sessionKey: Long = vm.sessionEpoch) {
    key(sessionKey) {
        MainScaffoldInner(vm, account)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffoldInner(vm: AppViewModel, account: AccountProfile) {
    val nav = rememberNavController()
    val updateVm: UpdateVm = viewModel()

    // 系统安装界面是独立 Activity，用户在其中点「取消」时我们拿不到任何回调，
    // 只能靠本App 重新回到前台（ON_RESUME）把Installing 状态收回来。
    // 否则「检查更新」按钮会被进度圈永久顶替，只能杀进程恢复。
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                updateVm.onInstallReturned()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
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
                }
                // 顶栏右侧刻意留空：更新与刷新入口已下沉到各页面自身
                // （检查更新 → 系统 → 关于，手动刷新 → 各页顶部）
            )
        },
        bottomBar = {
            NavigationBar {
                navItems.forEach { item ->
                    // 带参路由（logs?tab={tab}）下 Nav Compose 返回的是**路由模板**而非
                    // 填充后的路径，所以比较前必须把两边都截到「?」之前。
                    // 早期版本只截 alias 一侧，导致从概览页跳日志/防护时底栏整条不亮。
                    val base = currentRoute?.substringBefore("?")
                    val selected = base != null && (
                        base == item.route ||
                            item.aliases.any { it.substringBefore("?") == base }
                        )
                    NavigationBarItem(
                        selected = selected,
                        onClick = { nav.navigateToTab(item.route) },
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
            composable(Route.Dashboard) {
                DashboardScreen(account) { target ->
                    when (target) {
                        is DashTarget.Page -> nav.navigateToTab(target.route)
                        is DashTarget.LogTab -> nav.navigateToTab(Route.logsAt(target.index))
                        is DashTarget.ProtectTab -> nav.navigateToTab(Route.protectAt(target.key))
                    }
                }
            }
            composable(Route.Sites) { SitesScreen(account, nav) }
            composable(Route.Protect) { ProtectScreen(account) }
            composable(Route.Logs) { LogsScreen(account) }
            composable(Route.System) { SystemScreen(account, vm, updateVm) }
            composable(Route.About) {
                AboutScreen(
                    account = account,
                    appVm = vm,
                    onBack = { nav.popBackStack() },
                    updateVm = updateVm
                )
            }
            composable(
                route = Route.LogsAt,
                arguments = listOf(navArgument(Route.ARG_TAB) {
                    type = NavType.IntType
                    defaultValue = 0
                })
            ) { entry ->
                LogsScreen(account, entry.arguments?.getInt(Route.ARG_TAB) ?: 0)
            }
            composable(
                route = Route.ProtectAt,
                arguments = listOf(navArgument(Route.ARG_TAB) {
                    type = NavType.StringType
                    defaultValue = ""
                })
            ) { entry ->
                ProtectScreen(account, entry.arguments?.getString(Route.ARG_TAB)?.ifBlank { null })
            }
            composable(
                route = Route.SiteDetail,
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { entry ->
                SiteDetailScreen(account, entry.arguments?.getLong("id") ?: 0L) { nav.popBackStack() }
            }
        }
    }

    UpdateDialog(
        state = updateVm.state,
        onDismiss = { updateVm.dismiss() },
        onInstall = { updateVm.install() },
        onRetry = { updateVm.check() }
    )

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

/**
 * 顶栏标题。
 *
 * 必须先截掉 `?tab={tab}` 再匹配：带参路由下 destination.route 返回的是模板串，
 * 直接用 when(route) 全落进 else，从概览跳日志页时标题会显示成「总览」。
 */
private fun titleOf(route: String?): String {
    val base = route?.substringBefore("?")
    return when (base) {
        Route.Dashboard -> "总览"
        Route.Sites -> "防护站点"
        Route.Protect -> "防护配置"
        Route.Logs -> "日志中心"
        Route.System -> "系统管理"
        Route.About -> "关于"
        Route.SiteDetail -> "站点详情"
        else -> "总览"
    }
}

private fun shortUrl(url: String): String =
    url.removePrefix("https://").removePrefix("http://").trimEnd('/')

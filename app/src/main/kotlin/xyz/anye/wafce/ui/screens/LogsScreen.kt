package xyz.anye.wafce.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import xyz.anye.wafce.core.Resp
import xyz.anye.wafce.core.readable
import xyz.anye.wafce.data.net.ApiClient
import xyz.anye.wafce.data.store.AccountProfile
import xyz.anye.wafce.ui.components.AsyncBox
import xyz.anye.wafce.ui.components.CardShape
import xyz.anye.wafce.ui.components.EmptyBox
import xyz.anye.wafce.ui.components.SectionCard
import xyz.anye.wafce.ui.components.TagChip
import xyz.anye.wafce.ui.theme.Brand
import xyz.anye.wafce.ui.theme.Danger
import xyz.anye.wafce.ui.theme.InfoColor
import xyz.anye.wafce.ui.theme.Ok
import xyz.anye.wafce.ui.theme.Warn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LOG_TABS = listOf("攻击事件", "拦截记录", "人机验证", "身份认证")

data class LogEntry(
    val id: String,
    val title: String,
    val subtitle: String,
    val attrLine: String,
    val raw: JsonObject
)

enum class LogKind { EVENT, ACL, CHALLENGE, AUTH }

class LogsVm : ViewModel() {

    var tab by mutableIntStateOf(0)
    var page by mutableIntStateOf(1)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var entries by mutableStateOf(listOf<LogEntry>())
        private set

    private val _detail = MutableStateFlow<JsonObject?>(null)
    val detail: StateFlow<JsonObject?> = _detail

    /** 分页结果缓存：切回已看过的页时立即出内容，避免每次都转圈 */
    private val cache = java.util.concurrent.ConcurrentHashMap<Pair<Int, Int>, List<LogEntry>>()

    fun load(account: AccountProfile) {
        val key = tab to page
        cache[key]?.let { cached ->
            entries = cached
            refreshing = false
            error = null
        }
        viewModelScope.launch {
            refreshing = true
            error = null
            try {
                val api = ApiClient.apiFor(account)
                val q = mapOf("page" to page.toString(), "page_size" to "20")
                val body = when (kind()) {
                    LogKind.EVENT -> api.events(q)
                    LogKind.ACL -> api.recordsAcl(q)
                    LogKind.CHALLENGE -> api.recordsChallenge(q)
                    LogKind.AUTH -> api.recordsAuthDefense(q)
                }
                val raw = Resp.obj(body)
                entries = Resp.nodes(raw).ifEmpty { Resp.arr(body) }.map { buildEntry(it) }
                cache[tab to page] = entries
            } catch (e: Exception) {
                error = e.readable()
                entries = emptyList()
            }
            refreshing = false
        }
    }

    fun setTab(account: AccountProfile, index: Int) {
        if (index == tab) return
        tab = index
        page = 1
        load(account)
    }

    fun next(account: AccountProfile) { page += 1; load(account) }
    fun prev(account: AccountProfile) { if (page > 1) page -= 1; load(account) }

    fun openDetail(o: JsonObject) { _detail.value = o }
    fun closeDetail() { _detail.value = null }

    private fun kind(): LogKind = when (tab) {
        0 -> LogKind.EVENT
        1 -> LogKind.ACL
        2 -> LogKind.CHALLENGE
        else -> LogKind.AUTH
    }

    private fun buildEntry(o: JsonObject): LogEntry {
        val ip = Resp.str(o, "src_ip", "ip", "client_ip", "remote_addr", "source_ip")
        val host = Resp.str(o, "host", "server_name", "domain", "dst_host", "server")
        val url = Resp.str(o, "url", "uri", "path", "request_path", "target")
        val rule = Resp.str(o, "rule_id", "attack_type", "type", "category", "task_name")
        val hostPair = listOf(host, url).firstOrNull { it.isNotBlank() }
        val subtitleParts = mutableListOf<String>()
        if (ip.isNotBlank()) subtitleParts.add("来源 IP $ip")
        val method = Resp.str(o, "method", "http_method")
        if (method.isNotBlank()) subtitleParts.add(method)
        val status = Resp.l(o, "status_code", "status", "code")
        if (status > 0) subtitleParts.add("HTTP $status")
        val region = Resp.str(o, "province", "region", "country", "location")
        if (region.isNotBlank()) subtitleParts.add(region)
        return LogEntry(
            id = Resp.str(o, "id", "uid", "uuid").ifBlank { Resp.l(o, "id").toString() },
            title = hostPair ?: ip.ifBlank { "记录 ${System.currentTimeMillis()}" },
            subtitle = subtitleParts.joinToString(" · "),
            attrLine = listOf(rule, timeOf(o)).filter { it.isNotBlank() }.joinToString(" · "),
            raw = o
        )
    }

    private fun timeOf(o: JsonObject): String {
        val raw = Resp.l(o, "timestamp", "time", "create_time", "created_at", "ts")
        if (raw > 0) {
            val ms = if (raw > 10_000_000_000L) raw else raw * 1000L
            return SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
        }
        val str = Resp.str(o, "time_str", "time", "created_at", "datetime")
        return if (str.isNotBlank() && !str.all { it.isDigit() }) str else ""
    }
}

@Composable
fun LogsScreen(account: AccountProfile) {
    val vm: LogsVm = viewModel()
    // 关键：必须 collectAsState，直接读 StateFlow.value 不会触发重组，
    // 会导致弹窗「点不开 / 关不掉」
    val detail by vm.detail.collectAsState()

    DisposableEffect(account.id) {
        vm.load(account)
        onDispose { }
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = vm.tab) {
            LOG_TABS.forEachIndexed { i, t ->
                Tab(
                    selected = vm.tab == i,
                    onClick = { vm.setTab(account, i) },
                    text = { Text(t, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.prev(account) }) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "上一页")
            }
            Text("第 ${vm.page} 页", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.next(account) }) {
                Icon(Icons.Default.ChevronRight, contentDescription = "下一页")
            }
            IconButton(onClick = { vm.load(account) }) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
        }

        val error = vm.error
        if (error != null) {
            EmptyBox("读取失败：$error")
        } else if (vm.entries.isEmpty()) {
            EmptyBox(if (vm.refreshing) "加载中…" else "本页暂无记录")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(vm.entries) { e ->
                    LogRowView(entry = e, onClick = { vm.openDetail(e.raw) })
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    detail?.let { raw ->
        AlertDialog(
            onDismissRequest = { vm.closeDetail() },
            title = { Text("记录详情") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        GsonBuilder().setPrettyPrinting().create().toJson(raw),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.closeDetail() }) { Text("关闭") } }
        )
    }
}

@Composable
private fun LogRowView(entry: LogEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        onClick = onClick
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TagChip(text = entry.id.take(8), color = InfoColor)
            }
            if (entry.subtitle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    entry.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (entry.attrLine.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    entry.attrLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = Warn
                )
            }
        }
    }
}

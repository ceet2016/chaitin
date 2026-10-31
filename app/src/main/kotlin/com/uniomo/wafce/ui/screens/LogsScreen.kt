package com.uniomo.wafce.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
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
import com.uniomo.wafce.core.Resp
import com.uniomo.wafce.core.TimeFmt
import com.uniomo.wafce.core.UiState
import com.uniomo.wafce.core.readable
import com.uniomo.wafce.data.net.ApiClient
import com.uniomo.wafce.data.store.AccountProfile
import com.uniomo.wafce.ui.components.AsyncBox
import com.uniomo.wafce.ui.components.CardShape
import com.uniomo.wafce.ui.components.EmptyBox
import com.uniomo.wafce.ui.components.KeyValueRow
import com.uniomo.wafce.ui.components.SectionCard
import com.uniomo.wafce.ui.components.TagChip
import com.uniomo.wafce.ui.theme.Danger
import com.uniomo.wafce.ui.theme.InfoColor
import com.uniomo.wafce.ui.theme.Ok
import com.uniomo.wafce.ui.theme.Warn

/**
 * 日志分类。
 *
 * 「拦截记录」在雷池控制台里的官方叫法是「频率控制」——它记录的是
 * 访问控制/频率限制命中后的拦截，因此这里跟随控制台命名，避免用户困惑。
 *
 * 关于「访问日志」：官方 OpenAPI（chaitin/workspace-cli 的 safeline-ce/openapi.json）
 * 里日志类只暴露 `/open/records`（攻击日志）与 `/open/records/rule`（黑白名单命中日志），
 * **没有访问日志列表接口**。控制台的「流量分析」页之所以能展示访问曲线，
 * 是因为它走的是统计聚合接口而非日志列表。因此原先的第五个页签恒为 404，已移除。
 */
enum class LogKind(val label: String, val color: androidx.compose.ui.graphics.Color) {
    EVENT("攻击事件", Danger),
    ACL("频率控制", Warn),
    CHALLENGE("人机验证", InfoColor),
    AUTH("身份认证", Ok);

    companion object {
        fun of(index: Int): LogKind = entries.getOrElse(index) { EVENT }
    }
}

/**
 * 攻击日志的时间窗。
 *
 * 对应 /open/records 的 start / end —— 注意这两个参数是**毫秒**时间戳，
 * 传秒会得到 0 条（已实测），因此这里统一按 seconds 保存、请求时再 ×1000。
 */
enum class LogTimeRange(val label: String, val seconds: Long) {
    ALL("全部", 0),
    H1("近 1 小时", 3_600),
    H24("近 24 小时", 86_400),
    D7("近 7 天", 604_800),
    D30("近 30 天", 2_592_000)
}

private val LOG_TABS = LogKind.entries.map { it.label }

data class LogEntry(
    val id: String,
    val title: String,
    val subtitle: String,
    val attrLine: String,
    val raw: JsonObject
)

class LogsVm : ViewModel() {

    var tab by mutableIntStateOf(0)
    var page by mutableIntStateOf(1)
        private set

    /** 分页大小：服务端上限 100，200 会直接 400 */
    var pageSize by mutableIntStateOf(20)
        private set

    /** 攻击事件筛选条件（仅 EVENT 页签生效） */
    var filterIp by mutableStateOf("")
        private set
    var filterHost by mutableStateOf("")
        private set
    var timeRange by mutableStateOf(LogTimeRange.ALL)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var entries by mutableStateOf(listOf<LogEntry>())
        private set

    /** 详情弹窗内容：raw 为空表示正在加载 */
    private val _detail = MutableStateFlow<JsonObject?>(null)
    val detail: StateFlow<JsonObject?> = _detail

    /** 正在拉取详情（攻击事件会额外请求 /events/{id} 拿到完整字段） */
    var detailLoading by mutableStateOf(false)
        private set

    /** 详情是否来自服务端二次请求，用于在弹窗里标注 */
    var detailFromServer by mutableStateOf(false)
        private set

    /** 分页结果缓存：切回已看过的页时立即出内容，避免每次都转圈 */
    private val cache = java.util.concurrent.ConcurrentHashMap<Triple<Long, Int, Int>, List<LogEntry>>()

    /** accountId -> (siteId -> 站点展示名)，详情里把 site_uuid 换成可读名称 */
    private val siteNameCache = java.util.concurrent.ConcurrentHashMap<Long, Map<String, String>>()

    /**
     * 请求代次。
     *
     * 原本 LogsVm 没有 epoch，是三个日志页里唯一漏掉的一个。后果：
     * 快速「下一页 → 点另一个页签」时，下面 load() 里读的 tab/page 是四个不同时刻的值
     * （第①行取 key、协程内取 page 拼参数、取 kind 决定接口、最后再取 tab 写缓存），
     * 于是会「用B 页签的页码去请求 A 接口」，并把结果写进另一个 key 的缓存槽。
     * 更糟的是 cache 的键原本是 Pair<Int,Int>，**不含 accountId** ——
     * A 账号第 2 页的缓存会被 B 账号同键命中直接上屏。
     */
    private var epoch = 0

    fun kind(): LogKind = LogKind.of(tab)

    fun load(account: AccountProfile) {
        // 关键：把tab/page 快照成局部val，协程内全程只用快照，
        // 不再读可变状态，从根上消除竞态
        val myTab = tab
        val myPage = page
        val mySize = pageSize
        val myIp = filterIp.trim()
        val myHost = filterHost.trim()
        val myRange = timeRange
        val myEpoch = ++epoch

        cache[Triple(account.id, myTab, myPage)]?.let { cached ->
            entries = cached
            refreshing = false
            error = null
        }
        viewModelScope.launch {
            refreshing = true
            error = null
            try {
                val api = ApiClient.apiFor(account)
                val k = LogKind.of(myTab)
                val q = buildMap {
                    put("page", myPage.toString())
                    put("page_size", mySize.toString())
                    if (k == LogKind.EVENT) {
                        if (myIp.isNotBlank()) put("ip", myIp)
                        if (myHost.isNotBlank()) put("host", myHost)
                        // start/end 必须是毫秒；传秒服务端返回 0 条，看起来像「没有攻击」
                        if (myRange.seconds > 0) {
                            val nowMs = System.currentTimeMillis()
                            put("start", (nowMs - myRange.seconds * 1000L).toString())
                            put("end", nowMs.toString())
                        }
                    }
                }
                val body = when (k) {
                    // 9.4.x：请求级攻击日志在 /open/records；
                    // /open/events 只是 IP+Host 的会话聚合，没有 URL/方法等详情字段
                    LogKind.EVENT -> api.records(q)
                    LogKind.ACL -> api.recordsAcl(q)
                    LogKind.CHALLENGE -> api.recordsChallenge(q)
                    LogKind.AUTH -> api.recordsAuthDefense(q)
                }
                // listOf 统一按data 的真实类型分派，比 nodes().ifEmpty{arr()} 更能兜住版本差异
                val list = Resp.listOf(body).map { buildEntry(it) }
                // 代次校验：慢返回的旧请求结果直接丢弃，不污染当前界面
                if (myEpoch != epoch) return@launch
                entries = list
                cache[Triple(account.id, myTab, myPage)] = list
            } catch (e: Exception) {
                // 协程取消属于正常终止，不能当失败，更不能清空已有数据
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (myEpoch != epoch) return@launch
                error = e.readable()
                // 刻意不清空 entries：瞬时网络抖动不该把已显示的 20 条数据变成空白页。
                // 错误提示由 UI 层在entries 为空时才整页替换。
            }
            if (myEpoch == epoch) refreshing = false
        }
    }

    fun setTab(account: AccountProfile, index: Int) {
        if (index == tab) {
            // 已经是该页签，但可能还没加载过（深链直达 tab=0 时首屏就是空）。
            // 这里不能简单 return —— 否则深链进入日志页会一片空白。
            if (entries.isEmpty() && error == null) load(account)
            return
        }
        tab = index
        page = 1
        load(account)
    }

    fun next(account: AccountProfile) { page += 1; load(account) }
    fun prev(account: AccountProfile) { if (page > 1) page -= 1; load(account) }

    /** 应用筛选：页码回到 1，并清掉旧条件下的分页缓存 */
    fun applyFilter(
        account: AccountProfile,
        ip: String,
        host: String,
        range: LogTimeRange,
        size: Int
    ) {
        filterIp = ip.trim()
        filterHost = host.trim()
        timeRange = range
        pageSize = size.coerceIn(20, 100)
        page = 1
        cache.clear()
        load(account)
    }

    fun setPageSize(account: AccountProfile, size: Int) {
        val safe = size.coerceIn(20, 100)
        if (safe == pageSize) return
        pageSize = safe
        page = 1
        cache.clear()
        load(account)
    }

    fun clearFilter(account: AccountProfile) {
        filterIp = ""
        filterHost = ""
        timeRange = LogTimeRange.ALL
        page = 1
        cache.clear()
        load(account)
    }

    /** 筛选条上的一行摘要；空串表示当前没有任何筛选 */
    fun filterSummary(): String = listOfNotNull(
        filterIp.takeIf { it.isNotBlank() }?.let { "IP $it" },
        filterHost.takeIf { it.isNotBlank() },
        timeRange.takeIf { it != LogTimeRange.ALL }?.label
    ).joinToString(" · ")

    fun closeDetail() { _detail.value = null }

    /**
     * 打开详情。
     *
     * 攻击日志（/open/records）列表只返回列表级字段，完整的方法、URL、请求头、
     * 请求体、响应等要再打 /open/record/{event_id}（UUID）。
     * 早期实现用的是 /open/events/{id}：该路径在 9.4.3 已不存在，而且 events
     * 本身是「IP+Host 会话聚合」，没有这些字段，所以详情只能看到列表里的概要。
     */
    fun openDetail(account: AccountProfile, entry: LogEntry) {
        _detail.value = entry.raw
        detailFromServer = false
        if (kind() != LogKind.EVENT) return

        val detailId = Resp.str(entry.raw, "event_id", "EventId").ifBlank { entry.id }
        if (detailId.isBlank() || detailId == "0") return

        detailLoading = true
        viewModelScope.launch {
            val api = ApiClient.apiFor(account)
            // 新版本走 record/{uuid}；老版本 events 的数字 id 再回退旧路径
            val fetched = runCatching { Resp.obj(api.recordDetail(detailId)) }.getOrNull()
                ?: detailId.takeIf { it.all(Char::isDigit) }
                    ?.let { runCatching { Resp.obj(api.eventDetail(it)) }.getOrNull() }

            val merged = JsonObject()
            entry.raw.entrySet().forEach { (k, v) -> merged.add(k, v) }
            fetched?.entrySet()?.forEach { (k, v) -> merged.add(k, v) }

            val siteId = Resp.str(merged, "site_uuid", "site_id")
            val siteName = runCatching { siteNameOf(account, siteId) }.getOrDefault("")
            if (siteName.isNotBlank()) merged.addProperty("site_name", siteName)

            detailLoading = false
            _detail.value = merged
            detailFromServer = fetched != null && fetched.size() > 0
        }
    }

    /** 站点 id -> 展示名（备注名优先，没有就用第一个域名）；每个账号只拉一次 */
    private suspend fun siteNameOf(account: AccountProfile, siteId: String): String {
        if (siteId.isBlank()) return ""
        val names = siteNameCache[account.id] ?: runCatching {
            Resp.listOf(
                ApiClient.apiFor(account).sites(mapOf("page" to "1", "page_size" to "200"))
            ).mapNotNull { o ->
                val id = Resp.l(o, "id").takeIf { it > 0 } ?: return@mapNotNull null
                val name = Resp.str(o, "comment").ifBlank {
                    Resp.strList(o, "server_names", "domains").firstOrNull().orEmpty()
                }
                id.toString() to name
            }.filter { it.second.isNotBlank() }.toMap()
        }.getOrDefault(emptyMap()).also { if (it.isNotEmpty()) siteNameCache[account.id] = it }
        return names[siteId].orEmpty()
    }

    private fun buildEntry(o: JsonObject): LogEntry {
        val ip = Resp.str(o, "src_ip", "ip", "client_ip", "remote_addr", "source_ip")
        val host = Resp.str(o, "host", "server_name", "domain", "dst_host", "server")
        val url = Resp.str(o, "url", "uri", "path", "request_path", "target", "url_path")
        val rule = Resp.str(o, "rule_id", "attack_type", "type", "category", "task_name", "policy_name")
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
            id = Resp.str(o, "id", "uid", "uuid", "event_id").ifBlank { Resp.l(o, "id").toString() },
            title = hostPair ?: ip.ifBlank { "记录 ${System.currentTimeMillis()}" },
            subtitle = subtitleParts.joinToString(" · "),
            // 时间统一走 TimeFmt：各接口的 timestamp(int) 与 created_at(string) 都能读成人能看懂的格式
            attrLine = listOf(rule, TimeFmt.logTimeOf(o)).filter { it.isNotBlank() }.joinToString(" · "),
            raw = o
        )
    }
}

@Composable
fun LogsScreen(account: AccountProfile, initialTab: Int = -1) {
    val vm: LogsVm = viewModel()
    // 关键：必须 collectAsState，直接读 StateFlow.value 不会触发重组，
    // 会导致弹窗「点不开 / 关不掉」
    val detail by vm.detail.collectAsState()
    var filterOpen by remember { mutableStateOf(false) }

    DisposableEffect(account.id, initialTab) {
        // key 带上 initialTab：深链进入时首次加载就直接按目标页签发起，
        // 否则会先按 tab=0 请求一次再改tab，多余且第一屏内容与高亮不符
        if (initialTab >= 0) vm.setTab(account, initialTab.coerceIn(0, LOG_TABS.lastIndex))
        else vm.load(account)
        onDispose { }
    }

    // 从首页指标卡跳转过来时，直接落到对应分类。
    // 必须做上界收口：路由 logs?tab={tab} 是外部可见的深链入口，
    // 传进来的值超过页签数量时 TabRow(selectedTabIndex = 99) 会直接抛
    // IndexOutOfBoundsException 崩掉整页。
    //
    // 注意这里**不能**先写 vm.tab = safe 再调 setTab：
    // setTab 首行是 `if (index == tab) return`，tab 已被改掉就会早退，
    // load() 永远不执行 —— 结果高亮在目标页签、内容却是上一个页签的数据，
    // 且不会自动纠正（首页5 个指标卡入口里有 3 个会踩到）。
    LaunchedEffect(initialTab) {
        val safe = initialTab.coerceIn(0, LOG_TABS.lastIndex)
        if (initialTab >= 0) vm.setTab(account, safe)
    }

    val safeTab = vm.tab.coerceIn(0, LOG_TABS.lastIndex)
    LaunchedEffect(safeTab) { if (vm.tab != safeTab) vm.tab = safeTab }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = safeTab) {
            LOG_TABS.forEachIndexed { i, t ->
                Tab(
                    selected = safeTab == i,
                    onClick = { vm.setTab(account, i) },
                    text = { Text(t, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }

        // 只有攻击事件支持 IP / 域名 / 时间筛选；其余页签接口参数不同，不显示
        if (vm.kind() == LogKind.EVENT) {
            EventFilterBar(vm, account) { filterOpen = true }
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
        // 只在「一条数据都没有」时才整页替换成错误页。
        // 否则用户翻到第 5 页后一次网络抖动，就会把屏上 20 条数据全换成一句报错——
        // 瞬时抖动被放大成数据丢失。有数据时用顶部细条提示，列表保留。
        if (error != null && vm.entries.isEmpty()) {
            EmptyBox("读取失败：$error")
        } else if (vm.entries.isEmpty()) {
            EmptyBox(if (vm.refreshing) "加载中…" else "本页暂无记录")
        } else {
            // 有数据但刷新失败：内联提示条，不打断用户阅读
            if (error != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "刷新失败：$error",
                        style = MaterialTheme.typography.labelSmall,
                        color = Danger,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { vm.load(account) }) { Text("重试") }
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                // 日志翻页后内容完全换一批，必须给 key，否则按位复用会导致
                // 「条目内容串位」；id 缺失时带下标兜底，避免 key 撞车崩溃
                itemsIndexed(vm.entries, key = { i, e -> "${e.id}_$i" }) { _, e ->
                    LogRowView(entry = e, onClick = { vm.openDetail(account, e) })
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    detail?.let { raw ->
        LogDetailDialog(
            kind = vm.kind(),
            raw = raw,
            loading = vm.detailLoading,
            enhanced = vm.detailFromServer,
            onDismiss = { vm.closeDetail() }
        )
    }

    if (filterOpen) {
        EventFilterSheet(vm, account) { filterOpen = false }
    }
}

@Composable
private fun EventFilterBar(vm: LogsVm, account: AccountProfile, onOpen: () -> Unit) {
    var sizeMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onOpen) {
            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("筛选", style = MaterialTheme.typography.labelMedium)
        }
        Text(
            vm.filterSummary().ifBlank { "全部记录" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Row {
            TextButton(onClick = { sizeMenu = true }) {
                Text("${vm.pageSize} 条/页", style = MaterialTheme.typography.labelSmall)
            }
            DropdownMenu(expanded = sizeMenu, onDismissRequest = { sizeMenu = false }) {
                listOf(20, 50, 100).forEach { n ->
                    DropdownMenuItem(
                        text = { Text("$n 条/页") },
                        onClick = {
                            sizeMenu = false
                            vm.setPageSize(account, n)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventFilterSheet(vm: LogsVm, account: AccountProfile, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var ip by remember { mutableStateOf(vm.filterIp) }
    var host by remember { mutableStateOf(vm.filterHost) }
    var range by remember { mutableStateOf(vm.timeRange) }
    var size by remember { mutableStateOf(vm.pageSize) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("筛选攻击事件", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = ip,
                onValueChange = { ip = it },
                label = { Text("攻击 IP") },
                placeholder = { Text("支持前缀，如 223.87") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("域名") },
                placeholder = { Text("精确匹配，如 api.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))
            Text("时间范围", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogTimeRange.entries.take(3).forEach { r ->
                    FilterChoice(r.label, range == r) { range = r }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogTimeRange.entries.drop(3).forEach { r ->
                    FilterChoice(r.label, range == r) { range = r }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("每页条数", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(20, 50, 100).forEach { n ->
                    FilterChoice("$n 条", size == n) { size = n }
                }
            }

            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        ip = ""
                        host = ""
                        range = LogTimeRange.ALL
                        size = 20
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("重置") }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = {
                        vm.applyFilter(account, ip, host, range, size)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("应用") }
            }
        }
    }
}

@Composable
private fun FilterChoice(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(9.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
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

/**
 * 攻击/拦截日志详情。
 *
 * 分两层展示：
 * 1. 「关键信息」——把常见字段翻译成中文并按语义分组，用户不用读 JSON 也能看懂
 * 2. 「原始记录」——完整 JSON，保留排查现场的能力
 */
@Composable
private fun LogDetailDialog(
    kind: LogKind,
    raw: JsonObject,
    loading: Boolean,
    enhanced: Boolean,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var showRaw by remember { mutableStateOf(false) }
    val groups = remember(raw) { groupize(raw) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("${kind.label}详情")
                if (loading) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "正在拉取完整详情…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (enhanced) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "已加载服务端完整记录",
                        style = MaterialTheme.typography.labelSmall,
                        color = Ok
                    )
                }
            }
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // 概要
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = CardShape,
                    colors = CardDefaults.cardColors(containerColor = kind.color.copy(alpha = 0.10f))
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        val logTime = TimeFmt.logTimeOf(raw)
                        if (logTime.isNotBlank()) {
                            KeyValueRow("发生时间", logTime, kind.color)
                        }
                        KeyValueRow("来源 IP", Resp.str(raw, "src_ip", "ip", "client_ip", "remote_addr").ifBlank { "—" })
                        KeyValueRow("目标 Host", Resp.str(raw, "host", "server_name", "domain", "dst_host").ifBlank { "—" })
                        KeyValueRow("所属站点", Resp.str(raw, "site_name", "site_title", "site_comment").ifBlank {
                            Resp.str(raw, "site_uuid", "site_id")
                                .takeIf { it.isNotBlank() }?.let { "站点 #$it" } ?: "—"
                        })
                        KeyValueRow("请求方法", Resp.str(raw, "method", "http_method").ifBlank { "—" })
                        KeyValueRow("请求 URL", Resp.str(raw, "website", "url", "uri", "request_path", "url_path").ifBlank { "—" })
                        KeyValueRow("状态码", Resp.l(raw, "status_code", "status", "code").takeIf { it > 0 }?.toString() ?: "—")
                        KeyValueRow("归属地区", Resp.str(raw, "province", "region", "country", "location").ifBlank { "—" })
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 攻击/拦截特征
                val feature = groups.firstOrNull { it.first == "attack" }
                if (feature != null && feature.second.isNotEmpty()) {
                    GroupCard("攻击特征")
                    feature.second.forEach { (k, v) -> KeyValueRow(FEATURE_LABELS[k] ?: k, v) }
                    Spacer(Modifier.height(12.dp))
                }

                val req = groups.firstOrNull { it.first == "request" }
                if (req != null && req.second.isNotEmpty()) {
                    GroupCard("请求内容")
                    req.second.forEach { (k, v) ->
                        if (k.lowercase() in RAW_BLOCK_KEYS) {
                            BodyBlock(REQUEST_LABELS[k] ?: k, v)
                        } else {
                            KeyValueRow(REQUEST_LABELS[k] ?: k, v, InfoColor)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                val other = groups.firstOrNull { it.first == "other" }
                if (other != null && other.second.isNotEmpty()) {
                    GroupCard("其他字段")
                    other.second.forEach { (k, v) -> KeyValueRow(OTHER_LABELS[k] ?: k, v) }
                    Spacer(Modifier.height(12.dp))
                }

                // 原始 JSON
                Surface(
                    onClick = { showRaw = !showRaw },
                    modifier = Modifier.fillMaxWidth(),
                    shape = CardShape,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (showRaw) "收起原始记录" else "查看原始记录（JSON）",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                clipboard.setText(
                                    AnnotatedString(GsonBuilder().setPrettyPrinting().create().toJson(raw))
                                )
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "复制 JSON",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                if (showRaw) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        GsonBuilder().setPrettyPrinting().create().toJson(raw),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun GroupCard(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
    }
    Spacer(Modifier.height(4.dp))
}

/**
 * 把原始 JSON 按语义分成三组，避免所有字段平铺导致关键信息被淹没。
 * 返回 Pair<分组名, 字段列表>，分组名取值：attack / request / other。
 */
private fun groupize(raw: JsonObject): List<Pair<String, List<Pair<String, String>>>> {
    val attack = mutableListOf<Pair<String, String>>()
    val request = mutableListOf<Pair<String, String>>()
    val other = mutableListOf<Pair<String, String>>()

    for ((k, v) in raw.entrySet()) {
        if (v.isJsonNull) continue
        val key = k.lowercase()
        val text = when {
            v.isJsonPrimitive && v.asJsonPrimitive.isNumber -> v.asString
            v.isJsonPrimitive && v.asJsonPrimitive.isBoolean -> if (v.asBoolean) "是" else "否"
            // 时间类字符串统一转成人能看懂的格式，避免详情页出现原始 ISO 串
            key in TIME_KEYS -> TimeFmt.parseText(v.asString)
                .takeIf { it > 0 }?.let { TimeFmt.full(it) } ?: v.asString
            v.isJsonPrimitive -> v.asString
            v.isJsonArray -> v.asJsonArray.size().let { "$it 项" }
            else -> continue
        }
        if (text.isBlank()) continue

        when {
            key in ATTACK_KEYS -> attack.add(k to text)
            key in REQUEST_KEYS -> request.add(k to text)
            key in SKIP_KEYS -> Unit
            else -> other.add(k to text)
        }
    }
    return listOf("attack" to attack, "request" to request, "other" to other)
}

/**
 * 字段名取自官方 OpenAPI 的日志模型
 * （model.DetectLog / acl.Log / api.ChallengeLog / auth_defense.*）。
 */
private val ATTACK_KEYS = setOf(
    "attack_type", "attack", "rule_id", "rule_name", "rule_id_list", "short_rule_id",
    "task_name", "category", "engine", "event_type", "type", "level", "severity",
    "confidence", "risk_level", "payload", "evidence", "detail", "reason",
    "cve", "cve_id", "log_id", "policy_name", "module", "reason", "query_key",
    "block_min", "deny_count", "denied_count", "valid_before", "result"
)

private val REQUEST_KEYS = setOf(
    "user_agent", "useragent", "referer", "referrer", "cookie", "request_body",
    "req_body", "body", "query", "query_string", "headers", "req_header",
    "rsp_header", "rsp_body", "attack_detail", "req_id", "trace_id",
    "x_forwarded_for", "decode_path",
    "ja4_fingerprint", "protocol", "dst_port", "src_port", "dst_ip"
)

private val TIME_KEYS = setOf(
    "timestamp", "time_stamp", "time", "ts", "created_at", "updated_at",
    "create_time", "start_at", "started_at", "end_at", "ended_at", "dur_sec",
    "date", "datetime", "valid_before"
)

private val SKIP_KEYS = setOf(
    "src_ip", "ip", "client_ip", "remote_addr", "source_ip", "socket_ip",
    "host", "server_name", "domain", "dst_host", "server", "website",
    "url", "uri", "path", "request_path", "url_path",
    "status_code", "status", "code", "method", "http_method",
    "province", "region", "country", "location", "city", "lat", "lng",
    "id", "uid", "uuid", "event_id", "eventid",
    "site_id", "site_uuid", "site_title", "site_name", "site_comment",
    "site_icon", "site_server_names", "user_id", "username", "uuid",
    "policy_id", "auth_source_id", "third_id", "source_type", "status",
    "timestamp", "time_stamp", "time", "ts", "created_at", "updated_at",
    "create_time", "start_at", "started_at", "end_at", "ended_at",
    "date", "datetime"
)

private val FEATURE_LABELS = mapOf(
    "attack_type" to "攻击类型",
    "attack" to "攻击描述",
    "rule_id" to "命中规则 ID",
    "rule_name" to "命中规则",
    "rule_id_list" to "命中规则组",
    "short_rule_id" to "规则短 ID",
    "task_name" to "任务名称",
    "category" to "分类",
    "engine" to "检测引擎",
    "event_type" to "事件类型",
    "type" to "类型",
    "level" to "危险等级",
    "severity" to "严重程度",
    "confidence" to "置信度",
    "risk_level" to "风险等级",
    "module" to "防护模块",
    "payload" to "攻击载荷",
    "evidence" to "判定依据",
    "detail" to "详情",
    "reason" to "命中原因",
    "cve" to "CVE 编号",
    "cve_id" to "CVE 编号",
    "log_id" to "日志 ID",
    "policy_name" to "命中策略",
    "query_key" to "命中的 Key",
    "block_min" to "封禁时长(分)",
    "deny_count" to "累计拦截",
    "denied_count" to "累计拦截",
    "count" to "次数",
    "trigger_count" to "触发次数",
    "pass_count" to "通过次数",
    "dur_sec" to "持续时长(秒)",
    "valid_before" to "有效期至",
    "result" to "处置结果",
    "finished" to "是否结束",
    "rule_type" to "规则类型",
    "period" to "周期",
    "source_type" to "来源类型"
)

private val REQUEST_LABELS = mapOf(
    "user_agent" to "User-Agent",
    "useragent" to "User-Agent",
    "referer" to "Referer",
    "referrer" to "Referer",
    "cookie" to "Cookie",
    "request_body" to "请求体",
    "req_body" to "请求体",
    "rsp_body" to "响应体",
    "body" to "请求体",
    "query_string" to "查询串",
    "query" to "查询串",
    "headers" to "请求头",
    "req_header" to "请求头",
    "rsp_header" to "响应头",
    "decode_path" to "解码后路径",
    "attack_detail" to "报文详情",
    "req_id" to "请求 ID",
    "trace_id" to "Trace ID",
    "ja4_fingerprint" to "JA4 指纹",
    "protocol" to "协议",
    "dst_port" to "目标端口",
    "src_port" to "来源端口",
    "dst_ip" to "目标 IP",
    "x_forwarded_for" to "X-Forwarded-For"
)

/** 头部/报文类字段按块展示：塞进右对齐的键值行里会把多行内容压变形 */
private val RAW_BLOCK_KEYS = setOf(
    "req_header", "rsp_header", "req_body", "rsp_body", "payload",
    "headers", "request_body", "body", "attack_detail"
)

/**
 * 尝试把响应体解成可读文本。
 *
 * 雷池返回的 rsp_body 是 Go %q 风格的转义串（如 `\x1f\x8b\b\x00...`），
 * 也可能直接是原始控制字符。两种形态都先还原成字节，再判断 gzip。
 * 非 gzip / 解码失败返回 null，调用方继续展示原文。
 */
private fun gunzipBody(value: String): String? {
    if (value.isEmpty() || value.length > 262_144) return null
    val bytes = when {
        value.startsWith("\\x1f\\x8b") -> unescapeBody(value) ?: return null
        value.length >= 2 && value[0].code == 0x1f && value[1].code == 0x8b ->
            value.toByteArray(Charsets.ISO_8859_1)
        else -> return null
    }
    if (bytes.size < 4 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte()) return null
    return runCatching {
        java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(bytes))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()
}

/** 解析 `\x1f`、`\b`、`\n` 这类转义；注意 `\x` 后的十六进制可能是 1~2 位 */
private fun unescapeBody(s: String): ByteArray? {
    val out = java.io.ByteArrayOutputStream(s.length)
    val hex = "0123456789abcdefABCDEF"
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            val n = s[i + 1]
            if (n == 'x') {
                var j = i + 2
                var h = ""
                while (j < s.length && h.length < 2 && s[j] in hex) {
                    h += s[j]; j++
                }
                if (h.isEmpty()) {
                    out.write(n.code); i += 2; continue
                }
                out.write(h.toInt(16)); i = j; continue
            }
            val b = when (n) {
                'b' -> 8
                't' -> 9
                'n' -> 10
                'f' -> 12
                'r' -> 13
                '\\' -> 92
                '"' -> 34
                else -> n.code
            }
            out.write(b); i += 2
        } else {
            if (c.code < 256) out.write(c.code)
            else out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
    }
    return out.toByteArray()
}

/**
 * 请求/响应报文块。
 *
 * 检测到 gzip 时在右上角提供「原始数据 / gzip 解码」切换；
 * 普通文本没有可切换的内容，仍走原来的单块展示。
 */
@Composable
private fun BodyBlock(label: String, raw: String) {
    val decoded = remember(raw) { gunzipBody(raw) }
    if (decoded == null) {
        RawBlock(label, raw)
        return
    }
    var showDecoded by remember(raw) { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            BodyTab("原始数据", !showDecoded) { showDecoded = false }
            Spacer(Modifier.width(6.dp))
            BodyTab("gzip 解码", showDecoded) { showDecoded = true }
        }
        Spacer(Modifier.height(4.dp))
        Surface(
            shape = CardShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (showDecoded) decoded else raw,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().padding(10.dp)
            )
        }
    }
}

@Composable
private fun BodyTab(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(7.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun RawBlock(label: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Surface(
            shape = CardShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().padding(10.dp)
            )
        }
    }
}

private val OTHER_LABELS = mapOf(
    "action" to "处置动作",
    "node_id" to "节点 ID",
    "is_waf" to "是否 WAF 响应",
    "duration" to "耗时"
)

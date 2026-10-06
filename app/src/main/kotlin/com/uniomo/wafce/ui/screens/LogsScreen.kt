package com.uniomo.wafce.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
                val q = mapOf("page" to myPage.toString(), "page_size" to "20")
                val k = LogKind.of(myTab)
                val body = when (k) {
                    LogKind.EVENT -> api.events(q)
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

    fun closeDetail() { _detail.value = null }

    /**
     * 打开详情。
     *
     * 攻击事件（/open/events）列表返回的是精简字段，真正的详情要再打
     * /open/events/{id} 才能拿到攻击类型、命中规则、请求报文等；
     * 其余日志列表本身就是完整记录，直接展示即可。
     */
    fun openDetail(account: AccountProfile, entry: LogEntry) {
        _detail.value = entry.raw
        detailFromServer = false
        if (kind() != LogKind.EVENT || entry.id.isBlank() || entry.id == "0") return

        detailLoading = true
        viewModelScope.launch {
            val fetched = runCatching {
                Resp.obj(ApiClient.apiFor(account).eventDetail(entry.id))
            }.getOrNull()
            detailLoading = false
            if (fetched != null && fetched.size() > 0) {
                // 服务端详情为准，但保留列表里独有的字段（如列表侧的 host 展示名）
                val merged = JsonObject()
                entry.raw.entrySet().forEach { (k, v) -> merged.add(k, v) }
                fetched.entrySet().forEach { (k, v) -> merged.add(k, v) }
                _detail.value = merged
                detailFromServer = true
            }
        }
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
                        KeyValueRow("所属站点", Resp.str(raw, "site_title", "site_name", "site_comment", "website").ifBlank { "—" })
                        KeyValueRow("请求方法", Resp.str(raw, "method", "http_method").ifBlank { "—" })
                        KeyValueRow("请求 URL", Resp.str(raw, "url", "uri", "path", "request_path", "url_path").ifBlank { "—" })
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
                    req.second.forEach { (k, v) -> KeyValueRow(REQUEST_LABELS[k] ?: k, v, InfoColor) }
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
    "x_forwarded_for", "decode_path", "method", "http_method", "status_code",
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
    "status_code", "status", "code",
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

private val OTHER_LABELS = mapOf(
    "action" to "处置动作",
    "node_id" to "节点 ID",
    "is_waf" to "是否 WAF 响应",
    "duration" to "耗时"
)

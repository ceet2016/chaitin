package com.uniomo.wafce.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.uniomo.wafce.core.Resp
import com.uniomo.wafce.core.UiState
import com.uniomo.wafce.core.readable
import com.uniomo.wafce.data.net.ApiClient
import com.uniomo.wafce.data.store.AccountProfile
import com.uniomo.wafce.ui.components.AsyncBox
import com.uniomo.wafce.ui.components.CardShape
import com.uniomo.wafce.ui.components.EmptyBox
import com.uniomo.wafce.ui.components.HeroHeader
import com.uniomo.wafce.ui.components.HeroMetric
import com.uniomo.wafce.ui.components.SectionCard
import com.uniomo.wafce.ui.components.StatCard
import com.uniomo.wafce.ui.components.TrendChart
import com.uniomo.wafce.ui.theme.Brand
import com.uniomo.wafce.ui.theme.Danger
import com.uniomo.wafce.ui.theme.InfoColor
import com.uniomo.wafce.ui.theme.Ok
import com.uniomo.wafce.ui.theme.Warn
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 仪表盘页内的跳转目标。
 *
 * 首页的每张指标卡都可点进对应页面，因此需要一个与导航解耦的描述类型，
 * 由 [DashboardScreen] 的调用方（AppRoot）翻译成真实的 NavController 路由。
 */
sealed interface DashTarget {
    /** 跳到某个主页面 */
    data class Page(val route: String) : DashTarget

    /** 跳到日志页的指定分类 */
    data class LogTab(val index: Int) : DashTarget

    /** 跳到防护页的指定页签 */
    data class ProtectTab(val key: String) : DashTarget
}

/**
 * 仪表盘数据。
 *
 * 数据源取舍（雷池 9.4.x 社区版实测）：
 * - 各日志接口的 total 计数在社区版是真实数据，作为拦截类指标的主来源
 * - /stat/qps 提供实时 QPS 序列
 * - 访问量与拦截量趋势走 /stat/requests、/stat/intercepts，
 *   老版本回退到 /dashboard/requests、/dashboard/intercepts；
 *   两者都拿不到时退化为「近 N 条日志按时间分桶」，保证图一定有内容
 * - security_posture 社区版多数返回 0，仅在有非零值时补充展示
 */
data class DashboardData(
    val version: String = "",
    val attackEvents: Long = 0,
    val aclRecords: Long = 0,
    val challengeRecords: Long = 0,
    val authRecords: Long = 0,
    val siteTotal: Int = 0,
    val policyTotal: Int = 0,
    val ipGroupTotal: Int = 0,
    val certTotal: Int = 0,
    val qpsSeries: List<Float> = emptyList(),
    /** 访问量趋势，元素为数值点 */
    val accessSeries: List<Float> = emptyList(),
    val accessLabels: List<String> = emptyList(),
    /** 拦截量趋势 */
    val blockSeries: List<Float> = emptyList(),
    val blockLabels: List<String> = emptyList(),
    val realtime: List<RealtimeEvent> = emptyList(),
    val posture: Map<String, Long> = emptyMap(),
    /** 趋势数据来源说明，用于在页面上诚实标注 */
    val accessSource: String = "",
    val blockSource: String = ""
)

data class RealtimeEvent(
    val siteName: String,
    val ip: String,
    val attackType: String,
    val timeText: String
)

class DashboardVm : ViewModel() {

    var state: UiState<DashboardData> by mutableStateOf(UiState.Idle)
        private set

    /** 静默刷新中：用于顶部细进度条，避免整页闪一下 Loading */
    var refreshing by mutableStateOf(false)
        private set

    /** 自动刷新开关的对外状态，供按钮显示 */
    var autoRefresh by mutableStateOf(true)
        private set

    private var pollJob: Job? = null

    /**
     * @param silent 为 true 时不进入 Loading 态，保留旧数据静默替换，
     *               仅在尚无数据时才显示加载；失败也不覆盖已有数据。
     */
    fun load(target: AccountProfile, silent: Boolean = false) {
        val hasData = state is UiState.Success
        viewModelScope.launch {
            if (!silent || !hasData) state = UiState.Loading
            refreshing = true
            val result = runCatching { fetch(target) }
            refreshing = false
            if (result.isSuccess) {
                state = UiState.Success(result.getOrThrow())
            } else if (!silent || !hasData) {
                val e = result.exceptionOrNull()!!
                state = UiState.Fail(e.readable(), e is com.uniomo.wafce.core.UnauthorizedException)
            }
        }
    }

    private suspend fun fetch(target: AccountProfile): DashboardData {
        targetId = target.id
        return coroutineScope {
            val api = ApiClient.apiFor(target)
            val one = mapOf("page" to "1", "page_size" to "1")

            val vSystem = async { runCatching { api.systemInfo() } }
            val vEvents = async { runCatching { api.events(one) } }
            val vAcl = async { runCatching { api.recordsAcl(one) } }
            val vChallenge = async { runCatching { api.recordsChallenge(one) } }
            val vAuth = async { runCatching { api.recordsAuthDefense(one) } }
            val vSites = async { runCatching { api.sites(one) } }
            val vPolicy = async { runCatching { api.policies(mapOf("page" to "1", "page_size" to "1", "action" to "-1")) } }
            val vIpGroup = async { runCatching { api.ipGroups(one) } }
            val vCert = async { runCatching { api.certs(one) } }
            val vQps = async { runCatching { api.statQps(emptyMap()) } }
            val vRealtime = async { runCatching { api.securityRealtime(emptyMap()) } }
            val vPosture = async { runCatching { api.securityPosture() } }

            // 趋势：优先内部统计接口，失败则用日志分桶兜底
            val vAccess = async { trend(ACCESS_TREND_PATHS) }
            val vBlock = async { trend(BLOCK_TREND_PATHS) }
            val vEventsList = async { logBuckets { api.events(mapOf("page" to "1", "page_size" to "100")) } }
            val vAclList = async { logBuckets { api.recordsAcl(mapOf("page" to "1", "page_size" to "100")) } }

            val access = vAccess.await()
            val block = vBlock.await()
            val fallbackAccess = vEventsList.await()
            val fallbackBlock = vAclList.await()

            DashboardData(
                version = versionOf(vSystem.await().getOrNull()),
                attackEvents = totalOf(vEvents.await().getOrNull()),
                aclRecords = totalOf(vAcl.await().getOrNull()),
                challengeRecords = totalOf(vChallenge.await().getOrNull()),
                authRecords = totalOf(vAuth.await().getOrNull()),
                siteTotal = totalOf(vSites.await().getOrNull()).toInt(),
                policyTotal = totalOf(vPolicy.await().getOrNull()).toInt(),
                ipGroupTotal = totalOf(vIpGroup.await().getOrNull()).toInt(),
                certTotal = totalOf(vCert.await().getOrNull()).toInt(),
                qpsSeries = qpsOf(vQps.await().getOrNull()),
                accessSeries = access.first.ifEmpty { fallbackAccess.first },
                accessLabels = access.second.ifEmpty { fallbackAccess.second },
                blockSeries = block.first.ifEmpty { fallbackBlock.first },
                blockLabels = block.second.ifEmpty { fallbackBlock.second },
                realtime = realtimeOf(vRealtime.await().getOrNull()),
                posture = postureOf(vPosture.await().getOrNull()),
                accessSource = if (access.first.isNotEmpty()) access.third else "日志分桶（近 100 条）",
                blockSource = if (block.first.isNotEmpty()) block.third else "日志分桶（近 100 条）"
            )
        }
    }

    /**
     * 依次尝试多个统计路径，返回「数值序列 + 首尾标签 + 来源说明」。
     * 全部失败时返回空列表，由调用方决定是否用日志分桶兜底。
     */
    private suspend fun trend(paths: List<String>): Triple<List<Float>, List<String>, String> {
        val pts = ApiClient.probeSeries(targetId, paths)
        if (pts.isEmpty()) return Triple(emptyList(), emptyList(), "")
        return Triple(
            pts.map { it.value },
            listOf(pts.first().label, pts.last().label),
            paths.first()
        )
    }

    /** 趋势探测需要账号 id 来取 Retrofit 实例，随每次 fetch 记录一次 */
    private var targetId: Long = -1L

    /**
     * 兜底：取最近 N 条日志，按时间戳聚成 12 个时间桶。
     * 至少能反映「最近这段时间的相对分布」，比一条空图有用。
     */
    private suspend fun logBuckets(block: suspend () -> JsonObject): Pair<List<Float>, List<String>> {
        val body = runCatching { block() }.getOrNull() ?: return emptyList<Float>() to emptyList()
        val nodes = runCatching { Resp.nodes(Resp.obj(body)) }.getOrNull().orEmpty()
        if (nodes.isEmpty()) return emptyList<Float>() to emptyList()

        val times = nodes.mapNotNull { tsOf(it) }
        if (times.size < 4) return emptyList<Float>() to emptyList()

        val newest = times.max()
        val oldest = times.min()
        val span = (newest - oldest).coerceAtLeast(1L)
        val buckets = IntArray(BUCKET_COUNT)
        for (t in times) {
            val idx = (((t - oldest).toDouble() / span) * (BUCKET_COUNT - 1)).toInt()
            buckets[idx.coerceIn(0, BUCKET_COUNT - 1)]++
        }
        val spanSec = span / BUCKET_COUNT
        val labels = listOf(clockOf(oldest), clockOf(newest))
        return buckets.map { it.toFloat() } to labels
    }

    private fun tsOf(o: JsonObject): Long? {
        val raw = Resp.l(o, "timestamp", "time_stamp", "time", "create_time", "created_at", "ts")
        if (raw <= 0) return null
        return if (raw > 10_000_000_000L) raw else raw * 1000L
    }

    private fun clockOf(ms: Long): String = try {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
    } catch (e: Exception) {
        ""
    }

    /**
     * 切换自动刷新。方法名不能叫 setAutoRefresh——那会和 `private set`
     * 生成的 setter 撞 JVM 签名，编译期直接报 Platform declaration clash。
     */
    fun toggleAutoRefresh(target: AccountProfile) {
        autoRefresh = !autoRefresh
        if (autoRefresh) startPolling(target) else stopPolling()
    }

    /**
     * 轮询刷新。首页默认开启，进入即最新。
     * 用 delay 前置而不是先请求再 delay，保证页面刚进入时不会触发两次请求。
     */
    fun startPolling(target: AccountProfile, seconds: Int = 20) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(seconds * 1000L)
                load(target, silent = true)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }

    private fun versionOf(body: JsonObject?): String = runCatching {
        Resp.str(Resp.obj(body), "version", "release", "tag")
    }.getOrDefault("")

    /** 各日志接口统一返回 { data: { total: N, ... } } */
    private fun totalOf(body: JsonObject?): Long = runCatching {
        val data = Resp.unwrap(body)
        if (data.isJsonObject) {
            val t = Resp.total(data.asJsonObject)
            if (t > 0) return@runCatching t.toLong()
        }
        (Resp.firstArray(data)?.size() ?: 0).toLong()
    }.getOrDefault(0L)

    /** /api/stat/qps 的 data.nodes 形如 [{"0.0.0.0:0": 12, "time": "22:57:40"}] */
    private fun qpsOf(body: JsonObject?): List<Float> = Resp.seriesOf(body).map { it.value }

    private fun postureOf(body: JsonObject?): Map<String, Long> = runCatching {
        val o = Resp.obj(body)
        val out = mutableMapOf<String, Long>()
        for ((k, v) in o.entrySet()) {
            if (v.isJsonPrimitive && v.asJsonPrimitive.isNumber) {
                val n = v.asLong
                if (n > 0) out[k] = n
            }
        }
        out
    }.getOrDefault(emptyMap())

    private fun realtimeOf(body: JsonObject?): List<RealtimeEvent> = runCatching {
        val arr = Resp.firstArray(Resp.unwrap(body)) ?: return@runCatching emptyList()
        val out = mutableListOf<RealtimeEvent>()
        for (el in arr) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            val inner = Resp.firstObj(o) ?: o
            val ts = Resp.l(o, "time_stamp", "timestamp", "time")
            out.add(
                RealtimeEvent(
                    siteName = Resp.str(inner, "site_name", "host", "site").ifBlank { "—" },
                    ip = Resp.str(inner, "ip", "src_ip").ifBlank { "—" },
                    attackType = Resp.str(o, "attack_type", "event_type", "type").ifBlank { "安全事件" },
                    timeText = if (ts > 0) formatTime(ts) else ""
                )
            )
            if (out.size >= 8) break
        }
        out
    }.getOrDefault(emptyList())

    private fun formatTime(sec: Long): String = try {
        val ms = if (sec > 10_000_000_000L) sec else sec * 1000L
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
    } catch (e: Exception) {
        ""
    }

    private companion object {
        const val BUCKET_COUNT = 12

        /** 访问量趋势的候选路径：新版 stat 优先，回退老版 dashboard */
        val ACCESS_TREND_PATHS = listOf(
            "api/stat/requests",
            "api/dashboard/requests"
        )

        /** 拦截量趋势的候选路径 */
        val BLOCK_TREND_PATHS = listOf(
            "api/stat/intercepts",
            "api/dashboard/intercepts"
        )
    }
}

@Composable
fun DashboardScreen(account: AccountProfile, onNavigate: (DashTarget) -> Unit = {}) {
    val vm: DashboardVm = viewModel()

    DisposableEffect(account.id) {
        vm.load(account)
        vm.startPolling(account)
        onDispose { vm.stopPolling() }
    }

    Column(Modifier.fillMaxSize()) {
        // 静默刷新只显示细进度条，不打断阅读
        if (vm.refreshing) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Brand,
                trackColor = Color.Transparent
            )
        } else {
            Spacer(Modifier.height(2.dp))
        }

        AsyncBox(state = vm.state, onRetry = { vm.load(account) }) { data ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                HeroHeader(
                    title = "安全态势概览",
                    subtitle = "${shortHost(account.baseUrl)} · 版本 ${data.version.ifBlank { "未知" }}",
                    trailing = {
                        Row {
                            IconButton(onClick = { vm.toggleAutoRefresh(account) }) {
                                Icon(
                                    Icons.Default.Speed,
                                    contentDescription = if (vm.autoRefresh) "关闭自动刷新" else "开启自动刷新",
                                    tint = if (vm.autoRefresh) Color.White else Color.White.copy(alpha = 0.6f)
                                )
                            }
                            IconButton(onClick = { vm.load(account, silent = true) }) {
                                Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = Color.White)
                            }
                        }
                    }
                ) {
                    Row(Modifier.fillMaxWidth()) {
                        HeroMetric("攻击事件", DecimalFormat("#,###").format(data.attackEvents), Modifier.weight(1f))
                        HeroMetric("频率控制", DecimalFormat("#,###").format(data.aclRecords), Modifier.weight(1f))
                        HeroMetric(
                            "实时 QPS",
                            DecimalFormat("0.#").format(data.qpsSeries.lastOrNull() ?: 0f),
                            Modifier.weight(1f)
                        )
                    }
                }

                Content(data, onNavigate)
            }
        }
    }
}

@Composable
private fun Content(data: DashboardData, onNavigate: (DashTarget) -> Unit) {
    val fmt = DecimalFormat("#,###")
    Column(Modifier.padding(bottom = 28.dp)) {

        // ---------- 实时趋势 ----------
        SectionCard(
            title = "实时访问趋势",
            subtitle = "近时段请求量 · 来源 ${data.accessSource.ifBlank { "—" }}"
        ) {
            if (data.accessSeries.size > 1) {
                TrendChart(
                    points = data.accessSeries,
                    color = InfoColor,
                    height = 110.dp,
                    labels = data.accessLabels
                )
            } else {
                EmptyBox("暂无访问趋势数据")
            }
        }

        SectionCard(
            title = "实时拦截趋势",
            subtitle = "近时段拦截量 · 来源 ${data.blockSource.ifBlank { "—" }}"
        ) {
            if (data.blockSeries.size > 1) {
                TrendChart(
                    points = data.blockSeries,
                    color = Danger,
                    height = 110.dp,
                    labels = data.blockLabels
                )
            } else {
                EmptyBox("暂无拦截趋势数据")
            }
        }

        if (data.qpsSeries.size > 1) {
            SectionCard(
                title = "实时 QPS",
                subtitle = "当前 ${DecimalFormat("0.#").format(data.qpsSeries.lastOrNull() ?: 0f)} 次/秒"
            ) {
                TrendChart(points = data.qpsSeries, color = Brand, height = 120.dp)
            }
        }

        // ---------- 防护拦截 ----------
        SectionCard("防护拦截", subtitle = "各类型日志的累计量，点击卡片进入对应日志") {
            MetricGrid(
                onNavigate = onNavigate,
                cards = listOf(
                    card("攻击事件", fmt.format(data.attackEvents), Danger, Icons.Default.Bolt, "语义分析检出", DashTarget.LogTab(0)),
                    card("频率控制", fmt.format(data.aclRecords), Warn, Icons.Default.Block, "访问控制拦截", DashTarget.LogTab(1)),
                    card("人机验证", fmt.format(data.challengeRecords), InfoColor, Icons.Default.Security, "挑战次数", DashTarget.LogTab(2)),
                    card("身份认证", fmt.format(data.authRecords), Ok, Icons.Default.Verified, "认证记录", DashTarget.LogTab(3))
                )
            )
        }

        // ---------- 防护资源 ----------
        SectionCard("防护资源", subtitle = "站点、规则与凭据规模，点击进入管理页") {
            MetricGrid(
                onNavigate = onNavigate,
                cards = listOf(
                    card("防护站点", data.siteTotal.toString(), Brand, Icons.Default.Dns, "反代应用", DashTarget.Page("sites")),
                    card("自定义规则", data.policyTotal.toString(), InfoColor, Icons.Default.Rule, "策略条数", DashTarget.ProtectTab("policy")),
                    card("IP 组", data.ipGroupTotal.toString(), Warn, Icons.Default.Groups, "黑白名单组", DashTarget.ProtectTab("ipgroup")),
                    card("证书", data.certTotal.toString(), Ok, Icons.Default.Verified, "已导入证书", DashTarget.Page("system"))
                )
            )
        }

        // ---------- 防护能力 ----------
        SectionCard("防护能力", subtitle = "防护模块、频率限制与黑白名单入口") {
            MetricGrid(
                onNavigate = onNavigate,
                cards = listOf(
                    card("防护模块", "配置", Brand, Icons.Default.Security, "语义分析 / Bot 等", DashTarget.ProtectTab("mode")),
                    card("频率限制", "配置", Warn, Icons.Default.Block, "CC 防护策略", DashTarget.ProtectTab("acl")),
                    card("黑白名单", "管理", Danger, Icons.Default.Groups, "白名单 / 黑名单", DashTarget.ProtectTab("rule")),
                    card("访问日志", "查看", InfoColor, Icons.Default.TrendingUp, "实时请求记录", DashTarget.LogTab(4))
                )
            )
        }

        if (data.posture.isNotEmpty()) {
            SectionCard("安全态势明细", subtitle = "来自 security_posture 统计接口") {
                val entries = data.posture.entries.toList()
                entries.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { (k, v) ->
                            StatCard(
                                title = POSTURE_LABELS[k] ?: k,
                                value = fmt.format(v),
                                color = Brand,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        if (data.realtime.isNotEmpty()) {
            SectionCard("最新安全事件", subtitle = "安全态势实时流") {
                data.realtime.forEach { e -> RealtimeRow(e) }
            }
        }

        SectionCard("数据说明") {
            Text(
                "拦截类指标取自各日志接口的真实累计计数；访问与拦截趋势优先使用控制台内部统计接口，" +
                    "接口不可用时按最近 100 条日志的时间分布分桶估算，仅反映相对趋势。" +
                    "安全态势统计接口在社区版多数返回 0，因此仅在有非零值时展示。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (data.attackEvents == 0L && data.aclRecords == 0L && data.realtime.isEmpty()) {
            EmptyBox("暂无安全事件数据。请确认 API Token 已勾选「事件/日志」相关权限")
        }
    }
}

/**
 * 指标卡 + 跳转目标的绑定。
 * 抽成独立类型是为了让排版与点击行为解耦：卡片怎么排是 [MetricGrid] 的事，
 * 点下去去哪里由 [DashTarget] 描述。
 */
private data class MetricCard(
    val title: String,
    val value: String,
    val color: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val subtitle: String,
    val target: DashTarget
)

/** 每行两张卡；末尾不足两张时补空位，避免 Row 宽度塌陷导致卡片被拉伸 */
@Composable
private fun MetricGrid(cards: List<MetricCard>, onNavigate: (DashTarget) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        cards.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { c ->
                    StatCard(
                        title = c.title,
                        value = c.value,
                        color = c.color,
                        icon = c.icon,
                        subtitle = c.subtitle,
                        onClick = { onNavigate(c.target) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun card(
    title: String,
    value: String,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    subtitle: String,
    target: DashTarget
) = MetricCard(title, value, color, icon, subtitle, target)

private val POSTURE_LABELS = mapOf(
    "attack_deny" to "攻击拦截",
    "attack_allow" to "攻击放行",
    "black_hit" to "黑名单命中",
    "black_deny" to "黑名单拦截",
    "black_allow" to "黑名单放行",
    "white_hit" to "白名单命中",
    "acl_hit" to "访问控制命中",
    "waiting_hit" to "等待室命中",
    "challenge_deny" to "验证拦截",
    "challenge_allow" to "验证通过",
    "auth_deny" to "认证拦截",
    "auth_allow" to "认证通过"
)

@Composable
private fun RealtimeRow(e: RealtimeEvent) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(Warn.copy(alpha = 0.16f), CardShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Security, contentDescription = null, tint = Warn, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    e.siteName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    listOf(e.attackType, e.ip).filter { it.isNotBlank() && it != "—" }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (e.timeText.isNotBlank()) {
                Text(
                    e.timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun shortHost(url: String): String =
    url.removePrefix("https://").removePrefix("http://").trimEnd('/')

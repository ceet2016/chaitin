package xyz.anye.wafce.ui.screens

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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
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
import xyz.anye.wafce.core.Resp
import xyz.anye.wafce.core.UiState
import xyz.anye.wafce.core.readable
import xyz.anye.wafce.data.net.ApiClient
import xyz.anye.wafce.data.store.AccountProfile
import xyz.anye.wafce.ui.components.AsyncBox
import xyz.anye.wafce.ui.components.CardShape
import xyz.anye.wafce.ui.components.EmptyBox
import xyz.anye.wafce.ui.components.HeroHeader
import xyz.anye.wafce.ui.components.HeroMetric
import xyz.anye.wafce.ui.components.SectionCard
import xyz.anye.wafce.ui.components.StatCard
import xyz.anye.wafce.ui.components.TrendChart
import xyz.anye.wafce.ui.theme.Brand
import xyz.anye.wafce.ui.theme.Danger
import xyz.anye.wafce.ui.theme.InfoColor
import xyz.anye.wafce.ui.theme.Ok
import xyz.anye.wafce.ui.theme.Warn
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 仪表盘数据。
 *
 * 数据源取舍（雷池 9.4.2 社区版实测）：
 * - /open/security_posture/statistics 不带时间参数返回全 0，带 time_preset 则
 *   返回 license required —— 该统计在社区版拿不到真实值，仅作补充展示
 * - 因此主指标改用各日志接口的 total 计数，这些在社区版是真实数据
 * - QPS 用 /stat/qps，实时事件用 /open/security_posture/realtime
 */
data class DashboardData(
    val version: String = "",
    val attackEvents: Long = 0,
    val aclRecords: Long = 0,
    val challengeRecords: Long = 0,
    val authRecords: Long = 0,
    val siteTotal: Int = 0,
    val policyTotal: Int = 0,
    val qpsSeries: List<Float> = emptyList(),
    val realtime: List<RealtimeEvent> = emptyList(),
    val posture: Map<String, Long> = emptyMap()
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
                state = UiState.Fail(e.readable(), e is xyz.anye.wafce.core.UnauthorizedException)
            }
        }
    }

    private suspend fun fetch(target: AccountProfile): DashboardData {
        return coroutineScope {
            val api = ApiClient.apiFor(target)
        val vSystem = async { runCatching { api.systemInfo() } }
        val vEvents = async { runCatching { api.events(mapOf("page" to "1", "page_size" to "1")) } }
        val vAcl = async { runCatching { api.recordsAcl(mapOf("page" to "1", "page_size" to "1")) } }
        val vChallenge = async { runCatching { api.recordsChallenge(mapOf("page" to "1", "page_size" to "1")) } }
        val vAuth = async { runCatching { api.recordsAuthDefense(mapOf("page" to "1", "page_size" to "1")) } }
        val vSites = async { runCatching { api.sites(mapOf("page" to "1", "page_size" to "1")) } }
        val vPolicy = async { runCatching { api.policies(mapOf("page" to "1", "page_size" to "1")) } }
        val vQps = async { runCatching { api.statQps(emptyMap()) } }
        val vRealtime = async { runCatching { api.securityRealtime(emptyMap()) } }
        val vPosture = async { runCatching { api.securityPosture() } }

        DashboardData(
            version = versionOf(vSystem.await().getOrNull()),
            attackEvents = totalOf(vEvents.await().getOrNull()),
            aclRecords = totalOf(vAcl.await().getOrNull()),
            challengeRecords = totalOf(vChallenge.await().getOrNull()),
            authRecords = totalOf(vAuth.await().getOrNull()),
            siteTotal = totalOf(vSites.await().getOrNull()).toInt(),
            policyTotal = totalOf(vPolicy.await().getOrNull()).toInt(),
            qpsSeries = qpsOf(vQps.await().getOrNull()),
            realtime = realtimeOf(vRealtime.await().getOrNull()),
            posture = postureOf(vPosture.await().getOrNull())
        )
        }
    }

    fun startPolling(target: AccountProfile, seconds: Int = 30) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(seconds * 1000L)
                load(target, silent = true)
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
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
    private fun qpsOf(body: JsonObject?): List<Float> = runCatching {
        val arr = Resp.firstArray(Resp.unwrap(body)) ?: return@runCatching emptyList()
        val out = mutableListOf<Float>()
        for (el in arr) {
            if (!el.isJsonObject) continue
            var sum = 0.0
            for ((_, v) in el.asJsonObject.entrySet()) {
                if (v.isJsonPrimitive && v.asJsonPrimitive.isNumber) sum += v.asDouble
            }
            out.add(sum.toFloat())
        }
        out
    }.getOrDefault(emptyList())

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
}

@Composable
fun DashboardScreen(account: AccountProfile) {
    val vm: DashboardVm = viewModel()
    var autoRefresh by remember { mutableStateOf(false) }

    DisposableEffect(account.id) {
        vm.load(account)
        onDispose { }
    }
    DisposableEffect(autoRefresh, account.id) {
        if (autoRefresh) vm.startPolling(account) else Unit
        onDispose { }
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
                            IconButton(onClick = { autoRefresh = !autoRefresh }) {
                                Icon(
                                    Icons.Default.Speed,
                                    contentDescription = "自动刷新",
                                    tint = if (autoRefresh) Color.White else Color.White.copy(alpha = 0.6f)
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
                        HeroMetric("拦截记录", DecimalFormat("#,###").format(data.aclRecords), Modifier.weight(1f))
                        HeroMetric(
                            "实时 QPS",
                            DecimalFormat("0.#").format(data.qpsSeries.lastOrNull() ?: 0f),
                            Modifier.weight(1f)
                        )
                    }
                }

                Content(data)
            }
        }
    }
}

@Composable
private fun Content(data: DashboardData) {
    val fmt = DecimalFormat("#,###")
    Column(Modifier.padding(bottom = 28.dp)) {
        SectionCard("防护拦截", subtitle = "按日志类型统计的真实累计量") {
            Row(Modifier.fillMaxWidth()) {
                StatCard(
                    title = "攻击事件", value = fmt.format(data.attackEvents), color = Danger,
                    icon = Icons.Default.Bolt, subtitle = "语义分析检出", modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "拦截记录", value = fmt.format(data.aclRecords), color = Warn,
                    icon = Icons.Default.Block, subtitle = "访问控制拦截", modifier = Modifier.weight(1f)
                )
            }
            Row(Modifier.fillMaxWidth()) {
                StatCard(
                    title = "人机验证", value = fmt.format(data.challengeRecords), color = InfoColor,
                    icon = Icons.Default.Security, subtitle = "挑战次数", modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "身份认证", value = fmt.format(data.authRecords), color = Ok,
                    icon = Icons.Default.Verified, subtitle = "认证记录", modifier = Modifier.weight(1f)
                )
            }
        }

        SectionCard("资源规模") {
            Row(Modifier.fillMaxWidth()) {
                StatCard(
                    title = "防护站点", value = data.siteTotal.toString(), color = Brand,
                    icon = Icons.Default.Dns, modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "自定义规则", value = data.policyTotal.toString(), color = InfoColor,
                    icon = Icons.Default.Rule, modifier = Modifier.weight(1f)
                )
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
                "趋势图为雷池商业版能力（stat/advance 系列），社区版返回 license required；" +
                    "安全态势统计接口在社区版不带时间参数时返回 0，因此主指标采用各日志接口的真实累计计数。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (data.attackEvents == 0L && data.aclRecords == 0L && data.realtime.isEmpty()) {
            EmptyBox("暂无安全事件数据。请确认 API Token 已勾选「事件/日志」相关权限")
        }
    }
}

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

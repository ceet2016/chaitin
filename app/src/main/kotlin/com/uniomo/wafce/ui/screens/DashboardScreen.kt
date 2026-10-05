package com.uniomo.wafce.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import java.util.Locale
import com.uniomo.wafce.core.Resp
import com.uniomo.wafce.core.TimeFmt
import com.uniomo.wafce.core.UiState
import com.uniomo.wafce.core.readable
import com.uniomo.wafce.data.net.AccessSummary
import com.uniomo.wafce.data.net.ApiClient
import com.uniomo.wafce.data.net.ErrorStatusSummary
import com.uniomo.wafce.data.net.GeoItem
import com.uniomo.wafce.data.net.GeoRank
import com.uniomo.wafce.data.net.InterceptSummary
import com.uniomo.wafce.data.store.AccountProfile
import com.uniomo.wafce.ui.components.AsyncBox
import com.uniomo.wafce.ui.components.CardShape
import com.uniomo.wafce.ui.components.EmptyBox
import com.uniomo.wafce.ui.components.HeroHeader
import com.uniomo.wafce.ui.components.FlowMetric
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
 * 数据源取舍（以长亭官方 OpenAPI 为准，basePath=/api）：
 * - 各日志接口的 total 计数在社区版是真实数据，作为拦截类指标的主来源
 * - /api/stat/qps 提供实时 QPS 序列
 * - 访问/拦截趋势走 /api/stat/advance/trend/{access,intercept}，
 *   参数为秒级 begin_time / end_time；社区版回退 /api/stat/basic/{access,attack}。
 *   时间窗按「近 24 小时 → 近 7 天」依次尝试，**都没有数据时整个卡片不渲染**
 *   （不做日志分桶之类的估算，避免给用户看一个并非真实统计的图）
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
    /** 访问量趋势，元素为数值点；为空表示无数据，卡片应整体隐藏 */
    val accessSeries: List<Float> = emptyList(),
    val accessLabels: List<String> = emptyList(),
    /** 拦截量趋势 */
    val blockSeries: List<Float> = emptyList(),
    val blockLabels: List<String> = emptyList(),
    val realtime: List<RealtimeEvent> = emptyList(),
    val posture: Map<String, Long> = emptyMap(),
    /** 趋势数据来源说明，用于在页面上诚实标注 */
    val accessSource: String = "",
    val blockSource: String = "",
    /**
     * 访问侧汇总（对应控制台「流量分析」页的指标卡）。
     * null 表示该版本控制台不提供此统计，卡片应整体隐藏而不是显示 0。
     */
    val accessSummary: AccessSummary? = null,
    /** 拦截侧汇总 */
    val interceptSummary: InterceptSummary? = null,
    /** 汇总指标的时间窗标签，用于标明这些数字的统计范围 */
    val summaryWindow: String = "",
    /**
     * 错误状态码统计（4xx / 5xx）。
     * 对应控制台「流量分析」页的「错误统计」区块；社区版 OpenAPI 未公开该接口，
     * 探测不到时为 null，UI 层整体隐藏。
     */
    val errorSummary: ErrorStatusSummary? = null,
    /**
     * 来源地分布排行（国家 / 省份）。
     * 对应控制台「流量分析」页的地理位置区块；社区版未公开该接口，
     * 探测不到时为 null，UI 层整体隐藏。
     */
    val geoRank: GeoRank? = null
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

            // 趋势：近 24 小时优先，无数据再退近 7 天；两者皆无则不渲染卡片
            val vAccess = async { trend(ACCESS_TREND_PATHS) }
            val vBlock = async { trend(BLOCK_TREND_PATHS) }

            val access = vAccess.await()
            val block = vBlock.await()

            // 汇总指标与趋势共用同一个时间窗，避免「卡片写 24 小时、数字却是 7 天」
            val winLabel = access.third.substringBefore(" · ").ifBlank {
                block.third.substringBefore(" · ")
            }
            val winSec = TREND_WINDOWS.firstOrNull { it.first == winLabel }?.second
                ?: 24L * 3600L
            val nowSec = System.currentTimeMillis() / 1000L

            // 汇总指标取不到就返回 null，UI 层隐藏整个区块（不显示假 0）
            val accessSum = async {
                ApiClient.probeAccessSummary(targetId, ACCESS_SUMMARY_PATHS, nowSec - winSec, nowSec)
            }
            val interceptSum = async {
                ApiClient.probeInterceptSummary(targetId, INTERCEPT_SUMMARY_PATHS, nowSec - winSec, nowSec)
            }
            // 4xx/5xx：社区版未公开该接口，只做候选探测，取不到就整块隐藏
            val errorSum = async {
                ApiClient.probeErrorStatusSummary(targetId, ERROR_SUMMARY_PATHS, nowSec - winSec, nowSec)
            }
            // 来源地分布：同样未公开，只做候选探测
            val geoRank = async {
                ApiClient.probeGeoRank(targetId, GEO_PATHS, nowSec - winSec, nowSec)
            }

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
                accessSeries = access.first,
                accessLabels = access.second,
                blockSeries = block.first,
                blockLabels = block.second,
                realtime = realtimeOf(vRealtime.await().getOrNull()),
                posture = postureOf(vPosture.await().getOrNull()),
                accessSource = access.third,
                blockSource = block.third,
                accessSummary = accessSum.await(),
                interceptSummary = interceptSum.await(),
                summaryWindow = winLabel,
                errorSummary = errorSum.await(),
                geoRank = geoRank.await()
            )
        }
    }

    /**
     * 按「近 24 小时 → 近 7 天」依次探测趋势接口。
     *
     * 返回 (数值序列, 首尾标签, 来源说明)；两个时间窗都没有数据时返回空列表，
     * 由 UI 决定隐藏整个卡片 —— 不做任何形式的估算兜底。
     */
    private suspend fun trend(paths: List<String>): Triple<List<Float>, List<String>, String> {
        val nowSec = System.currentTimeMillis() / 1000L
        for ((label, windowSec) in TREND_WINDOWS) {
            val pts = ApiClient.probeTrend(targetId, paths, nowSec - windowSec, nowSec)
            if (pts.size >= 2) {
                return Triple(
                    pts.map { it.value },
                    listOf(pts.first().label, pts.last().label),
                    "$label · ${paths.first().substringAfterLast('/')}"
                )
            }
        }
        return Triple(emptyList(), emptyList(), "")
    }

    /** 趋势探测需要账号 id 来取 Retrofit 实例，随每次 fetch 记录一次 */
    private var targetId: Long = -1L

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
            out.add(
                RealtimeEvent(
                    siteName = Resp.str(inner, "site_name", "host", "site").ifBlank { "—" },
                    ip = Resp.str(inner, "ip", "src_ip").ifBlank { "—" },
                    attackType = Resp.str(o, "attack_type", "event_type", "type").ifBlank { "安全事件" },
                    // time_stamp 是整数秒，统一交给 TimeFmt 处理量级判定
                    timeText = TimeFmt.formatOf(o, TimeFmt.PAT_MINUTE_STAMP, "time_stamp", "timestamp", "time")
                )
            )
            if (out.size >= 8) break
        }
        out
    }.getOrDefault(emptyList())

    private companion object {
        /** 趋势时间窗：先试 24 小时，再试 7 天。都无数据则隐藏卡片。 */
        val TREND_WINDOWS = listOf(
            "近 24 小时" to 24L * 3600L,
            "近 7 天" to 7L * 24L * 3600L
        )

        /** 访问趋势候选路径：官方 /stat/advance/trend/access */
        val ACCESS_TREND_PATHS = listOf(
            "api/stat/advance/trend/access"
        )

        /** 拦截趋势候选路径：官方 /stat/advance/trend/intercept */
        val BLOCK_TREND_PATHS = listOf(
            "api/stat/advance/trend/intercept"
        )

        /**
         * 访问侧汇总候选路径（请求次数 / PV / UV / 独立 IP）。
         * 对应官方 stat.AdvanceAccessRes。
         */
        val ACCESS_SUMMARY_PATHS = listOf(
            "api/stat/advance/access"
        )

        /** 拦截侧汇总候选路径（拦截次数 / 攻击 IP），对应 stat.AdvanceAttackRes */
        val INTERCEPT_SUMMARY_PATHS = listOf(
            "api/stat/advance/attack"
        )

        /**
         * 4xx / 5xx 错误统计候选路径，对应 stat.AdvanceErrorStatusCodeRes。
         *
         * 该模型在官方 OpenAPI 的 definitions 里存在但没有任何 path 引用它，
         * 社区版未公开；控制台「流量分析」页能显示说明服务端内部有数据，
         * 这里按stat 系列的命名规律列候选，探测不到就整块隐藏（不显示假 0）。
         */
        val ERROR_SUMMARY_PATHS = listOf(
            "api/stat/advance/error_status_code",
            "api/stat/advance/error_statuscode",
            "api/stat/advance/error-status-code",
            "api/stat/advance/status_code",
            "api/stat/advance/error"
        )

        /**
         * 来源地分布候选路径，对应 model.ReportDataGeo。
         * 同样未在paths 中公开，按 stat / report 两条线列候选。
         */
        val GEO_PATHS = listOf(
            "api/stat/advance/geo",
            "api/stat/advance/geo_info",
            "api/report/geo",
            "api/report/data"
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

/**
 * 错误率格式化：保留两位小数百分比。
 * 分母为 0 时返回 "—"，避免出现 NaN% / Infinity%。
 */
private fun pct(part: Long, total: Long): String {
    if (total <= 0L) return "—"
    val v = part.toDouble() * 100.0 / total.toDouble()
    return String.format(Locale.CHINA, "%.2f%%", v)
}

/**
 * 来源地排行列表：名称 + 计数 + 按占比长度的条形。
 *
 * 条形宽度按当前列表的最大值归一化，这样即使绝对值很小也能看出分布差异；
 * 全0 时不画条（避免画一条长度为 0 的线看着像渲染错误）。
 */
@Composable
private fun GeoRankList(
    title: String,
    items: List<GeoItem>,
    fmt: DecimalFormat,
    color: androidx.compose.ui.graphics.Color
) {
    if (items.isEmpty()) return
    val maxCount = items.maxOf { it.count }.coerceAtLeast(1L)
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        items.forEach { item ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                // 背景条：Base 主题下用 surfaceVariant，深色下自动变暗
                Box(
                    Modifier
                        .weight(2f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    val ratio = (item.count.toFloat() / maxCount.toFloat()).coerceIn(0f, 1f)
                    if (item.count > 0L && ratio > 0f) {
                        Box(
                            Modifier
                                .fillMaxWidth(ratio)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(3.dp))
                                .background(color.copy(alpha = 0.75f))
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    fmt.format(item.count),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun Content(data: DashboardData, onNavigate: (DashTarget) -> Unit) {
    val fmt = DecimalFormat("#,###")
    Column(Modifier.padding(bottom = 28.dp)) {

        // ---------- 流量分析指标卡 ----------
        // 对应控制台「流量分析」页的红框区域。取不到统计时整块隐藏，
        // 不显示全 0 的假指标 —— 那比不显示更误导人。
        val accessSum = data.accessSummary
        val interceptSum = data.interceptSummary
        if (accessSum != null || interceptSum != null) {
            SectionCard(
                title = "流量分析",
                subtitle = data.summaryWindow.ifBlank { "统计窗口以服务端为准" }
            ) {
                Column {
                    Row(Modifier.fillMaxWidth()) {
                        if (accessSum != null) {
                            FlowMetric("请求次数", fmt.format(accessSum.requests), Brand, Modifier.weight(1f))
                            FlowMetric("访问 PV", fmt.format(accessSum.pageViews), InfoColor, Modifier.weight(1f))
                            FlowMetric("访客 UV", fmt.format(accessSum.uniqueVisitors), Ok, Modifier.weight(1f))
                            FlowMetric("独立 IP", fmt.format(accessSum.uniqueIps), Warn, Modifier.weight(1f))
                        } else if (interceptSum != null) {
                            FlowMetric(
                                "拦截次数", fmt.format(interceptSum.intercept), Danger, Modifier.weight(1f)
                            )
                        }
                    }
                    if (accessSum != null && interceptSum != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth()) {
                            FlowMetric("拦截次数", fmt.format(interceptSum.intercept), Danger, Modifier.weight(1f))
                            FlowMetric("攻击 IP", fmt.format(interceptSum.attackIps), Warn, Modifier.weight(1f))
                            Spacer(Modifier.weight(2f))
                        }
                    }
                }
            }
        }

        // ---------- 错误统计（4xx / 5xx） ----------
        // 对应控制台「流量分析」页的「错误统计」区块。
        // 社区版 OpenAPI 未公开该接口（stat.AdvanceErrorStatusCodeRes 无path 引用），
        // 探测不到就不渲染，不显示两个 0 让人误以为统计正常。
        val errSum = data.errorSummary
        if (errSum != null) {
            // 错误率分母用请求次数；拿不到请求次数时只显示错误数，不编造分母
            val totalReq = accessSum?.requests ?: 0L
            SectionCard(
                title = "错误统计",
                subtitle = data.summaryWindow.ifBlank { "统计窗口以服务端为准" }
            ) {
                Column {
                    Row(Modifier.fillMaxWidth()) {
                        FlowMetric("4xx 错误数", fmt.format(errSum.error4xx), Warn, Modifier.weight(1f))
                        FlowMetric("5xx 错误数", fmt.format(errSum.error5xx), Danger, Modifier.weight(1f))
                        FlowMetric(
                            "4xx 错误率",
                            if (totalReq > 0L) pct(errSum.error4xx, totalReq) else "—",
                            Warn,
                            Modifier.weight(1f)
                        )
                        FlowMetric(
                            "5xx 错误率",
                            if (totalReq > 0L) pct(errSum.error5xx, totalReq) else "—",
                            Danger,
                            Modifier.weight(1f)
                        )
                    }
                    if (totalReq <= 0L) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "错误率需要请求次数作为分母，当前实例未提供该统计",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ---------- 来源地分布 ----------
        // 对应控制台「流量分析」页的地理位置区块（地球图 + 国家排行）。
        // 移动端不画 3D 地球（无 3D 依赖且交互价值低），改为排行列表：
        // 同样一眼看出「流量来自哪、拦截来自哪」，且文字可被无障碍朗读。
        val geo = data.geoRank
        if (geo != null) {
            SectionCard(
                title = "来源地分布",
                subtitle = data.summaryWindow.ifBlank { "统计窗口以服务端为准" }
            ) {
                Column {
                    GeoRankList(
                        title = "访问来源国家",
                        items = geo.requestCountry,
                        fmt = fmt,
                        color = InfoColor
                    )
                    if (geo.interceptCountry.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        GeoRankList(
                            title = "拦截来源国家",
                            items = geo.interceptCountry,
                            fmt = fmt,
                            color = Danger
                        )
                    }
                    if (geo.requestProvince.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        GeoRankList(
                            title = "访问来源省份",
                            items = geo.requestProvince,
                            fmt = fmt,
                            color = Ok
                        )
                    }
                }
            }
        }

        // ---------- 实时趋势 ----------
        // 无数据时整块不渲染：宁可少一个卡片，也不给用户看一个并非真实统计的图
        if (data.accessSeries.size > 1) {
            SectionCard(
                title = "访问趋势",
                subtitle = "${data.accessSource.ifBlank { "统计接口" }} · 共 ${data.accessSeries.size} 个数据点"
            ) {
                TrendChart(
                    points = data.accessSeries,
                    color = InfoColor,
                    height = 110.dp,
                    labels = data.accessLabels
                )
            }
        }

        if (data.blockSeries.size > 1) {
            SectionCard(
                title = "拦截趋势",
                subtitle = "${data.blockSource.ifBlank { "统计接口" }} · 共 ${data.blockSeries.size} 个数据点"
            ) {
                TrendChart(
                    points = data.blockSeries,
                    color = Danger,
                    height = 110.dp,
                    labels = data.blockLabels
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
                    card("攻击日志", "查看", InfoColor, Icons.Default.TrendingUp, "全部攻击记录", DashTarget.LogTab(0))
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
                "拦截类指标取自各日志接口的真实累计计数。" +
                    "访问与拦截趋势通过官方统计接口 /api/stat/advance/trend/{access,intercept} 获取，" +
                    "按秒级时间戳先取近 24 小时，无数据再取近 7 天；两个时间窗都没有数据时不显示该图。" +
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

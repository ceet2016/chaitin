package com.uniomo.wafce.data.net

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.uniomo.wafce.core.HttpStatusException
import com.uniomo.wafce.core.LicenseRequiredException
import com.uniomo.wafce.core.Resp
import com.uniomo.wafce.core.UnauthorizedException
import com.uniomo.wafce.data.store.AccountProfile
import com.uniomo.wafce.data.store.AccountStore
import com.uniomo.wafce.data.store.AuthMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 依据账号配置动态注入鉴权头。
 *
 * - csrf / login 接口本身不带凭证，否则新版本会直接拒绝
 * - 其余接口按账号的认证方式注入 X-SLCE-API-TOKEN 或 Authorization: Bearer
 */
private class AuthInterceptor(private val accountId: Long) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val origin = chain.request()
        val builder = origin.newBuilder()
            .header("Accept", "application/json")
            .header("User-Agent", "SafeLineCEClient/1.0 (Android)")

        val path = origin.url.encodedPath
        val skipAuth = path.endsWith("/auth/csrf") || path.endsWith("/auth/login") || path.endsWith("/init/info")

        if (!skipAuth) {
            val account = AccountStore.all().firstOrNull { it.id == accountId }
            if (account != null) {
                when (account.authMode) {
                    AuthMode.TOKEN -> {
                        val token = AccountStore.secretOf(accountId).apiToken
                        if (token.isNotBlank()) builder.header("X-SLCE-API-TOKEN", token)
                    }
                    AuthMode.SESSION -> {
                        SessionStore.jwtOf(accountId)?.let {
                            if (it.isNotBlank() && !SessionStore.isExpired(accountId)) {
                                builder.header("Authorization", "Bearer $it")
                            }
                        }
                    }
                }
            }
        }
        return chain.proceed(builder.build())
    }
}

/** 统一把非 2xx 转成业务异常，并把服务端正文带上便于排查 */
private class HttpErrorInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        logRequest(request)
        val resp = chain.proceed(request)
        if (resp.code == 401 || resp.code == 403) {
            val snippet = try {
                resp.peekBody(2048).string()
            } catch (e: Exception) {
                ""
            }
            resp.close()
            // 403 + license required 是商业版能力限制，绝不是登录失效
            if (resp.code == 403 && snippet.contains("license required", ignoreCase = true)) {
                throw LicenseRequiredException()
            }
            throw UnauthorizedException()
        }
        if (resp.code !in 200..299) {
            val snippet = try {
                resp.peekBody(4096).string()
            } catch (e: Exception) {
                ""
            }
            Log.w(TAG, "HTTP ${resp.code} proto=${resp.protocol} <- ${request.url} \n body: ${snippet.take(500)}")
            resp.close()
            throw HttpStatusException(resp.code, request.url.toString(), snippet)
        }
        Log.d(TAG, "HTTP ${resp.code} proto=${resp.protocol} <- ${request.url.encodedPath}")
        return resp
    }
}

private const val TAG = "SafeLineHTTP"

/**
 * 打印请求体用于排查 400 类错误。
 *
 * 安全要求（这条曾经出过真实问题）：
 * - 按「键名黑名单」整体遮蔽值，长度、位数一概不泄露；
 * - 黑名单必须覆盖 TOTP 的 code 与 csrf_token ——它们是30 秒有效的一次性凭据，
 *   落进 logcat 等于把 2FA 码交给任何有 READ_LOGS 权限的进程；
 * - Release 包同样会输出，所以脱敏不能靠 BuildConfig.DEBUG 兜底。
 */
private val SENSITIVE_KEYS = listOf(
    "password", "passwd", "secret",
    "token", "api_token", "access_token", "refresh_token", "jwt",
    "csrf_token", "code", "key", "private_key", "authorization"
)

/**
 * 敏感键名 → 值全部遮蔽。
 *
 * 匹配形如 `"token": "任意内容"`，同时保留键名和引号结构，方便排查时
 * 还能看出请求带了哪些字段。
 */
private val MASK_REGEX: Regex = Regex(
    pattern = buildString {
        append("\"(")
        append(SENSITIVE_KEYS.joinToString("|") { Regex.escape(it) })
        append(")\"\\s*:\\s*\"(?:\\\\.|[^\"\\\\])*\"")
    },
    option = RegexOption.IGNORE_CASE
)

private fun logRequest(request: okhttp3.Request) {
    val body = request.body ?: return
    try {
        val buffer = okio.Buffer()
        body.writeTo(buffer)
        val raw = buffer.readUtf8()
        val masked = MASK_REGEX.replace(raw) { m ->
            val key = m.groupValues[1]
            "\"" + key + "\": \"<redacted>\""
        }
        Log.d(TAG, "REQ ${request.method} ${request.url.encodedPath} ct=${body.contentType()} body=${masked.take(400)}")
    } catch (e: Exception) {
        Log.d(TAG, "REQ ${request.method} ${request.url.encodedPath} <body 读取失败>")
    }
}

object ApiClient {

    private val gson = Gson()
    private val apiCache = ConcurrentHashMap<Long, Pair<String, SafeLineApi>>()

    fun apiFor(account: AccountProfile): SafeLineApi {
        val cached = apiCache[account.id]
        if (cached != null && cached.first == account.baseUrl) return cached.second

        val okHttp = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // 雷池控制台前的 nginx 在 HTTP/2 下会对部分 POST 返回 400 invalid request，
            // 因此这里锁定 HTTP/1.1，与官方控制台浏览器的行为保持一致
            .protocols(listOf(Protocol.HTTP_1_1))
            .apply {
                // 社区版控制台使用自签证书，必须放宽校验，否则无法连接
                if (account.trustAllCert) {
                    sslSocketFactory(Tls.permissiveSocketFactory, Tls.manager)
                    hostnameVerifier { _, _ -> true }
                }
            }
            .addInterceptor(AuthInterceptor(account.id))
            .addInterceptor(HttpErrorInterceptor())
            .build()

        val api = Retrofit.Builder()
            .baseUrl(account.baseUrl)
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(SafeLineApi::class.java)

        apiCache[account.id] = account.baseUrl to api
        return api
    }

    fun invalidate(accountId: Long) {
        apiCache.remove(accountId)
    }

    /**
     * 探测趋势统计接口。
     *
     * 存在的原因：官方 OpenAPI 给的路径在部分版本属商业版能力
     *（社区版返回 403 license required），且路径本身随版本变化，
     * 因此按候选顺序试探，命中第一个能解析出 ≥2 个数据点的即返回。
     *
     * 参数口径易错：官方要求 begin_time / end_time 为**秒级**时间戳，
     * 写成毫秒会导致服务端返回空数组，看起来像「没有数据」。
     *
     * @param beginSec 起始时间（秒）
     * @param endSec 结束时间（秒）
     * @return 解析出的时序点；全部候选都不可用或无数据时返回空列表
     */
    suspend fun probeTrend(
        accountId: Long,
        candidates: List<String>,
        beginSec: Long,
        endSec: Long
    ): List<Resp.SeriesPoint> {
        val body = probeJson(accountId, candidates, beginSec, endSec) ?: return emptyList()
        val pts = Resp.seriesOf(body)
        // 至少 2 个点才画得出趋势；全 0 也视为无数据，避免画出贴底的假图
        return if (pts.size >= 2 && pts.any { it.value > 0f }) pts else emptyList()
    }

    /**
     * 按候选顺序取第一个能成功解析的响应体，失败或返回空则继续试下一个。
     * 统一收口让趋势与汇总指标探测共用同一套错误处理。
     */
    private suspend fun probeJson(
        accountId: Long,
        candidates: List<String>,
        beginSec: Long,
        endSec: Long
    ): JsonObject? {
        val api = apiCache[accountId]?.second ?: return null
        val q = mapOf(
            "begin_time" to beginSec.toString(),
            "end_time" to endSec.toString()
        )
        for (path in candidates) {
            val body = runCatching { api.rawGet(path, q) }.getOrNull() ?: continue
            if (Resp.obj(body).size() > 0) return body
        }
        return null
    }

    /**
     * 探测访问侧汇总指标（请求次数 / PV / UV / 独立 IP）。
     *
     * 对应官方 stat.AdvanceAccessRes。字段在不同版本命名略有出入，
     * 这里交给 [Resp] 的多key 兜底解析，解析不出时返回 null 由调用方决定是否展示。
     */
    suspend fun probeAccessSummary(
        accountId: Long,
        candidates: List<String>,
        beginSec: Long,
        endSec: Long
    ): AccessSummary? {
        val body = probeJson(accountId, candidates, beginSec, endSec) ?: return null
        val o = Resp.obj(body)
        val requests = Resp.l(o, "access", "request_count", "requests", "count", "total")
        val pv = Resp.l(o, "pv", "pv_count", "page_view")
        val uv = Resp.l(o, "session", "uv", "visitor_count", "visitors")
        val ip = Resp.l(o, "ip", "ip_count", "unique_ip")
        if (requests <= 0 && pv <= 0 && uv <= 0 && ip <= 0) return null
        return AccessSummary(
            requests = requests,
            pageViews = pv,
            uniqueVisitors = uv,
            uniqueIps = ip
        )
    }

    /** 探测拦截侧汇总指标（拦截次数 / 攻击 IP） */
    suspend fun probeInterceptSummary(
        accountId: Long,
        candidates: List<String>,
        beginSec: Long,
        endSec: Long
    ): InterceptSummary? {
        val body = probeJson(accountId, candidates, beginSec, endSec) ?: return null
        val o = Resp.obj(body)
        // intercept 在官方模型里是 object（按状态码分组），总数需要单独求和。
        // 兼容两种形态：对象分组求和 / 直接是数字。
        val interceptEl = o.get("intercept")
        val intercept = when {
            interceptEl == null || interceptEl.isJsonNull -> 0L
            interceptEl.isJsonObject ->
                // 分组对象求和：{"4xx": 12, "5xx": 3} 或 {"4xx": {"count": 12}}
                interceptEl.asJsonObject.entrySet().sumOf { (_, v) ->
                    if (v.isJsonObject) Resp.l(v.asJsonObject, "count", "value", "total")
                    else runCatching { v.asJsonPrimitive.asLong }.getOrDefault(0L)
                }
            else -> {
                // intercept 直接是数字或数字字符串
                val flat = runCatching { interceptEl.asJsonPrimitive.asLong }.getOrDefault(0L)
                if (flat > 0L) flat
                else Resp.l(o, "intercept_count", "blocked", "count", "total")
            }
        }
        val attackIp = Resp.l(o, "attack_ip", "attack_ips", "attack_ip_count")
        if (intercept <= 0 && attackIp <= 0) return null
        return InterceptSummary(intercept = intercept, attackIps = attackIp)
    }

    /**
     * 探测错误状态码统计（4xx / 5xx）。
     *
     * 对应官方 stat.AdvanceErrorStatusCodeRes（error_4xx / error_5xx两个 integer）。
     * 注意：该模型在官方 OpenAPI 的 definitions 里存在，但**没有任何 path 引用它**，
     * 即社区版未公开该接口。因此这里只做候选探测：控制台「流量分析」页能显示，
     * 说明服务端内部有数据，但路径名随版本变化，猜不准则整块不显示（返回 null）。
     *
     * 错误率由调用方用「错误数 / 请求次数」算，本函数不返回分母。
     */
    suspend fun probeErrorStatusSummary(
        accountId: Long,
        candidates: List<String>,
        beginSec: Long,
        endSec: Long
    ): ErrorStatusSummary? {
        val body = probeJson(accountId, candidates, beginSec, endSec) ?: return null
        val o = Resp.obj(body)
        val e4 = Resp.l(o, "error_4xx", "4xx", "err4xx", "error4xx", "client_error")
        val e5 = Resp.l(o, "error_5xx", "5xx", "err5xx", "error5xx", "server_error")
        // 全 0 说明这段时间没有错误，视为无数据而不是显示两个 0
        if (e4 <= 0 && e5 <= 0) return null
        return ErrorStatusSummary(error4xx = e4, error5xx = e5)
    }

    /**
     * 探测来源国家/省份分布排行。
     *
     * 对应官方 model.ReportDataGeo（request_country / request_province /
     * intercept_country / intercept_province，元素为 {location, count}）。
     * 与 [probeErrorStatusSummary] 同样：模型存在于 definitions 但无 path 引用，
     * 社区版未公开，故按候选探测，取不到返回 null。
     *
     * @param topN 只保留访问量最高的若干条，避免把上百行直接塞进 UI
     */
    suspend fun probeGeoRank(
        accountId: Long,
        candidates: List<String>,
        beginSec: Long,
        endSec: Long,
        topN: Int = 8
    ): GeoRank? {
        val body = probeJson(accountId, candidates, beginSec, endSec) ?: return null
        val o = Resp.obj(body)
        // geo 可能直接挂在根对象上，也可能包一层 {"geo": {...}}
        val geo = Resp.obj(o).let { root ->
            val nested = root.get("geo")
            if (nested != null && nested.isJsonObject) Resp.obj(nested.asJsonObject) else root
        }
        val requestCountry = geoItems(geo, "request_country")
        val interceptCountry = geoItems(geo, "intercept_country")
        val requestProvince = geoItems(geo, "request_province")
        if (requestCountry.isEmpty() && interceptCountry.isEmpty() && requestProvince.isEmpty()) {
            return null
        }
        return GeoRank(
            requestCountry = requestCountry,
            interceptCountry = interceptCountry,
            requestProvince = requestProvince
        ).let { r ->
            // 统一截断到 topN，按 count 降序
            GeoRank(
                requestCountry = r.requestCountry.sortedByDescending { it.count }.take(topN),
                interceptCountry = r.interceptCountry.sortedByDescending { it.count }.take(topN),
                requestProvince = r.requestProvince.sortedByDescending { it.count }.take(topN)
            )
        }
    }

    /**
     * 从 geo 对象里取一个数组字段（元素形如 {"location": "中国", "count": 12}）。
     * 兼容两种元素形态：{location,count} 与 {key,count}。
     */
    private fun geoItems(geo: JsonObject, key: String): List<GeoItem> {
        val arr = geo.get(key) ?: return emptyList()
        if (!arr.isJsonArray) return emptyList()
        return runCatching {
            arr.asJsonArray.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                val name = Resp.str(o, "location", "key", "name", "country", "province")
                    .ifBlank { return@mapNotNull null }
                val count = Resp.l(o, "count", "value", "total", "num")
                GeoItem(name, count)
            }
        }.getOrDefault(emptyList())
    }
}

/** 访问侧汇总，对应官方 stat.AdvanceAccessRes */
data class AccessSummary(
    val requests: Long,
    val pageViews: Long,
    val uniqueVisitors: Long,
    val uniqueIps: Long
)

/** 拦截侧汇总，对应官方 stat.AdvanceAttackRes */
data class InterceptSummary(
    val intercept: Long,
    val attackIps: Long
)

/** 错误状态码统计，对应官方 stat.AdvanceErrorStatusCodeRes */
data class ErrorStatusSummary(
    val error4xx: Long,
    val error5xx: Long
)

/** 来源地分布条目，对应官方 model.ReportDataGeoItem（location + count） */
data class GeoItem(
    val name: String,
    val count: Long
)

/**
 * 来源地分布排行，对应官方 model.ReportDataGeo。
 * 三组列表各自按 count 降序、截断到 topN；空列表表示该维度无数据。
 */
data class GeoRank(
    val requestCountry: List<GeoItem>,
    val interceptCountry: List<GeoItem>,
    val requestProvince: List<GeoItem>
)

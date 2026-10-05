package com.uniomo.wafce.data.net

import com.google.gson.JsonObject
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.QueryMap
import retrofit2.http.Url

/**
 * 雷池社区版接口定义。
 *
 * 所有返回统一为 JsonObject（雷池响应外壳 { err, data, msg }），
 * 具体字段由上层用 [com.uniomo.wafce.core.Resp] 惰性解析。
 *
 * 路径约定：
 * - OpenAPI 前缀为 api/open，支持 X-SLCE-API-TOKEN 鉴权，也兼容会话 JWT
 * - 统计类前缀 api/dashboard、api/stat 属于控制台内部接口，仅支持会话 JWT
 */
interface SafeLineApi {

    // ===================== 认证 =====================

    /** 获取一次性 csrf_token，登录与 TFA 前都必须先取一次 */
    @GET("api/open/auth/csrf")
    suspend fun csrf(): JsonObject

    /** 密码登录，返回中间 JWT；若账号开启了两步验证，还需继续调用 [tfa] */
    @POST("api/open/auth/login")
    suspend fun login(@Body body: JsonObject): JsonObject

    /** 校验 TOTP 动态码，返回正式的会话 JWT */
    @POST("api/open/auth/tfa")
    suspend fun tfa(@Body body: JsonObject): JsonObject

    @POST("api/open/auth/logout")
    suspend fun logout(): JsonObject

    /** 判断系统是否完成初始化 / 是否已存在用户 */
    @GET("api/open/init/info")
    suspend fun initInfo(): JsonObject

    // ===================== 概览 / 仪表盘 =====================

    @GET("api/open/system")
    suspend fun systemInfo(): JsonObject

    @GET("api/stat/qps")
    suspend fun statQps(@QueryMap q: Map<String, String>): JsonObject

    /**
     * 访问量 / 拦截量趋势。
     *
     * 这两个接口属于控制台内部接口，路径与可用性随版本变化：
     * 社区版实测 /api/stat/requests 与 /api/stat/intercepts 可用，
     * 老版本则是 /api/dashboard/requests、/api/dashboard/intercepts。
     * 客户端通过 [rawGet] 依次尝试，命中任一即可，不做硬编码假设。
     */
    @GET("api/stat/requests")
    suspend fun statRequests(@QueryMap q: Map<String, String>): JsonObject

    @GET("api/stat/intercepts")
    suspend fun statIntercepts(@QueryMap q: Map<String, String>): JsonObject

    /**
     * 安全态势统计 —— 社区版可用。
     * 旧版 dashboard 系列接口在 9.x 已移除；stat/advance 系列为商业版能力，
     * 社区版请求会返回 403 license required，因此仪表盘以本接口为准。
     * 注意：注释里不要写带星号的路径通配，否则会触发 Kotlin 嵌套块注释。
     */
    @GET("api/open/security_posture/statistics")
    suspend fun securityPosture(): JsonObject

    /** 实时安全事件流 */
    @GET("api/open/security_posture/realtime")
    suspend fun securityRealtime(@QueryMap q: Map<String, String>): JsonObject

    // ===================== 防护站点 =====================

    @GET("api/open/site")
    suspend fun sites(@QueryMap q: Map<String, String>): JsonObject

    @GET("api/open/site/{id}")
    suspend fun site(@Path("id") id: Long): JsonObject

    @POST("api/open/site")
    suspend fun createSite(@Body body: JsonObject): JsonObject

    @PUT("api/open/site/{id}")
    suspend fun updateSite(@Path("id") id: Long, @Body body: JsonObject): JsonObject

    @DELETE("api/open/site/{id}")
    suspend fun deleteSite(@Path("id") id: Long): JsonObject

    /** 站点级防护配置（防护模式 / CC / 人机等） */
    @GET("api/open/site/{id}/mode")
    suspend fun siteMode(@Path("id") id: Long): JsonObject

    @PUT("api/open/site/{id}/mode")
    suspend fun updateSiteMode(@Path("id") id: Long, @Body body: JsonObject): JsonObject

    /** 站点级访问控制 / 频率限制（9.4.x 无全局 global/acl，频率限制下沉到站点） */
    @GET("api/open/site/{id}/acl")
    suspend fun siteAcl(@Path("id") id: Long): JsonObject

    @POST("api/open/site/{id}/acl")
    suspend fun createSiteAcl(@Path("id") id: Long, @Body body: JsonObject): JsonObject

    // ===================== 证书 =====================

    @GET("api/open/cert")
    suspend fun certs(@QueryMap q: Map<String, String>): JsonObject

    @GET("api/open/cert/{id}")
    suspend fun cert(@Path("id") id: Long): JsonObject

    @POST("api/open/cert")
    suspend fun createCert(@Body body: JsonObject): JsonObject

    @PUT("api/open/cert/{id}")
    suspend fun updateCert(@Path("id") id: Long, @Body body: JsonObject): JsonObject

    @DELETE("api/open/cert/{id}")
    suspend fun deleteCert(@Path("id") id: Long): JsonObject

    // ===================== IP 组 =====================

    @GET("api/open/ipgroup")
    suspend fun ipGroups(@QueryMap q: Map<String, String>): JsonObject

    @POST("api/open/ipgroup")
    suspend fun createIpGroup(@Body body: JsonObject): JsonObject

    @PUT("api/open/ipgroup")
    suspend fun updateIpGroup(@Body body: JsonObject): JsonObject

    @DELETE("api/open/ipgroup/{id}")
    suspend fun deleteIpGroup(@Path("id") id: Long): JsonObject

    // ===================== 访问控制规则 =====================

    /** 自定义规则列表（action: 0 放行 / 1 阻断 / 2 人机验证 / -1 全部） */
    @GET("api/open/policy")
    suspend fun policies(@QueryMap q: Map<String, String>): JsonObject

    @POST("api/open/policy")
    suspend fun createPolicy(@Body body: JsonObject): JsonObject

    @PUT("api/open/policy/{id}")
    suspend fun updatePolicy(@Path("id") id: Long, @Body body: JsonObject): JsonObject

    @DELETE("api/open/policy/{id}")
    suspend fun deletePolicy(@Path("id") id: Long): JsonObject

    /** 黑白名单规则 */
    @GET("api/open/rule")
    suspend fun rules(@QueryMap q: Map<String, String>): JsonObject

    @POST("api/open/rule")
    suspend fun createRule(@Body body: JsonObject): JsonObject

    @PUT("api/open/rule/{id}")
    suspend fun updateRule(@Path("id") id: Long, @Body body: JsonObject): JsonObject

    @DELETE("api/open/rule/{id}")
    suspend fun deleteRule(@Path("id") id: Long): JsonObject

    // ===================== 全局防护配置 =====================

    /** 防护模块开关（语义分析、CC、Bot、动态防护等） */
    @GET("api/open/global/mode")
    suspend fun globalMode(): JsonObject

    @PUT("api/open/global/mode")
    suspend fun updateGlobalMode(@Body body: JsonObject): JsonObject

    /** 频率限制 / CC 防护 */
    @GET("api/open/global/acl")
    suspend fun globalAcl(): JsonObject

    @PUT("api/open/global/acl")
    suspend fun updateGlobalAcl(@Body body: JsonObject): JsonObject

    /** 增强规则（引擎/升级规则） */
    @GET("api/open/skynet/rule")
    suspend fun skynetRules(@QueryMap q: Map<String, String>): JsonObject

    @PUT("api/open/skynet/rule")
    suspend fun updateSkynetRule(@Body body: JsonObject): JsonObject

    // ===================== 日志与查询 =====================

    /** 攻击事件 */
    @GET("api/open/events")
    suspend fun events(@QueryMap q: Map<String, String>): JsonObject

    @GET("api/open/events/{id}")
    suspend fun eventDetail(@Path("id") id: String): JsonObject

    /** 拦截日志（访问控制） */
    @GET("api/open/records/acl")
    suspend fun recordsAcl(@QueryMap q: Map<String, String>): JsonObject

    /** 人机验证日志 */
    @GET("api/open/records/challenge")
    suspend fun recordsChallenge(@QueryMap q: Map<String, String>): JsonObject

    /** 身份认证日志 */
    @GET("api/open/records/auth_defense")
    suspend fun recordsAuthDefense(@QueryMap q: Map<String, String>): JsonObject

    /** 访问日志（放行） */
    @GET("api/open/records/access")
    suspend fun recordsAccess(@QueryMap q: Map<String, String>): JsonObject

    // ===================== 系统管理 =====================

    @GET("api/open/users")
    suspend fun users(@QueryMap q: Map<String, String>): JsonObject

    @POST("api/open/users")
    suspend fun createUser(@Body body: JsonObject): JsonObject

    @PUT("api/open/users/{id}")
    suspend fun updateUser(@Path("id") id: Long, @Body body: JsonObject): JsonObject

    @DELETE("api/open/users/{id}")
    suspend fun deleteUser(@Path("id") id: Long): JsonObject

    /** API Token 管理（9.4.x 正确路径为 /api/open/auth/token，/api/open/token 已不存在） */
    @GET("api/open/auth/token")
    suspend fun apiTokens(@QueryMap q: Map<String, String>): JsonObject

    @POST("api/open/auth/token")
    suspend fun createToken(@Body body: JsonObject): JsonObject

    @PUT("api/open/auth/token")
    suspend fun updateToken(@Body body: JsonObject): JsonObject

    @DELETE("api/open/auth/token")
    suspend fun deleteToken(): JsonObject

    /** 告警通知（商业版能力，社区版返回 license required） */
    @GET("api/commercial/alarm")
    suspend fun alarm(): JsonObject

    @POST("api/open/notice")
    suspend fun createNotice(@Body body: JsonObject): JsonObject

    @PUT("api/open/notice/{id}")
    suspend fun updateNotice(@Path("id") id: Long, @Body body: JsonObject): JsonObject

    @DELETE("api/open/notice/{id}")
    suspend fun deleteNotice(@Path("id") id: Long): JsonObject

    /** 通行证 / 白名单之类的豁免名单 */
    @GET("api/open/whitelist")
    suspend fun whitelist(@QueryMap q: Map<String, String>): JsonObject

    // ===================== 通用兜底 =====================

    @GET
    suspend fun rawGet(@Url url: String, @QueryMap q: Map<String, String> = emptyMap()): JsonObject

    @POST
    suspend fun rawPost(@Url url: String, @Body body: JsonObject = JsonObject()): JsonObject

    @PUT
    suspend fun rawPut(@Url url: String, @Body body: JsonObject = JsonObject()): JsonObject

    @DELETE
    suspend fun rawDelete(@Url url: String, @Body body: JsonObject = JsonObject()): JsonObject
}

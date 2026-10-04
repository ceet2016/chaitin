package com.uniomo.wafce.data.net

import android.util.Log
import com.google.gson.Gson
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.uniomo.wafce.core.HttpStatusException
import com.uniomo.wafce.core.LicenseRequiredException
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
 * 密码 / Token 等敏感字段打码，只暴露字段是否齐全、长度多少。
 */
private fun logRequest(request: okhttp3.Request) {
    val body = request.body ?: return
    try {
        val buffer = okio.Buffer()
        body.writeTo(buffer)
        val raw = buffer.readUtf8()
        val masked = raw
            .replace(Regex("\"password\"\\s*:\\s*\"[^\"]*\""), "\"password\":\"<len=${Regex("\"password\"\\s*:\\s*\"([^\"]*)\"").find(raw)?.groupValues?.get(1)?.length ?: 0}>\"")
            .replace(Regex("\"key\"\\s*:\\s*\"[^\"]{8,}\""), "\"key\":\"<redacted>\"")
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
}

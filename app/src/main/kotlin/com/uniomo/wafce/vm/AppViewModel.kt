package com.uniomo.wafce.vm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import com.uniomo.wafce.core.ApiException
import com.uniomo.wafce.core.AuthEvents
import com.uniomo.wafce.core.HttpDiag
import com.uniomo.wafce.core.HttpStatusException
import com.uniomo.wafce.core.Resp
import com.uniomo.wafce.core.Totp
import com.uniomo.wafce.core.safeCall
import com.uniomo.wafce.data.net.ApiClient
import com.uniomo.wafce.data.net.SafeLineApi
import com.uniomo.wafce.data.net.SessionStore
import com.uniomo.wafce.data.store.AccountProfile
import com.uniomo.wafce.data.store.AccountSecret
import com.uniomo.wafce.data.store.AccountStore
import com.uniomo.wafce.data.store.AuthMode

enum class SessionState { UNKNOWN, AUTHENTICATED, NEED_LOGIN, NEED_TOTP }

sealed class LoginOutcome {
    data object Ok : LoginOutcome()
    data object NeedTotp : LoginOutcome()
    data class Error(val message: String) : LoginOutcome()
}

/**
 * 全局应用状态：多账号管理 + 会话生命周期。
 *
 * 设计要点：
 * - 账号配置持久化在本地，凭据由 Keystore 加密
 * - SESSION 模式支持用保存的密码 + TOTP 密钥静默重登
 * - 任意接口返回 401 都会经 [AuthEvents] 回到登录态
 */
class AppViewModel : ViewModel() {

    var accounts: List<AccountProfile> by mutableStateOf(emptyList())
        private set

    var current: AccountProfile? by mutableStateOf(null)
        private set

    var sessionState: SessionState by mutableStateOf(SessionState.UNKNOWN)
        private set

    var busy: Boolean by mutableStateOf(false)
        private set

    var toast: String? by mutableStateOf(null)
        private set

    init {
        reload()
        AuthEvents.unauthorized
            .onEach { id ->
                if (id == -1L || current?.id == id) {
                    sessionState = SessionState.NEED_LOGIN
                }
            }
            .launchIn(viewModelScope)
    }

    fun showToast(msg: String) { toast = msg }

    fun consumeToast() { toast = null }

    fun reload() {
        accounts = AccountStore.all()
        current = AccountStore.current() ?: accounts.firstOrNull()
    }

    // ---------------- 账号切换 ----------------

    fun switchTo(id: Long) {
        val target = accounts.firstOrNull { it.id == id } ?: return
        AccountStore.setCurrent(id)
        current = AccountStore.current()
        sessionState = SessionState.UNKNOWN
        viewModelScope.launch { ensureSession(target) }
    }

    /**
     * 进入「实例管理」页：刷新列表后再清空当前选中，触发 AppRoot 渲染账号列表，
     * 从而支持在已登录状态下继续添加/删除实例。
     *
     * 注意顺序：必须先 reload 再清空，否则 reload() 会把 current 重新填回来，
     * 导致页签点了没反应。
     */
    fun beginManageAccounts() {
        accounts = AccountStore.all()
        current = null
    }

    /** 从实例管理页回到某个账号 */
    fun focusAccount(id: Long) {
        current = AccountStore.all().firstOrNull { it.id == id } ?: accounts.firstOrNull()
    }

    fun saveAccount(profile: AccountProfile, secret: AccountSecret? = null): Long {
        val saved = AccountStore.save(profile, secret)
        reload()
        return saved.id
    }

    fun deleteAccount(id: Long) {
        AccountStore.delete(id)
        SessionStore.clear(id)
        reload()
        if (current == null) sessionState = SessionState.NEED_LOGIN
    }

    fun logout(id: Long = current?.id ?: -1L) {
        val acc = accounts.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            runCatching { ApiClient.apiFor(acc).logout() }
        }
        SessionStore.clear(id)
        sessionState = SessionState.NEED_LOGIN
    }

    // ---------------- 会话保障 ----------------

    /** 进入主界面前调用：确保当前账号处于可用会话 */
    fun ensureCurrentSession() {
        val acc = current ?: run {
            sessionState = SessionState.NEED_LOGIN
            return
        }
        viewModelScope.launch { ensureSession(acc) }
    }

    /**
     * 只校验 API Token：请求一次 /api/open/system，能拿到数据即视为可接入。
     * 不再走账号密码登录（该接口有连续失败风控，触发后长时间不可用）。
     */
    private suspend fun ensureSession(account: AccountProfile) {
        sessionState = SessionState.UNKNOWN
        val token = AccountStore.secretOf(account.id).apiToken
        if (token.isBlank()) {
            sessionState = SessionState.NEED_LOGIN
            return
        }
        val result = runCatching { Resp.obj(ApiClient.apiFor(account).systemInfo()) }
        sessionState = if (result.isSuccess) SessionState.AUTHENTICATED else SessionState.NEED_LOGIN
        if (result.isFailure) {
            AccountStore.setLastError(account.id, vmReadable(result.exceptionOrNull()!!))
        }
    }

    fun setSessionAuthed() { sessionState = SessionState.AUTHENTICATED }

    fun setNeedTotp() { sessionState = SessionState.NEED_TOTP }

    // ---------------- 登录 ----------------

    /**
     * 用 API Token 接入并保存账号。
     * 会先真实请求一次 /api/open/system 验证 Token 有效，通过后才进入主界面。
     */
    fun loginWithToken(
        account: AccountProfile,
        token: String,
        onResult: ((ok: Boolean, message: String) -> Unit)? = null
    ) {
        viewModelScope.launch {
            busy = true
            val existing = AccountStore.secretOf(account.id)
            val target = AccountStore.save(
                account.copy(authMode = AuthMode.TOKEN),
                AccountSecret(
                    password = "",
                    apiToken = token,
                    totpSecret = existing.totpSecret
                )
            )
            ApiClient.invalidate(target.id)
            val result = runCatching { Resp.obj(ApiClient.apiFor(target).systemInfo()) }
            reload()
            current = AccountStore.all().firstOrNull { it.id == target.id } ?: target
            val ok = result.isSuccess
            sessionState = if (ok) SessionState.AUTHENTICATED else SessionState.NEED_LOGIN
            busy = false
            val msg = result.exceptionOrNull()?.let { vmReadable(it) } ?: ""
            if (!ok) AccountStore.setLastError(target.id, msg)
            onResult?.invoke(ok, msg)
        }
    }

    /** 保存 TOTP 密钥，用于后续自动登录 */
    fun persistTotpSecret(id: Long, secretKey: String) {
        val s = AccountStore.secretOf(id)
        AccountStore.save(
            accounts.firstOrNull { it.id == id } ?: return,
            s.copy(totpSecret = secretKey)
        )
    }

    /**
     * 登录主流程：csrf -> 密码登录 -> （按需）TFA 校验 -> 会话落库
     *
     * 附带一次协议回退：部分内网/反向代理环境只提供 http 控制台，
     * 若地址是按 https 自动补全的，会在失败后自动改用 http 重试一次。
     */
    private suspend fun performLogin(
        account: AccountProfile,
        password: String,
        totpCode: String?
    ): LoginOutcome {
        val first = runCatching { handshakeLogin(account, password, totpCode) }
        if (first.isSuccess) return first.getOrThrow()

        val failure = first.exceptionOrNull()!!
        if (account.baseUrl.startsWith("https://") && shouldTryHttp(failure)) {
            val alt = account.copy(baseUrl = account.baseUrl.replaceFirst("https://", "http://"))
            ApiClient.invalidate(alt.id)
            AccountStore.save(alt)
            val second = runCatching { handshakeLogin(alt, password, totpCode) }
            if (second.isSuccess) return second.getOrThrow()
            SessionStore.clear(alt.id)
            return LoginOutcome.Error(vmReadable(second.exceptionOrNull()!!))
        }

        SessionStore.clear(account.id)
        return LoginOutcome.Error(vmReadable(failure))
    }

    /**
     * 取 CSRF 令牌。不同版本字段名可能是 csrf_token / token / csrf，
     * 极端情况下兜底取 data 里唯一的字符串字段。
     */
    private suspend fun fetchCsrf(api: SafeLineApi): String {
        val data = Resp.obj(api.csrf())
        val direct = Resp.str(data, "csrf_token", "token", "csrf", "csrfToken", "csrfCode")
        if (direct.isNotBlank()) return direct
        return data.entrySet().firstNotNullOfOrNull { (_, v) ->
            val p = v.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
            if (p != null && p.isString) p.asString.takeIf { it.isNotBlank() } else null
        }.orEmpty()
    }

    /**
     * 登录接口返回 400 时给一句人话。
     * 雷池在账号连续登录失败过多 / 账号状态异常时会返回 invalid request 而非密码错误，
     * 直接抛状态码会让用户误以为是客户端 bug。
     */
    private fun loginHint(e: HttpStatusException): String? {
        if (e.httpCode != 400 || !e.url.contains("/auth/login")) return null
        val body = e.body.lowercase()
        return when {
            "invalid request" in body ->
                "服务器拒绝了登录请求：该账号可能因连续登录失败过多被临时锁定，或账号状态异常。\n" +
                    "建议稍等几分钟后重试；着急使用可改用「API Token」方式接入（走另一个接口，不受此限制）。"
            "invalid character" in body ->
                "请求体编码异常：${HttpDiag.summarize(e.httpCode, e.body)}"
            else -> null
        }
    }

    /** 仅在「协议明显不匹配」或 TLS 握手失败时才回退，避免掩盖真实错误 */
    private fun shouldTryHttp(e: Throwable): Boolean = when (e) {
        is HttpStatusException -> HttpDiag.isSchemeMismatch(e.body)
        is javax.net.ssl.SSLException -> true
        else -> false
    }

    private suspend fun handshakeLogin(
        account: AccountProfile,
        password: String,
        totpCode: String?
    ): LoginOutcome {
        val api = ApiClient.apiFor(account)
        val csrfTok = fetchCsrf(api)
        if (csrfTok.isBlank()) {
            throw ApiException("未能从服务端取到 CSRF 令牌，控制台版本可能与客户端不匹配")
        }
        val body = JsonObject().apply {
            addProperty("username", account.username)
            addProperty("password", password)
            addProperty("csrf_token", csrfTok)
        }
        val loginData = Resp.obj(api.login(body))
            val jwt = Resp.str(loginData, "jwt", "token")
            val expired = Resp.l(loginData, "expired_at", "expire_at", "expires_at", "expire")
            if (jwt.isBlank()) {
                return LoginOutcome.Error("登录失败：服务端未返回会话凭据")
            }
            SessionStore.put(account.id, jwt, expired.takeIf { it > 0 })

            // 判定是否需要两步验证：优先看服务端标记，否则用一次轻量请求探测
            val flagged = Resp.b(loginData, "tfa_required", "mfa_required", "need_tfa", "totp_enabled")
            if (!flagged) {
                val probe = runCatching { Resp.obj(api.systemInfo()) }
                if (probe.isSuccess) {
                    return LoginOutcome.Ok
                }
            }

            val code = totpCode?.takeIf { it.isNotBlank() }
                ?: run {
                    val saved = AccountStore.secretOf(account.id).totpSecret
                    if (saved.isNotBlank()) Totp.generate(saved) else ""
                }
            if (code.length < 6) {
                return LoginOutcome.NeedTotp
            }

            val csrf2 = fetchCsrf(api)
            val tfaBody = JsonObject().apply {
                addProperty("code", code)
                addProperty("timestamp", System.currentTimeMillis())
                addProperty("csrf_token", csrf2)
            }
            val tfaData = Resp.obj(api.tfa(tfaBody))
            val finalJwt = Resp.str(tfaData, "jwt", "token")
            if (finalJwt.isBlank()) {
                return LoginOutcome.Error("动态码校验失败")
            }
            SessionStore.put(
                account.id,
                finalJwt,
                Resp.l(tfaData, "expired_at", "expire_at", "expires_at", "expire").takeIf { it > 0 }
            )
            return LoginOutcome.Ok
    }

    private fun vmReadable(e: Throwable): String = when (e) {
        is com.uniomo.wafce.core.ApiException -> e.serverMessage
        is HttpStatusException -> loginHint(e) ?: "HTTP ${e.httpCode} · ${HttpDiag.summarize(e.httpCode, e.body)}"
        is com.uniomo.wafce.core.UnauthorizedException -> "账号或密码错误"
        is java.net.UnknownHostException -> "无法解析服务器地址，请检查网络或域名"
        is java.net.SocketTimeoutException -> "请求超时，请检查控制台是否可达"
        is java.net.ConnectException -> "连接失败，请确认地址与端口（默认 9443）"
        is javax.net.ssl.SSLException -> "TLS 握手失败，可尝试更换 http/https 或关闭证书校验"
        is java.io.IOException -> e.message ?: "网络异常"
        else -> e.localizedMessage ?: "未知错误"
    }
}

/** 供各页面统一使用的 Api 获取方式 */
fun currentApi(account: AccountProfile) = ApiClient.apiFor(account)

/** 统一的网络请求包装：把异常转成 Result，同时记录会话失效 */
suspend fun <T> call(account: AccountProfile, block: suspend () -> T): Result<T> =
    safeCall(account.id, block)

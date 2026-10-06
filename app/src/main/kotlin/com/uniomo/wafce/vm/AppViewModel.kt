package com.uniomo.wafce.vm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.util.Log
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
import com.uniomo.wafce.core.UnauthorizedException
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
private const val TAG = "SafeLineClient"

class AppViewModel : ViewModel() {

    var accounts: List<AccountProfile> by mutableStateOf(emptyList())
        private set

    var current: AccountProfile? by mutableStateOf(null)
        private set

    var sessionState: SessionState by mutableStateOf(SessionState.UNKNOWN)
        private set

    /**
     * 会话世代计数。每次切换账号 / 退出登录都自增，
     * AppRoot 用它作为 MainScaffold 的 key，强制重建 NavController，
     * 否则 rememberSaveable 会把上一个账号的返回栈（含深链页）还原回来。
     */
    var sessionEpoch by mutableStateOf(0L)
        private set

    var busy: Boolean by mutableStateOf(false)
        private set

    var toast: String? by mutableStateOf(null)
        private set

    init {
        reload()
        AuthEvents.unauthorized
            .onEach { id ->
                // id == -1L 表示「无法确定是哪个账号」（safeCall 的默认值）。
                // 此时**不能**无条件踢人，否则任何一次归属不明的 401 都会把当前账号登出。
                // 现在 401 统一由 HttpErrorInterceptor 带 accountId 上报，
                // 正常情况下不会走到 -1L 分支；保留判断只是为了兜底。
                val hit = if (id == -1L) {
                    Log.w(TAG, "收到无归属的 401 通知，忽略（不改变当前会话）")
                    false
                } else {
                    current?.id == id
                }
                if (hit) {
                    sessionState = SessionState.NEED_LOGIN
                    sessionEpoch++
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
        sessionEpoch++
        // 清理上一个账号的 UI 态，避免旧账号的操作结果 toast 带到新账号界面
        toast = null
        busy = false
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
        val target = AccountStore.all().firstOrNull { it.id == id } ?: accounts.firstOrNull()
        current = target
        // 必须重置为 UNKNOWN 触发重新校验：用户在实例管理页停留期间 Token 可能已过期，
        // 若沿用 AUTHENTICATED 会直接进主界面，所有接口 401 后才被踢回来。
        sessionState = SessionState.UNKNOWN
        sessionEpoch++
        target?.let { viewModelScope.launch { ensureSession(it) } }
    }

    fun saveAccount(profile: AccountProfile, secret: AccountSecret? = null): Long {
        val saved = AccountStore.save(profile, secret)
        reload()
        return saved.id
    }

    /**
     * 删除实例。
     *
     * 两个容易漏的点：
     * 1. **必须先 invalidate**。账号 id 是序号式生成的（maxId+1），
     *    删掉最大的那个再新增就会**复用同一个 id**。若先 delete 再 invalidate，
     *    期间并发新增抢到同一 id 时会把新账号刚建的 client 一起清掉；
     *    而不 invalidate 则更糟——旧账号的 OkHttpClient 残留，新账号 baseUrl 相同时
     *    直接复用，若trustAllCert 不同就会出现「明明开了证书校验却仍握手失败」的死锁。
     * 2. **必须 sessionEpoch++**。删掉当前账号时 AccountStore 会把current 指向别的账号，
     *    但 sessionState 仍是 AUTHENTICATED，界面不会重建，
     *    用户会继续在旧账号的 NavController 里操作。
     */
    fun deleteAccount(id: Long) {
        ApiClient.invalidate(id)
        AccountStore.delete(id)
        SessionStore.clear(id)
        reload()
        val next = current
        if (next == null) {
            sessionState = SessionState.NEED_LOGIN
            sessionEpoch++
        } else {
            sessionEpoch++
            sessionState = SessionState.UNKNOWN
            viewModelScope.launch { ensureSession(next) }
        }
    }

    /**
     * 退出登录。
     *
     * 注意必须 [ApiClient.invalidate]：本客户端只用 API Token 接入，鉴权头来自
     * AccountStore 里持久化的 token（不存于 SessionStore），只清 SessionStore 等于没登出，
     * 缓存的 OkHttp 实例会继续带着有效 token 发请求。
     */
    fun logout(id: Long = current?.id ?: -1L) {
        val acc = accounts.firstOrNull { it.id == id }
        if (acc == null) {
            // 从实例管理页触发时current 为 null，这里明确置态而不是静默无操作
            sessionState = SessionState.NEED_LOGIN
            sessionEpoch++
            return
        }
        viewModelScope.launch {
            // 本地登出不依赖服务端结果，失败也无需阻塞界面
            runCatching { ApiClient.apiFor(acc).logout() }
        }
        SessionStore.clear(id)
        ApiClient.invalidate(id)
        sessionState = SessionState.NEED_LOGIN
        sessionEpoch++
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
     *
     * 两点关键：
     * - **只有明确的凭据问题才判NEED_LOGIN**。网络超时 / SSL 握手失败 / 服务端 5xx
     *   都说明 Token 可能仍是好的，原实现一律判 NEED_LOGIN 会让用户误以为 Token 过期，
     *   白白重走一遍连接流程，而根因其实是网络。这些情况保持 UNKNOWN 让用户重试。
     * - **带代次校验**。快速连续切两个账号时，两个 ensureSession 并发，
     *   慢的那个返回后若无条件覆盖 sessionState，界面状态会与 current 不一致。
     */
    private suspend fun ensureSession(account: AccountProfile) {
        val myEpoch = sessionEpoch
        sessionState = SessionState.UNKNOWN
        val token = AccountStore.secretOf(account.id).apiToken
        if (token.isBlank()) {
            if (myEpoch == sessionEpoch) sessionState = SessionState.NEED_LOGIN
            return
        }
        val result = runCatching { Resp.unwrap(ApiClient.apiFor(account).systemInfo()) }
        // 过期结果直接丢弃，不影响当前账号的界面状态
        if (myEpoch != sessionEpoch || current?.id != account.id) return
        val e = result.exceptionOrNull()
        sessionState = when {
            result.isSuccess -> SessionState.AUTHENTICATED
            // 明确的鉴权失败：Token 无效 / 被吊销 / 业务拒绝
            e is UnauthorizedException -> SessionState.NEED_LOGIN
            e is ApiException -> SessionState.NEED_LOGIN
            // 网络 / SSL / 服务端故障：Token 大概率仍是有效的，保留当前态让用户重试
            else -> SessionState.UNKNOWN
        }
        if (sessionState != SessionState.AUTHENTICATED) {
            AccountStore.setLastError(account.id, vmReadable(e ?: ApiException("未知错误")))
        }
    }

    fun setSessionAuthed() { sessionState = SessionState.AUTHENTICATED }

    fun setNeedTotp() { sessionState = SessionState.NEED_TOTP } // 见 performLogin 的说明

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

    /** 保存 TOTP 密钥，供 [performLogin] 的两步验证使用 */
    @Suppress("unused")
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
     *
     * ⚠️ **当前无调用点**：登录页只提供「API Token」一种接入方式
     * （见 LoginScreen → [loginWithToken]），用户名密码路径没有 UI 入口。
     * 这条链连同 [handshakeLogin] / [fetchCsrf] / [shouldTryHttp] 一并保留，
     * 是因为它**功能完整且自洽**（csrf、协议回退、TFA 都已实现），
     * 一旦后续要加回密码登录，接线即可。
     * **不要因为「看起来是死代码」就顺手删掉** —— 同时被它撑着的还有
     * `AuthMode.SESSION` 分支（[com.uniomo.wafce.data.net.AuthInterceptor]）、
     * `SessionStore` 的 JWT 缓存、`SessionState.NEED_TOTP` 状态与 TOTP 密钥存储，
     * 删一半会让整套 SESSION 认证能力变成无源的残骸。
     */
    @Suppress("unused")
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
     *
     * 仅 [handshakeLogin] 使用，见那里的说明。
     */
    @Suppress("unused")
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

    /** 仅在「协议明显不匹配」或 TLS 握手失败时才回退，避免掩盖真实错误。见 [performLogin] */
    @Suppress("unused")
    private fun shouldTryHttp(e: Throwable): Boolean = when (e) {
        is HttpStatusException -> HttpDiag.isSchemeMismatch(e.body)
        is javax.net.ssl.SSLException -> true
        else -> false
    }

    /** [performLogin] 的单次握手：csrf -> 登录 -> 必要时TFA。见 performLogin 的说明 */
    @Suppress("unused")
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

package com.uniomo.wafce.core

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import com.uniomo.wafce.data.net.SessionStore

/** 统一的异步加载状态 */
sealed interface UiState<out T> {
    data object Idle : UiState<Nothing>
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Fail(val message: String, val unauthorized: Boolean = false) : UiState<Nothing>
}

/** 会话过期事件总线：任意页面收到凭据失效都会回到登录页 */
object AuthEvents {
    private val flow = MutableSharedFlow<Long>(extraBufferCapacity = 4)
    val unauthorized: SharedFlow<Long> = flow
    fun unauthorizedHappened(accountId: Long) {
        flow.tryEmit(accountId)
    }
}

private const val TAG = "SafeLineClient"

/**
 * 统一错误编排（供页面自行包runCatching 时参考，无需调用）。
 *
 * 注意：这里的 401 通知分支**已不再使用** —— 会话失效现在由
 * `HttpErrorInterceptor` 在拦截器层直接上报（那里已持有 accountId，
 * 归属准确、不依赖调用方传参）。保留 safeCall 只会让人误以为
 * 页面 catch 里的 UnauthorizedException 会自动触发登出，实际上不会。
 */
@Deprecated(
    message = "401 已由 HttpErrorInterceptor 统一上报，此包装器零调用点；请直接用 runCatching + readable()",
    replaceWith = ReplaceWith("runCatching(block).onFailure { Log.w(TAG, \"request failed\", it) }")
)
suspend fun <T> safeCall(accountId: Long = -1L, block: suspend () -> T): Result<T> = runCatching {
    block()
}.onFailure { e ->
    Log.w(TAG, "request failed", e)
}

fun Throwable.readable(): String = when (this) {
    is ApiException -> serverMessage
    is UnauthorizedException -> message ?: "登录状态已失效"
    is java.net.UnknownHostException -> "无法解析服务器地址，请检查网络或域名"
    is java.net.SocketTimeoutException -> "请求超时，请检查服务器是否可达"
    is java.net.ConnectException -> "连接失败，请确认控制台地址与端口（默认 9443）"
    is javax.net.ssl.SSLException -> "TLS 握手失败，可尝试关闭证书校验或使用 https 地址"
    is java.io.IOException -> message ?: "网络异常"
    else -> localizedMessage ?: "未知错误"
}

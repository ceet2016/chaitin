package xyz.anye.wafce.core

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import xyz.anye.wafce.data.net.SessionStore

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
 * 统一错误编排：
 * - ApiException     服务端 err 字段非空（业务性错误）
 * - UnauthorizedException  401/403，触发全局登出跳转
 * - 其余 IOException 视为网络错误
 */
suspend fun <T> safeCall(accountId: Long = -1L, block: suspend () -> T): Result<T> = runCatching {
    block()
}.onFailure { e ->
    Log.w(TAG, "request failed", e)
    when (e) {
        is UnauthorizedException -> {
            SessionStore.clear(accountId)
            AuthEvents.unauthorizedHappened(accountId)
        }
    }
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

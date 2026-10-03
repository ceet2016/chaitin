package xyz.anye.wafce.core

import android.content.Context
import java.io.IOException

/**
 * 全局 Application Context 持有者，避免把 Context 到处传递造成泄漏。
 */
object AppContextHolder {
    private var instance: Context? = null

    fun init(ctx: Context) {
        instance = ctx.applicationContext
    }

    fun get(): Context = instance ?: error("Application context 未初始化")
}

/** 服务端返回 err 非空时抛出，统一收敛为可读错误 */
open class ApiException(
    val serverMessage: String,
    val httpCode: Int = 0
) : IOException(serverMessage.ifBlank { "请求失败" })

/**
 * 商业版能力限制：雷池对社区版未授权的接口返回 403 + license required。
 * 这类响应与「登录失效」要严格区分，否则会误导用户重新登录。
 */
class LicenseRequiredException(
    msg: String = "该功能为雷池商业版能力，社区版不可用"
) : ApiException(msg, 403)

/** 会话失效，需要重新登录 */
class UnauthorizedException(msg: String = "登录状态已失效，请重新登录") : IOException(msg)

/**
 * 服务端返回非 2xx。
 * [body] 保留响应体原文片段——雷池/网关通常会把具体拒绝原因写在 body 里，
 * 这是排查 400 类问题最直接的线索。
 */
class HttpStatusException(
    val httpCode: Int,
    val url: String,
    val body: String
) : IOException("HTTP $httpCode：${HttpDiag.summarize(httpCode, body)}")

object HttpDiag {
    /** HTTPS 请求打到 HTTP 端口、或反之，网关通常会给出这类正文 */
    private val SCHEME_HINTS = listOf(
        "plain HTTP request was sent to HTTPS port",
        "Client sent an HTTP request to an HTTPS",
        "sent to HTTPS port"
    )

    fun isSchemeMismatch(body: String): Boolean =
        SCHEME_HINTS.any { body.contains(it, ignoreCase = true) }

    fun summarize(code: Int, body: String): String {
        val flat = body.replace(Regex("\\s+"), " ").trim()
        val head = when {
            flat.isBlank() -> "服务端未返回说明"
            flat.length > 220 -> flat.take(220) + "…"
            else -> flat
        }
        return when {
            isSchemeMismatch(body) -> "协议不匹配（可能用 https 访问了 http 服务）：$head"
            code == 404 -> "接口不存在：$head"
            code == 400 -> "请求被拒绝：$head"
            code == 405 -> "方法不被允许：$head"
            code >= 500 -> "服务端错误：$head"
            else -> head
        }
    }
}

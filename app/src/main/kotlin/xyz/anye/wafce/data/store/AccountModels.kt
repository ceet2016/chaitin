package xyz.anye.wafce.data.store

/**
 * 认证方式：
 * - SESSION：用户名 + 密码（+ TOTP）登录，拿到 Bearer JWT，可覆盖控制台全部功能
 * - TOKEN ：控制台生成的 API Token，走官方 OpenAPI，稳定但为功能子集
 */
enum class AuthMode { SESSION, TOKEN }

/** 非敏感的账号配置，持久化时明文保存 */
data class AccountProfile(
    val id: Long = 0,
    val name: String = "",           // 备注名，便于区分多台 WAF
    val baseUrl: String = "",        // 已规范化，形如 https://10.0.0.1:9443/
    val username: String = "",       // SESSION 模式下的登录账号
    val authMode: AuthMode = AuthMode.SESSION,
    val trustAllCert: Boolean = true,
    val createdAt: Long = 0L,
    val lastUsedAt: Long = 0L,
    val lastError: String = ""
)

/** 敏感凭据，落盘前由 Android Keystore 加密 */
data class AccountSecret(
    val password: String = "",
    val apiToken: String = "",
    val totpSecret: String = ""
)

fun normalizeBaseUrl(input: String): String {
    var s = input.trim()
    if (s.isBlank()) return ""
    if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) {
        s = "https://$s"
    }
    return if (s.endsWith("/")) s else "$s/"
}

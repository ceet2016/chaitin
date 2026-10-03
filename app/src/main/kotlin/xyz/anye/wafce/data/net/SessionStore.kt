package xyz.anye.wafce.data.net

import java.util.concurrent.ConcurrentHashMap

/**
 * 会话凭证内存态，进程结束即失效：
 * - accountId -> Bearer JWT（SESSION 模式）
 * - 中间态：用户名密码校验通过后返回的是临时 JWT，TFA 通过后覆盖为正式 JWT
 */
object SessionStore {

    private val jwtMap = ConcurrentHashMap<Long, String>()
    private val expiredMap = ConcurrentHashMap<Long, Long>()

    fun put(accountId: Long, jwt: String, expiresTsSec: Long? = null) {
        jwtMap[accountId] = jwt
        expiresTsSec?.let { expiredMap[accountId] = it * 1000L }
    }

    fun jwtOf(accountId: Long): String? = jwtMap[accountId]

    fun isExpired(accountId: Long): Boolean {
        val exp = expiredMap[accountId] ?: return false
        return System.currentTimeMillis() > exp - 30_000L
    }

    fun clear(accountId: Long) {
        jwtMap.remove(accountId)
        expiredMap.remove(accountId)
    }
}

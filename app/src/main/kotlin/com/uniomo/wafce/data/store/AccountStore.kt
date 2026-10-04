package com.uniomo.wafce.data.store

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.uniomo.wafce.core.AppContextHolder

/**
 * 多账号（多 WAF 实例）持久化仓库。
 *
 * 存储策略：
 * - 非敏感配置 -> SharedPreferences 明文 JSON
 * - 密码 / API Token / TOTP 密钥 -> 经 [CipherBox] 加密后单独存放
 * - 该文件已在 backup_rules.xml 中排除云端备份
 */
object AccountStore {

    private const val PREF = "safe_line_accounts"
    private const val KEY_LIST = "accounts"
    private const val KEY_SECRET = "secret_"
    private const val KEY_CURRENT = "current_id"
    private const val KEY_LAST_URL = "last_url"

    private val gson = Gson()
    private val prefs by lazy {
        AppContextHolder.get().getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }

    fun all(): List<AccountProfile> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<AccountProfile>>() {}.type
            gson.fromJson<List<AccountProfile>>(raw, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun current(): AccountProfile? {
        val id = prefs.getLong(KEY_CURRENT, -1L)
        if (id <= 0L) return null
        return all().firstOrNull { it.id == id }
    }

    fun currentId(): Long = prefs.getLong(KEY_CURRENT, -1L)

    fun currentOrFirst(): AccountProfile? = current() ?: all().firstOrNull()

    fun setCurrent(id: Long) {
        val target = all().firstOrNull { it.id == id } ?: return
        prefs.edit()
            .putLong(KEY_CURRENT, id)
            .putString(KEY_LAST_URL, target.baseUrl)
            .apply()
        save(target.copy(lastUsedAt = System.currentTimeMillis()))
    }

    fun clearCurrent() {
        prefs.edit().putLong(KEY_CURRENT, -1L).apply()
    }

    fun secretOf(id: Long): AccountSecret {
        val pwd = prefs.getString(KEY_SECRET + id + "_pwd", null)
        val token = prefs.getString(KEY_SECRET + id + "_token", null)
        val totp = prefs.getString(KEY_SECRET + id + "_totp", null)
        return AccountSecret(
            password = pwd?.let { CipherBox.decrypt(it) }.orEmpty(),
            apiToken = token?.let { CipherBox.decrypt(it) }.orEmpty(),
            totpSecret = totp?.let { CipherBox.decrypt(it) }.orEmpty()
        )
    }

    /** 保存/更新账号；secret 为空时为「不修改」语义 */
    fun save(profile: AccountProfile, secret: AccountSecret? = null): AccountProfile {
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        val finalProfile = if (profile.id == 0L) {
            profile.copy(id = nextId(list), createdAt = System.currentTimeMillis())
        } else {
            profile
        }
        if (idx >= 0) list[idx] = finalProfile else list.add(0, finalProfile)

        prefs.edit().putString(KEY_LIST, gson.toJson(list)).apply()

        val s = secret ?: secretOf(finalProfile.id)
        prefs.edit()
            .putString(KEY_SECRET + finalProfile.id + "_pwd", CipherBox.encrypt(s.password))
            .putString(KEY_SECRET + finalProfile.id + "_token", CipherBox.encrypt(s.apiToken))
            .putString(KEY_SECRET + finalProfile.id + "_totp", CipherBox.encrypt(s.totpSecret))
            .apply()

        if (prefs.getLong(KEY_CURRENT, -1L) <= 0L) {
            prefs.edit().putLong(KEY_CURRENT, finalProfile.id).apply()
        }
        return finalProfile
    }

    fun delete(id: Long) {
        val list = all().toMutableList()
        list.removeAll { it.id == id }
        prefs.edit()
            .putString(KEY_LIST, gson.toJson(list))
            .remove(KEY_SECRET + id + "_pwd")
            .remove(KEY_SECRET + id + "_token")
            .remove(KEY_SECRET + id + "_totp")
            .apply()
        if (currentId() == id) {
            prefs.edit().putLong(KEY_CURRENT, list.firstOrNull()?.id ?: -1L).apply()
        }
    }

    fun setLastError(id: Long, msg: String) {
        val target = all().firstOrNull { it.id == id } ?: return
        save(target.copy(lastError = msg))
    }

    private fun nextId(list: List<AccountProfile>): Long =
        (list.maxOfOrNull { it.id } ?: 0L) + 1L
}

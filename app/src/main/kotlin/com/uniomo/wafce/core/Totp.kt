package com.uniomo.wafce.core

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/**
 * TOTP（RFC 6238）生成器。
 *
 * 用途：当用户在账号中保存了两步验证密钥时，App 可以自动填充 6 位动态码，
 * 免去每次登录都翻验证器的麻烦；不保存则登录时手动输入。
 */
object Totp {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun isValidSecret(secret: String): Boolean =
        secret.replace(" ", "").uppercase().all { it in ALPHABET }

    fun generate(secret: String, period: Int = 30, digits: Int = 6): String {
        val clean = secret.replace(" ", "").uppercase()
        if (clean.isBlank()) return ""
        val key = decodeBase32(clean)
        val counter = System.currentTimeMillis() / 1000L / period
        val code = hotp(key, counter, digits)
        return code.toString().padStart(digits, '0')
    }

    private fun hotp(key: ByteArray, counter: Long, digits: Int): Int {
        val buffer = ByteBuffer.allocate(8).putLong(counter).array()
        val mac = Mac.getInstance("HmacSHA1").apply {
            init(SecretKeySpec(key, "HmacSHA1"))
        }
        val hash = mac.doFinal(buffer)
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary =
            ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)
        return binary % 10.0.pow(digits).toInt()
    }

    private fun decodeBase32(input: String): ByteArray {
        val bits = StringBuilder()
        for (ch in input) {
            val idx = ALPHABET.indexOf(ch)
            if (idx < 0) continue
            bits.append(idx.toString(2).padStart(5, '0'))
        }
        val out = mutableListOf<Byte>()
        var i = 0
        while (i + 8 <= bits.length) {
            val byte = bits.substring(i, i + 8).toInt(2)
            out.add(byte.toByte())
            i += 8
        }
        return out.toByteArray()
    }

    @Suppress("unused")
    fun md5(s: String): String = MessageDigest.getInstance("MD5")
        .digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

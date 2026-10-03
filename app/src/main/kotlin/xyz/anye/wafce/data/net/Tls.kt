package xyz.anye.wafce.data.net

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 雷池社区版控制台默认使用 9443 端口 + 自签名证书，
 * 因此客户端需要放宽 TLS 校验，否则任何实例都连不上。
 *
 * 风险说明：该配置等同于忽略中间人风险，仅适用于自建 WAF 控制台场景；
 * 若用户已在控制台部署了可信 CA 证书，可在账号设置中关闭「信任自签名证书」。
 */
object Tls {

    val manager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    val permissiveSocketFactory: SSLSocketFactory by lazy {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(manager), SecureRandom())
        ctx.socketFactory
    }
}

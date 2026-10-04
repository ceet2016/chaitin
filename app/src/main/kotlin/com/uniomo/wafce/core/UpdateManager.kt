package com.uniomo.wafce.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 应用内更新。
 *
 * 版本来源：CNB Release 的附件，该地址免登录可直接下载（已实测：
 * 返回 application/vnd.android.package-archive，约 12MB）。
 *
 * 判定方式：先把 APK 下载到应用专属目录，用 PackageManager 解析其中的
 * versionCode 与当前安装版本比较——这样不必额外维护一个版本清单接口。
 */
object UpdateManager {

    const val APK_URL =
        "https://cnb.cool/unicom/chaitin/-/releases/download/nightly/safeline-client.apk"

    /** 版本清单：只有几百字节，检查更新时先取它，避免白白下载 12MB APK */
    const val VERSION_URL =
        "https://cnb.cool/unicom/chaitin/-/releases/download/nightly/version.json"

    data class ApkInfo(val versionCode: Long, val versionName: String, val file: File)

    /** 拉取版本清单（轻量）。404 时返回 null，调用方据此回退 */
    suspend fun fetchLatest(): ApkInfo? = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        runCatching {
            client.newCall(Request.Builder().url(VERSION_URL).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val text = resp.body?.string().orEmpty()
                val code = Regex("\"versionCode\"\\s*:\\s*(\\d+)").find(text)
                    ?.groupValues?.get(1)?.toLongOrNull() ?: return@runCatching null
                val name = Regex("\"versionName\"\\s*:\\s*\"([^\"]+)\"").find(text)
                    ?.groupValues?.get(1) ?: ""
                ApkInfo(code, name, File(""))
            }
        }.getOrNull()
    }

    /** 当前已安装版本的 versionCode */
    fun currentCode(context: Context): Long = try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName, PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (e: Exception) {
        0L
    }

    /** 下载 APK，回调进度百分比 */
    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun download(
        context: Context,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
        val file = File(dir, "update.apk")
        if (file.exists()) file.delete()

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()

        client.newCall(Request.Builder().url(APK_URL).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("下载失败：HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("服务端返回空内容")
            val total = body.contentLength()
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        out.write(buf, 0, read)
                        done += read
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last) {
                                last = pct
                                withContext(Dispatchers.Main) { onProgress(pct) }
                            }
                        }
                    }
                }
            }
        }
        file
    }

    /** 不安装，只读取 APK 里的版本号 */
    fun parse(context: Context, file: File): ApkInfo? {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath, PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        } ?: return null

        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()

        @Suppress("DEPRECATION")
        return ApkInfo(code, info.versionName ?: "", file)
    }

    /** 调起系统安装界面 */
    fun install(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    /** 清理已下载的 APK */
    fun clear(context: Context) {
        runCatching {
            File(context.getExternalFilesDir(null), "updates").deleteRecursively()
        }
    }
}

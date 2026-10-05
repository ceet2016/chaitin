package com.uniomo.wafce.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.uniomo.wafce.core.UpdateManager
import com.uniomo.wafce.core.readable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 更新检查的各个阶段 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data class Found(val versionName: String, val versionCode: Long) : UpdateState
    data object UpToDate : UpdateState
    data class Failed(val message: String) : UpdateState
    data object Installing : UpdateState
}

class UpdateVm(app: Application) : AndroidViewModel(app) {

    var state: UpdateState by mutableStateOf(UpdateState.Idle)
        private set

    private var running = false

    /**
     * 已下载待安装的 APK。
     */
    private var pendingFile: java.io.File? = null

    /**
     * 上次检查的结论。
     *
     * 单独存一份是因为弹窗关闭后 state 必须回到 Idle（否则弹窗会再次弹出），
     * 但关于页仍需要如实显示「已是最新版本」而不是「尚未检查」。
     */
    data class CheckResult(val upToDate: Boolean, val remoteVersion: String = "")

    var lastResult: CheckResult? by mutableStateOf(null)
        private set

    /**
     * 关闭更新弹窗。
     *
     * 结论写入 [lastResult]，state 回到 Idle 让弹窗消失；
     * 关于页的 UpdatePanel 读 lastResult 继续展示，不会退回「尚未检查」。
     *
     * Installing 也允许关闭：系统安装界面是独立 Activity，用户点「取消」后
     * App 会重新回到前台，此时如果不提供出口 state 会永久卡在 Installing，
     * 「检查更新」按钮被进度圈顶替且永不可点，只能杀进程恢复。
     */
    fun dismiss() {
        // 下载中/检查中不允许中断
        if (state is UpdateState.Downloading || state is UpdateState.Checking) return
        state = UpdateState.Idle
    }

    /**
     * 从系统安装界面返回时调用。
     *
     * [UpdateManager.install] 只是 startActivity 调起系统安装器后立即返回，
     * 拿不到安装结果。用户在系统界面点「取消」时没有回调，只能靠
     * Activity 的 ON_RESUME 事件兜底把状态收回来。
     */
    fun onInstallReturned() {
        if (state is UpdateState.Installing) {
            state = UpdateState.Idle
            running = false
        }
    }

    /**
     * 检查更新：只拉取几百字节的版本清单做比较，**不下载 APK**。
     * 清单缺失（例如流水线尚未发布 version.json）时，
     * 回退为「下载 APK 解析版本」的老办法，保证功能可用。
     */
    fun check() {
        if (running) return
        running = true
        state = UpdateState.Checking
        val ctx = getApplication<Application>()
        val current = UpdateManager.currentCode(ctx)

        viewModelScope.launch {
            state = try {
                val latest = UpdateManager.fetchLatest()
                if (latest != null) {
                    if (latest.versionCode > current) {
                        lastResult = CheckResult(upToDate = false, remoteVersion = latest.versionName)
                        UpdateState.Found(latest.versionName, latest.versionCode)
                    } else {
                        lastResult = CheckResult(upToDate = true, remoteVersion = latest.versionName)
                        UpdateState.UpToDate
                    }
                } else {
                    // 回退：没有清单就只能下 APK 看版本
                    val file = UpdateManager.download(ctx) { pct -> state = UpdateState.Downloading(pct) }
                    val info = UpdateManager.parse(ctx, file)
                    if (info == null) {
                        UpdateManager.clear(ctx)
                        UpdateState.Failed("无法解析安装包，请稍后重试")
                    } else if (info.versionCode > current) {
                        pendingFile = file
                        lastResult = CheckResult(upToDate = false, remoteVersion = info.versionName)
                        UpdateState.Found(info.versionName, info.versionCode)
                    } else {
                        UpdateManager.clear(ctx)
                        lastResult = CheckResult(upToDate = true, remoteVersion = info.versionName)
                        UpdateState.UpToDate
                    }
                }
            } catch (e: CancellationException) {
                // 协程被取消（页面销毁/ ViewModel 清理）属于正常终止，
                // 不能当成失败，更不能在取消后继续执行清理副作用
                throw e
            } catch (e: Exception) {
                UpdateManager.clear(ctx)
                UpdateState.Failed(e.readable())
            }
            running = false
        }
    }

    /** 用户确认后才真正下载 APK，下完立即调起安装 */
    fun install() {
        if (running) return
        val cached = pendingFile
        if (cached != null && cached.exists()) {
            // 缓存命中时也要置 running，否则返回后立刻再次触发 check()
            // 会与 doInstall 并发写同一个 state
            running = true
            doInstall(cached)
            return
        }
        running = true
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            try {
                val file = UpdateManager.download(ctx) { pct -> state = UpdateState.Downloading(pct) }
                doInstall(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateManager.clear(ctx)
                state = UpdateState.Failed(e.readable())
                running = false
            }
        }
    }

    private fun doInstall(file: java.io.File) {
        val ctx = getApplication<Application>()
        state = UpdateState.Installing
        runCatching {
            UpdateManager.install(ctx, file)
        }.onFailure {
            state = UpdateState.Failed("调起安装失败：${it.readable()}")
            running = false
        }
    }
}
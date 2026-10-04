package com.uniomo.wafce.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.uniomo.wafce.core.UpdateManager
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

    /** 已下载待安装的 APK，点击确认后使用 */
    private var pendingFile: java.io.File? = null
    private var running = false

    fun dismiss() {
        if (state is UpdateState.Downloading || state is UpdateState.Checking) return
        state = UpdateState.Idle
    }

    /**
     * 检查更新：下载 APK → 解析版本 → 比较。
     * 只有 versionCode 严格大于当前版本才提示安装。
     */
    fun check() {
        if (running) return
        running = true
        state = UpdateState.Checking
        val ctx = getApplication<Application>()
        val current = UpdateManager.currentCode(ctx)

        viewModelScope.launch {
            state = try {
                val file = UpdateManager.download(ctx) { pct ->
                    state = UpdateState.Downloading(pct)
                }
                val info = UpdateManager.parse(ctx, file)
                if (info == null) {
                    UpdateManager.clear(ctx)
                    UpdateState.Failed("无法解析安装包，请稍后重试")
                } else if (info.versionCode > current) {
                    pendingFile = file
                    UpdateState.Found(info.versionName, info.versionCode)
                } else {
                    UpdateManager.clear(ctx)
                    UpdateState.UpToDate
                }
            } catch (e: Exception) {
                UpdateManager.clear(ctx)
                UpdateState.Failed(e.message ?: "检查更新失败")
            }
            running = false
        }
    }

    /** 用户确认安装 */
    fun install() {
        val file = pendingFile ?: return
        val ctx = getApplication<Application>()
        state = UpdateState.Installing
        runCatching {
            UpdateManager.install(ctx, file)
        }.onFailure {
            state = UpdateState.Failed("调起安装失败：${it.message ?: "未知错误"}")
        }
    }
}

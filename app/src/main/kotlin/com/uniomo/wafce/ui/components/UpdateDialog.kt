package com.uniomo.wafce.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.uniomo.wafce.BuildConfig
import com.uniomo.wafce.vm.UpdateState

/**
 * 更新对话框：按 [UpdateState] 展示不同阶段。
 * 下载中不显示关闭按钮，避免中断后残留半截文件。
 */
@Composable
fun UpdateDialog(
    state: UpdateState,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit
) {
    when (state) {
        UpdateState.Idle -> Unit
        UpdateState.Installing -> Unit

        UpdateState.Checking -> AlertDialog(
            onDismissRequest = {},
            title = { Text("检查更新") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(14.dp))
                    Text("正在获取最新版本…", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {}
        )

        is UpdateState.Downloading -> {
            val pct = state.percent
            AlertDialog(
                onDismissRequest = {},
                title = { Text("下载更新") },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (pct > 0) "已下载 $pct%" else "正在连接…",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        if (pct > 0) {
                            LinearProgressIndicator(
                                progress = { pct / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                },
                confirmButton = {}
            )
        }

        is UpdateState.Found -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = {
                Icon(Icons.Default.SystemUpdate, contentDescription = null)
            },
            title = { Text("发现新版本") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        "v${state.versionName}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "当前版本：v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "最新版本：v${state.versionName}（${state.versionCode}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "点击下方按钮才会开始下载安装包（约 12MB），完成后自动调起安装，无需卸载旧版本。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onInstall) { Text("下载并安装") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("稍后") }
            }
        )

        UpdateState.UpToDate -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            title = { Text("已是最新版本") },
            text = {
                Text(
                    "当前 v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）已是最新。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("确定") } }
        )

        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("更新失败") },
            text = {
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            },
            confirmButton = { TextButton(onClick = onRetry) { Text("重试") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
        )
    }
}

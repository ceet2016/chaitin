package com.uniomo.wafce.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uniomo.wafce.BuildConfig
import com.uniomo.wafce.ui.components.CardShape
import com.uniomo.wafce.ui.components.SectionCard
import com.uniomo.wafce.ui.components.TagChip
import com.uniomo.wafce.ui.theme.Brand
import com.uniomo.wafce.ui.theme.BrandDark
import com.uniomo.wafce.ui.theme.Danger
import com.uniomo.wafce.ui.theme.InfoColor
import com.uniomo.wafce.ui.theme.Ok
import com.uniomo.wafce.ui.theme.Warn
import com.uniomo.wafce.vm.AppViewModel
import com.uniomo.wafce.vm.UpdateState
import com.uniomo.wafce.vm.UpdateVm

private const val REPO_URL = "https://cnb.cool/unicom/chaitin"
private const val REPO_NAME = "cnb.cool/unicom/chaitin"
private const val DEV_NAME = "雨落秋垣"
private const val DEV_SITE = "https://uniomo.com"
private const val DEV_SITE_NAME = "uniomo.com"

/**
 * 「关于」页：本应用自身的版本与项目信息 + 当前连接的 WAF 引擎信息。
 *
 * 两个入口共用：
 * - 系统管理 → 关于
 * - 主界面顶栏 → 关于
 *
 * @param updateVm 传入时展示「检查更新」区块。更新入口原本在顶栏，
 *                 但顶栏空间宝贵且刷新/更新两个按钮挤在一起，现已下沉到本页。
 */
@Composable
fun AboutScreen(
    account: com.uniomo.wafce.data.store.AccountProfile?,
    appVm: AppViewModel,
    onBack: (() -> Unit)? = null,
    updateVm: UpdateVm? = null
) {
    val ctx = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // ---------- 应用主视觉 ----------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Brand, BrandDark)),
                    RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)
                )
                .padding(horizontal = 20.dp, vertical = 22.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                // 从主界面顶栏进入时才显示返回（系统管理页签内不需要）
                if (onBack != null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "返回",
                                tint = Color.White
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .background(Color.White.copy(alpha = 0.18f), CardShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Security,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "雷池客户端",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "SafeLine CE Android Client",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.72f)
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TagChip(
                        text = "v${BuildConfig.VERSION_NAME}",
                        color = Color.White
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "versionCode ${BuildConfig.VERSION_CODE}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // ---------- 项目信息 ----------
        SectionCard("项目信息") {
            LinkRow(
                icon = Icons.Default.Code,
                label = "开源仓库",
                value = REPO_NAME,
                tint = InfoColor,
                onClick = { openUrl(ctx, REPO_URL) },
                onCopy = { copyText(ctx, REPO_URL) }
            )
            Spacer(Modifier.height(10.dp))
            InfoRow(
                icon = Icons.Default.Person,
                label = "开发者",
                value = DEV_NAME,
                tint = Ok
            )
            Spacer(Modifier.height(10.dp))
            LinkRow(
                icon = Icons.Default.Language,
                label = "开发者网站",
                value = DEV_SITE_NAME,
                tint = Ok,
                onClick = { openUrl(ctx, DEV_SITE) },
                onCopy = { copyText(ctx, DEV_SITE) }
            )
        }

        // ---------- 应用信息 ----------
        SectionCard("应用信息") {
            InfoRow(Icons.Default.Android, "包名", BuildConfig.APPLICATION_ID, Brand)
            Spacer(Modifier.height(10.dp))
            InfoRow(Icons.Default.Description, "构建类型", BuildConfig.BUILD_TYPE, InfoColor)
            Spacer(Modifier.height(10.dp))
            InfoRow(Icons.Default.Info, "支持系统", "Android 8.0（API 26）及以上", Warn)
        }

        // ---------- 应用更新 ----------
        // 顶栏的更新按钮已移除，这里是唯一的更新入口
        if (updateVm != null) {
            SectionCard("应用更新", subtitle = "检查是否有新版本发布到 nightly 通道") {
                UpdatePanel(updateVm)
            }
        }

        // ---------- 引擎信息 ----------
        account?.let { acc ->
            SectionCard("WAF 引擎", subtitle = "当前连接的雷池实例") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            acc.name.ifBlank { "WAF 实例" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            acc.baseUrl,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = { appVm.logout() }) {
                        Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("退出")
                    }
                }
            }
        }

        SectionCard("声明") {
            Text(
                "本应用为第三方社区工具，与长亭科技官方无隶属关系。" +
                    "雷池（SafeLine）为长亭科技的开源产品。" +
                    "所有数据仅保存在本机，凭据经 Android Keystore 加密。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(28.dp))
    }
}

/**
 * 更新面板：把 [UpdateState] 映射成一行状态 + 一个主按钮。
 *
 * 只做「触发检查」与「展示结论」，下载/安装的弹窗由上层 UpdateDialog 负责，
 * 避免同一时刻出现两个弹窗。
 */
@Composable
private fun UpdatePanel(updateVm: UpdateVm) {
    val checking = updateVm.state is UpdateState.Checking ||
        updateVm.state is UpdateState.Downloading ||
        updateVm.state is UpdateState.Installing

    val (statusText, statusColor) = when (val s = updateVm.state) {
        UpdateState.Idle -> "尚未检查" to MaterialTheme.colorScheme.onSurfaceVariant
        UpdateState.Checking -> "正在检查…" to InfoColor
        is UpdateState.Downloading -> "下载中 ${s.percent}%" to InfoColor
        is UpdateState.Found -> "发现新版本 v${s.versionName}" to Warn
        UpdateState.UpToDate -> "已是最新版本" to Ok
        is UpdateState.Failed -> s.message to Danger
        UpdateState.Installing -> "正在调起安装" to InfoColor
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(Warn.copy(alpha = 0.14f), CardShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.SystemUpdate,
                    contentDescription = null,
                    tint = Warn,
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "当前版本 v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor
                )
            }
            Spacer(Modifier.width(8.dp))
            if (checking) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = { updateVm.check() }) { Text("检查更新") }
            }
        }
    }
}

/**
 * 纯展示行 —— 与 [LinkRow] 使用完全相同的 Card 容器与内边距，
 * 保证同一分组内的图标与文字左右对齐（之前裸 Row 会差 12dp 内边距）。
 */
@Composable
private fun InfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    tint: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(tint.copy(alpha = 0.14f), CardShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 可点击打开、并可复制的行 */
@Composable
private fun LinkRow(
    icon: ImageVector,
    label: String,
    value: String,
    tint: Color,
    onClick: () -> Unit,
    onCopy: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(tint.copy(alpha = 0.14f), CardShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onCopy, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "复制",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp)
                )
            }
            Icon(
                Icons.Default.OpenInNew,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun openUrl(ctx: Context, url: String) {
    runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        Toast.makeText(ctx, "无法打开链接", Toast.LENGTH_SHORT).show()
    }
}

private fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText("link", text))
    Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
}

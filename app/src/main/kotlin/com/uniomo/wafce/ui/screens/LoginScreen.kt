package com.uniomo.wafce.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.uniomo.wafce.data.store.AccountProfile
import com.uniomo.wafce.data.store.normalizeBaseUrl
import com.uniomo.wafce.ui.components.CardShape
import com.uniomo.wafce.ui.components.SectionCard
import com.uniomo.wafce.vm.AppViewModel

/**
 * 登录页 —— 仅支持 API Token 方式接入。
 *
 * 原因：账号密码登录要走 /api/open/auth/login，雷池对该接口有连续失败风控，
 * 触发后会直接返回 400 且长时间无法自动恢复；Token 方式走 X-SLCE-API-TOKEN 头，
 * 不经过登录接口，稳定性与可控性都更好。
 */
@Composable
fun LoginScreen(vm: AppViewModel, account: AccountProfile, onCancel: (() -> Unit)? = null) {
    var baseUrl by remember(account.id) { mutableStateOf(account.baseUrl.trimEnd('/')) }
    var name by remember(account.id) { mutableStateOf(account.name) }
    var token by remember { mutableStateOf("") }
    var trustAll by remember(account.id) { mutableStateOf(account.trustAllCert) }
    var error by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (onCancel != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "返回")
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            shape = CardShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("雷池 WAF 客户端", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "社区版 · API Token 接入",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("控制台地址") },
            placeholder = { Text("https://192.168.1.10:9443") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("备注名") },
            placeholder = { Text("例如：生产环境 WAF") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("API Token") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 4
        )

        Spacer(Modifier.height(12.dp))
        SectionCard(
            title = "如何获取 Token",
            subtitle = "控制台 → 系统设置 → OPEN API → 生成 Token"
        ) {
            Text(
                "生成时需要勾选本客户端要用的模块权限（站点、事件、IP 组、证书等）。" +
                    "未授权的模块会返回空数据，属正常现象。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("信任自签名证书", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "社区版控制台默认为自签证书，建议保持开启",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = trustAll, onCheckedChange = { trustAll = it })
        }

        if (error.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                when {
                    baseUrl.isBlank() -> error = "请填写控制台地址"
                    token.isBlank() -> error = "请填写 API Token"
                    else -> {
                        error = ""
                        vm.loginWithToken(
                            AccountProfile(
                                id = account.id,
                                name = name.trim(),
                                baseUrl = normalizeBaseUrl(baseUrl),
                                trustAllCert = trustAll
                            ),
                            token.trim()
                        ) { ok, msg ->
                            if (!ok) error = msg
                        }
                    }
                }
            },
            enabled = !vm.busy,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            if (vm.busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("连接并进入")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

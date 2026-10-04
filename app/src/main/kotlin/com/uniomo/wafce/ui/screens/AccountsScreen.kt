package com.uniomo.wafce.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uniomo.wafce.data.store.AccountProfile
import com.uniomo.wafce.data.store.AuthMode
import com.uniomo.wafce.ui.components.CardShape
import com.uniomo.wafce.ui.components.TagChip
import com.uniomo.wafce.ui.theme.Brand
import com.uniomo.wafce.ui.theme.Danger
import com.uniomo.wafce.ui.theme.Ok
import com.uniomo.wafce.vm.AppViewModel

/**
 * 账号（多 WAF 实例）管理页。
 * 首次启动没有任何实例时作为落地页使用。
 */
@Composable
fun AccountsScreen(vm: AppViewModel, firstLaunch: Boolean = false) {
    var editing by remember { mutableStateOf<AccountProfile?>(null) }
    var pendingDelete by remember { mutableStateOf<AccountProfile?>(null) }

    if (editing != null) {
        LoginScreen(vm = vm, account = editing!!, onCancel = { editing = null })
        return
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = AccountProfile(trustAllCert = true) }) {
                Icon(Icons.Default.Add, contentDescription = "新增实例")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null,
                    tint = Brand,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.size(10.dp))
                Column {
                    Text(
                        "雷池 WAF 实例",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "社区版 · 支持多实例快速切换",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (vm.accounts.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("还没有添加任何 WAF 实例", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "点击右下角按钮，填写控制台地址与凭据即可接入",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    item { Spacer(Modifier.height(8.dp)) }
                    items(vm.accounts, key = { it.id }) { acc ->
                        AccountRow(
                            account = acc,
                            isCurrent = acc.id == vm.current?.id,
                            onSwitch = { vm.switchTo(acc.id) },
                            onEdit = { editing = acc },
                            onDelete = { pendingDelete = acc }
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除实例") },
            text = { Text("确定删除「${target.name.ifBlank { target.baseUrl }}」？本地保存的凭据会一并清除。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAccount(target.id)
                    pendingDelete = null
                }) { Text("删除", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun AccountRow(
    account: AccountProfile,
    isCurrent: Boolean,
    onSwitch: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = CardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        onClick = onSwitch
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    account.name.ifBlank { account.baseUrl },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isCurrent) {
                    TagChip(text = "当前", color = Brand)
                    Spacer(Modifier.size(8.dp))
                }
                TagChip(
                    text = if (account.authMode == AuthMode.SESSION) "会话登录" else "API Token",
                    color = if (account.authMode == AuthMode.SESSION) Ok else Color(0xFF6B7280)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                account.baseUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (account.authMode == AuthMode.SESSION && account.username.isNotBlank()) {
                Text(
                    "账号：${account.username}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (account.lastError.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    account.lastError,
                    style = MaterialTheme.typography.labelSmall,
                    color = Danger,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(shape = CardShape, onClick = onEdit) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("编辑", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.size(8.dp))
                Surface(shape = CardShape, onClick = onDelete) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = Danger,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.size(4.dp))
                        Text("删除", style = MaterialTheme.typography.labelMedium, color = Danger)
                    }
                }
            }
        }
    }
}

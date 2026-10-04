package com.uniomo.wafce.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.launch
import com.uniomo.wafce.core.Resp
import com.uniomo.wafce.core.UiState
import com.uniomo.wafce.core.readable
import com.uniomo.wafce.data.net.ApiClient
import com.uniomo.wafce.data.store.AccountProfile
import com.uniomo.wafce.ui.components.AsyncBox
import com.uniomo.wafce.ui.components.CardShape
import com.uniomo.wafce.ui.components.EmptyBox
import com.uniomo.wafce.ui.components.KeyValueRow
import com.uniomo.wafce.ui.components.SectionCard
import com.uniomo.wafce.ui.components.TagChip
import com.uniomo.wafce.ui.theme.Brand
import com.uniomo.wafce.ui.theme.Danger
import com.uniomo.wafce.ui.theme.InfoColor
import com.uniomo.wafce.ui.theme.Ok

data class SiteItem(
    val id: Long,
    val displayName: String,
    val domains: List<String>,
    val ports: List<String>,
    val upstreams: List<String>,
    val comment: String,
    val certId: Long,
    val raw: JsonObject
)

class SitesVm : ViewModel() {

    var state: UiState<List<SiteItem>> by mutableStateOf(UiState.Idle)
        private set

    var editorOpen by mutableStateOf(false)
        private set
    var editingOriginal by mutableStateOf<SiteItem?>(null)
        private set
    var pendingDelete by mutableStateOf<SiteItem?>(null)
        private set
    var working by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun load(account: AccountProfile) {
        viewModelScope.launch {
            state = UiState.Loading
            state = try {
                val body = ApiClient.apiFor(account).sites(mapOf("page" to "1", "page_size" to "100"))
                val raw = Resp.obj(body)
                val list = Resp.nodes(raw).ifEmpty { Resp.arr(body) }
                UiState.Success(list.map { parse(it) })
            } catch (e: Exception) {
                UiState.Fail(e.readable(), e is com.uniomo.wafce.core.UnauthorizedException)
            }
        }
    }

    fun openNew() {
        editingOriginal = null
        editorOpen = true
    }

    fun openEdit(site: SiteItem) {
        editingOriginal = site
        editorOpen = true
    }

    fun closeEditor() { editorOpen = false }

    fun requestDelete(site: SiteItem) { pendingDelete = site }

    fun cancelDelete() { pendingDelete = null }

    fun ack() { message = null }

    fun save(
        account: AccountProfile,
        domains: String,
        ports: String,
        upstreams: String,
        comment: String
    ) {
        val original = editingOriginal
        viewModelScope.launch {
            working = true
            message = try {
                val api = ApiClient.apiFor(account)
                val domList = splitList(domains)
                val body = JsonObject().apply {
                    add("server_names", JsonArray().apply { domList.forEach { add(JsonPrimitive(it)) } })
                    add("ports", JsonArray().apply { splitList(ports).forEach { add(JsonPrimitive(it)) } })
                    add("upstreams", JsonArray().apply { splitList(upstreams).forEach { add(JsonPrimitive(it)) } })
                    addProperty("comment", comment)
                    val lb = JsonObject().apply { addProperty("balance_type", 1) }
                    add("load_balance", lb)
                }
                if (original != null) {
                    body.addProperty("id", original.id)
                    api.updateSite(original.id, body)
                } else {
                    api.createSite(body)
                }
                closeEditor()
                load(account)
                "保存成功"
            } catch (e: Exception) {
                e.readable()
            }
            working = false
        }
    }

    fun confirmDelete(account: AccountProfile) {
        val target = pendingDelete ?: return
        viewModelScope.launch {
            working = true
            message = try {
                ApiClient.apiFor(account).deleteSite(target.id)
                load(account)
                "站点已删除"
            } catch (e: Exception) {
                e.readable()
            }
            pendingDelete = null
            working = false
        }
    }

    private fun splitList(input: String): List<String> =
        input.split(",", "，", "\n", " ").map { it.trim() }.filter { it.isNotBlank() }

    private fun parse(o: JsonObject): SiteItem {
        val domains = Resp.strList(o, "server_names", "server_name", "domains", "hosts")
        val ports = Resp.strList(o, "ports", "port")
        val ups = Resp.strList(o, "upstreams", "upstream", "backend")
        return SiteItem(
            id = Resp.l(o, "id"),
            displayName = domains.firstOrNull()
                ?: Resp.str(o, "comment", "name", "server_name").ifBlank { "站点 ${Resp.l(o, "id")}" },
            domains = domains,
            ports = ports,
            upstreams = ups,
            comment = Resp.str(o, "comment", "remark", "desc"),
            certId = Resp.l(o, "cert_id", "ssl_id", "certificate_id"),
            raw = o
        )
    }
}

@Composable
fun SitesScreen(account: AccountProfile, nav: NavHostController) {
    val vm: SitesVm = viewModel()

    DisposableEffect(account.id) {
        vm.load(account)
        onDispose { }
    }

    vm.message?.let {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
        ) { Text(it, style = MaterialTheme.typography.bodySmall) }
        androidx.compose.runtime.LaunchedEffect(it) {
            kotlinx.coroutines.delay(2600)
            vm.ack()
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.openNew() }) {
                Icon(Icons.Default.Add, contentDescription = "新增站点")
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            AsyncBox(state = vm.state, onRetry = { vm.load(account) }) { list ->
                if (list.isEmpty()) {
                    EmptyBox("暂无防护站点，点击右下角新增")
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item { Spacer(Modifier.height(6.dp)) }
                        items(list, key = { it.id }) { site ->
                            SiteRow(
                                site = site,
                                onOpen = { nav.navigate("site/${site.id}") },
                                onEdit = { vm.openEdit(site) },
                                onDelete = { vm.requestDelete(site) }
                            )
                        }
                        item { Spacer(Modifier.height(80.dp)) }
                    }
                }
            }
        }
    }

    if (vm.editorOpen) {
        SiteEditorDialog(
            original = vm.editingOriginal,
            working = vm.working,
            onDismiss = { vm.closeEditor() },
            onSave = { d, p, u, c -> vm.save(account, d, p, u, c) }
        )
    }

    vm.pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { vm.cancelDelete() },
            title = { Text("删除站点") },
            text = { Text("确定删除站点「${target.displayName}」？删除后该域名将不再受保护。") },
            confirmButton = {
                TextButton(onClick = { vm.confirmDelete(account) }) {
                    Text("删除", color = Danger)
                }
            },
            dismissButton = { TextButton(onClick = { vm.cancelDelete() }) { Text("取消") } }
        )
    }
}

@Composable
private fun SiteRow(site: SiteItem, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        onClick = onOpen
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Dns, contentDescription = null, tint = Brand, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    site.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TagChip(
                    text = if (site.upstreams.isEmpty()) "无上游" else "×${site.upstreams.size}",
                    color = if (site.upstreams.isEmpty()) Danger else Ok
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "端口：${site.ports.joinToString("、").ifBlank { "未配置" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (site.upstreams.isNotEmpty()) {
                Text(
                    "上游：${site.upstreams.joinToString("、")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (site.comment.isNotBlank()) {
                Text(
                    "备注：${site.comment}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(shape = CardShape, onClick = onEdit) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(15.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("编辑", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.size(8.dp))
                Surface(shape = CardShape, onClick = onDelete) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "删除", tint = Danger, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("删除", style = MaterialTheme.typography.labelMedium, color = Danger)
                    }
                }
            }
        }
    }
}

@Composable
private fun SiteEditorDialog(
    original: SiteItem?,
    working: Boolean,
    onDismiss: () -> Unit,
    onSave: (domains: String, ports: String, upstreams: String, comment: String) -> Unit
) {
    var domains by remember { mutableStateOf(original?.domains?.joinToString(",") ?: "") }
    var ports by remember { mutableStateOf(original?.ports?.joinToString(",") ?: "80") }
    var upstreams by remember { mutableStateOf(original?.upstreams?.joinToString(",") ?: "") }
    var comment by remember { mutableStateOf(original?.comment ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original == null) "新增防护站点" else "编辑站点") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = domains, onValueChange = { domains = it },
                    label = { Text("域名（多个用逗号分隔，* 表示全部）") }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ports, onValueChange = { ports = it },
                    label = { Text("监听端口（逗号分隔）") }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = upstreams, onValueChange = { upstreams = it },
                    label = { Text("上游服务器") },
                    placeholder = { Text("http://10.0.0.2:8080") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = comment, onValueChange = { comment = it },
                    label = { Text("备注") }, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(domains, ports, upstreams, comment) },
                enabled = !working
            ) {
                if (working) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ------------------------------------------------------------------
// 站点详情
// ------------------------------------------------------------------

class SiteDetailVm : ViewModel() {
    var state: UiState<JsonObject> by mutableStateOf(UiState.Idle)
        private set

    fun load(account: AccountProfile, id: Long) {
        viewModelScope.launch {
            state = UiState.Loading
            state = try {
                val raw = Resp.obj(ApiClient.apiFor(account).site(id))
                UiState.Success(raw)
            } catch (e: Exception) {
                UiState.Fail(e.readable(), e is com.uniomo.wafce.core.UnauthorizedException)
            }
        }
    }
}

@Composable
fun SiteDetailScreen(account: AccountProfile, id: Long, onBack: () -> Unit) {
    val vm: SiteDetailVm = viewModel()
    DisposableEffect(id, account.id) {
        vm.load(account, id)
        onDispose { }
    }

    Column(Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxWidth().clickable { onBack() }.padding(horizontal = 16.dp, vertical = 10.dp),
            color = androidx.compose.ui.graphics.Color.Transparent
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("返回站点列表", style = MaterialTheme.typography.bodyMedium)
            }
        }

        AsyncBox(vm.state, { vm.load(account, id) }) { raw ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                SectionCard("基础信息", subtitle = "站点 ID：$id") {
                    KeyValueRow("域名", Resp.strList(raw, "server_names", "domains").joinToString("、").ifBlank { "未配置" })
                    KeyValueRow("监听端口", Resp.strList(raw, "ports").joinToString("、"))
                    KeyValueRow(
                        "上游服务器",
                        Resp.strList(raw, "upstreams", "upstream").joinToString("、").ifBlank { "未配置" }
                    )
                    KeyValueRow("备注", Resp.str(raw, "comment").ifBlank { "—" })
                    KeyValueRow(
                        "证书 ID",
                        Resp.l(raw, "cert_id", "ssl_id").takeIf { it > 0 }?.toString() ?: "未绑定",
                        if (Resp.l(raw, "cert_id", "ssl_id") > 0) Ok else InfoColor
                    )
                }
                SectionCard("原始配置返回值", subtitle = "用于排查字段差异") {
                    Text(
                        pretty(raw),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun pretty(o: JsonObject): String = try {
    com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(o)
} catch (e: Exception) {
    o.toString()
}

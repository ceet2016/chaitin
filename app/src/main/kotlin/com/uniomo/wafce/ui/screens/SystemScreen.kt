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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Logout
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.google.gson.JsonObject
import kotlinx.coroutines.launch
import com.uniomo.wafce.core.HttpStatusException
import com.uniomo.wafce.core.LicenseRequiredException
import com.uniomo.wafce.core.Resp
import com.uniomo.wafce.core.UnauthorizedException
import com.uniomo.wafce.core.UiState
import com.uniomo.wafce.core.readable
import com.uniomo.wafce.data.net.ApiClient
import com.uniomo.wafce.data.store.AccountProfile
import com.uniomo.wafce.ui.components.CardShape
import com.uniomo.wafce.ui.components.EmptyBox
import com.uniomo.wafce.ui.components.ErrorBox
import com.uniomo.wafce.ui.components.KeyValueRow
import com.uniomo.wafce.ui.components.SectionCard
import com.uniomo.wafce.ui.components.TagChip
import com.uniomo.wafce.ui.theme.Brand
import com.uniomo.wafce.ui.theme.Danger
import com.uniomo.wafce.ui.theme.Warn
import com.uniomo.wafce.vm.AppViewModel

data class SysTab(val key: String, val label: String)

private val ALL_TABS = listOf(
    SysTab("cert", "证书"),
    SysTab("users", "用户"),
    SysTab("token", "API Token"),
    SysTab("notice", "通知"),
    SysTab("system", "关于")
)

class SystemVm : ViewModel() {

    var tab by mutableIntStateOf(0)

    var certs: UiState<List<JsonObject>> by mutableStateOf(UiState.Idle)
        private set
    var users: UiState<List<JsonObject>> by mutableStateOf(UiState.Idle)
        private set
    var tokens: UiState<List<JsonObject>> by mutableStateOf(UiState.Idle)
        private set
    var notices: UiState<List<JsonObject>> by mutableStateOf(UiState.Idle)
        private set
    var system: UiState<JsonObject> by mutableStateOf(UiState.Idle)
        private set

    /** 该版本控制台不存在的接口（404），对应页签会自动移除 */
    var unsupported: Set<String> by mutableStateOf(emptySet())
        private set

    var message by mutableStateOf<String?>(null)
        private set
    var certEditorOpen by mutableStateOf(false)
        private set

    fun ack() { message = null }

    fun visibleTabs(): List<SysTab> = ALL_TABS.filter { it.key !in unsupported }

    fun currentKey(): String = visibleTabs().getOrNull(tab)?.key ?: "cert"

    fun loadAll(account: AccountProfile) {
        loadCerts(account); loadUsers(account); loadTokens(account)
        loadNotices(account); loadSystem(account)
    }

    fun loadCerts(account: AccountProfile) {
        viewModelScope.launch {
            loadInto("cert", { certs = it }) {
                list(ApiClient.apiFor(account).certs(mapOf("page" to "1", "page_size" to "100")))
            }
        }
    }

    fun loadUsers(account: AccountProfile) {
        viewModelScope.launch {
            loadInto("users", { users = it }) {
                list(ApiClient.apiFor(account).users(mapOf("page" to "1", "page_size" to "100")))
            }
        }
    }

    fun loadTokens(account: AccountProfile) {
        viewModelScope.launch {
            loadInto("token", { tokens = it }) {
                // 9.4.x 的 /api/open/auth/token 返回的 data 是「字符串」而非对象，
                // 直接当对象解析会报「返回数据格式非对象」，这里按原始类型分别处理。
                val api = ApiClient.apiFor(account)
                val data = Resp.unwrap(api.apiTokens(emptyMap()))
                val out = mutableListOf<JsonObject>()
                when {
                    data.isJsonPrimitive -> out.add(
                        JsonObject().apply {
                            addProperty("name", "当前 API Token")
                            addProperty("token", data.asString)
                        }
                    )
                    data.isJsonObject -> out.add(data.asJsonObject)
                    data.isJsonArray -> data.asJsonArray.mapNotNullTo(out) {
                        if (it.isJsonObject) it.asJsonObject else null
                    }
                }
                out
            }
        }
    }

    fun loadNotices(account: AccountProfile) {
        viewModelScope.launch {
            // 告警通知属商业版能力，社区版返回 403 license required，会被自动隐藏
            loadInto("notice", { notices = it }) {
                val raw = Resp.obj(ApiClient.apiFor(account).alarm())
                val arr = Resp.firstArray(raw)
                if (arr != null) arr.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
                else listOf(raw)
            }
        }
    }

    fun loadSystem(account: AccountProfile) {
        viewModelScope.launch {
            loadInto("system", { system = it }) {
                Resp.obj(ApiClient.apiFor(account).systemInfo())
            }
        }
    }

    /**
     * 统一加载编排：404 视为「该版本不支持」并登记后隐藏对应页签；
     * 其余异常按普通错误处理。**每个资源各自持有状态，互不覆盖**，
     * 否则会出现「列表有数据但顶部报 404」的错觉。
     */
    private suspend fun <T> loadInto(
        key: String,
        assign: (UiState<T>) -> Unit,
        block: suspend () -> T
    ) {
        assign(UiState.Loading)
        try {
            assign(UiState.Success(block()))
        } catch (e: Exception) {
            if (isUnsupported(e)) {
                if (key !in unsupported) unsupported = unsupported + key
            }
            assign(UiState.Fail(e.readable(), e is UnauthorizedException))
        }
    }

    /**
     * 404=该版本无此接口；LicenseRequired=商业版能力，社区版均视为不可用（隐藏页签）。
     * 注意：不要把这两种情况误报成「登录失效」。
     */
    private fun isUnsupported(e: Throwable): Boolean =
        e is LicenseRequiredException ||
            (e is HttpStatusException && e.httpCode == 404)

    private fun list(body: JsonObject): List<JsonObject> {
        val raw = Resp.obj(body)
        return Resp.nodes(raw).ifEmpty { Resp.arr(body) }
    }

    fun openCertEditor() { certEditorOpen = true }
    fun closeCertEditor() { certEditorOpen = false }

    fun uploadCert(account: AccountProfile, comment: String, crt: String, key: String) {
        viewModelScope.launch {
            message = try {
                val manual = JsonObject().apply {
                    addProperty("crt", crt)
                    addProperty("key", key)
                }
                val body = JsonObject().apply {
                    addProperty("comment", comment)
                    addProperty("type", 2)
                    add("manual", manual)
                }
                ApiClient.apiFor(account).createCert(body)
                closeCertEditor()
                loadCerts(account)
                "证书已导入"
            } catch (e: Exception) {
                "证书导入失败：${e.readable()}"
            }
        }
    }

    fun deleteCert(account: AccountProfile, id: Long) {
        viewModelScope.launch {
            message = try {
                ApiClient.apiFor(account).deleteCert(id)
                loadCerts(account)
                "证书已删除"
            } catch (e: Exception) {
                e.readable()
            }
        }
    }

    /** 删除当前 API Token（9.4.x 的 DELETE /api/open/auth/token 不带路径参数） */
    fun deleteToken(account: AccountProfile) {
        viewModelScope.launch {
            message = try {
                ApiClient.apiFor(account).deleteToken()
                loadTokens(account)
                "Token 已删除，请重新生成后登录"
            } catch (e: Exception) {
                e.readable()
            }
        }
    }
}

@Composable
fun SystemScreen(account: AccountProfile, appVm: AppViewModel) {
    val vm: SystemVm = viewModel()
    DisposableEffect(account.id) {
        vm.loadAll(account)
        onDispose { }
    }

    val tabs = vm.visibleTabs()
    if (vm.tab >= tabs.size && tabs.isNotEmpty()) vm.tab = 0

    vm.message?.let { m ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(m, style = MaterialTheme.typography.bodySmall, color = Brand)
        }
        androidx.compose.runtime.LaunchedEffect(m) {
            kotlinx.coroutines.delay(2600)
            vm.ack()
        }
    }

    if (tabs.isEmpty()) {
        EmptyBox("当前控制台版本未提供系统管理接口")
        return
    }

    Scaffold(
        // 外层 MainScaffold 已处理系统栏，这里清零避免重复叠加导致顶部留白
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            if (vm.currentKey() == "cert") {
                FloatingActionButton(onClick = { vm.openCertEditor() }) {
                    Icon(Icons.Default.Add, contentDescription = "导入证书")
                }
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(selectedTabIndex = vm.tab.coerceIn(0, tabs.lastIndex)) {
                tabs.forEachIndexed { i, t ->
                    Tab(
                        selected = vm.tab == i,
                        onClick = { vm.tab = i },
                        text = { Text(t.label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }
            when (vm.currentKey()) {
                "cert" -> ListArea(
                    vm.certs, "暂无证书，可点右下角导入",
                    { vm.loadCerts(account) },
                    onDelete = { o -> vm.deleteCert(account, Resp.l(o, "id")) }
                )
                "users" -> ListArea(vm.users, "未获取到用户（需管理员权限）", { vm.loadUsers(account) })
                "token" -> ListArea(
                    vm.tokens, "暂无 API Token 信息",
                    { vm.loadTokens(account) },
                    onDelete = { vm.deleteToken(account) }
                )
                "notice" -> ListArea(vm.notices, "暂无通知策略", { vm.loadNotices(account) })
                "system" -> AboutScreen(account = account, appVm = appVm)
            }
        }
    }

    if (vm.certEditorOpen) {
        CertDialog(
            onDismiss = { vm.closeCertEditor() },
            onSave = { c, crt, key -> vm.uploadCert(account, c, crt, key) }
        )
    }
}

@Composable
private fun ListArea(
    state: UiState<List<JsonObject>>,
    emptyText: String,
    onRetry: () -> Unit,
    onDelete: ((JsonObject) -> Unit)? = null
) {
    when (state) {
        UiState.Idle, UiState.Loading -> Box(
            Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp) }

        is UiState.Fail -> ErrorBox(state.message, onRetry)

        is UiState.Success -> {
            if (state.data.isEmpty()) {
                EmptyBox(emptyText)
                return
            }
            LazyColumn(Modifier.fillMaxSize()) {
                item { Spacer(Modifier.height(4.dp)) }
                items(state.data) { o ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
                        shape = CardShape,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    titleOf(o),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    subtitleOf(o),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (onDelete != null) {
                                IconButton(onClick = { onDelete(o) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "删除",
                                        tint = Danger,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

private fun titleOf(o: JsonObject): String = when {
    // API Token：/api/open/auth/token 返回的是字符串，已包装成 {name, token}
    Resp.str(o, "token", "api_token").isNotBlank() ->
        Resp.str(o, "name", "comment").ifBlank { "API Token" }
    Resp.str(o, "common_name", "domain", "server_name", "name").isNotBlank() ->
        Resp.str(o, "common_name", "domain", "server_name", "name")
    Resp.str(o, "username", "user").isNotBlank() -> Resp.str(o, "username", "user")
    Resp.str(o, "comment", "remark", "title").isNotBlank() -> Resp.str(o, "comment", "remark", "title")
    else -> "ID ${Resp.l(o, "id")}"
}

private fun subtitleOf(o: JsonObject): String {
    val token = Resp.str(o, "token", "api_token")
    if (token.isNotBlank()) return token
    val parts = mutableListOf<String>()
    val deadline = Resp.l(o, "deadline", "expire_at", "expires_at", "valid_until")
    if (deadline > 0) parts.add("有效期至 ${formatTs(deadline)}")
    Resp.str(o, "not_after", "expire", "expire_time").takeIf { it.isNotBlank() }?.let { parts.add(it) }
    Resp.str(o, "dns_names", "sans", "domains").takeIf { it.isNotBlank() }?.let { parts.add(it) }
    Resp.str(o, "remark", "note").takeIf { it.isNotBlank() }?.let { parts.add(it) }
    Resp.str(o, "expired_at", "created_at").takeIf { it.isNotBlank() }?.let { parts.add("创建 $it") }
    parts += "ID ${Resp.l(o, "id")}"
    return parts.joinToString(" · ")
}

private fun formatTs(sec: Long): String = try {
    val ms = if (sec > 10_000_000_000L) sec else sec * 1000L
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(ms))
} catch (e: Exception) {
    sec.toString()
}

@Composable
private fun CertDialog(
    onDismiss: () -> Unit,
    onSave: (comment: String, crt: String, key: String) -> Unit
) {
    var comment by remember { mutableStateOf("") }
    var crt by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入证书") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = comment, onValueChange = { comment = it },
                    label = { Text("备注名") }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = crt, onValueChange = { crt = it },
                    label = { Text("证书内容 (PEM)") },
                    minLines = 4, maxLines = 6, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text("私钥内容 (PEM)") },
                    minLines = 4, maxLines = 6, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(comment, crt, key) }) { Text("导入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

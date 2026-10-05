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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.uniomo.wafce.core.HttpStatusException
import com.uniomo.wafce.core.LicenseRequiredException
import com.uniomo.wafce.core.Resp
import com.uniomo.wafce.core.TimeFmt
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
import com.uniomo.wafce.ui.components.rememberMsgHost
import com.uniomo.wafce.ui.theme.Brand
import com.uniomo.wafce.ui.theme.Danger
import com.uniomo.wafce.ui.theme.Warn
import com.uniomo.wafce.vm.AppViewModel
import com.uniomo.wafce.vm.UpdateVm

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
    var pendingCertDelete by mutableStateOf<JsonObject?>(null)
        private set
    var pendingTokenDelete by mutableStateOf(false)
        private set

    /**
     * 写操作进行中。早期版本完全没有这个标记，导入证书 / 删除证书 / 删除 Token
     * 都能被连点，会并发打出多个请求。
     */
    var working by mutableStateOf(false)
        private set

    /**
     * 请求代次。viewModel() 作用域是 NavBackStackEntry，切账号时本实例复用，
     * 不校验的话旧账号慢返回的证书列表会覆盖新账号界面，
     * 配合「一键删除」用户会删掉错账号的证书。
     */
    private var epoch = 0
    private var loadJob: kotlinx.coroutines.Job? = null

    fun ack() { message = null }

    fun visibleTabs(): List<SysTab> = ALL_TABS.filter { it.key !in unsupported }

    fun currentKey(): String = visibleTabs().getOrNull(tab)?.key ?: "cert"

    fun loadAll(account: AccountProfile) {
        loadJob?.cancel()
        val myEpoch = ++epoch
        loadJob = viewModelScope.launch {
            loadCerts(account, myEpoch)
            loadUsers(account, myEpoch)
            loadTokens(account, myEpoch)
            loadNotices(account, myEpoch)
            loadSystem(account, myEpoch)
        }
    }

    fun requestDeleteCert(item: JsonObject) { pendingCertDelete = item }
    fun cancelDeleteCert() { pendingCertDelete = null }

    fun requestDeleteToken() { pendingTokenDelete = true }
    fun cancelDeleteToken() { pendingTokenDelete = false }

    fun loadCerts(account: AccountProfile, my: Int = epoch) {
        viewModelScope.launch {
            loadInto("cert", my, { certs = it }) {
                list(ApiClient.apiFor(account).certs(mapOf("page" to "1", "page_size" to "100")))
            }
        }
    }

    fun loadUsers(account: AccountProfile, my: Int = epoch) {
        viewModelScope.launch {
            loadInto("users", my, { users = it }) {
                list(ApiClient.apiFor(account).users(mapOf("page" to "1", "page_size" to "100")))
            }
        }
    }

    fun loadTokens(account: AccountProfile, my: Int = epoch) {
        viewModelScope.launch {
            loadInto("token", my, { tokens = it }) {
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

    fun loadNotices(account: AccountProfile, my: Int = epoch) {
        viewModelScope.launch {
            // 告警通知属商业版能力，社区版返回 403 license required，会被自动隐藏
            loadInto("notice", my, { notices = it }) {
                val raw = Resp.obj(ApiClient.apiFor(account).alarm())
                val arr = Resp.firstArray(raw)
                if (arr != null) arr.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
                else listOf(raw)
            }
        }
    }

    fun loadSystem(account: AccountProfile, my: Int = epoch) {
        viewModelScope.launch {
            loadInto("system", my, { system = it }) {
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
        my: Int,
        assign: (UiState<T>) -> Unit,
        block: suspend () -> T
    ) {
        assign(UiState.Loading)
        try {
            val data = block()
            if (my != epoch) return
            assign(UiState.Success(data))
        } catch (e: Exception) {
            if (my != epoch) return
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
            working = true
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
            working = false
        }
    }

    fun confirmDeleteCert(account: AccountProfile) {
        val target = pendingCertDelete ?: return
        val id = Resp.l(target, "id")
        if (id <= 0L) {
            pendingCertDelete = null
            message = "该证书缺少 id，无法删除"
            return
        }
        viewModelScope.launch {
            working = true
            message = try {
                ApiClient.apiFor(account).deleteCert(id)
                loadCerts(account)
                "证书已删除"
            } catch (e: Exception) {
                e.readable()
            }
            pendingCertDelete = null
            working = false
        }
    }

    /**
     * 删除当前 API Token（9.4.x 的 DELETE /api/open/auth/token 不带路径参数）。
     * 注意这删的就是本客户端正在用的那个 Token，删完立即自锁，
     * 所以 UI 层必须先做二次确认并明确提示后果。
     */
    fun confirmDeleteToken(account: AccountProfile) {
        viewModelScope.launch {
            working = true
            message = try {
                ApiClient.apiFor(account).deleteToken()
                loadTokens(account)
                "Token 已删除，本客户端已失去访问权限，请重新生成"
            } catch (e: Exception) {
                e.readable()
            }
            pendingTokenDelete = false
            working = false
        }
    }
}

@Composable
fun SystemScreen(account: AccountProfile, appVm: AppViewModel, updateVm: UpdateVm? = null) {
    val vm: SystemVm = viewModel()
    DisposableEffect(account.id) {
        vm.loadAll(account)
        onDispose { }
    }

    // 操作结果提示必须挂在 snackbarHost：写成 Scaffold 的兄弟节点会被其不透明背景盖住，
    // 之前导入/删除证书失败的原因用户完全看不到
    val msgHost = rememberMsgHost()
    LaunchedEffect(vm.message) {
        val m = vm.message ?: return@LaunchedEffect
        msgHost.show(m, long = true)
        delay(3200)
        vm.ack()
    }

    val tabs = vm.visibleTabs()
    if (tabs.isEmpty()) {
        EmptyBox("当前控制台版本未提供系统管理接口")
        return
    }
    // 组合期间直接写state 会触发本帧重组重跑，改用副作用一次收口
    val safeTab = vm.tab.coerceIn(0, tabs.lastIndex)
    LaunchedEffect(safeTab, tabs.size) { if (vm.tab != safeTab) vm.tab = safeTab }

    Scaffold(
        // 外层 MainScaffold 已处理系统栏，这里清零避免重复叠加导致顶部留白
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { msgHost.Host() },
        floatingActionButton = {
            if (vm.currentKey() == "cert") {
                FloatingActionButton(onClick = { vm.openCertEditor() }) {
                    Icon(Icons.Default.Add, contentDescription = "导入证书")
                }
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(selectedTabIndex = safeTab) {
                tabs.forEachIndexed { i, t ->
                    Tab(
                        selected = safeTab == i,
                        onClick = { vm.tab = i },
                        text = { Text(t.label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }
            when (vm.currentKey()) {
                "cert" -> ListArea(
                    vm.certs, "暂无证书，可点右下角导入",
                    { vm.loadCerts(account) },
                    onDelete = { o -> vm.requestDeleteCert(o) }
                )
                "users" -> ListArea(vm.users, "未获取到用户（需管理员权限）", { vm.loadUsers(account) })
                "token" -> ListArea(
                    vm.tokens, "暂无 API Token 信息",
                    { vm.loadTokens(account) },
                    onDelete = { vm.requestDeleteToken() }
                )
                "notice" -> ListArea(vm.notices, "暂无通知策略", { vm.loadNotices(account) })
                "system" -> AboutScreen(account = account, appVm = appVm, updateVm = updateVm)
            }
        }
    }

    // 删除证书：证书被站点引用，删掉会导致该站点 HTTPS 直接失效，必须二次确认
    vm.pendingCertDelete?.let { o ->
        val name = Resp.str(o, "comment", "name", "domain", "subject")
        AlertDialog(
            onDismissRequest = { vm.cancelDeleteCert() },
            title = { Text("删除证书") },
            text = {
                Text(
                    "确定删除证书「${name.ifBlank { Resp.l(o, "id").toString() }}」？\n" +
                        "若该证书正被防护站点引用，删除后站点的 HTTPS 将立即失效。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.confirmDeleteCert(account) },
                    enabled = !vm.working
                ) { Text("删除", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { vm.cancelDeleteCert() }) { Text("取消") } }
        )
    }

    // 删除 Token：删掉的是本客户端正在用的那个，点了就自锁，必须把后果说清楚
    if (vm.pendingTokenDelete) {
        AlertDialog(
            onDismissRequest = { vm.cancelDeleteToken() },
            title = { Text("删除 API Token") },
            text = {
                Text(
                    "确定删除当前 API Token？\n\n" +
                        "这删的就是本客户端正在使用的凭据，删除后本客户端的所有请求都会失败、" +
                        "需要重新生成 Token 并在「实例管理」里更新配置才能继续使用。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.confirmDeleteToken(account) },
                    enabled = !vm.working
                ) { Text("仍然删除", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { vm.cancelDeleteToken() }) { Text("取消") } }
        )
    }

    if (vm.certEditorOpen) {
        CertDialog(
            working = vm.working,
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
                // key带下标：服务端缺 id 时全为 0，纯 id 会抛「Key was already used」
                itemsIndexed(state.data, key = { i, o -> respKey(o, i) }) { _, o ->
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

/**
 * LazyColumn 的稳定 key。
 * 服务端可能不返回 id（Resp.l 缺失时为 0），纯 id 会让多条记录撞成同一个 key，
 * LazyColumn 直接抛 IllegalArgumentException 整页崩溃，因此附加下标兜底。
 */
private fun respKey(o: JsonObject, index: Int): String = "${Resp.l(o, "id")}_$index"

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
    // 证书有效期：官方 OpenAPI 里 cert 的这些字段声明为 string，
    // 早期版本用 Resp.l 只认数字，导致有效期整段被静默丢弃。这里统一用 TimeFmt，
    // 数字与字符串两种形态都能解析；实在解析不出来才回退到原始文本。
    val expiryRaw = Resp.str(o, "not_after", "expire", "expire_time", "expire_at", "expires_at")
    val expiryMs = TimeFmt.millisOf(
        o,
        "deadline", "not_after", "expire_at", "expires_at", "valid_until", "expire_time"
    )
    when {
        expiryMs > 0L -> parts.add("有效期至 ${TimeFmt.date(expiryMs)}")
        expiryRaw.isNotBlank() -> parts.add("有效期至 $expiryRaw")
    }
    Resp.strList(o, "dns_names", "sans", "domains").takeIf { it.isNotEmpty() }
        ?.let { parts.add(it.joinToString("、")) }
    Resp.str(o, "remark", "note").takeIf { it.isNotBlank() }?.let { parts.add(it) }
    // 创建时间可能是字符串（官方 OpenAPI 中 cert 的 updated_at/created_at 均为 string）
    TimeFmt.logTimeOf(o).takeIf { it.isNotBlank() }?.let { parts.add("创建 $it") }
    parts += "ID ${Resp.l(o, "id")}"
    return parts.joinToString(" · ")
}

@Composable
private fun CertDialog(
    working: Boolean,
    onDismiss: () -> Unit,
    onSave: (comment: String, crt: String, key: String) -> Unit
) {
    // 弹窗存在状态在 ViewModel 里，旋转屏幕后弹窗会重建。
    // 用 remember 的话用户粘贴的 PEM 内容会全部消失（动辄上千字符，重新粘贴成本极高），
    // 所以这里必须 rememberSaveable。
    var comment by rememberSaveable { mutableStateOf("") }
    var crt by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf("") }

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
        confirmButton = {
            TextButton(onClick = { onSave(comment, crt, key) }, enabled = !working) {
                if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("导入")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("取消") } }
    )
}

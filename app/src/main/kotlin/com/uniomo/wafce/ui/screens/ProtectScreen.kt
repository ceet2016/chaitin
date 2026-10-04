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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.launch
import com.uniomo.wafce.core.HttpStatusException
import com.uniomo.wafce.core.LicenseRequiredException
import com.uniomo.wafce.core.Resp
import com.uniomo.wafce.core.UiState
import com.uniomo.wafce.core.readable
import com.uniomo.wafce.data.net.ApiClient
import com.uniomo.wafce.data.store.AccountProfile
import com.uniomo.wafce.ui.components.AsyncBox
import com.uniomo.wafce.ui.components.CardShape
import com.uniomo.wafce.ui.components.EmptyBox
import com.uniomo.wafce.ui.components.SectionCard
import com.uniomo.wafce.ui.components.TagChip
import com.uniomo.wafce.ui.theme.Brand
import com.uniomo.wafce.ui.theme.Danger
import com.uniomo.wafce.ui.theme.InfoColor
import com.uniomo.wafce.ui.theme.Ok
import com.uniomo.wafce.ui.theme.Warn

private const val TAB_IPGROUP = "ipgroup"
private const val TAB_POLICY = "policy"
private const val TAB_MODE = "mode"
private const val TAB_ACL = "acl"

data class ProtectTab(val key: String, val label: String)

private val PROTECT_TABS = listOf(
    ProtectTab(TAB_IPGROUP, "IP 组"),
    ProtectTab(TAB_POLICY, "自定义规则"),
    ProtectTab(TAB_MODE, "防护模块"),
    ProtectTab(TAB_ACL, "频率限制")
)

data class IpGroupItem(val id: Long, val comment: String, val ips: List<String>, val raw: JsonObject)
data class PolicyItem(val id: Long, val name: String, val action: String, val raw: JsonObject)

class ProtectVm : ViewModel() {

    var tab by mutableIntStateOf(0)

    var groups: UiState<List<IpGroupItem>> by mutableStateOf(UiState.Idle)
        private set
    var policies: UiState<List<PolicyItem>> by mutableStateOf(UiState.Idle)
        private set
    var modeRaw: UiState<JsonObject> by mutableStateOf(UiState.Idle)
        private set
    var aclRaw: UiState<JsonObject> by mutableStateOf(UiState.Idle)
        private set

    var groupEditor by mutableStateOf<IpGroupItem?>(null)
        private set
    var groupEditorIsNew by mutableStateOf(false)
        private set
    var pendingGroupDelete by mutableStateOf<IpGroupItem?>(null)
        private set
    var jsonEditor by mutableStateOf<Pair<String, String>?>(null) // title -> current json
        private set
    var working by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    /** 该版本控制台不存在的接口（404），对应页签会自动移除 */
    var unsupported: Set<String> by mutableStateOf(emptySet())
        private set

    fun ack() { message = null }

    fun visibleTabs(): List<ProtectTab> = PROTECT_TABS.filter { it.key !in unsupported }

    fun currentKey(): String = visibleTabs().getOrNull(tab)?.key ?: TAB_IPGROUP

    fun loadAll(account: AccountProfile) {
        loadGroups(account)
        loadPolicies(account)
        loadMode(account)
        loadAcl(account)
    }

    /**
     * 统一编排：404 → 登记为「该版本不支持」并隐藏页签；其它异常按普通错误处理。
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
            assign(UiState.Fail(e.readable(), e is com.uniomo.wafce.core.UnauthorizedException))
        }
    }

    /** 404=该版本无此接口；LicenseRequired=商业版能力，社区版均视为不可用 */
    private fun isUnsupported(e: Throwable): Boolean =
        e is LicenseRequiredException ||
            (e is HttpStatusException && e.httpCode == 404)

    fun loadGroups(account: AccountProfile) {
        viewModelScope.launch {
            loadInto(TAB_IPGROUP, { groups = it }) {
                val body = ApiClient.apiFor(account).ipGroups(mapOf("page" to "1", "page_size" to "100"))
                val raw = Resp.obj(body)
                Resp.nodes(raw).ifEmpty { Resp.arr(body) }.map {
                    IpGroupItem(
                        id = Resp.l(it, "id"),
                        comment = Resp.str(it, "comment", "name", "remark"),
                        ips = Resp.strList(it, "ips", "ip"),
                        raw = it
                    )
                }
            }
        }
    }

    fun loadPolicies(account: AccountProfile) {
        viewModelScope.launch {
            loadInto(TAB_POLICY, { policies = it }) {
                val body = ApiClient.apiFor(account).policies(
                    mapOf("page" to "1", "page_size" to "100", "action" to "-1")
                )
                val raw = Resp.obj(body)
                Resp.nodes(raw).ifEmpty { Resp.arr(body) }.map {
                    PolicyItem(
                        id = Resp.l(it, "id"),
                        name = Resp.str(it, "name", "comment", "title").ifBlank { "规则 ${Resp.l(it, "id")}" },
                        action = actionText(Resp.l(it, "action")),
                        raw = it
                    )
                }
            }
        }
    }

    fun loadMode(account: AccountProfile) {
        viewModelScope.launch {
            loadInto(TAB_MODE, { modeRaw = it }) { Resp.obj(ApiClient.apiFor(account).globalMode()) }
        }
    }

    fun loadAcl(account: AccountProfile) {
        viewModelScope.launch {
            loadInto(TAB_ACL, { aclRaw = it }) { Resp.obj(ApiClient.apiFor(account).globalAcl()) }
        }
    }

    // ---------- IP 组 ----------

    fun openNewGroup() { groupEditorIsNew = true; groupEditor = IpGroupItem(0, "", emptyList(), JsonObject()) }
    fun openEditGroup(g: IpGroupItem) { groupEditorIsNew = false; groupEditor = g }
    fun closeGroupEditor() { groupEditor = null }
    fun requestDeleteGroup(g: IpGroupItem) { pendingGroupDelete = g }
    fun cancelDeleteGroup() { pendingGroupDelete = null }

    fun saveGroup(account: AccountProfile, comment: String, ipsRaw: String) {
        val current = groupEditor ?: return
        val isNew = groupEditorIsNew
        viewModelScope.launch {
            working = true
            message = try {
                val api = ApiClient.apiFor(account)
                val ips = ipsRaw.split(",", "，", "\n", " ").map { it.trim() }.filter { it.isNotBlank() }
                val body = JsonObject().apply {
                    addProperty("comment", comment)
                    add("ips", JsonArray().apply { ips.forEach { add(JsonPrimitive(it)) } })
                    addProperty("reference", current.raw.get("reference")?.asString ?: "")
                    if (!isNew) addProperty("id", current.id)
                }
                if (isNew) api.createIpGroup(body) else api.updateIpGroup(body)
                closeGroupEditor()
                loadGroups(account)
                "IP 组已保存"
            } catch (e: Exception) {
                e.readable()
            }
            working = false
        }
    }

    fun confirmDeleteGroup(account: AccountProfile) {
        val target = pendingGroupDelete ?: return
        viewModelScope.launch {
            working = true
            message = try {
                ApiClient.apiFor(account).deleteIpGroup(target.id)
                loadGroups(account)
                "IP 组已删除"
            } catch (e: Exception) {
                e.readable()
            }
            pendingGroupDelete = null
            working = false
        }
    }

    fun deletePolicy(account: AccountProfile, p: PolicyItem) {
        viewModelScope.launch {
            message = try {
                ApiClient.apiFor(account).deletePolicy(p.id)
                loadPolicies(account)
                "规则已删除"
            } catch (e: Exception) {
                e.readable()
            }
        }
    }

    // ---------- 专家 JSON 编辑 ----------

    fun openJsonEditor(title: String, json: String) { jsonEditor = title to json }
    fun closeJsonEditor() { jsonEditor = null }

    fun saveJson(account: AccountProfile, kind: String, text: String) {
        viewModelScope.launch {
            working = true
            message = try {
                val parsed = GsonBuilder().create().fromJson(text, JsonObject::class.java)
                val api = ApiClient.apiFor(account)
                if (kind == "mode") api.updateGlobalMode(parsed) else api.updateGlobalAcl(parsed)
                loadMode(account); loadAcl(account)
                closeJsonEditor()
                "配置已下发"
            } catch (e: Exception) {
                "配置更新失败：${e.readable()}"
            }
            working = false
        }
    }

    private fun actionText(code: Long): String = when (code) {
        0L -> "放行"
        1L -> "拦截"
        2L -> "人机验证"
        3L -> "重定向"
        -1L -> "全部"
        else -> "未知($code)"
    }
}

@Composable
fun ProtectScreen(account: AccountProfile) {
    val vm: ProtectVm = viewModel()
    DisposableEffect(account.id) {
        vm.loadAll(account)
        onDispose { }
    }

    vm.message?.let { msg ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(msg, style = MaterialTheme.typography.bodySmall, color = Brand)
        }
        androidx.compose.runtime.LaunchedEffect(msg) {
            kotlinx.coroutines.delay(2600)
            vm.ack()
        }
    }

    Scaffold(
        // 外层 MainScaffold 已处理系统栏，这里清零避免重复叠加导致顶部留白
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            if (vm.currentKey() == TAB_IPGROUP) {
                FloatingActionButton(onClick = { vm.openNewGroup() }) {
                    Icon(Icons.Default.Add, contentDescription = "新增 IP 组")
                }
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            val tabs = vm.visibleTabs()
            if (tabs.isEmpty()) {
                EmptyBox("当前控制台版本未提供防护配置接口")
                return@Scaffold
            }
            if (vm.tab >= tabs.size) vm.tab = 0
            TabRow(selectedTabIndex = vm.tab.coerceIn(0, tabs.lastIndex)) {
                tabs.forEachIndexed { i, t ->
                    Tab(
                        selected = vm.tab == i,
                        onClick = { vm.tab = i },
                        text = { Text(t.label, style = MaterialTheme.typography.labelLarge) }
                    )
                }
            }
            when (vm.currentKey()) {
                TAB_IPGROUP -> GroupsTab(vm, account)
                TAB_POLICY -> PoliciesTab(vm, account)
                TAB_MODE -> RawConfigTab(
                    state = vm.modeRaw,
                    hint = "防护模块开关：语义分析、CC、人机验证、动态防护等模块的启用状态。修改后请谨慎下发。",
                    onReload = { vm.loadMode(account) },
                    onEdit = { json -> vm.openJsonEditor("防护模块", json) }
                )
                TAB_ACL -> RawConfigTab(
                    state = vm.aclRaw,
                    hint = "频率限制（CC 防护）全局配置。编辑前建议先复制原始内容备份。",
                    onReload = { vm.loadAcl(account) },
                    onEdit = { json -> vm.openJsonEditor("频率限制", json) }
                )
            }
        }
    }

    vm.groupEditor?.let { g ->
        IpGroupDialog(
            item = g,
            isNew = vm.groupEditorIsNew,
            working = vm.working,
            onDismiss = { vm.closeGroupEditor() },
            onSave = { c, i -> vm.saveGroup(account, c, i) }
        )
    }

    vm.pendingGroupDelete?.let { g ->
        AlertDialog(
            onDismissRequest = { vm.cancelDeleteGroup() },
            title = { Text("删除 IP 组") },
            text = { Text("确定删除「${g.comment.ifBlank { "IP 组 ${g.id}" }}」？引用该组的规则可能失效。") },
            confirmButton = {
                TextButton(onClick = { vm.confirmDeleteGroup(account) }) { Text("删除", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { vm.cancelDeleteGroup() }) { Text("取消") } }
        )
    }

    vm.jsonEditor?.let { (title, json) ->
        JsonEditorDialog(
            title = title,
            initial = json,
            working = vm.working,
            onDismiss = { vm.closeJsonEditor() },
            onSave = { vm.saveJson(account, if (title.contains("防护")) "mode" else "acl", it) }
        )
    }
}

@Composable
private fun GroupsTab(vm: ProtectVm, account: AccountProfile) {
    AsyncBox(vm.groups, { vm.loadGroups(account) }) { list ->
        if (list.isEmpty()) EmptyBox("暂无 IP 组，点击右下角新增")
        else LazyColumn(Modifier.fillMaxSize()) {
            item { Spacer(Modifier.height(6.dp)) }
            items(list, key = { it.id }) { g ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = CardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                g.comment.ifBlank { "IP 组 ${g.id}" },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            TagChip(text = "${g.ips.size} 条", color = InfoColor)
                        }
                        if (g.ips.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                g.ips.take(6).joinToString("、") + if (g.ips.size > 6) " …" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            IconButton(onClick = { vm.openEditGroup(g) }) {
                                Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { vm.requestDeleteGroup(g) }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除", tint = Danger, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun PoliciesTab(vm: ProtectVm, account: AccountProfile) {
    AsyncBox(vm.policies, { vm.loadPolicies(account) }) { list ->
        if (list.isEmpty()) EmptyBox("暂无自定义规则")
        else LazyColumn(Modifier.fillMaxSize()) {
            item { Spacer(Modifier.height(6.dp)) }
            items(list, key = { it.id }) { p ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = CardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Row {
                                TagChip(text = "ID ${p.id}", color = Brand)
                                Spacer(Modifier.size(6.dp))
                                TagChip(
                                    text = p.action,
                                    color = when (p.action) {
                                        "拦截" -> Danger
                                        "放行" -> Ok
                                        "人机验证" -> Warn
                                        else -> InfoColor
                                    }
                                )
                            }
                        }
                        IconButton(onClick = { vm.deletePolicy(account, p) }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除", tint = Danger, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun RawConfigTab(
    state: UiState<JsonObject>,
    hint: String,
    onReload: () -> Unit,
    onEdit: (String) -> Unit
) {
    AsyncBox(state, onReload) { raw ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            SectionCard("当前配置", subtitle = hint, actions = {
                TextButton(onClick = { onEdit(GsonBuilder().setPrettyPrinting().create().toJson(raw)) }) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("编辑")
                }
            }) {
                val pretty = remember(raw) { GsonBuilder().setPrettyPrinting().create().toJson(raw) }
                Text(
                    pretty,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun IpGroupDialog(
    item: IpGroupItem,
    isNew: Boolean,
    working: Boolean,
    onDismiss: () -> Unit,
    onSave: (comment: String, ips: String) -> Unit
) {
    var comment by remember { mutableStateOf(item.comment) }
    var ips by remember { mutableStateOf(item.ips.joinToString("\n")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新增 IP 组" else "编辑 IP 组") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = comment, onValueChange = { comment = it },
                    label = { Text("组名称/备注") }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = ips, onValueChange = { ips = it },
                    label = { Text("IP 列表") },
                    placeholder = { Text("每行一个，支持 CIDR，如 1.2.3.4/32") },
                    minLines = 5, maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(comment, ips) }, enabled = !working) {
                if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun JsonEditorDialog(
    title: String,
    initial: String,
    working: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑：$title") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 10,
                maxLines = 16,
                modifier = Modifier.fillMaxWidth().height(320.dp)
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = !working) {
                if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                Text("下发")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

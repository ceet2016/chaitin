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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import com.uniomo.wafce.ui.components.rememberMsgHost
import com.uniomo.wafce.ui.theme.Brand
import com.uniomo.wafce.ui.theme.Danger
import com.uniomo.wafce.ui.theme.InfoColor
import com.uniomo.wafce.ui.theme.Ok
import com.uniomo.wafce.ui.theme.Warn

private const val TAB_IPGROUP = "ipgroup"
private const val TAB_RULE = "rule"
private const val TAB_POLICY = "policy"
private const val TAB_MODE = "mode"
private const val TAB_ACL = "acl"

data class ProtectTab(val key: String, val label: String)

private val PROTECT_TABS = listOf(
    ProtectTab(TAB_IPGROUP, "IP 组"),
    ProtectTab(TAB_RULE, "黑白名单"),
    ProtectTab(TAB_POLICY, "自定义规则"),
    ProtectTab(TAB_MODE, "防护模块"),
    ProtectTab(TAB_ACL, "频率限制")
)

/**
 * IP 组条目。
 *
 * [ips] 取自「用户原始输入」的 original 字段而非服务端展开的 cidrs：
 * 展开后的 cidrs 会把 1.1.1.1-1.1.1.4 拆成 4 条，条数比用户填的多，看着像虚高。
 * [count] 是原始条数，[expandedCount] 是展开后的 CIDR 数，两者都展示最清晰。
 */
data class IpGroupItem(
    val id: Long,
    val comment: String,
    val ips: List<String>,
    val expandedCount: Int,
    val isSystem: Boolean,
    val raw: JsonObject
)

data class PolicyItem(val id: Long, val name: String, val action: String, val raw: JsonObject)

/**
 * 专家 JSON 编辑器对应的目标接口。
 *
 * 早期版本靠弹窗标题字符串 `contains("防护")` 判断该下发到哪个接口，
 * 文案一改就会把全局防护配置写到错误的接口。用枚举替代这种猜测。
 */
enum class RawKind(val title: String) {
    MODE("防护模块"),
    ACL("频率限制")
}

data class JsonEdit(val kind: RawKind, val json: String)

/** 黑白名单规则：action 0=白名单（放行）1=黑名单（拦截） */
data class RuleItem(
    val id: Long,
    val name: String,
    val isWhite: Boolean,
    val targetCount: Int,
    val raw: JsonObject
)

class ProtectVm : ViewModel() {

    var tab by mutableIntStateOf(0)

    /**
     * 请求代次自增标记。
     *
     * viewModel() 的作用域是 NavBackStackEntry，切换账号时本实例会被复用，
     * 若不加校验，旧账号慢返回的响应会覆盖新账号的界面（甚至让人误删旧账号的资源）。
     * 每次 loadAll 把 epoch 推进一代，请求回来时若代次已变就丢弃结果。
     */
    private var epoch = 0
    private var loadJob: Job? = null

    var groups: UiState<List<IpGroupItem>> by mutableStateOf(UiState.Idle)
        private set
    var rules: UiState<List<RuleItem>> by mutableStateOf(UiState.Idle)
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
    var pendingRuleDelete by mutableStateOf<RuleItem?>(null)
        private set
    var pendingPolicyDelete by mutableStateOf<PolicyItem?>(null)
        private set
    var jsonEditor by mutableStateOf<JsonEdit?>(null) // 专家 JSON 编辑器
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
        // 推进代次，让上一批尚未返回的请求结果全部作废（账号已切换）。
        // epoch 必须在协程外推进：loadAll 之后紧跟的单个 load*() 要能读到新值。
        //
        // 注意 loadJob?.cancel() 只能取消下面这个「顺序派发 5 次」的编排协程，
        // 它在派发完就结束了；5 个 load* 各自 viewModelScope.launch，
        // 父协程是 viewModelScope 而不是 loadJob，取消对其无效。
        // 因此真正防串号的是 epoch 校验，不是这里的 cancel —— 别误以为已有取消保护。
        val myEpoch = ++epoch
        loadJob = viewModelScope.launch {
            loadGroups(account, myEpoch)
            loadRules(account, myEpoch)
            loadPolicies(account, myEpoch)
            loadMode(account, myEpoch)
            loadAcl(account, myEpoch)
        }
    }

    /**
     * 统一编排：404 → 登记为「该版本不支持」并隐藏页签；其它异常按普通错误处理。
     *
     * @param my 本次请求的代次；与当前 epoch 不一致说明账号已切换，结果直接丢弃。
     */
    private suspend fun <T> loadInto(
        key: String,
        my: Int,
        assign: (UiState<T>) -> Unit,
        block: suspend () -> T
    ) {
        // 代次校验必须在写 Loading **之前**：
        // 旧代次的请求结果虽然会被丢弃，但它若先把该页签打成「加载中」，
        // 而新代次随后失败写了 Fail，就会出现「Fail 被后到的 Loading 覆盖 → 永久转圈」。
        if (my != epoch) return
        assign(UiState.Loading)
        try {
            val data = block()
            if (my != epoch) return
            assign(UiState.Success(data))
        } catch (e: Exception) {
            // 协程取消属于正常终止，不能当失败处理
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (my != epoch) return
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

    fun loadGroups(account: AccountProfile, my: Int = epoch) {
        viewModelScope.launch {
            loadInto(TAB_IPGROUP, my, { groups = it }) {
                val body = ApiClient.apiFor(account).ipGroups(mapOf("page" to "1", "page_size" to "100"))
                Resp.listOf(body).map {
                    val original = Resp.strList(it, "original", "ips", "ip")
                    val cidrs = Resp.strList(it, "cidrs", "expanded")
                    IpGroupItem(
                        id = Resp.l(it, "id"),
                        comment = Resp.str(it, "comment", "name", "remark"),
                        // original 优先：它是用户填的原始写法；缺失时退回展开后的 cidrs
                        ips = original.ifEmpty { cidrs },
                        expandedCount = if (cidrs.isNotEmpty()) cidrs.size else original.size,
                        isSystem = Resp.b(it, "is_system", "system", "builtin") ||
                            Resp.str(it, "type", "source").contains("system", true),
                        raw = it
                    )
                }
            }
        }
    }

    /** 黑白名单规则：action=0 视为白名单（放行），其余视为黑名单 */
    fun loadRules(account: AccountProfile, my: Int = epoch) {
        viewModelScope.launch {
            loadInto(TAB_RULE, my, { rules = it }) {
                val body = ApiClient.apiFor(account).rules(mapOf("page" to "1", "page_size" to "100"))
                Resp.listOf(body).map { o ->
                    val action = Resp.l(o, "action")
                    RuleItem(
                        id = Resp.l(o, "id"),
                        name = Resp.str(o, "name", "comment", "title")
                            .ifBlank { "规则 ${Resp.l(o, "id")}" },
                        isWhite = action == 0L,
                        targetCount = countTargets(o),
                        raw = o
                    )
                }
            }
        }
    }

    /** 规则命中的目标数（IP / URL / Header 匹配项），用于列表副标题 */
    private fun countTargets(o: JsonObject): Int {
        val direct = Resp.strList(o, "targets", "target", "values", "value", "ips", "ip", "url", "urls")
        if (direct.isNotEmpty()) return direct.size
        val cond = o.getAsJsonObject("cond") ?: o.getAsJsonObject("condition") ?: return 0
        var n = 0
        for ((k, v) in cond.entrySet()) {
            if (!v.isJsonArray) continue
            val size = v.asJsonArray.size()
            if (size > 0) n = maxOf(n, size)
        }
        return n
    }

    fun loadPolicies(account: AccountProfile, my: Int = epoch) {
        viewModelScope.launch {
            loadInto(TAB_POLICY, my, { policies = it }) {
                val body = ApiClient.apiFor(account).policies(
                    mapOf("page" to "1", "page_size" to "100", "action" to "-1")
                )
                Resp.listOf(body).map {
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

    fun loadMode(account: AccountProfile, my: Int = epoch) {
        viewModelScope.launch {
            loadInto(TAB_MODE, my, { modeRaw = it }) { Resp.obj(ApiClient.apiFor(account).globalMode()) }
        }
    }

    fun loadAcl(account: AccountProfile, my: Int = epoch) {
        viewModelScope.launch {
            loadInto(TAB_ACL, my, { aclRaw = it }) { Resp.obj(ApiClient.apiFor(account).globalAcl()) }
        }
    }

    // ---------- IP 组 ----------

    fun openNewGroup() {
        groupEditorIsNew = true
        groupEditor = IpGroupItem(0, "", emptyList(), 0, false, JsonObject())
    }

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

    fun requestDeletePolicy(p: PolicyItem) { pendingPolicyDelete = p }
    fun cancelDeletePolicy() { pendingPolicyDelete = null }

    fun confirmDeletePolicy(account: AccountProfile) {
        val target = pendingPolicyDelete ?: return
        viewModelScope.launch {
            working = true
            message = try {
                ApiClient.apiFor(account).deletePolicy(target.id)
                loadPolicies(account)
                "自定义规则已删除"
            } catch (e: Exception) {
                e.readable()
            }
            pendingPolicyDelete = null
            working = false
        }
    }

    fun requestDeleteRule(r: RuleItem) { pendingRuleDelete = r }
    fun cancelDeleteRule() { pendingRuleDelete = null }

    fun confirmDeleteRule(account: AccountProfile) {
        val target = pendingRuleDelete ?: return
        viewModelScope.launch {
            working = true
            message = try {
                ApiClient.apiFor(account).deleteRule(target.id)
                loadRules(account)
                "黑白名单规则已删除"
            } catch (e: Exception) {
                e.readable()
            }
            pendingRuleDelete = null
            working = false
        }
    }

    // ---------- 专家 JSON 编辑 ----------

    fun openJsonEditor(kind: RawKind, json: String) { jsonEditor = JsonEdit(kind, json) }
    fun closeJsonEditor() { jsonEditor = null }

    /**
     * 待确认下发的全局配置。
     * 专家模式的裸 JSON 编辑器语法检查只有「Gson 能否解析」，
     * 用户手写一个合法的 `{}` 就会把WAF 的全局防护模式 / 全局访问控制直接清空，
 * * 破坏性等同于「删除全部防护配置」，必须二次确认。
     */
    var pendingJsonSave by mutableStateOf<Pair<RawKind, String>?>(null)
        private set

    fun requestSaveJson(kind: RawKind, text: String) {
        // 先本地解析一次：语法错误直接在这里拦下，不弹确认框
        val parsed = runCatching { GsonBuilder().create().fromJson(text, JsonObject::class.java) }
            .getOrNull()
        if (parsed == null) {
            message = "JSON 语法有误，请检查后再下发"
            return
        }
        pendingJsonSave = kind to text
    }

    fun cancelSaveJson() { pendingJsonSave = null }

    fun saveJson(account: AccountProfile, kind: RawKind, text: String) {
        viewModelScope.launch {
            working = true
            message = try {
                val parsed = GsonBuilder().create().fromJson(text, JsonObject::class.java)
                val api = ApiClient.apiFor(account)
                when (kind) {
                    RawKind.MODE -> api.updateGlobalMode(parsed)
                    RawKind.ACL -> api.updateGlobalAcl(parsed)
                }
                loadMode(account); loadAcl(account)
                closeJsonEditor()
                "配置已下发"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
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
fun ProtectScreen(account: AccountProfile, initialTabKey: String? = null) {
    val vm: ProtectVm = viewModel()
    DisposableEffect(account.id) {
        vm.loadAll(account)
        onDispose { }
    }

    // 操作结果提示：必须挂在 snackbarHost 上。
    // 早期版本把它写成 Scaffold 的兄弟节点，被 Scaffold 的不透明背景完全盖住，
    // 导致所有写操作的失败原因用户一条都看不到。
    val msgHost = rememberMsgHost()
    LaunchedEffect(vm.message) {
        val m = vm.message ?: return@LaunchedEffect
        msgHost.show(m, long = true)
        delay(2600)
        vm.ack()
    }

    // 从首页指标卡跳进来时直接落到目标页签。
    // 必须等 unsupported 判定完成后再定位，否则页签被隐藏会导致索引错位；
    // 但只定位一次，否则某个请求晚到的 404 会把用户已选好的页签强行拉回。
    var tabLocated by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialTabKey, vm.unsupported) {
        val key = initialTabKey ?: return@LaunchedEffect
        if (tabLocated) return@LaunchedEffect
        val idx = vm.visibleTabs().indexOfFirst { it.key == key }
        if (idx >= 0) {
            vm.tab = idx
            tabLocated = true
        }
    }

    Scaffold(
        // 外层 MainScaffold 已处理系统栏，这里清零避免重复叠加导致顶部留白
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { msgHost.Host() },
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
            // 组合期间直接写 state 会触发本帧重组重跑，改用副作用一次收口
            val safeTab = vm.tab.coerceIn(0, tabs.lastIndex)
            LaunchedEffect(safeTab, tabs.size) { if (vm.tab != safeTab) vm.tab = safeTab }
            TabRow(selectedTabIndex = safeTab) {
                tabs.forEachIndexed { i, t ->
                    Tab(
                        selected = safeTab == i,
                        onClick = { vm.tab = i },
                        text = { Text(t.label, style = MaterialTheme.typography.labelLarge) }
                    )
                }
            }
            when (vm.currentKey()) {
                TAB_IPGROUP -> GroupsTab(vm, account)
                TAB_RULE -> RulesTab(vm, account)
                TAB_POLICY -> PoliciesTab(vm, account)
                TAB_MODE -> RawConfigTab(
                    state = vm.modeRaw,
                    hint = "防护模块开关：语义分析、CC、人机验证、动态防护等模块的启用状态。修改后请谨慎下发。",
                    onReload = { vm.loadMode(account) },
                    onEdit = { json -> vm.openJsonEditor(RawKind.MODE, json) }
                )
                TAB_ACL -> RawConfigTab(
                    state = vm.aclRaw,
                    hint = "频率限制（CC 防护）全局配置。编辑前建议先复制原始内容备份。",
                    onReload = { vm.loadAcl(account) },
                    onEdit = { json -> vm.openJsonEditor(RawKind.ACL, json) }
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
                TextButton(
                    onClick = { vm.confirmDeleteGroup(account) },
                    enabled = !vm.working
                ) { Text("删除", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { vm.cancelDeleteGroup() }) { Text("取消") } }
        )
    }

    vm.pendingRuleDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { vm.cancelDeleteRule() },
            title = { Text("删除${if (r.isWhite) "白" else "黑"}名单规则") },
            text = { Text("确定删除「${r.name}」？删除后该规则立即失效。") },
            confirmButton = {
                TextButton(
                    onClick = { vm.confirmDeleteRule(account) },
                    enabled = !vm.working
                ) { Text("删除", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { vm.cancelDeleteRule() }) { Text("取消") } }
        )
    }

    vm.pendingPolicyDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { vm.cancelDeletePolicy() },
            title = { Text("删除自定义规则") },
            text = {
                Text(
                    "确定删除「${p.name.ifBlank { "规则 ${p.id}" }}」？\n" +
                        "该策略（${p.action}）删除后立即失效，且不可恢复。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.confirmDeletePolicy(account) },
                    enabled = !vm.working
                ) { Text("删除", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { vm.cancelDeletePolicy() }) { Text("取消") } }
        )
    }

    vm.jsonEditor?.let { edit ->
        JsonEditorDialog(
            title = edit.kind.title,
            initial = edit.json,
            working = vm.working,
            onDismiss = { vm.closeJsonEditor() },
            onSave = { vm.requestSaveJson(edit.kind, it) }
        )
    }

    // 全局配置下发的二次确认：一次错误的全局配置会影响所有站点的防护策略
    vm.pendingJsonSave?.let { (kind, text) ->
        val keyCount = runCatching {
            GsonBuilder().create().fromJson(text, JsonObject::class.java).size()
        }.getOrDefault(0)
        AlertDialog(
            onDismissRequest = { vm.cancelSaveJson() },
            title = { Text("下发${kind.title}") },
            text = {
                // 用 AnnotatedString 做局部加粗：Compose 的Text 不解析 Markdown，
                // 直接写 ** 会原样显示星号
                val warn = "全部防护站点"
                Text(
                    buildAnnotatedString {
                        append("即将用你编辑的内容覆盖当前${kind.title}配置（$keyCount 个顶层字段）。\n")
                        append("该配置作用于")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(warn) }
                        append("，下发后立即生效且不可撤销。\n\n确认继续？")
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.cancelSaveJson()
                        vm.saveJson(account, kind, text)
                    },
                    enabled = !vm.working
                ) { Text("确认下发", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { vm.cancelSaveJson() }) { Text("取消") }
            }
        )
    }
}

/**
 * IP 组列表。
 *
 * 顶部固定一条统计：组数、原始 IP 条数、展开 CIDR 数、系统内置组数。
 * 之前每张卡只显示「N 条」且 N 恒为 0（读的是不存在的 ips 字段），
 * 导致用户无法判断 IP 组到底有没有内容。
 */
@Composable
private fun GroupsTab(vm: ProtectVm, account: AccountProfile) {
    AsyncBox(vm.groups, { vm.loadGroups(account) }) { list ->
        val rawTotal = list.sumOf { it.ips.size }
        val expandedTotal = list.sumOf { it.expandedCount }
        val systemCount = list.count { it.isSystem }

        Column(Modifier.fillMaxSize()) {
            GroupSummaryBar(
                groupCount = list.size,
                rawCount = rawTotal,
                expandedCount = expandedTotal,
                systemCount = systemCount
            )
            if (list.isEmpty()) {
                EmptyBox("暂无 IP 组，点击右下角新增")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    item { Spacer(Modifier.height(2.dp)) }
                    itemsIndexed(list, key = { i, it -> "${it.id}_$i" }) { _, g ->
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
                                    if (g.isSystem) {
                                        TagChip(text = "系统内置", color = InfoColor)
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    TagChip(
                                        text = "${g.ips.size} 条",
                                        color = if (g.ips.isEmpty()) Warn else Ok
                                    )
                                }
                                if (g.ips.isNotEmpty()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        g.ips.take(6).joinToString("、") +
                                            if (g.ips.size > 6) " …（共 ${g.ips.size} 条）" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (g.expandedCount > g.ips.size) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "展开为 ${g.expandedCount} 个 CIDR（IP 段写法会被服务端拆分）",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = InfoColor
                                        )
                                    }
                                } else {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "该组暂无 IP 条目",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Warn
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    IconButton(onClick = { vm.openEditGroup(g) }) {
                                        Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(onClick = { vm.requestDeleteGroup(g) }) {
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
}

/** IP 组汇总条：四个数字平铺，避免每张卡都写「0 条」造成误读 */
@Composable
private fun GroupSummaryBar(groupCount: Int, rawCount: Int, expandedCount: Int, systemCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SummaryCell("IP 组", groupCount.toString(), Brand, Modifier.weight(1f))
            SummaryCell("IP 条目", rawCount.toString(), Ok, Modifier.weight(1f))
            SummaryCell("展开 CIDR", expandedCount.toString(), InfoColor, Modifier.weight(1f))
            SummaryCell("系统内置", systemCount.toString(), Warn, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SummaryCell(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/**
 * 黑白名单规则列表。
 *
 * action=0 为白名单（放行），action=1 为黑名单（拦截）。
 * 顶部同样给一条统计，让用户一眼看到白/黑各多少条。
 */
@Composable
private fun RulesTab(vm: ProtectVm, account: AccountProfile) {
    AsyncBox(vm.rules, { vm.loadRules(account) }) { list ->
        val white = list.count { it.isWhite }
        val black = list.size - white

        Column(Modifier.fillMaxSize()) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = CardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    SummaryCell("白名单", white.toString(), Ok, Modifier.weight(1f))
                    SummaryCell("黑名单", black.toString(), Danger, Modifier.weight(1f))
                    SummaryCell("规则总数", list.size.toString(), Brand, Modifier.weight(1f))
                }
            }

            if (list.isEmpty()) {
                EmptyBox("暂无黑白名单规则")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    item { Spacer(Modifier.height(2.dp)) }
                    itemsIndexed(list, key = { i, it -> "${it.id}_$i" }) { _, r ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            shape = CardShape,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        r.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Row {
                                        TagChip(
                                            text = if (r.isWhite) "白名单" else "黑名单",
                                            color = if (r.isWhite) Ok else Danger
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        TagChip(text = "ID ${r.id}", color = Brand)
                                        if (r.targetCount > 0) {
                                            Spacer(Modifier.width(6.dp))
                                            TagChip(text = "${r.targetCount} 个匹配项", color = InfoColor)
                                        }
                                    }
                                }
                                IconButton(onClick = { vm.requestDeleteRule(r) }) {
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
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
private fun PoliciesTab(vm: ProtectVm, account: AccountProfile) {
    AsyncBox(vm.policies, { vm.loadPolicies(account) }) { list ->
        // 统计卡放在 LazyColumn 外面：与GroupsTab / RulesTab 保持一致，
        // 空列表时也保留汇总信息（否则「0 条规则」和「无数据」看起来一样）
        Column(Modifier.fillMaxSize()) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = CardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    SummaryCell("规则总数", list.size.toString(), Brand, Modifier.weight(1f))
                    SummaryCell("拦截", list.count { it.action == "拦截" }.toString(), Danger, Modifier.weight(1f))
                    SummaryCell("放行", list.count { it.action == "放行" }.toString(), Ok, Modifier.weight(1f))
                    SummaryCell("人机验证", list.count { it.action == "人机验证" }.toString(), Warn, Modifier.weight(1f))
                }
            }
            if (list.isEmpty()) {
                EmptyBox("暂无自定义规则")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(list, key = { i, it -> "${it.id}_$i" }) { _, p ->
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
                                IconButton(onClick = { vm.requestDeletePolicy(p) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = Danger, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
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
        // Resp.obj 在 data 为 null 时返回空 JsonObject，此时不渲染「{}」卡片，
        // 否则用户看到的是一份看起来有效、实则什么都没返回的配置
        if (raw.size() == 0) {
            EmptyBox("服务端未返回该配置内容")
            return@AsyncBox
        }
        // pretty 只生成一次，避免「看到的」与「下发的」序列化结果可能不同
        val pretty = remember(raw) { GsonBuilder().setPrettyPrinting().create().toJson(raw) }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            SectionCard("当前配置", subtitle = hint, actions = {
                TextButton(onClick = { onEdit(pretty) }) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("编辑")
                }
            }) {
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
    // rememberSaveable：弹窗的存在状态在 ViewModel 里，旋转屏幕后弹窗会重建，
    // 用 remember 的话用户刚输入的 IP 列表和备注会全部清空
    var comment by rememberSaveable(item.id) { mutableStateOf(item.comment) }
    var ips by rememberSaveable(item.id) { mutableStateOf(item.ips.joinToString("\n")) }

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
    // rememberSaveable：弹窗存在状态在 ViewModel 里，旋转后弹窗会重建，
    // 用 remember 会让用户粘贴的整段 JSON 消失
    var text by rememberSaveable { mutableStateOf(initial) }
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

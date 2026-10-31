# 雷池 WAF 社区版 · Android 客户端

面向 [雷池 SafeLine 社区版](https://waf-ce.chaitin.cn/) 的原生安卓管理客户端。
使用 Kotlin + Jetpack Compose + Material 3 编写，**支持多实例（多账号）快速切换**，凭据由 Android Keystore 加密落盘。

- 包名：`com.uniomo.wafce`
- 最低支持：Android 8.0（API 26）
- 接入方式：**仅 API Token**（用户名密码会话链路已在代码中实现，但当前没有 UI 入口，见第二节）
- 构建产物：`app/build/outputs/apk/release/app-release.apk`

---

## 界面预览

| 实例管理 | API Token 接入 | 安全态势仪表盘 |
|:---:|:---:|:---:|
| ![实例管理](docs/screenshot-accounts.jpg) | ![登录接入](docs/screenshot-login.jpg) | ![安全态势仪表盘](docs/screenshot-dashboard.jpg) |

> 左：多实例管理，支持快速切换/新增/编辑/删除；中：API Token 接入，凭据由 Keystore 加密保存；右：仪表盘，展示攻击事件、拦截记录、人机验证等真实计数与实时 QPS、最新安全事件流。

---

## 一、功能对照

| 模块 | 客户端能力 | 说明 |
| --- | --- | --- |
| **多账号切换** | ✅ 完整 | 顶部切换面板可一键在多台 WAF 之间切换、新增、编辑、删除 |
| **仪表盘** | ✅ 完整 | 进入即自动刷新（默认 20 秒轮询，可手动刷新、可关闭自动刷新）；访问趋势、拦截趋势、实时 QPS 三张图（优先近 24 小时，无数据再退近 7 天，都没有则隐藏该卡片）；流量分析、错误统计、来源地分布、安全态势明细均按控制台版本探测，取不到就整块隐藏；防护拦截、防护资源、防护能力三组指标卡**全部可点击**跳转到对应页面；最新安全事件流 |
| **防护站点** | ✅ 增删改查 | 列表、新增、编辑、删除、详情；可编辑域名、监听端口、上游服务器、备注；证书绑定与负载均衡仅展示（新增时 `balance_type` 固定为 1，暂不可选） |
| **IP 组** | ✅ 完整 | 增删改查，支持批量粘贴 IP / CIDR；顶部汇总条显示**组数 / IP 条目数 / 展开后 CIDR 数 / 系统内置数** |
| **黑白名单** | ✅ 查看 + 删除 | 白名单（放行）与黑名单（拦截）规则列表，顶部统计白/黑/总条数 |
| **自定义规则** | ✅ 查看 + 删除 | 展示策略 ID 与动作，顶部统计总数及拦截/放行/人机验证分布 |
| **防护模块 / 频率限制(CC)** | ✅ 查看 + 专家下发 | 读取 `global/mode`、`global/acl` 原始配置，支持 JSON 编辑后直接 PUT 下发 |
| **日志中心** | ✅ 四类日志 | 攻击事件、**频率控制**、人机验证、身份认证；点开攻击事件会二次请求 `/events/{id}` 拉取完整详情，按「概要 / 攻击特征 / 请求内容 / 其他字段」展示，可展开原始 JSON 并复制。官方 OpenAPI 未提供访问日志列表接口，故不含「访问日志」 |
| **证书管理** | ✅ 新增 + 删除 | 支持手动粘贴 PEM 证书与私钥导入；不支持在线编辑已有证书 |
| **用户 / API Token / 通知** | ✅ 用户增删改 / Token 删除 | 用户支持新增、编辑、删除；API Token 仅可查看与删除（删除的是当前正在使用的 Token，会立即自锁）；通知只读。均受控制台权限限制 |
| **系统信息 / 关于** | ✅ | 引擎版本、仓库与开发者信息、退出登录；**检查更新入口在「系统 → 关于」** |

---

## 二、接入方式

### 1. API Token（当前唯一 UI 入口，推荐）

在控制台「系统设置 → OPEN API」生成 Token 后填入即可，请求头携带：

```
X-SLCE-API-TOKEN: <token>
```

接入流程：

1. 填写控制台地址（未写协议时默认补全 `https://`，也可手动填 `http://`）、备注名、API Token；
2. 点击「连接并进入」，App 会先保存实例，再真实请求一次 `/api/open/system` 验证 Token；
3. 验证通过进入主界面；失败则停在当前页并给出可读错误（实例会保留并记录 `lastError`，改完可直接重试）。

**为什么只保留 Token 方式**：账号密码登录要经过 `/api/open/auth/login`，雷池对该接口有连续失败风控，触发后会直接返回 `400 invalid request` 并长时间无法自动恢复。Token 走独立的请求头鉴权，不经过登录接口，稳定性和可控性都更好。

**特点**：官方支持的稳定接口；**但覆盖面是子集**，且受创建 Token 时勾选的权限限制——仪表盘部分统计、内部接口会拿不到数据。适合看数据、改 IP 组这类场景。

### 2. 会话模式（已实现，暂无 UI 入口）

代码中保留了一条完整可用的会话链路，登录页目前**没有**切换到它的入口：

```
GET  /api/open/auth/csrf        -> 取得一次性 csrf_token
POST /api/open/auth/login       -> { username, password, csrf_token } 返回中间 JWT
POST /api/open/auth/tfa         -> { code, timestamp, csrf_token }    返回正式 JWT（开启两步验证时）
后续请求统一携带：Authorization: Bearer <jwt>
```

- `AppViewModel.performLogin` / `handshakeLogin` / `fetchCsrf` 已实现 csrf、密码登录、TFA、https→http 协议回退，均标注 `@Suppress("unused")`；
- 保存 TOTP 密钥后，`Totp`（RFC 6238 本地实现）可自动生成 6 位动态码；
- `AuthMode.SESSION`、`SessionStore` 的 JWT 缓存、`SessionState.NEED_TOTP` 都依赖这条链路，所以没有删除；
- 由于 UI 只调用 `loginWithToken`，**当前所有实例实际都是 `AuthMode.TOKEN`**。要恢复密码登录，需要在 `LoginScreen` 增加入口并把 `performLogin` 接上。

### 3. 会话失效处理

无论哪种模式，只要接口返回 401，或返回 403 且不是 `license required`，`HttpErrorInterceptor` 都会清掉该实例的会话缓存并通知 `AppViewModel` 切回登录页；403 + `license required` 属于商业版能力限制，只提示、不踢登录态。

---

## 三、安全设计

| 项 | 做法 |
| --- | --- |
| 凭据存储 | API Token / TOTP 密钥（以及会话模式下的密码）经 **Android Keystore AES-256-GCM** 加密后存放，密钥由系统保管、应用无法导出 |
| 备份隔离 | `backup_rules.xml` 与 `data_extraction_rules.xml` 已将账号库 `safe_line_accounts.xml` 排除出云端备份与设备迁移 |
| TLS | 社区版控制台默认是 9443 端口的自签证书；每个实例可单独开关「信任自签名证书」，默认开启。`network_security_config` 同时放行 cleartext，便于内网 http 环境 |
| 会话 | JWT 仅存于内存（`SessionStore`），进程结束即失效；Token 模式下不产生 JWT |
| 日志脱敏 | 请求体按 key 黑名单整体遮蔽（`password` / `token` / `csrf_token` / `code` 等），Release 包同样生效，不依赖 `BuildConfig.DEBUG` |

---

## 四、下载与安装

每次推送 `main` 分支，流水线都会自动构建最新 APK 并发布：

> **下载地址：** https://cnb.cool/unicom/chaitin/-/releases

在 `nightly` 这个 Release 的附件里下载 `safeline-client.apk` 即可安装（需允许「安装未知来源应用」）。

另外，每次提交的构建产物也会挂在该 commit 的附件上，方便回溯任意历史版本。

### 应用内检查更新

入口在「系统 → 关于 → 检查更新」：

1. 先拉取 Release 附件里的 `version.json`（几百字节），与当前安装的 `versionCode` 比较；
2. 有新版才弹出确认，用户同意后才下载约 12MB 的 APK；
3. 下载完成交给系统安装器（`FileProvider` + `REQUEST_INSTALL_PACKAGES`），从安装界面返回后自动复位状态；
4. `version.json` 不存在时，回退为「下载 APK 后用 `PackageManager` 解析版本号」的老办法。

`version.json` 由流水线生成，包含 `versionCode` / `versionName` / `commit` / `time` / `apkUrl`。

### 本地生成签名证书

Android 要求每个 APK 用开发者自己的私钥签名。没配签名时 Gradle 会用 **debug 签名**兜底
（能装，但换机器/换环境签名就变，用户升级时必须卸载重装），所以正式分发前请生成自己的证书。

**方式 A（推荐）：用一键脚本**

仓库自带生成脚本，只需两步：

1. 新建 `keystore/signing.env`（`keystore/` 整个目录都在 `.gitignore` 里，不会入库），填写脚本要求的 12 个键：

   ```ini
   DNAME_CN=yourdomain.com
   DNAME_OU=Tools
   DNAME_O=YourName
   DNAME_L=City
   DNAME_ST=Province
   DNAME_C=CN
   KEY_ALIAS=safeline
   STORE_PASSWORD=换成你的密码
   KEY_PASSWORD=换成你的密码
   VALIDITY_DAYS=10950
   KEYSTORE_FILE=keystore/safeline-client.jks
   PROPS_FILE=keystore.properties
   ```

   `KEYSTORE_FILE`、`PROPS_FILE` 都相对**项目根目录**解析；密码不要留 `TODO`（PowerShell 版会直接拒绝执行）。

2. 在项目根目录执行（**PowerShell**，Windows 下推荐）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\generate-signing.ps1
```

或在 Git Bash 里：

```bash
bash scripts/generate-signing.sh
```

脚本会生成 `keystore/safeline-client.jks` 并自动写好 `keystore.properties`。
若证书已存在会先要求输入 `yes` 确认，避免误覆盖（覆盖会导致已安装的 App 无法覆盖升级）。

> 两个脚本功能一致，任选其一。**PowerShell 5.1 会按系统 ANSI 编码读取无 BOM 的脚本**，
> 因此 `.ps1` 内保持纯 ASCII 输出（中文放在 `.env` 里，用 UTF-8 读取）。

JDK 不在默认路径时：

```powershell
$env:JDK_BIN="D:\jdk17"; powershell -ExecutionPolicy Bypass -File scripts\generate-signing.ps1
```

**方式 B：手动执行 keytool**（脚本做的事就是这些）

```bash
# 换成你自己的 JDK 路径
JDK="C:/Users/Administrator/.workbuddy/binaries/android/jdk17tmp/jdk-17.0.20.1+1"
mkdir -p keystore

"$JDK/bin/keytool" -genkeypair -v \
  -keystore keystore/safeline-client.jks \
  -storetype PKCS12 \
  -keyalg RSA -keysize 2048 \
  -validity 10950 \
  -alias safeline \
  -storepass "换成你的密码" \
  -keypass   "换成你的密码" \
  -dname "CN=yourdomain.com, OU=Tools, O=YourName, L=City, ST=Province, C=CN"
```

参数说明：

| 参数 | 建议 |
| --- | --- |
| `-storetype PKCS12` | 推荐，比老式 JKS 更安全、兼容性更好（AGP 7+ 均支持） |
| `-keyalg RSA -keysize 2048` | 标准配置，兼顾兼容与安全 |
| `-validity 10950` | 30 年。Google Play 要求有效期晚于 2033-10-22，太短会影响上架 |
| `-alias` | 密钥别名，一个 keystore 可放多把密钥 |
| `-dname` | 证书主体信息，会显示给安装者（CN 一般是域名或姓名） |

**② 把路径与密码写进 `keystore.properties`**（该文件已被 gitignore，不会入库）：

```properties
storeFile=keystore/safeline-client.jks
storePassword=<上一步的 storepass>
keyAlias=safeline
keyPassword=<上一步的 keypass>
```

> ⚠️ **路径陷阱**：`storeFile` 是相对于**项目根目录**解析的（Gradle 里用的是
> `rootProject.file(...)`），**不是** `app/` 目录。写成 `../keystore/xxx.jks` 或
> `app/keystore/xxx.jks` 都会找不到文件，从而静默退回 debug 签名——
> 构建不会报错，但签名其实没生效。

**③ 构建并验证签名确实生效**：

```bash
export JAVA_HOME="C:/.../jdk-17.0.20.1+1"
export ANDROID_HOME="C:/.../android/sdk"
./gradlew assembleRelease

# 验证：应显示你自己的 DN，而不是 Android Debug
"$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

看到 `Signer #1 certificate DN: CN=yourdomain.com, ...` 即成功；
若出现 `CN=Android Debug` 说明没配上，回去检查 `storeFile` 路径。

**④ 查看已有证书信息（可选）**：

```bash
"$JDK/bin/keytool" -list -v -keystore keystore/safeline-client.jks -storepass "<密码>"
```

> 🔒 **务必备份**：keystore 文件 + 两个密码一旦丢失，**无法再为这个应用发布更新**
> （用户只能卸载重装，且无法覆盖升级）。建议离线备份，且不要提交进任何仓库。

---

### CI 注入正式签名（可选）

未注入签名密钥时，流水线用 **debug 签名**产出 APK，保证任何人都能直接安装体验。

如需换成正式签名，需要 4 个变量：

| 变量名 | 说明 |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | `release.jks` 的 base64 编码（`base64 -w0 release.jks`） |
| `SIGNING_STORE_PASSWORD` | keystore 密码 |
| `SIGNING_KEY_ALIAS` | 密钥别名 |
| `SIGNING_KEY_PASSWORD` | 密钥密码 |

> ⚠️ CNB **没有**「仓库设置 → 环境变量」这个页面。变量通过**密钥仓库 + `imports`** 注入。

#### 1. 导出 keystore base64（本地）

PowerShell（Windows 推荐，顺带复制到剪贴板）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\export-keystore-base64.ps1
```

等价的一条命令写法：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("keystore\safeline-client.jks")) | Set-Clipboard
```

Git Bash / Linux：

```bash
base64 -w0 keystore/safeline-client.jks
```

> ⚠️ 这串 base64 **等同于你的私钥**（只差密码），不要提交进仓库、不要贴到 issue 或聊天里。
> 上面的脚本只写入 `keystore/keystore.b64.txt`（已被 gitignore）并复制到剪贴板。

#### 2. 新建密钥仓库并写入 signing.yml

1. 进入 [新建仓库](https://cnb.cool/new/repos)，仓库类型选 **`密钥仓库`**
   （密钥仓库禁止 clone 到本地，只能在网页上编辑，适合存密码）。
2. 在其中新建 `signing.yml`：

   ```yaml
   allow_slugs: unicom/chaitin
   SIGNING_KEYSTORE_BASE64: "<上一步导出的那串>"
   SIGNING_STORE_PASSWORD: "keystore 密码"
   SIGNING_KEY_ALIAS: "别名"
   SIGNING_KEY_PASSWORD: "密钥密码"
   ```

   `allow_slugs` 声明只有本仓库的流水线能读这个文件，避免其它项目蹭到密钥。
3. 编辑 `.cnb.yml` 的 `imports`，指向该文件的 blob 地址（形如
   `https://cnb.cool/<密钥仓库 slug>/-/blob/main/signing.yml`）。

> 当前仓库的 `.cnb.yml` **已启用** `imports`，指向 `unicom/unicom` 密钥仓库的 `signing.yml`。
> 如果你 fork 到别的组织，需要改成自己的密钥仓库地址；想退回 debug 签名，把整段 `imports` 注释掉即可
> （此时变量为空，流水线里的「准备签名」阶段会打印提示并跳过）。

配好后下次 push，流水线会自动识别并改用正式签名；没配则继续走 debug 兜底。

> 密钥只存在于密钥仓库，不会落进本仓库版本库；构建产物也不会被回写。

---

## 五、在本机构建

准备好 **JDK 17** 与 **Android SDK（platform 34 / build-tools 34.0.0）**，然后：

```bash
./gradlew assembleRelease   # 正式包（未配签名时用 debug 签名兜底）
./gradlew assembleDebug     # 调试包
```

产物路径：`app/build/outputs/apk/release/app-release.apk`

### 版本号自动递增

版本号集中维护在项目根目录的 `version.properties`（当前为 1.0.14 / 15，每打一次包会自动增长）：

```properties
versionCode=15
versionName=1.0.14
```

每次执行 `./gradlew assembleRelease`，`bumpVersion` 任务都会把它改成下一个版本
（`versionCode` +1，`versionName` 修订号 +1，如 `1.0.14 -> 1.0.15`），构建日志里会打印一行：

```
[version] 1.0.14 (15) -> 1.0.15 (16)
```

好处：每次打包都是新版本号，装到手机上能直接覆盖升级，不会出现「版本号没变装不上」的情况。

想跳大版本就手动编辑该文件（例如改成 `2.0.0`）。

> ⚠️ `bumpVersion` 是通过 `finalizedBy` 挂在 `assembleRelease` 上的，而 Gradle 的 finalizer
> **在任务失败时也会执行**。所以构建失败同样可能消耗掉一个版本号，失败后请检查
> `version.properties`，需要时手动改回。

> CI 环境每次都是干净检出，写回的 `version.properties` 不会提交，
> 因此流水线产物始终用仓库里记录的版本号。需要按次递增的话，把变更提交回仓库即可。

技术栈版本：Kotlin 2.0.21 / AGP 8.5.2 / Gradle 8.7 / Compose BOM 2024.09.03 / OkHttp 4.12 / Retrofit 2.11 / JDK 17 / compileSdk 与 targetSdk 34 / minSdk 26。

签名密钥放在 `keystore/safeline-client.jks`，路径与密码写进 `keystore.properties`（已被 gitignore，不要入库）：

```properties
storeFile=keystore/safeline-client.jks
storePassword=...
keyAlias=...
keyPassword=...
```

> 未提供 `keystore.properties` 时，`assembleRelease` 会自动退回 **debug 签名**，
> 保证本地和 CI 都能直接产出可安装的 APK。证书的生成方法见上文
> [本地生成签名证书](#本地生成签名证书)。

---

## 六、目录结构

```
app/src/main/kotlin/com/uniomo/wafce/
├── App.kt / MainActivity.kt
├── core/           Resp（响应解析）、UiState、Totp、TimeFmt、UpdateManager、AppContextHolder
├── data/
│   ├── net/        SafeLineApi（接口定义）、ApiClient（OkHttp+拦截器）、SessionStore、Tls
│   └── store/      AccountStore（多账号持久化）、AccountModels、CipherBox（Keystore 加解密）
├── ui/
│   ├── AppRoot.kt  导航骨架 + 账号切换面板
│   ├── components/ 卡片、图表、排行、异步容器、更新弹窗
│   ├── screens/    登录、账号管理、仪表盘、站点（含详情页）、防护、日志、系统、关于
│   └── theme/      配色与主题
└── vm/             AppViewModel（会话与账号生命周期）、UpdateVm（应用内更新）
```

主代码（Kotlin，不含资源）约 9000 行。

---

## 七、已知限制

1. **登录方式**：当前只有 API Token 一种入口；`AuthMode.SESSION`（用户名密码 + TOTP）链路完整但未接线，见第二节。
2. API Token 模式下，控制台未授权到该 Token 的模块会返回空数据或错误。
3. 站点「新增/编辑」采用官方 OpenAPI 的最小可用字段集（域名 / 端口 / 上游 / 备注），负载均衡 `balance_type` 固定为 1，证书绑定只读。若需要 SSL、HTTP→HTTPS 跳转等高级项，建议先在网页版建好，再用客户端维护。
4. 仪表盘的趋势与汇总指标依赖 `/api/stat/*`、`/api/security_posture/*` 等未公开或半公开接口，路径随版本变化。客户端按「候选路径 + 多字段名」逐个探测：优先近 24 小时，无数据退近 7 天；仍取不到就**整块隐藏**（不显示 0，也不再用日志条数估算），图表副标题会标注实际数据来源。错误统计、来源地分布同理，社区版取不到时默认不出现。
5. 日志中心里的「频率控制」对应雷池控制台的「拦截记录」（`/api/open/records/acl`），客户端跟随控制台命名。
6. 「系统 → API Token」里删除的是**本客户端正在使用的那一个 Token**，删除后立即失去访问权限，需要在控制台重新生成并回填。
7. 证书只支持新增（粘贴 PEM）与删除，不支持编辑已有证书。
8. `global/mode`、`global/acl` 属控制台内部接口，字段名随版本可能变化，因此采用「读取原始 JSON + 专家模式下发」的设计，而非硬编码开关 UI。

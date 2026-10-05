# 雷池 WAF 社区版 · Android 客户端

面向 [雷池 SafeLine 社区版](https://waf-ce.chaitin.cn/) 的原生安卓管理客户端。
使用 Kotlin + Jetpack Compose + Material 3 编写，**支持多实例（多账号）快速切换**，凭据由 Android Keystore 加密落盘。

- 包名：`com.uniomo.wafce`
- 最低支持：Android 8.0（API 26）
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
| **仪表盘** | ✅ 完整 | 进入即自动刷新（20 秒轮询，可手动触发）；实时访问趋势、实时拦截趋势、实时 QPS 三张图；防护拦截、防护资源、防护能力三组指标卡**全部可点击**跳转到对应页面；最新安全事件流 |
| **防护站点** | ✅ 完整 | 列表、新增、编辑、删除、详情；域名、端口、上游服务器、备注、证书绑定均可读写 |
| **IP 组** | ✅ 完整 | 增删改查，支持批量粘贴 IP / CIDR；顶部汇总条显示**组数 / IP 条目数 / 展开后 CIDR 数 / 系统内置数** |
| **黑白名单** | ✅ 查看 + 删除 | 白名单（放行）与黑名单（拦截）规则列表，顶部统计白/黑/总条数 |
| **自定义规则** | ✅ 列表 + 删除 | 展示策略 ID 与动作，顶部统计拦截/放行/人机验证分布 |
| **防护模块 / 频率限制(CC)** | ✅ 查看 + 专家下发 | 读取 `global/mode`、`global/acl` 原始配置，支持 JSON 编辑后直接 PUT 下发 |
| **日志中心** | ✅ 完整 | 攻击事件、**频率控制**、人机验证、身份认证、访问日志五类日志；点开攻击事件会二次请求 `/events/{id}` 拉取完整详情，按「概要 / 攻击特征 / 请求内容 / 其他字段」分组展示，可展开原始 JSON 并复制 |
| **证书管理** | ✅ 增删改 | 支持手动粘贴 PEM 证书与私钥导入 |
| **用户 / API Token / 通知** | ✅ 查看（Token 可删除） | 受控制台权限限制 |
| **系统信息 / 关于** | ✅ | 引擎版本等；**检查更新入口已从顶栏迁移到「系统 → 关于」** |

---

## 二、两种接入方式

### 1. 会话模式（推荐）

与网页版一致，通过 `用户名 + 密码（+ 两步验证）` 登录，可操作**控制台全部功能**。

登录流程复刻自雷池控制台真实链路：

```
GET  /api/open/auth/csrf        -> 取得一次性 csrf_token
POST /api/open/auth/login       -> { username, password, csrf_token } 返回中间 JWT
POST /api/open/auth/tfa         -> { code, timestamp, csrf_token }    返回正式 JWT（开启两步验证时）
后续请求统一携带：Authorization: Bearer <jwt>
```

- 账号里保存了 TOTP 密钥时，App 会自动生成 6 位动态码（RFC 6238 本地实现），无需翻验证器
- 未保存密钥时，登录页会弹出动态码输入框
- 会话失效（401/403）会自动回到登录态

### 2. API Token 模式

在控制台「系统设置 → OPEN API」生成 Token 后填入即可，请求头携带：

```
X-SLCE-API-TOKEN: <token>
```

**特点**：官方支持的稳定接口；**但覆盖面是子集**，且受创建 Token 时勾选的权限限制——仪表盘部分统计、内部接口会拿不到数据。适合只需要看数据/改 IP 组的场景。

> 建议平时用会话模式，自动化场景用 Token 模式。

---

## 三、安全设计

| 项 | 做法 |
| --- | --- |
| 凭据存储 | 密码 / API Token / TOTP 密钥经 **Android Keystore AES-256-GCM** 加密后存放，密钥由系统保管、应用无法导出 |
| 备份隔离 | `backup_rules.xml` 与 `data_extraction_rules.xml` 已将账号库排除出云端备份 |
| TLS | 社区版控制台默认是 9443 端口的自签证书，客户端默认放宽校验；若已部署可信证书，可在账号中关闭「信任自签名证书」 |
| 会话 | JWT 仅存于内存（`SessionStore`），进程结束即失效 |

---

## 四、下载 APK

每次推送 `main` 分支，流水线都会自动构建最新 APK 并发布：

> **下载地址：** https://cnb.cool/unicom/chaitin/-/releases

在 `nightly` 这个 Release 的附件里下载 `safeline-client.apk` 即可安装（需允许「安装未知来源应用」）。

另外，每次提交的构建产物也会挂在该 commit 的附件上，方便回溯任意历史版本。

### 第一步：生成你的签名证书（本地）

Android 要求每个 APK 用开发者自己的私钥签名。没配签名时 Gradle 会用 **debug 签名**兜底
（能装，但换机器/换环境签名就变，用户升级时必须卸载重装），所以正式分发前请生成自己的证书。

**方式 A（推荐）：用一键脚本**

仓库自带生成脚本，只需两步：

1. 编辑 `keystore/signing.env`，填写证书信息与两个密码（该文件**不会入库**）
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

### 关于签名

流水线默认使用 **debug 签名**产出 APK，保证任何人都能直接安装体验。

如需换成正式签名，需要 4 个变量：

| 变量名 | 说明 |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | `release.jks` 的 base64 编码（`base64 -w0 release.jks`） |
| `SIGNING_STORE_PASSWORD` | keystore 密码 |
| `SIGNING_KEY_ALIAS` | 密钥别名 |
| `SIGNING_KEY_PASSWORD` | 密钥密码 |

> ⚠️ CNB **没有**「仓库设置 → 环境变量」这个页面。变量通过**密钥仓库 + `imports`** 注入，
> 具体步骤见下节。

#### 配置步骤

1. 进入 [新建仓库](https://cnb.cool/new/repos)，仓库类型选 **`密钥仓库`**
   （密钥仓库禁止 clone 到本地，只能在网页上编辑，适合存密码）。
2. 在其中新建 `signing.yml`：

   ```yaml
   allow_slugs: unicom/chaitin
   SIGNING_KEYSTORE_BASE64: "<用 scripts\export-keystore-base64.ps1 导出的那串>"
   SIGNING_STORE_PASSWORD: "keystore 密码"
   SIGNING_KEY_ALIAS: "别名"
   SIGNING_KEY_PASSWORD: "密钥密码"
   ```

   `allow_slugs` 声明只有本仓库的流水线能读这个文件，避免其它项目蹭到密钥。
3. 打开 `.cnb.yml`，把 `imports` 两行的注释去掉，链接换成上一步文件的 blob 地址（形如
   `https://cnb.cool/<密钥仓库 slug>/-/blob/main/signing.yml`）。

配好后下次 push，流水线会自动识别并改用正式签名；没配则继续走 debug 兜底。

> 密钥只存在于密钥仓库，不会落进本仓库版本库；构建产物也不会被回写。

### 本地准备正式签名包

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

---

## 五、在本机构建

准备好 **JDK 17** 与 **Android SDK（platform 34 / build-tools 34.0.0）**，然后：

```bash
./gradlew assembleRelease   # 正式包（未配签名时用 debug 签名兜底）
./gradlew assembleDebug     # 调试包
```

产物路径：`app/build/outputs/apk/release/app-release.apk`

### 版本号自动递增

版本号集中维护在项目根目录的 `version.properties`：

```properties
versionCode=2
versionName=1.0.1
```

每次 `./gradlew assembleRelease` **成功后**，`bumpVersion` 任务会自动把它改成下一个版本
（`versionCode` +1，`versionName` 修订号 +1，如 `1.0.1 -> 1.0.2`），构建日志里会打印一行：

```
[version] 1.0.1 (2) -> 1.0.2 (3)
```

好处：每次打包都是新版本号，装到手机上能直接覆盖升级，不会出现「版本号没变装不上」的情况。

想跳大版本就手动编辑该文件（例如改成 `2.0.0`）；只在构建成功时递增，构建失败不会吃掉版本号。

> CI 环境每次都是干净检出，写回的 `version.properties` 不会提交，
> 因此流水线产物始终用仓库里记录的版本号。需要按次递增的话，把变更提交回仓库即可。

技术栈版本：Kotlin 2.0.21 / AGP 8.5.2 / Compose BOM 2024.09.03 / OkHttp 4.12 / Retrofit 2.11 / targetSdk 34。

签名密钥放在 `keystore/safeline-client.jks`，路径与密码写进 `keystore.properties`（已被 gitignore，不要入库）：

```properties
storeFile=keystore/safeline-client.jks
storePassword=...
keyAlias=...
keyPassword=...
```

> 未提供 `keystore.properties` 时，`assembleRelease` 会自动退回 **debug 签名**，
> 保证本地和 CI 都能直接产出可安装的 APK。证书的生成方法见上文
> [第一步：生成你的签名证书](#第一步生成你的签名证书本地)。

---

## 六、目录结构

```
app/src/main/kotlin/xyz/anye/wafce/
├── App.kt / MainActivity.kt
├── core/           Resp（响应解析）、UiState、Totp、AppContextHolder
├── data/
│   ├── net/        SafeLineApi（接口定义）、ApiClient（OkHttp+拦截器）、SessionStore、Tls
│   └── store/      AccountStore（多账号持久化）、CipherBox（Keystore 加解密）
├── ui/
│   ├── AppRoot.kt  导航骨架 + 账号切换面板
│   ├── components/ 卡片、图表、排行、异步容器
│   └── screens/    登录、账号管理、仪表盘、站点、防护、日志、系统
└── vm/             AppViewModel（会话与账号生命周期）
```

---

## 七、已知限制

1. `global/mode`、`global/acl` 属控制台内部接口，字段名随版本可能变化，因此客户端采用「读取原始 JSON + 专家模式下发」的设计，而非硬编码开关 UI。
2. API Token 模式下，控制台未授权到该 Token 的模块会返回空数据或错误。
3. 站点「新增」采用官方 OpenAPI 的最小可用字段集（域名/端口/上游/备注/轮询）。若需要 SSL、HTTP→HTTPS 跳转等高级项，建议先在网页版建好，再用客户端维护。
4. 仪表盘的统计数据依赖控制台内部接口（`/api/stat/*`、`/api/dashboard/*`）。这些接口雷池官方未公开文档，大版本升级后若路径调整，趋势图会降级为「按最近 100 条日志的时间分布分桶」——图仍有内容，但只反映相对趋势，不代表绝对量值。页面会在图表副标题里标注当前数据来源。
5. 日志中心里的「频率控制」对应雷池控制台的「拦截记录」（`/api/open/records/acl`），客户端跟随控制台命名。

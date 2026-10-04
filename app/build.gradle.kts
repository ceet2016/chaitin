import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.uniomo.wafce"
    compileSdk = 34

    // 签名信息优先级：keystore.properties（本地） > 环境变量（CI） > 无签名
    val keystorePropsFile = rootProject.file("keystore.properties")
    val localSigningProps = Properties().apply {
        if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
    }

    fun signingValue(key: String, envName: String): String? =
        (localSigningProps.getProperty(key) ?: System.getenv(envName))?.takeIf { it.isNotBlank() }

    // CI 下由流水线写入 keystore.properties，storeFile 用相对路径即可
    val storeFilePath = signingValue("storeFile", "SIGNING_STORE_FILE")
        ?.let { rootProject.file(it) }

    val signingConfigured = storeFilePath?.exists() == true
            && signingValue("storePassword", "SIGNING_STORE_PASSWORD") != null
            && signingValue("keyAlias", "SIGNING_KEY_ALIAS") != null
            && signingValue("keyPassword", "SIGNING_KEY_PASSWORD") != null

    if (!signingConfigured) {
        logger.lifecycle("[build] 未配置正式签名，release 包将使用 debug 签名")
    }

    signingConfigs {
        if (signingConfigured) {
            create("release") {
                storeFile = storeFilePath
                storePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
            }
        }
    }

    // 版本号集中放在项目根目录的 version.properties，构建成功后自动 +1
    val versionPropsFile = rootProject.file("version.properties")
    val versionProps = Properties().apply {
        if (versionPropsFile.exists()) versionPropsFile.inputStream().use { load(it) }
        else setProperty("versionCode", "1").also { setProperty("versionName", "1.0.0") }
    }
    val appVersionCode = versionProps.getProperty("versionCode", "1").toInt()
    val appVersionName = versionProps.getProperty("versionName", "1.0.0")

    defaultConfig {
        applicationId = "com.uniomo.wafce"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        resourceConfigurations += setOf("zh", "en")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }

    buildFeatures {
        compose = true
        // AGP 8 默认不生成 BuildConfig，关于页需要读 VERSION_NAME / VERSION_CODE
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*"
            )
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 未配置正式签名时用 debug 签名兜底，保证 CI 产物可直接安装
            signingConfig = if (signingConfigured) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // ---------- AndroidX 核心 ----------
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ---------- Compose ----------
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---------- 网络 ----------
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}

// ------------------------------------------------------------------
// 版本号自动递增
//
// 规则：release 构建成功后，version.properties 里的 versionCode +1，
//       versionName 的修订号（第三段）+1，例如 1.0.3 -> 1.0.4。
// 这样每次打包都是新版本号，安装时可覆盖升级，不会出现「装不上」的情况。
// ------------------------------------------------------------------
tasks.register("bumpVersion") {
    doLast {
        val file = rootProject.file("version.properties")
        val props = Properties().apply {
            if (file.exists()) file.inputStream().use { load(it) }
        }
        val oldCode = props.getProperty("versionCode", "1").toInt()
        val oldName = props.getProperty("versionName", "1.0.0")
        val newCode = oldCode + 1
        val newName = bumpPatch(oldName)

        props.setProperty("versionCode", newCode.toString())
        props.setProperty("versionName", newName)
        file.outputStream().use { props.store(it, "auto bumped by bumpVersion task") }

        logger.lifecycle("[version] $oldName ($oldCode) -> $newName ($newCode)")
    }
}

fun bumpPatch(name: String): String {
    val parts = name.trim().split(".").toMutableList()
    while (parts.size < 3) parts.add("0")
    val patch = parts[2].toIntOrNull() ?: 0
    parts[2] = (patch + 1).toString()
    return parts.take(3).joinToString(".")
}

// assembleRelease 成功后才递增，避免构建失败也把版本号吃掉。
// AGP 的变体任务是延迟创建的，必须用 matching 而不是 named，否则报
// "Task with name 'assembleRelease' not found"。
tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy("bumpVersion")
}

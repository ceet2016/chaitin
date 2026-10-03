import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "xyz.anye.wafce"
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

    defaultConfig {
        applicationId = "xyz.anye.wafce"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

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

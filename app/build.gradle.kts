import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.icdqcow2"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "org.icdqcow2"
        // 33：miuix-blur 要求（RuntimeShader / RenderEffect 那套），kill 掉 28 的想法
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // ── 签名 ──────────────────────────────────────────────────────────────────
    // 密钥与密码一律在仓库外：根目录 keystore.properties（已 .gitignore，含 storeFile 路径
    // 与四个密码字段）+ 真正的 .jks。别人 clone 下来没有这个文件 → 自动回落 debug 签名，
    // 照样能编译能装；只有本机放了 properties 才走正式签名。
    // 具体格式见同目录 keystore.properties.example。
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = if (keystorePropsFile.exists())
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures { compose = true }

    // .so 由 imgtool/android-cross.sh 交叉编译后放进 jniLibs（Gradle 不调 NDK）
    packaging { jniLibs { useLegacyPackaging = false } }

    // JNI 入口 Native.java 与 JNI 薄层是仓库里的同一份，别在 App 里抄第二份
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "../jni/java")
        }
    }
}

dependencies {
    // 代码编辑器内核（canvas 绘制、按行虚拟化）：Compose 的 BasicTextField 会把整篇文本
    // 一次性排版，"一整行 19KB"这种文件必然又卡又崩 —— 只能换内核（LGPL 2.1）。
    implementation("io.github.Rosemoe.sora-editor:editor:0.23.6")
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // Miuix：组件 + 图标 + 模糊 + 偏好
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
    // 液态玻璃（Kyant0/AndroidLiquidGlass）——底部导航栏的背景折射
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation("io.github.kyant0:shapes:1.2.1")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    // Miuix 的 WindowDialog 需要 NavigationEventDispatcherOwner（见 MainActivity）
    implementation("androidx.navigationevent:navigationevent-compose:1.1.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Shizuku：借 shell 权限读 /storage/emulated、/data 这类普通应用进不去的位置
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
// ── native 库护栏（2026-11-09 加）────────────────────────────────────────────
// 背景：`.gitignore` 曾整体忽略 `*.so`，两个 native 库不在版本库里。
//   而 AGP 的 jniLibs 合并任务会产出陈旧结果 —— APK **静默**不含库，
//   装到新机上所有工具命令全部 UnsatisfiedLinkError，整个 App 不可用。
//   这个坑已经踩过两次（一次丢 libimgapp.so，一次新 clone 出来零库）。
//
// 这里做两道校验：
//   ① 打包前：源文件必须在（否则提前失败，别等打包完才发现）
//   ② 打包后：APK 里必须真的含这两个库（否则构建失败，坏包不许流出去）
val requiredNativeLibs = listOf(
    "lib/arm64-v8a/libimgtool.so",
    "lib/arm64-v8a/libimgapp.so",
)

tasks.matching { it.name == "preBuild" }.configureEach {
    doLast {
        val missing = requiredNativeLibs.mapNotNull { entry ->
            val name = entry.substringAfterLast('/')
            val f = file("src/main/jniLibs/arm64-v8a/$name")
            if (!f.exists()) entry else null
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "缺少 native 库：${missing.joinToString()}\n" +
                    "  这两个库必须入库（.gitignore 已放行）。若确实要重新生成：\n" +
                    "    1) 工具端库：clone https://github.com/lgylxyyy/imgtool && " +
                    "./android-deps.sh && ./android-cross.sh arm64-v8a\n" +
                    "    2) App 的 JNI 桥：见 tools/build-native-libs.sh\n" +
                    "  版本与来源记录在 tools/native-libs.md5。"
            )
        }
    }
}

tasks.matching { it.name == "assembleDebug" || it.name == "assembleRelease" }.configureEach {
    doLast {
        val outDir = layout.buildDirectory.dir("outputs/apk").get().asFile
        val apks = outDir.walkTopDown().filter { it.isFile && it.name.endsWith(".apk") }.toList()
        apks.forEach { apk ->
            ZipFile(apk).use { z ->
                val missing = requiredNativeLibs.filter { z.getEntry(it) == null }
                if (missing.isNotEmpty()) {
                    throw GradleException(
                        "APK ${apk.name} 缺少 native 库：${missing.joinToString()}\n" +
                            "  装到机器上会 UnsatisfiedLinkError。检查 jniLibs 是否被 gitignore、" +
                            "以及 mergeNativeLibs 任务是否产出陈旧结果（可 --rerun-tasks）。"
                    )
                }
            }
        }
    }
}

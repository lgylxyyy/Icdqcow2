import java.security.MessageDigest
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
        // versionCode 每次发布 +1：Android 只允许用更大的版本号覆盖安装
        versionCode = 4
        versionName = "0.1.0"

        // CI 构建时由 workflow 传 -PciRun=<run_number>，本地构建则是 local。
        // 配合「设置 · 关于」里的签名指纹，能直接分辨手上这个包是哪儿来的。
        val ciRun = providers.gradleProperty("ciRun").orNull
        buildConfigField(
            "String", "BUILD_STAMP",
            "\"${if (ciRun.isNullOrBlank()) "local" else "ci-$ciRun"}\"",
        )
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
        debug {
            isMinifyEnabled = false
            // 本机调试包也用发布密钥签名（密钥存在时）：与 GitHub 正式版保持同一身份，
            // 两者可以互相覆盖安装，不用先卸载丢数据。区分版本看「设置 · 关于」里的
            // 构建标识（local / ci-N）与是否混淆，不看签名。
            signingConfig = if (keystorePropsFile.exists())
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        release {
            // 正式版（GitHub 发布用）：开 R8 混淆 + 资源压缩，体积从 ~63 MB 降到 20 MB 级。
            // 调试版（本机装）保持不混淆，方便看堆栈。两条产物签名相同（都用密钥库里的那把），
            // 但 debug 变体走 AGP 的 debug 签名，所以本机调试包与发布包签名不同、互不覆盖。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    buildFeatures {
        compose = true
        // 「设置 · 关于」要显示构建标识：local = 本机构建，ci-N = GitHub Actions 第 N 次构建
        buildConfig = true
    }

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

        // ③ 内容校验：md5 必须与 tools/native-libs.md5 一致。
        //    为什么需要：release 构建链里有个环节会**原地**把 jniLibs 下的库改写成剥离版，
        //    2026-11-10 实测 libimgtool.so 被从 6,579,368 B 改成 6,579,144 B，
        //    md5 由 c3d70283… 变成 6a9de1de…，提交时差点把改坏（或来源不明）的库推上去。
        val md5Record = rootProject.file("tools/native-libs.md5")
        if (md5Record.exists()) {
            md5Record.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { line ->
                    val parts = line.split(Regex("\\s+"), 2)
                    if (parts.size == 2) {
                        val want = parts[0].lowercase()
                        val f = rootProject.file(parts[1].trim())
                        if (f.exists()) {
                            val got = MessageDigest.getInstance("MD5")
                                .digest(f.readBytes())
                                .joinToString("") { b -> "%02x".format(b) }
                            if (got != want) {
                                throw GradleException(
                                    "native 库内容与记录不符：${f.name}\n" +
                                        "  记录 $want\n  实际 $got\n" +
                                        "  若是有意换库，先更新 tools/native-libs.md5 再构建；\n" +
                                        "  若是被构建过程改写，用 git checkout 恢复。"
                                )
                            }
                        }
                    }
                }
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

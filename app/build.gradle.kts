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

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
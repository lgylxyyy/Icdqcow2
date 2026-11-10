pluginManagement {
    repositories {
        // 官方源优先：有网就走官方，任何人 clone 下来都是标准行为
        google()
        mavenCentral()
        gradlePluginPortal()
        // 可选加速：网络受限（连不上 dl.google.com）时才回退到这里，不是必需
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://repo.huaweicloud.com/repository/maven")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 可选加速，同上
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://repo.huaweicloud.com/repository/maven")
    }
}

rootProject.name = "Icdqcow2"
include(":app")
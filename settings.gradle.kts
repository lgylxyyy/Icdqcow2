pluginManagement {
    repositories {
        // ★ 本沙箱里 Java 连不上 dl.google.com（curl 能，JVM 超时），
        //   所以把阿里云的 Google 镜像放第一位；mavenCentral 的 domain 可直连。
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
        maven("https://repo.huaweicloud.com/repository/maven")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
        maven("https://repo.huaweicloud.com/repository/maven")
    }
}

rootProject.name = "Icdqcow2"
include(":app")
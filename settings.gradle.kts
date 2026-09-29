pluginManagement {
    repositories {
        // 国内镜像优先，避免外网超时
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
        maven("https://cache-redirector.jetbrains.com/plugins.gradle.org/m2")
    }
}

rootProject.name = "zhixueyao"

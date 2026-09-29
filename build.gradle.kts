import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.10"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.zhixueyao"
version = "0.2.0"

/**
 * 本机 Android Studio 的安装目录。
 *
 * 为什么不写死：这个值是**每台机器都不一样**的，写死的话别人 clone 下来第一步就编译不过。
 * 解析顺序（谁先命中用谁）：
 *  1. `-PstudioPath=...`            —— 命令行临时指定，最灵活
 *  2. `studioPath=...`（gradle.properties，不进版本库）—— 本机固定配置
 *  3. 环境变量 `ANDROID_STUDIO_HOME`
 *  4. 各平台的常见默认安装位置
 *
 * 找不到时会**明确报错告诉你该设哪个**，而不是抛一个看不懂的路径异常。
 */
val studioPath: String = run {
    val configured = (findProperty("studioPath") as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv("ANDROID_STUDIO_HOME")?.takeIf { it.isNotBlank() }
    val candidates = listOfNotNull(
        configured,
        "D:/Android studio",
        "C:/Program Files/Android/Android Studio",
        "${System.getProperty("user.home")}/Applications/Android Studio.app/Contents",
        "/Applications/Android Studio.app/Contents",
        "/opt/android-studio",
        "${System.getProperty("user.home")}/android-studio"
    )
    candidates.firstOrNull { File(it).isDirectory } ?: candidates.first()
}

val studioDir = File(studioPath)
if (!studioDir.isDirectory) {
    throw GradleException(
        "找不到 Android Studio 安装目录（当前取值：$studioPath）。\n" +
            "请在 gradle.properties 里写一行：studioPath=<你的 Android Studio 安装目录>\n" +
            "或者命令行传：./gradlew buildPlugin -PstudioPath=\"D:/Android studio\""
    )
}

logger.lifecycle("使用 Android Studio：$studioPath")

repositories {
    mavenCentral()
    maven("https://maven.aliyun.com/repository/public")
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 直接以本机已安装的 Android Studio 作为编译平台，无需下载 IDE 发行包
        local(studioPath)
        bundledPlugin("com.intellij.java")
    }

    // ---- AS 2026 模块化布局适配 ----
    // 从 2026 版起，平台被拆分为 intellij.platform.*.jar 等模块化构件，
    // 而 local() 只会把 app.jar 等少量聚合包放进编译类路径，
    // 导致 Project / PsiFile / CompilerManager 等基础类型无法解析。
    // 这里把平台与语言插件的 jar 显式补进编译类路径（含 modules / plugins 子目录）。
    // 用 compileOnly 而非 implementation：运行时这些类由 IDE 自身提供，不应打进插件包。
    compileOnly(
        fileTree("$studioPath/lib") { include("*.jar") }
    )
    compileOnly(
        fileTree("$studioPath/plugins/java/lib") { include("**/*.jar") }
    )
    compileOnly(
        fileTree("$studioPath/plugins/Kotlin/lib") { include("**/*.jar") }
    )
    compileOnly(
        fileTree("$studioPath/plugins/android/lib") { include("**/*.jar") }
    )
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

intellijPlatform {
    // 关闭字节码插桩与索引构建：纯 Kotlin 插件不需要，且可省去额外组件下载
    instrumentCode.set(false)
    buildSearchableOptions.set(false)

    pluginConfiguration {
        name.set("止血药")
        ideaVersion {
            sinceBuild.set("261")
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// 诊断任务：打印编译类路径，用于确认本地平台的 jar 暴露范围
tasks.register("dumpClasspath") {
    doLast {
        val cfg = configurations.getByName("compileClasspath")
        cfg.resolvedConfiguration.resolvedArtifacts
            .map { it.file.name }
            .sorted()
            .forEach { println("CP: $it") }
    }
}

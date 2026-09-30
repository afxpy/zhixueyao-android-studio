import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.10"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.zhixueyao"
version = "0.2.0"

/**
 * 编译平台从哪来 —— **优先本机，找不到就下载**。
 *
 * ## 为什么加了「下载」这条路
 *
 * 原来只支持本机安装：找不到目录就直接抛异常。这在开发机上没问题，
 * 但 **CI 上（GitHub 会自动跑 ./gradlew 做依赖图提交）根本没有安装 Android Studio** ——
 * 配置阶段就崩了，日志是：
 *
 * ```
 * 找不到 Android Studio 安装目录（当前取值：D:/Android studio）。
 * ```
 *
 * 所以现在分两条路：
 *
 *  - **本机模式**（本机装了）：用安装目录作为编译平台，不下载任何发行包 —— 快，可离线
 *  - **下载模式**（没装 / CI）：交给 IntelliJ Platform Gradle 插件从官方源自动下载
 *    对应版本（首次约 1.5GB，之后走 Gradle 缓存）
 *
 * 本机查找顺序（谁先命中用谁，**必须真实存在**）：
 *  1. `-PstudioPath=...` 或 `gradle.properties` 里的 `studioPath=...`
 *  2. 环境变量 `ANDROID_STUDIO_HOME`
 *  3. 各平台的常见默认安装位置
 *
 * 想复现 CI 的行为（强制走下载模式）：加 `-PstudioDownload=true`。
 */

/** 本机 Android Studio 的安装目录；`null` = 没找到，走下载模式 */
val localStudioPath: String? = run {
    // 显式要求走下载模式 —— 用来在不装 AS 的假设下验证构建（CI 复现）
    if ((findProperty("studioDownload") as String?)?.toBoolean() == true) {
        logger.lifecycle("studioDownload=true —— 强制走下载模式（忽略本机安装）")
        return@run null
    }

    val configured = (findProperty("studioPath") as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv("ANDROID_STUDIO_HOME")?.takeIf { it.isNotBlank() }
    if (configured != null && !File(configured).isDirectory) {
        // 不静默：路径写错时用户需要知道，但继续往下找（可能默认位置里有）
        logger.warn("studioPath 指向的目录不存在，已忽略：$configured")
    }

    listOfNotNull(
        configured,
        "D:/Android studio",
        "C:/Program Files/Android/Android Studio",
        "${System.getProperty("user.home")}/Applications/Android Studio.app/Contents",
        "/Applications/Android Studio.app/Contents",
        "/opt/android-studio",
        "${System.getProperty("user.home")}/android-studio"
    ).firstOrNull { File(it).isDirectory }
}

/**
 * 下载模式用的 Android Studio 版本。
 *
 * `2026.1.3.8` = **2026.1.3 Patch 1** —— 与本机安装的版本一致
 * （本机 build：`AI-261.26222.65.2613.16025427`）。
 *
 * Android Studio 的版本号是 `年份.主版本.次版本.补丁序号`，与 IntelliJ 平台的
 * `261.xxxxx` 是两套编号；对应关系可以在官方清单里查：
 * `https://jb.gg/android-studio-releases-list.json`。
 *
 * 想换版本：`-PstudioDownloadVersion=2026.1.4.8`。
 */
val DOWNLOAD_STUDIO_VERSION: String =
    (findProperty("studioDownloadVersion") as String?)?.takeIf { it.isNotBlank() }
        ?: "2026.1.3.8"

if (localStudioPath != null) {
    logger.lifecycle("使用本机 Android Studio：$localStudioPath")
} else {
    logger.lifecycle(
        "未找到本机 Android Studio —— 改用下载模式（$DOWNLOAD_STUDIO_VERSION，首次构建约 1.5GB）。" +
            "想指定本机目录：-PstudioPath=<安装目录>"
    )
}

repositories {
    mavenCentral()
    maven("https://maven.aliyun.com/repository/public")
    intellijPlatform {
        // 含 Android Studio 发行包仓库（androidStudioInstallers）——
        // 下载模式下就是从它解析发行包的
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        if (localStudioPath != null) {
            // 本机模式：直接以本机已安装的 Android Studio 作为编译平台，无需下载 IDE 发行包
            local(localStudioPath)
        } else {
            // 下载模式（CI / 未安装本机）：从官方源解析 Android Studio 发行包
            androidStudio(DOWNLOAD_STUDIO_VERSION)
        }
        bundledPlugin("com.intellij.java")
    }

    // ---- AS 2026 模块化布局适配（**仅本机模式需要**）----
    // 从 2026 版起，平台被拆分为 intellij.platform.*.jar 等模块化构件，
    // 而 local() 只会把 app.jar 等少量聚合包放进编译类路径，
    // 导致 Project / PsiFile / CompilerManager 等基础类型无法解析。
    // 这里把平台与语言插件的 jar 显式补进编译类路径（含 modules / plugins 子目录）。
    // 用 compileOnly 而非 implementation：运行时这些类由 IDE 自身提供，不应打进插件包。
    //
    // 下载模式不需要这段 —— 插件对下载来的发行包会解析出完整的模块化布局。
    if (localStudioPath != null) {
        compileOnly(
            fileTree("$localStudioPath/lib") { include("*.jar") }
        )
        compileOnly(
            fileTree("$localStudioPath/plugins/java/lib") { include("**/*.jar") }
        )
        compileOnly(
            fileTree("$localStudioPath/plugins/Kotlin/lib") { include("**/*.jar") }
        )
        compileOnly(
            fileTree("$localStudioPath/plugins/android/lib") { include("**/*.jar") }
        )
    }
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

// 诊断任务：打印编译类路径，用于确认平台的 jar 暴露范围
tasks.register("dumpClasspath") {
    doLast {
        val cfg = configurations.getByName("compileClasspath")
        cfg.resolvedConfiguration.resolvedArtifacts
            .map { it.file.name }
            .sorted()
            .forEach { println("CP: $it") }
    }
}

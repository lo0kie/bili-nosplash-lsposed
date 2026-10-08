import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lookie.bilinosplash"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.lookie.bilinosplash"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xno-param-assertions")
    }

    // 正式签名从**根目录的 `keystore.properties`** 读（该文件不入库，见 .gitignore）：
    //   storeFile=release.jks
    //   storePassword=…
    //   keyAlias=opluswubi
    //   keyPassword=…
    // 文件不存在就不创建这个 signingConfig，release 会自动退回 debug 签名。
    //
    // ⚠️ 这一段必须排在 `buildTypes` **之前**：下面 `signingConfig = signingConfigs.findByName(...)`
    // 是配置期立刻求值的，写在 `buildTypes` 后面就永远查不到，release 会静默退回 debug 签名。
    signingConfigs {
        val propsFile = rootProject.file("keystore.properties")
        if (propsFile.exists()) {
            val props = Properties()
            propsFile.inputStream().use { props.load(it) }
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // debug 也用同一把正式签名（`keystore.properties` 在的时候）。
            // 否则本机 debug 包（Android Debug 签名）和 CI 出的 release 包（release.jks）
            // 签名不同，互相覆盖安装会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，只能卸载重装。
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        release {
            // 正式版：混淆 + 资源收缩 + 非 debuggable。
            // 「不含 debug 信息」靠三件事：`isDebuggable = false`、不 keep `SourceFile` /
            // `LineNumberTable`、以及 `XLog.d` / `XLog.i` 用 `const val` 在编译期折掉
            //（详见 XLog.kt 与 src/debug|release 下那两份 BuildFlags.kt）。
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (signingConfigs.findByName("release") != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "⚠️ 没找到 keystore.properties，release 包用 debug 签名 —— 只能本机测试，不可发布" +
                        "（建 key：keytool -genkeypair -keystore release.jks -alias opluswubi " +
                        "-keyalg RSA -keysize 2048 -validity 10000）",
                )
                signingConfigs.getByName("debug")
            }
        }
    }

    // 不需要 BuildConfig：详细日志开关用的是按构建类型各放一份的 `const val`
    //（`src/debug|release/java/com/lookie/bilinosplash/BuildFlags.kt`），编译期就能折掉。
    //
    // 模块**没有界面**，所以也不用 Compose —— 整个模块的代码只有注入侧那一份，
    // 一个 androidx 类都不引用。

    // META-INF/xposed/* 必须原样打进 APK，不要被 resources 排除规则吃掉
    packaging {
        resources {
            pickFirsts += setOf(
                "META-INF/xposed/java_init.list",
                "META-INF/xposed/scope.list",
                "META-INF/xposed/module.prop",
            )
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // ---- 现代 Xposed API (libxposed API 102) ----------------------------------
    // 运行时由 LSPosed 框架提供，因此 compileOnly，不打包进模块 APK。
    compileOnly("io.github.libxposed:api:102.0.0")

    // 就这一条依赖。模块没有界面，所以 `io.github.libxposed:service`（给界面查启用状态用的）、
    // Compose、Miuix 一概不需要 —— 整个模块的代码只有注入侧那一份，一个 androidx 类都不引用。

    // ---- 目标 App 的库一律不声明依赖 -------------------------------------------
    // 目标 App 的类（含它用到的 androidx 组件）一律「从目标 ClassLoader 解析 Class 再反射调用」
    //（见 Reflect）。不声明依赖，正好让误加的直接引用变成编译错误，
    // 而不是运行时 NoClassDefFoundError —— 模块自己的 ClassLoader 看不到目标 App 的 dex。
}

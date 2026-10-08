package com.lookie.bilinosplash

import android.content.Context

/**
 * 注入侧的运行时环境（**注入进宿主进程的那一半代码**靠它拿上下文）。
 *
 * ## 为什么要单独收口
 *
 * 模块只在作用域进程里加载，但宿主是**多进程**的：主进程、`:web`、`:p2p`、播放器进程……
 * 同一份 Hook 装到哪个进程、以及要不要装，取决于进程名；而「拿宿主 Context」「按版本分支」
 * 这类事又到处都要用。所以进程名 / 包名 / 版本 / ClassLoader / Context 统一在这里存一份，
 * 各 Hook 只管读。
 *
 * ## 只在宿主进程里有值
 *
 * 模块没有界面，这份环境就是给注入侧的 Hook 读的；`bind()` 在 `onPackageReady` 里调，
 * 所以「没命中作用域包」时读到的是空值 —— 各 Hook 用之前必须判空。
 *
 * ## ⚠️ Context 只能从宿主那里「接」
 *
 * 注入侧拿不到 `AndroidAppHelper`（现代 API 没有），所以由 [BiliModule] 统一 Hook 宿主的
 * `Application.onCreate`，把那一刻的 Context 交进来 —— 那是每个进程里最早能拿到 Context 的地方。
 * 这一步必须**先于**任何需要 Context 的 Hook，且 [bindContext] 是幂等的。
 */
internal object HostEnv {

    @Volatile
    private var processName: String = ""

    @Volatile
    private var pkg: String = ""

    @Volatile
    private var loader: ClassLoader? = null

    @Volatile
    private var versionName: String = ""

    @Volatile
    private var versionCode: Long = 0L

    @Volatile
    private var appContext: Context? = null

    /** `onModuleLoaded` 里调：进程名要在装 Hook **之前**记下来（各 Hook 靠它决定装不装）。 */
    fun bindProcess(name: String) {
        processName = name
    }

    /** `onPackageReady` 里调：包名 / ClassLoader / 版本一次性记下。 */
    fun bind(
        pkg: String,
        loader: ClassLoader,
        versionName: String,
        versionCode: Long,
    ) {
        this.pkg = pkg
        this.loader = loader
        this.versionName = versionName
        this.versionCode = versionCode
    }

    /** 拿到宿主 Context（幂等；重复绑定以第一次为准，避免被后来的代理 Context 覆盖）。 */
    fun bindContext(context: Context) {
        if (appContext == null) appContext = context
    }

    fun context(): Context? = appContext

    /** 目标 App 的 ClassLoader —— 解析宿主类的**唯一**入口（不许直接 import 宿主类型）。 */
    fun classLoader(): ClassLoader? = loader

    fun packageName(): String = pkg

    fun process(): String = processName

    fun versionName(): String = versionName

    fun versionCode(): Long = versionCode

    /** 是不是主进程（设置页 / 启动页都在主进程）。 */
    val isMainProcess: Boolean
        get() = processName == pkg

    /** 日志用的一行摘要。 */
    fun describe(): String =
        "进程=$processName 包=$pkg 版本=$versionName/$versionCode Context=${appContext != null}"
}

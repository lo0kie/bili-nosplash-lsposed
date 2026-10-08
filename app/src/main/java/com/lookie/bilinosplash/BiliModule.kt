package com.lookie.bilinosplash

import android.content.Context
import android.content.pm.ApplicationInfo
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 模块入口（现代 libxposed API）。
 *
 * 与旧 API 的差别（见 LSPosed wiki "Develop Xposed Modules Using Modern Xposed API"）：
 *  - 入口由 `META-INF/xposed/java_init.list` 声明，不再用 `assets/xposed_init`；
 *  - 模块名/描述取自 `android:label` / `android:description`，作用域取自
 *    `META-INF/xposed/scope.list`，版本约束取自 `META-INF/xposed/module.prop`；
 *  - 入口继承 [XposedModule]，框架会自动 `attachFramework()`，
 *    模块不应在 [onModuleLoaded] 之前做初始化；
 *  - Hook 是 OkHttp 风格的拦截器链：`hook(executable).intercept { chain -> ... }`；
 *    本模块统一走 [hookGuarded]（装失败只降级、不中断同一 `install()` 里后面的 Hook）。
 *
 * 编译期依赖最新的 `io.github.libxposed:api:102.0.0`，但只用到 API 101 就有的能力
 * （`onModuleLoaded` / `onPackageReady` / interceptor chain），
 * 所以 `module.prop` 里声明 `minApiVersion=101` / `targetApiVersion=101`
 * —— LSPosed 2.0（lsposed-it-7598 起）已不再加载声明为 API 100 的模块。
 * 框架自身的版本号在日志里用 getter 读（都是 API 101 就有的）。
 */
class BiliModule : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        XLog.bind(this)
        // 进程名要在装 Hook 之前记下来：宿主是多进程的（主进程 / :web / :p2p …），
        // 后面的 Hook 靠它决定「该不该在这个进程里装」。
        HostEnv.bindProcess(param.getProcessName())
        XLog.i(
            "模块已加载 | 进程=${param.getProcessName()} | systemServer=${param.isSystemServer()} " +
                "| framework=${frameworkInfo()} | build=$BUILD_ID",
        )
    }

    /**
     * 包加载完成（AppComponentFactory 已建好 ClassLoader、Application 尚未创建）。
     * 这是安装 Hook 的标准时机：类都还没被初始化，能保证 Hook 到所有调用。
     */
    override fun onPackageReady(param: PackageReadyParam) {
        val packageName = param.packageName

        // 作用域里的第二个包：系统启动窗口（那段白屏）由 WMShell 在 SystemUI 进程里画，
        // 所以深色化的另一半装在那边，走完全独立的一条路（见 SystemSplashTheme）。
        if (packageName == SYSTEMUI_PKG) {
            HostEnv.bind(packageName, param.classLoader, "", 0L)
            XLog.i("命中系统界面：$packageName，进程=${HostEnv.process()}")
            XLog.guard("安装系统启动窗口深色化") {
                SystemSplashTheme.install(this, param.classLoader)
            }
            return
        }

        // 第一道闸：不在作用域内的包直接不碰。
        // `scope.list` 已经限制了范围，这里再判一次是为了「作用域被用户手动勾大」时不误伤。
        if (packageName != TARGET_PKG) return

        val info = param.applicationInfo
        val (versionName, versionCode) = readVersion(info)
        val loader = param.classLoader

        HostEnv.bind(packageName, loader, versionName, versionCode)
        XLog.i("命中目标应用：$packageName $versionName/$versionCode，进程=${HostEnv.process()}")

        // 尽早把宿主 Context 拿到手：后面凡是需要 Context 的 Hook 都从 HostEnv 取。
        // 类名取自 applicationInfo、按名字从宿主 ClassLoader 解析，**不直接引用**
        //（模块自己的 ClassLoader 看不到宿主的 dex）。
        XLog.guard("Hook Application.onCreate（捕获宿主 Context）") {
            val appClass = Reflect.findClass(loader, info.className)
            val onCreate = appClass?.let { Reflect.method(it, "onCreate") }
            if (onCreate == null) {
                XLog.w("没找到 ${info.className}.onCreate，Context 捕获降级为按需反射")
            } else {
                hookGuarded(onCreate) { chain ->
                    val result = chain.proceed()
                    runCatching { (chain.getThisObject() as? Context)?.let { HostEnv.bindContext(it) } }
                    result
                }
            }
        }

        // ⚠️ 必须 `catch (Throwable)`，不能用 `runCatching`：Hook 安装过程中会读宿主的静态字段，
        // 可能触发类初始化，抛的是 `ExceptionInInitializerError` / `NoClassDefFoundError` ——
        // 它们是 **`Error` 不是 `Exception`**。这类 `Error` 会直接穿过 `onPackageReady` 抛回框架，
        // 结果就是**宿主一启动就崩**。
        //
        // 模块的定位是「锦上添花」，绝不能因为自己出错就把用户的 App 弄崩。
        try {
            installHooks(loader, versionName, versionCode)
        } catch (e: Throwable) {
            XLog.e("Hook 安装失败（已忽略，不影响宿主启动）", e)
        }
    }

    /**
     * 业务接入点 —— **启动屏相关的 Hook 装在这里**。
     *
     * 写之前先把这三件事定下来：
     *  1. **按版本分支**：目标 App 的类名 / 方法名大多被混淆，每次升级都可能换；
     *     [versionName] / [versionCode] 就是给这个闸门用的，未验证过的版本区间不要乱 Hook。
     *  2. **只反射、不引用**：宿主类型一律从 [loader] 解析 `Class` 再按名字调用
     *     （见 [Reflect]）；直接 import 会在运行时 `NoClassDefFoundError`。
     *  3. **每一步都包住**：装 Hook 走 [hookGuarded]，整段走 [XLog.guard] / `catch (Throwable)`，
     *     失败只降级、绝不连累宿主启动。
     *
     * 启动屏深色化**没有**按版本号设闸门：三层各自的判据都是运行时的「身份」而不是版本号 ——
     * [SplashTheme] 问的是「这个 Activity 的主题把 `windowBackground` 指到 `layerlist_splash` 吗」、
     * 「这个 Fragment 的根 view 里有 `splash_container` 吗」，[SystemSplashTheme] 问的是
     * `SplashViewBuilder.mActivityInfo.packageName`。换版本、换入口 Activity 都不会漏，
     * 也不会误伤别的页面。
     */
    private fun installHooks(loader: ClassLoader, versionName: String, versionCode: Long) {
        XLog.i("准备安装 Hook：版本 $versionName/$versionCode，进程=${HostEnv.process()}")
        // ColorOS 的启动画面不止一条路：除 WMShell 画的那份，ROM 在 system_server 里还有一套
        // （oplus-services.jar: OplusStartingSurfaceController / OplusStartingSurfaceControllerBase /
        //  QuickStartUtils / OplusCaptureFile / StartingSurfacePreviewPolicy）。它会按主题的
        //  windowBackground 铺面、并把结果**缓存/拍成快照**（进程内按 uiMode 缓存 +
        //  getCapBitmapFileForStartingSurface 落盘）。本模块作用域只有 B 站 + SystemUI，盖不到那个进程，
        //  所以那一层只能靠「启动屏改深后重启一次，让缓存/快照重新生成」来生效。
        XLog.i(
            "ColorOS 启动画面：system_server 里还有一套（OplusStartingSurfaceController/QuickStartUtils），" +
                "本模块盖不到；它会缓存/快照启动画面，改完启动屏需重启一次才会重新生成",
        )
        SplashTheme.install(this, loader)
    }

    /**
     * 构建标识：**每改一版就手动 +1**。
     *
     * 日志里有一行自证的版本号，排查「用户到底装的哪一版」时一眼就能定，
     * 不用反复来回猜。
     */
    private companion object {
        const val BUILD_ID = "2026-10-09-0530"

        /** 目标应用包名（与 `META-INF/xposed/scope.list` 保持一致）。 */
        const val TARGET_PKG = "tv.danmaku.bili"

        /** 系统界面包名：启动窗口由它画（见 [SystemSplashTheme]）。 */
        const val SYSTEMUI_PKG = "com.android.systemui"
    }

    /**
     * 读目标包的版本号。
     *
     * 注意：`ApplicationInfo.versionName / versionCode` 在 API 35 的编译期 stub 里已经被移除
     * （编译会直接报 unresolved），但设备运行时字段依然存在，所以这里用反射读，
     * 既保证能编译，又保证真机拿到真实版本。
     */
    private fun readVersion(info: ApplicationInfo): Pair<String, Long> {
        val name = Reflect.fieldValue(info, info.javaClass, "versionName") as? String ?: ""
        val code = (Reflect.fieldValue(info, info.javaClass, "longVersionCode") as? Number)?.toLong()
            ?: (Reflect.fieldValue(info, info.javaClass, "versionCode") as? Number)?.toLong()
            ?: 0L
        return name to code
    }

    /** 框架名与版本。 */
    private fun frameworkInfo(): String =
        "${getFrameworkName()} ${getFrameworkVersion()} (api=${getApiVersion()})"
}

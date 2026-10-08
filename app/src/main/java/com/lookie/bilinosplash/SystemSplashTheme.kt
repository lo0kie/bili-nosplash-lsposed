package com.lookie.bilinosplash

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import io.github.libxposed.api.XposedModule
import java.util.Collections
import java.util.WeakHashMap

/**
 * 系统启动窗口（starting window）跟随深色模式 —— 注入 `com.android.systemui` 的那一半。
 *
 * ## 为什么需要它
 *
 * 冷启动 B 站时用户看到的那段白屏**不是** App 自己的窗口背景，而是系统画的启动窗口
 * （`Splash Screen tv.danmaku.bili`，`ty=APPLICATION_STARTING`）：它在 `am start` 后约
 * 20ms 上屏、App 首帧出现时撤掉，整段约 1s。App 侧那套（见 [SplashTheme]）改的是
 * `Activity.getWindow()` 的背景，真机探针实测「一帧都没露过面」，所以只靠它消不掉白屏。
 *
 * 本机（ColorOS / PLK110 / Android 17）这段由 WMShell 绘制，而 WMShell 跑在
 * `com.android.systemui` 进程里 —— 所以作用域里加了第二个包，这份代码在那里装 Hook。
 *
 * ## 白屏到底是哪一份 drawable
 *
 * 抓帧 + 运行时视图树探针把结论钉死了：屏幕上那层白的来源自始至终只有一份 ——
 * B 站启动页主题的 `android:windowBackground`，也就是
 * `tv.danmaku.bili:drawable/layerlist_splash`（白底 + 粉色 bilibili 字标，
 * **没有 `-night` 变体**）。探针 dump 出来的 `SplashScreenView` 背景正是它的结构：
 *
 * ```
 * SplashScreenView 1272x2772 bg=LayerDrawable[ColorDrawable(color=ffffffff), BitmapDrawable]
 * ```
 *
 * 麻烦在于系统会**从三个不同的入口**分别把这份 drawable 取走、铺上去，谁最后写谁说了算：
 *
 * ```
 * makeSplashScreenContentView
 *   ├─ SplashViewBuilder.overlayDrawable(peekLegacySplashscreenContent(...))   ← 入口 1
 *   ├─ SplashViewBuilder.createIconDrawable(mTmpAttrs.mSplashScreenIcon, …)    ← 入口 2（legacy 下整屏）
 *   └─ SplashscreenWindowCreator
 *        └─ OplusShellStartingWindowManager.handleSplashScreenView
 *             ├─ SplashScreenWindowAttrs.getWindowBgImage(ctx)                 ← 入口 3
 *             └─ setContentViewBackground(view, 上面那份)                       ← 覆盖前两个的结果
 * ```
 *
 * 前两个入口（改参数）单独试过、三个一起也试过：日志都打出了「已换成深色版」，
 * 但同一毫秒 dump 出来的背景依旧是 `ffffffff` —— 因为入口 3 在最后又把原始那份设了回去。
 *
 * ## 做法：在「取 drawable」这一层收口
 *
 * 与其去堵三个入口（数量还会随 ROM 变），不如钉住它们的共同上游：
 * **`Resources#getDrawableForDensity(int, int, Theme)`**。`getDrawable(int)` 与
 * `getDrawable(int, Theme)` 都收敛到它，上面三个入口也全都走它。命中 `layerlist_splash`
 * 时就地换成深色版，于是**不管谁最后写、写几次，写上去的都是深色**。
 *
 * 判据用资源名（`tv.danmaku.bili:drawable/layerlist_splash`）而不是资源 id：id 是
 * per-package 的，拿 SystemUI 自己的 id 去比会误伤；资源名里带包名，天然只命中 B 站。
 * 为了不给 SystemUI 的每次取图都加一次资源名查询，按 `Resources` 实例缓存「这个 id 是不是它」，
 * 非命中的 id 进否定缓存 —— 稳态下只有一次哈希查找。
 *
 * `SplashViewBuilder` 上的三个 Hook 保留着当兜底：它们覆盖「drawable 不经 Resources」的路径
 * （个别 ROM 会自己构造），多一层保险，装不上也只是不生效。
 *
 * ## 边界与风险
 *
 *  - 深色版按 [SplashTheme.darken] 重建（第一层颜色换掉、logo 层照搬），找不到颜色层就
 *    退化成纯色 —— 最差是丢 logo，不会不生效。
 *  - 判据取这份 `Resources` 的 configuration，拿不到就退回系统 configuration，与 [SplashTheme] 同源。
 *  - 注入的是 **SystemUI 进程**，它崩了会连状态栏 / 桌面一起带走。所以：**hooker 里任何一步
 *    失败都必须原样 `chain.proceed()`**，绝不抛出去，也绝不改变别的资源的行为。
 */
internal object SystemSplashTheme {

    /** 只对 B 站生效。 */
    private const val TARGET_PKG = "tv.danmaku.bili"

    /**
     * 启动屏主题里 `windowBackground` 指的 drawable，**带包名前缀**。
     *
     * SystemUI 里能同时看到多个包的资源，所以判据必须是带包名的全名。
     */
    private val SPLASH_RES_NAME = "$TARGET_PKG:drawable/${SplashTheme.SPLASH_DRAWABLE}"

    /**
     * 启动屏构造器。
     *
     * 类名**没有混淆**（dex 里就是这个名字），但也别指望它跨 ROM 稳定：换 ROM / 换 Android 版本
     * 时 WMShell 的实现会变。查不到就只打一条日志跳过，不影响 SystemUI 本身。
     */
    private const val BUILDER_CLASS =
        "com.android.wm.shell.startingsurface.SplashscreenContentDrawer\$SplashViewBuilder"

    private const val METHOD_SET_WINDOW_BG_COLOR = "setWindowBGColor"

    private const val METHOD_OVERLAY = "overlayDrawable"

    /**
     * legacy splash 下真正的「画面」入口：`createIconDrawable(icon, isLegacy, loadInDetail)`。
     *
     * 它在 `build()` 里被调用，`icon` 就是 App 主题的 `windowBackground`；整屏那份白底 + 字标
     * 是它包出来的 `ImmobileIconDrawable` 画的，压在底色与叠层之上。
     */
    private const val METHOD_CREATE_ICON = "createIconDrawable"

    private const val METHOD_GET_DRAWABLE = "getDrawableForDensity"

    /**
     * 启动窗口属性对象（反汇编本机 SystemUI 得到，未混淆）。
     *
     * 它的 `getWindowBgImage(Context)` 会**缓存**取到的 drawable，是「白底最后又被写回去」的那一环。
     */
    private const val ATTRS_CLASS =
        "com.android.wm.shell.startingsurface.SplashscreenContentDrawer\$SplashScreenWindowAttrs"

    private const val METHOD_GET_WINDOW_BG = "getWindowBgImage"

    private const val FIELD_ACTIVITY_INFO = "mActivityInfo"

    private const val FIELD_CONTEXT = "mContext"

    /** 「读不到 mActivityInfo」只报一次，不然每个 App 每次启动都要刷一条。 */
    @Volatile
    private var warnedNoActivityInfo = false

    /** 每个 `Resources` 实例一份「哪些 id 是 / 不是 `layerlist_splash`」的判定结果。 */
    private class ResGuard {

        /** 已确认是它的 id；0 表示还没找到。 */
        @Volatile
        var splashId = 0

        /** 已确认**不是**它的 id，避免每次都去查一遍资源名。 */
        val others: MutableSet<Int> = HashSet()
    }

    /**
     * ⚠️ 用 `WeakHashMap`：`Resources` 的存活周期跟包走，系统换语言 / 换密度时会重建
     * `Resources` 对象，键弱引用才不会把旧对象一直吊住。
     */
    private val resGuards: MutableMap<Resources, ResGuard> =
        Collections.synchronizedMap(WeakHashMap())

    /**
     * 装上 Hook。
     *
     * ⚠️ 注入的是 **SystemUI 进程**，不是目标 App：类从 SystemUI 的 ClassLoader 解析
     *（`onPackageReady` 递进来的那个），失败一律降级。
     */
    fun install(module: XposedModule, loader: ClassLoader) {
        installDrawableGuard(module)
        // View 层收口：启动窗口最终也是某个 view 的 background，这里再兜一次（并留日志探针）。
        SplashTheme.installViewBackgroundHook(module, "systemui")
        installWindowBgImageHook(module, loader)
        installBuilderHooks(module, loader)
    }

    /**
     * 再收一层：`SplashScreenWindowAttrs#getWindowBgImage(Context)`。
     *
     * 反汇编本机 SystemUI 的 `classes3.dex` 看到它的实现是：
     *
     * ```
     * Resources res = ctx.getResources();
     * int uiMode = res.getConfiguration().uiMode;
     * if (mWindowBgImage != null && uiMode == this.uiMode) return mWindowBgImage;   // ← 缓存命中
     * if (mWindowBgResId == 0) return mWindowBgImage;
     * mWindowBgImage = ctx.getDrawable(mWindowBgResId);   // Context.getDrawable → getDrawableForDensity
     * this.uiMode = uiMode;
     * return mWindowBgImage;
     * ```
     *
     * 关键是**它把结果缓存在 `mWindowBgImage` 里**：只要 `uiMode` 没变，之后每次启动窗口都直接
     * 复用那份缓存 —— 哪怕那份是白的。所以在它返回处再兜一次，把「白底 layer-list」换成深色版，
     * 缓存里存的就也是深色。
     */
    private fun installWindowBgImageHook(module: XposedModule, loader: ClassLoader) {
        val cls = Reflect.findClass(loader, ATTRS_CLASS)
        val method = if (cls == null) null else Reflect.method(cls, METHOD_GET_WINDOW_BG, Context::class.java)
        if (method == null) {
            XLog.w("没找到 $ATTRS_CLASS#$METHOD_GET_WINDOW_BG，启动窗口背景缓存那一层跳过")
            return
        }
        module.hookGuarded(method) { chain ->
            val result = chain.proceed()
            val ctx = chain.getArg(0) as? Context
            val dark = guarded {
                SplashTheme.darkenWhiteSplash(
                    result as? Drawable,
                    ctx?.resources,
                    "systemui/attrs",
                    "getWindowBgImage 缓存",
                )
            }
            if (dark == null) result else dark
        }
        XLog.i("启动窗口深色化已就绪：$ATTRS_CLASS#$METHOD_GET_WINDOW_BG 收口")
    }

    /**
     * 收口那一层：`layerlist_splash` 不管被谁取走，在这里换成深色版。
     *
     * `Resources` 是引导类加载器里的框架类，直接引用即可（不需要走宿主 ClassLoader）。
     */
    private fun installDrawableGuard(module: XposedModule) {
        val getDrawable = Reflect.method(
            Resources::class.java,
            METHOD_GET_DRAWABLE,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Resources.Theme::class.java,
        )
        if (getDrawable == null) {
            XLog.w("没找到 Resources#$METHOD_GET_DRAWABLE，系统启动窗口深色化跳过")
            return
        }
        module.hookGuarded(getDrawable) { chain ->
            val result = chain.proceed()
            // 三步判断都在拿到结果之后做，为的是**只 proceed 一次**（重复 proceed 是非法的）。
            val res = chain.getThisObject() as? Resources
            val id = chain.getArg(0) as? Int
            if (res == null || id == null || result !is LayerDrawable || !isSplashRes(res, id)) {
                return@hookGuarded result
            }
            val dark = guarded { darkSplash(res, result) }
            if (dark == null) {
                result
            } else {
                XLog.i("系统启动窗口背景已换成深色版：$SPLASH_RES_NAME（id=0x${hex(id)}）")
                dark
            }
        }
        XLog.i("系统启动窗口深色化已就绪：Resources#$METHOD_GET_DRAWABLE 收口")
    }

    /**
     * 兜底：`SplashViewBuilder` 上的三个入参。
     *
     * 三个 Hook 各自独立 —— 一个装不上不影响另外两个。参数都是基本类型 / 具体类型，
     * 签名精确匹配，免得哪天出现同名重载时挂到别的方法上。
     */
    private fun installBuilderHooks(module: XposedModule, loader: ClassLoader) {
        val builder = Reflect.findClass(loader, BUILDER_CLASS)
        if (builder == null) {
            XLog.w("没找到 $BUILDER_CLASS，系统启动窗口构造阶段的兜底 Hook 跳过（收口那一层照常生效）")
            return
        }
        val colorMethod = Reflect.method(
            builder,
            METHOD_SET_WINDOW_BG_COLOR,
            Int::class.javaPrimitiveType!!,
        )
        val overlayMethod = Reflect.method(builder, METHOD_OVERLAY, Drawable::class.java)
        val iconMethod = Reflect.method(
            builder,
            METHOD_CREATE_ICON,
            Drawable::class.java,
            Boolean::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
        )
        if (colorMethod == null && overlayMethod == null && iconMethod == null) {
            XLog.w("没找到 $BUILDER_CLASS 上的底色 / 叠层 / 图标方法，兜底 Hook 跳过")
            return
        }

        // 底色：`setWindowBGColor` 写的是 `mThemeColor`，最终落到
        // `SplashScreenView.Builder.setBackgroundColor(mThemeColor)`。
        if (colorMethod != null) {
            module.hookGuarded(colorMethod) { chain ->
                // 目标方法只有一个 int 参数，取不到就说明签名变了 —— 原样放行。
                val original = guarded { chain.getArg(0) as? Int }
                val dark = if (original == null) null else guarded { darkColor(chain.getThisObject()) }
                if (original == null || dark == null || dark == original) {
                    chain.proceed()
                } else {
                    // `getArgs()` 返回的是不可变列表，改参数只能走 `proceed(Object[])`
                    //（`proceedWith(Object)` 换的是 this，不是参数）。
                    XLog.i("系统启动窗口底色：0x${hex(original)} → 0x${hex(dark)}")
                    chain.proceed(arrayOf<Any>(dark))
                }
            }
        }

        // 叠层：style 4 时这里就是 App 主题的 `windowBackground`（白底 + logo），
        // 它会铺满整屏盖住底色，必须一起换成深色版。
        if (overlayMethod != null) {
            module.hookGuarded(overlayMethod) { chain ->
                val base = chain.getArg(0) as? Drawable
                val dark = if (base == null) null else guarded { darkOverlay(chain.getThisObject(), base) }
                if (base == null || dark == null) {
                    chain.proceed()
                } else {
                    XLog.i("系统启动窗口叠层已换成深色版：${base.javaClass.simpleName}")
                    chain.proceed(arrayOf<Any>(dark))
                }
            }
        }

        // 图标：legacy splash 下这一份才是铺满整屏的那层（`build()` 拿 App 主题的
        // `windowBackground` 当图标）。前两个入参换了它照样盖着，必须一起换。
        if (iconMethod != null) {
            module.hookGuarded(iconMethod) { chain ->
                val base = chain.getArg(0) as? Drawable
                val dark = if (base == null) null else guarded { darkIcon(chain.getThisObject(), base) }
                if (base == null || dark == null) {
                    chain.proceed()
                } else {
                    XLog.i("系统启动窗口图标已换成深色版：${base.javaClass.simpleName}")
                    chain.proceed(arrayOf<Any>(dark, chain.getArg(1), chain.getArg(2)))
                }
            }
        }

        val installed = buildList {
            if (colorMethod != null) add(METHOD_SET_WINDOW_BG_COLOR)
            if (overlayMethod != null) add(METHOD_OVERLAY)
            if (iconMethod != null) add(METHOD_CREATE_ICON)
        }
        XLog.i("系统启动窗口深色化兜底已就绪：$BUILDER_CLASS#${installed.joinToString(" / ")}")
    }

    /**
     * 这个 id 是不是 `layerlist_splash`。
     *
     * 稳态下只走一次哈希查找：第一次遇到某个 id 时查一次资源名，命中就记住 id，
     * 不命中就进否定缓存 —— 之后同样的 id 不再查。
     */
    private fun isSplashRes(res: Resources, id: Int): Boolean {
        val guard = resGuards[res] ?: ResGuard().also { resGuards[res] = it }
        if (guard.splashId == id) return true
        if (guard.others.contains(id)) return false
        val name = runCatching { res.getResourceName(id) }.getOrNull()
        return if (name == SPLASH_RES_NAME) {
            guard.splashId = id
            true
        } else {
            guard.others.add(id)
            false
        }
    }

    /** 把 `layerlist_splash` 换成深色版；不该动（不是深色）时返回 null。 */
    private fun darkSplash(res: Resources, base: LayerDrawable): Drawable? {
        if (!isNight(res) && !isNight(Resources.getSystem())) return null
        return SplashTheme.darken(base, res, SplashTheme.FALLBACK_DARK)
    }

    /**
     * 把「图标」drawable 换成深色版。
     *
     * 只在它是 `LayerDrawable`（= App 主题的 `windowBackground`，那种 `<layer-list>`）时动手：
     * 非 legacy 的 ROM 上这个方法拿到的是 App 图标，换成纯色会把图标变成一块色块，宁可不动。
     */
    private fun darkIcon(builder: Any?, base: Drawable): Drawable? {
        if (builder == null || base !is LayerDrawable) return null
        val dark = darkColor(builder) ?: return null
        val res = resources(builder, builder.javaClass) ?: return null
        return SplashTheme.darken(base, res, dark)
    }

    /**
     * 把叠层 drawable 换成深色版：与 [SplashTheme.darken] 同一套做法
     *（第一层颜色换掉、其余层照搬），失败就退化成纯色，最差是丢 logo。
     */
    private fun darkOverlay(builder: Any?, base: Drawable): Drawable? {
        if (builder == null) return null
        val dark = darkColor(builder) ?: return null
        val res = resources(builder, builder.javaClass)
        return if (res == null) base else SplashTheme.darken(base, res, dark)
    }

    /**
     * 该用哪个深色；返回 null 表示「这个不是 B 站 / 不是深色 / 拿不到信息，别动」。
     *
     * 判据是 `SplashViewBuilder.mActivityInfo.packageName`，不是 `StartingWindowInfo` ——
     * 后者在这一环拿不到，而 builder 上正好有。
     */
    private fun darkColor(builder: Any?): Int? {
        if (builder == null) return null
        val cls = builder.javaClass

        val info = Reflect.fieldValue(builder, cls, FIELD_ACTIVITY_INFO) as? ActivityInfo
        if (info == null) {
            if (!warnedNoActivityInfo) {
                warnedNoActivityInfo = true
                XLog.w("读不到 $BUILDER_CLASS#$FIELD_ACTIVITY_INFO，系统启动窗口深色化降级为不动")
            }
            return null
        }
        // 不是 B 站就静默放行 —— 这是常态，不能刷日志。
        if (info.packageName != TARGET_PKG) return null

        // 拿不到 App 的 Resources（字段被改名的 ROM）：退回固定底色，不再去猜 App 主题，
        // 避免拿系统 Resources 解 App 的 theme id 解出个乱七八糟的颜色。
        val res = resources(builder, cls) ?: return if (isNight(Resources.getSystem())) {
            SplashTheme.FALLBACK_DARK
        } else {
            null
        }
        if (!isNight(res)) return null

        // 与 App 侧同源：问 App 自己的主题要深色窗口底色（`@color/Ga1` 的 night 值），
        // 换版本时只要那条父链还在，颜色就自动跟着走。
        return SplashTheme.nightWindowBackground(res, info.applicationInfo?.theme ?: 0)
            ?: SplashTheme.FALLBACK_DARK
    }

    private fun resources(builder: Any, cls: Class<*>): Resources? =
        (Reflect.fieldValue(builder, cls, FIELD_CONTEXT) as? Context)?.resources

    private fun isNight(res: Resources): Boolean =
        (res.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun hex(v: Int): String = Integer.toHexString(v)

    /** 与 [XLog.guard] 同义，只是要一个返回值给调用方用。 */
    private inline fun <T> guarded(block: () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        XLog.w("系统启动窗口深色化失败（已忽略，启动屏保持系统原样）", e)
        null
    }
}

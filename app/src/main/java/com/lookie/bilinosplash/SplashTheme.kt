package com.lookie.bilinosplash

import android.app.Activity
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.Window
import io.github.libxposed.api.XposedModule

/**
 * 启动屏跟随深色模式（**App 侧**）。
 *
 * 系统画的那段启动窗口在 [SystemSplashTheme]（作用域里第二个包 `com.android.systemui`），
 * 两份代码用同一套「问 App 主题要深色窗口底色」的逻辑，底色因此永远一致。
 *
 * ## 问题在哪
 *
 * 启动页（`tv.danmaku.bili.MainActivityV2` 等 4 个组件）用的主题是
 * `AppTheme.NoActionBar.Home`，它把 `android:windowBackground` 硬编码成
 * `@drawable/layerlist_splash` —— 一个 layer-list：第一层是纯白 `<color>`，第二层是底部
 * 居中、距底 16dp 的 `ic_logo_default`（粉色 bilibili 字标）。这个 drawable **没有
 * `-night` 变体**，主题本身也只有 `()` / `(v27)` 两套、没有 `(night)`。
 *
 * 于是哪怕系统在深色模式、B 站自己的界面早就是深色（内容底色 `#0a0b0c`，正是
 * `@color/Ga1` 的 night 值），冷启动那一段依旧是整屏白。
 *
 * ## 做法
 *
 * Hook `Activity.onCreate`，在宿主自己的 `onCreate`（它才会走到 `setContentView` →
 * `PhoneWindow.generateLayout`）**之前**把窗口背景换掉：只要这个 Activity 的主题把
 * `windowBackground` 指到 `layerlist_splash`、且当前是深色配置，就换成「同一个
 * layer-list，只把第一层颜色替换成 App 的深色窗口底色」——logo、gravity、inset 全部照搬，
 * 视觉上就是 B 站本该有的深色启动屏。
 *
 * ## 为什么设在 `onCreate` 之前
 *
 * `Window.setBackgroundDrawable` 的既定用法就是「在 `setContentView` 之前调用」：
 * `PhoneWindow.generateLayout` 只在 `mBackgroundDrawable == null` 时才回头去主题里取背景，
 * 提前设好就不会被主题值盖掉。而 `onCreate` 进入时 `Activity.attach()` 已经建好 PhoneWindow，
 * `getWindow()` 可用；此时离第一帧绘制还隔着 `onResume`，来得及。
 *
 * ## 判据取 Activity 自己的配置，而不是系统全局
 *
 * B 站是 `Theme.AppCompat.DayNight`，`AppCompatDelegate` 的夜间模式覆盖会落到
 * `Activity.getResources()` 的 configuration 上。用它当判据，启动屏才会和 B 站**当前实际**
 * 的深浅色一致（B 站默认「跟随系统」，此时两者等价）；若 B 站被设成浅色，启动屏也保持白，
 * 不会出现「界面浅色、启动屏深色」的割裂。
 *
 * ## 这一段改的是哪一层（真机实测）
 *
 * 冷启动时系统会先画一个 `APPLICATION_STARTING` 的启动窗口（`Splash Screen <包名>`），
 * 内容同样取自这个 `windowBackground` —— 也就是说**用户看到的白屏几乎全是它**：
 *
 * ```
 * +0.00s  addStartingWindow        系统建启动窗口
 * +0.02s  HAS_DRAWN                启动窗口上屏（内容 = layerlist_splash）
 * +0.98s  Finish StartingWindow    App 首帧出现，启动窗口撤掉   ← 白屏到此为止
 * ```
 *
 * 而 App 自己那个窗口的背景**从来没有露过面**：B 站的 `MainActivityV2` 在 `onCreate`
 * 里并不 `setContentView`（实测 `peekDecorView()` 为 null），内容一画上来就把窗口背景盖住了。
 * 把本 Hook 的底色临时改成洋红做探针、冷启动连拍 24 帧，**一帧洋红都没有** —— 印证了这点。
 *
 * 所以本 Hook 单独用**消不掉**白屏，它保的是另一半：App 窗口从 `Finish StartingWindow`
 * 到首帧之间那一两帧的过渡底色（实测是 `#707071` 一类的中间色），以及在启动窗口机制
 * 不同的 ROM 上「App 窗口背景会露出来」的情形。真正那段白屏由 [SystemSplashTheme] 处理。
 *
 * ## 还有第三层：App 自己的品牌启动页
 *
 * 系统启动窗口撤掉之后、首页内容画出来之前，B 站还会显示自己的**品牌启动页**
 * （`tv.danmaku.bili.ui.splash.brand.ui.BaseBrandSplashFragment`）—— 它的**根 view
 * 就是** `R.id.splash_container`，而 `android:background` 直接写着 `@color/white`，
 * 所以这一段也是白的。这一层既不属于系统启动窗口，也不属于 Activity 的窗口背景，
 * 前两个 Hook 都管不到。
 *
 * 两版对照（8.34.0 / 9.13.0）连布局都是同一份（`res/W0I.xml`），结构一模一样：
 *
 * ```
 * ConstraintLayout  android:id="@+id/splash_container"
 *                   android:background="@color/white"      ← 白底在这一层，写死的
 *   ├─ BiliImageView                                       ← 品牌图（默认 GONE）
 *   └─ LinearLayout → ...                                  ← 顶部 logo / 底部角标
 * ```
 *
 * 做法（与「哔哩漫游」的 `auto_dark_splash` 同一处、同一手法，反汇编 `me.iacn.biliroaming`
 * 的 `biliroaming.Ah#afterHookedMethod` 确认）：
 *
 * ```
 * BaseBrandSplashFragment.onViewCreated(view, savedInstanceState)
 *   └─ view.findViewById(R.id.splash_container)   ← 根 view 自己（findViewById 先查自身）
 *        └─ setBackgroundColor(深色)
 * ```
 *
 * 三个要点：
 *
 *  - **在宿主自己那段之后动手**：白底是布局里写死的，宿主那一段不会改它，但「后设」永远比「先设」稳。
 *  - **判据是「能不能 `findViewById` 到 `splash_container`」**，不是 Fragment 类名。
 *    这样即使类名哪天变了、Hook 落到 [FRAGMENT_BASE] 上，也不会误伤别的页面 ——
 *    找不到这个 id 就说明这个 Fragment 不是启动页，原样不动。
 *  - **id 用名字解析，不硬编码**：两版实测 id 不同（8.34.0 = `0x7f093d58`，9.13.0 = `0x7f0941e9`）。
 *  - **颜色仍走 [nightWindowBackground]**：三层共用一个底色，才不会出现「系统启动窗口一个深色、
 *    品牌启动页另一个深色」的接缝。
 *
 * ## 第四层：inflate 兜底 + 两个「同类白底」页面
 *
 * **收尾那条白到底是谁**：把全 APK 的 dex 按资源 id 扫了一遍，`layerlist_splash` 的 id 只在
 * 一份**数据表**（id 数组）里出现、没有任何代码引用它 —— 也就是说它只会经主题的
 * `windowBackground` 出现；再把所有引用 `ic_logo_default`（粉色字标）的 XML 挑出来，只有三份：
 * `drawable/layerlist_splash`、`layout/bili_app_fragment_splash`（广告启动页）、
 * `layout/bili_layout_fragment_splash_mod_download`（**启动页资源下载页**）。
 *
 * 最后那份就是白条的来源：
 *
 * ```
 * layout/bili_layout_fragment_splash_mod_download（res/bub.xml）
 *   LinearLayout  android:background="@color/white"    ← 满屏白底，而且**根上没有 id**
 *     ├─ FrameLayout (weight=7) → ImageView(ic_splash_default) + 下载进度
 *     └─ ImageView  id=logo  src=@drawable/ic_logo_default  weight=1  scaleType=CENTER
 * ```
 *
 * 它满屏白、底部居中摆着粉色字标；实测那条白条（底部 79px、满宽、白底、字标底部露一截）
 * 与它的几何完全吻合（按 560dpi / 720:1272 折算，预测字标底边 ≈1498、实测 ≈1504）。
 * 它只在「启动页资源需要下载」时出现，所以是偶发；系统启动窗口 / 品牌启动页 / 广告页那几层
 * 都改深之后它照样白，正是因为它既不属主题、也没有我们认得的 id。
 *
 * 做法：钩 `android.view.View#onFinishInflate`。每个被 inflate 出来的布局**根 view** 都会走一次，
 * 于是「谁 inflate 的、inflate 几次」都不影响判据：
 *
 *  - 根 view 的 id 是 `splash_container` → 品牌启动页（补第三层的漏：被别的路径 inflate 的那种）；
 *  - 根 view 的类是 `SplashContainerView` → 广告启动页，把它的根和 `logo_layout` 一起换深色；
 *  - 根 view 是**纯白底**且同时含 `download_progress` + `logo` → 启动页资源下载页，整页换深色。
 *
 * ## 为什么以前没发现（复现方式）
 *
 * **这一层只在「从桌面图标启动」时才出现**。用 `am start` 冷启动 B 站时，
 * 系统日志里 `mCallingUid` 是 `2000 (com.android.shell)`，B 站直接跳过品牌启动页 ——
 * 所以 `am start` 连测十几次都「没有白屏」，问题根本不在场。改成点桌面图标启动
 *（`mCallingUid=10239 (com.android.launcher)`）才复现。
 *
 * 实测（8.34.0 / 本机 ColorOS，桌面图标冷启动，60fps 抽帧、逐帧算平均亮度）：
 *
 * ```
 * 关掉本 Hook：  亮度 11 → 25 → 233（连续 41 帧 ≈ 0.68s 纯白）→ 42 → 首页
 * 开着本 Hook：  亮度 11 → 30（品牌启动页深色底 + 字标）      → 42 → 首页
 * ```
 *
 * 那 41 帧白屏就是用户看到的「深色启动屏之后、首页出来之前」那一段。
 */
internal object SplashTheme {

    /**
     * 启动页主题里 `windowBackground` 指的那个 drawable 名。
     *
     * `internal` 是给 [SystemSplashTheme] 用的：系统侧那一层要靠「带包名的资源全名」
     * 认出这份 drawable（见 `SystemSplashTheme.SPLASH_RES_NAME`），名字只能有一处定义。
     */
    internal const val SPLASH_DRAWABLE = "layerlist_splash"

    /** 名字查不到时的兜底资源 id（8.34.0 实测：`0x7f081884`）。 */
    private const val FALLBACK_SPLASH_ID = 0x7f081884

    /** 连资源都取不到时的兜底底色（`@color/Ga1` 的 night 值，8.34.0）。[SystemSplashTheme] 也用它。 */
    internal const val FALLBACK_DARK = 0xFF0A0B0C.toInt()

    /**
     * 品牌启动页那个白底容器的资源名。
     *
     * 判据只认这个名字：`android:background="@color/white"` 写死在布局里，没有任何
     * 夜间变体，所以「谁是启动页」这件事只能靠「谁身上有这个 id」来认。
     */
    private const val SPLASH_CONTAINER_ID = "splash_container"

    /**
     * 品牌启动页的 Fragment 类名。
     *
     * **没被混淆**，8.34.0（`classes21.dex`）与 9.13.0（`classes25.dex`）同名，
     * 且都自己声明了 `onViewCreated(View, Bundle)`（内部 `invoke-super` 到
     * `com.bilibili.lib.ui.BaseFragment`）—— 所以必须挂它本身，挂 Fragment 基类抓不到。
     */
    private const val BRAND_SPLASH_CLASS =
        "tv.danmaku.bili.ui.splash.brand.ui.BaseBrandSplashFragment"

    /**
     * 兜底基类：品牌启动页的父类。
     *
     * 只在 [BRAND_SPLASH_CLASS] 解析不到时才用（比如哪天它被混淆）。挂这一层会**对每个
     * Fragment** 都触发一次，所以 [darkenBrandSplash] 里那道「`findViewById` 不到
     * `splash_container` 就原样不动」的判据是必须的，不是可选的。
     */
    private const val FRAGMENT_BASE = "com.bilibili.lib.ui.BaseFragment"

    /**
     * 广告启动页（`layout/bili_app_fragment_splash`，`res/0Kt.xml`）根 view 的类名。
     *
     * 未混淆。判据用它而不是 id：根 view 的 id 名是 `root_container`（`0x7f0932a1`），
     * 这个名字在别的布局里也被用，拿它 `findViewById` 会误伤。
     */
    private const val AD_SPLASH_ROOT = "tv.danmaku.bili.ui.splash.widget.SplashContainerView"

    /**
     * 广告启动页底部那条「满宽白底 + 居中粉色字标」的容器 id 名。
     *
     * 布局里 `android:background="@android:color/white"`，子 view 是 `BiliImageView`（id `logo`），
     * `failureImage` 指着 `@drawable/ic_logo_default`。广告取不到时就退化成白条 + 粉色字标。
     */
    private const val AD_LOGO_BAR_ID = "logo_layout"

    /**
     * 「启动页资源下载页」（`layout/bili_layout_fragment_splash_mod_download`，`res/bub.xml`）
     * 的两个子 id。
     *
     * 这个页面的根是**满屏白底**、且根上没有 id，所以只能靠这两个子 id 一起认：
     * `download_progress`（进度条）+ `logo`（`src=@drawable/ic_logo_default`，粉色字标）。
     * 单独一个都不够：广告启动页也有 `logo`，别处也可能有 `download_progress`。
     */
    private const val DOWNLOAD_PROGRESS_ID = "download_progress"

    private const val DOWNLOAD_LOGO_ID = "logo"

    private const val METHOD_ON_CREATE = "onCreate"

    private const val METHOD_ON_VIEW_CREATED = "onViewCreated"

    /** `Resources#getDrawableForDensity(int, int, Theme)` —— App 侧的资源收口。 */
    private const val METHOD_GET_DRAWABLE = "getDrawableForDensity"

    /** `com.android.internal.policy.PhoneWindow#setContentView(View)` —— 窗口背景复核的挂点。 */
    private const val PHONE_WINDOW_CLASS = "com.android.internal.policy.PhoneWindow"

    private const val METHOD_SET_CONTENT_VIEW = "setContentView"

    private const val METHOD_ON_FINISH_INFLATE = "onFinishInflate"

    /** `layerlist_splash` 的资源 id，进程内解析一次（`getIdentifier` 不算便宜）。 */
    @Volatile
    private var splashId = 0

    /** `splash_container` 的资源 id，进程内解析一次。 */
    @Volatile
    private var containerId = 0

    /** `logo_layout`（广告启动页底部白条）的资源 id，进程内解析一次。 */
    @Volatile
    private var adLogoBarId = 0

    /**
     * 广告启动页根 view 的 `Class`，进程内解析一次（`null` = 这个版本没有，直接跳过那一支）。
     *
     * 用 `Class` 而不是类名字符串比较：`onFinishInflate` 每个布局根都会调一次，
     * 这条是热路径，`isInstance` 只走一遍父类链，比每次 `javaClass.name` 拼字符串便宜。
     */
    @Volatile
    private var adRootClass: Class<*>? = null

    /** 上面那次解析是否已经做过（`null` 也要缓存，否则每个布局根都要重新 `Class.forName`）。 */
    @Volatile
    private var adRootClassResolved = false

    /** inflate 兜底那一层的一次性初始化（`splash_container` / `logo_layout` / 广告页根类）是否已做。 */
    @Volatile
    private var idsReady = false

    /** 「资源里没有 `splash_container`」只报一次，不然每个 Fragment 都要刷一条。 */
    @Volatile
    private var warnedNoContainerId = false

    /** 「资源里没有 `logo_layout`」只报一次（同上，这条在热路径上）。 */
    @Volatile
    private var warnedNoAdLogoBarId = false

    /** 「资源里按名字找不到 `layerlist_splash`」只报一次（App 侧资源收口用）。 */
    @Volatile
    private var splashIdMissing = false

    /** 「启动页资源下载页」那两个子 id（`download_progress` / `logo`），进程内解析一次。 */
    @Volatile
    private var downloadProgressId = 0

    @Volatile
    private var downloadLogoId = 0

    /**
     * 品牌启动页 Hook 是不是**直接挂在** [BRAND_SPLASH_CLASS] 上。
     *
     * 只有这种情况下才值得记「这个 Fragment 里没有 `splash_container`」—— 退到
     * [FRAGMENT_BASE] 时那个 Hook 每个 Fragment 都会跑，逐条记日志没有意义。
     */
    @Volatile
    private var brandHookOnExactClass = false

    /**
     * 装上 Hook。
     *
     * **只反射、不引用宿主类型**：`android.app.Activity` / `android.view.View` 都是框架类，
     * 从宿主 ClassLoader 解析只是为了和项目里其它地方保持同一种写法。
     *
     * 各层互相独立：Activity 窗口背景（[installActivityHook]）、App 侧资源收口
     * （[installDrawableHook]）、窗口背景复核（[installPhoneWindowHook]）、品牌启动页
     * （[installBrandSplashHook]）、inflate 兜底 + 广告启动页（[installInflateHook]）。
     * 系统启动窗口那一层在 [SystemSplashTheme]。
     */
    fun install(module: XposedModule, loader: ClassLoader) {
        installActivityHook(module, loader)
        installDrawableHook(module)
        installViewBackgroundHook(module, "app")
        installPhoneWindowHook(module, loader)
        installBrandSplashHook(module, loader)
        installInflateHook(module, loader)
    }

    /**
     * **View 层收口**：`android.view.View#setBackground(Drawable)`。
     *
     * 不管那份白底是怎么来的（资源缓存、`ConstantState` 复用、别处直接 new 出来的），最后一步都是
     * 某个 view 的 `setBackground`。这里认「第一层是纯白 `ColorDrawable` + 第二层是 360x180 的
     * `BitmapDrawable`」= 启动屏那份 layer-list（`layerlist_splash` / `safe_mode_layerlist_splash`
     * 都是这个结构），命中就换成深色版，并**打一条日志**（日志本身就是探针：能看出是哪个 view、
     * 什么时候被设成白底）。
     *
     * 换上去的那份第一层是深色，谓词不再成立，不会递归。
     *
     * 这个方法 App 侧和 SystemUI 侧各装一次（[SystemSplashTheme] 也调它）——启动窗口在
     * SystemUI 进程里，App 自己的窗口在 App 进程里，两边都得盖住。
     */
    internal fun installViewBackgroundHook(module: XposedModule, tag: String) {
        val setBackground = Reflect.method(View::class.java, "setBackground", Drawable::class.java)
        if (setBackground == null) {
            XLog.w("没找到 View#setBackground，View 层收口跳过（$tag）")
            return
        }
        module.hookGuarded(setBackground) { chain ->
            val result = chain.proceed()
            val view = chain.getThisObject() as? View
            if (view != null) guarded { reworkWhiteSplashBackground(view, tag) }
            result
        }
        XLog.i("启动屏深色化已就绪：View#setBackground 收口（$tag）")
    }

    /** 见 [installViewBackgroundHook]。 */
    private fun reworkWhiteSplashBackground(view: View, tag: String) {
        val bg = view.background ?: return
        val res = view.resources ?: return
        val what = "view=${view.javaClass.name} id=0x${Integer.toHexString(view.id)}"
        val out = darkenWhiteSplash(bg, res, tag, what) ?: return
        runCatching { view.background = out }
    }

    /**
     * 认「启动屏那份白底 layer-list」并换成深色版；不是它（或不是深色模式）返回 null。
     *
     * App 侧（[reworkWhiteSplashBackground]）与 SystemUI 侧（`SystemSplashTheme` 里
     * `getWindowBgImage` 那一支）共用，`tag` / `what` 只进日志 —— **日志本身就是探针**，
     * 能看出是哪一份、谁在什么时候把它设成白底。
     */
    internal fun darkenWhiteSplash(bg: Drawable?, res: Resources?, tag: String, what: String): Drawable? {
        if (bg == null || res == null) return null
        if (!looksLikeSplashLayerList(bg)) return null
        if (!isNight(res)) return null
        val dark = nightWindowBackground(res, HostEnv.context()?.applicationInfo?.theme ?: 0)
            ?: FALLBACK_DARK
        XLog.i("启动屏白底已换深色（View 层/$tag）：$what → #${Integer.toHexString(dark)}")
        return guarded { darken(bg, res, dark) }
    }

    /**
     * 是不是启动屏那份 layer-list：第一层纯白 `ColorDrawable` + 第二层 360x180 的 `BitmapDrawable`。
     *
     * 第二层的尺寸判据（`ic_logo_default` / `safe_mode_ic_logo_default` 都是 360x180）是为了
     * **不误伤**别的「白底 + 位图」layer-list —— SystemUI 那边也会跑这个 Hook。
     */
    private fun looksLikeSplashLayerList(bg: Drawable): Boolean {
        if (bg !is LayerDrawable || bg.numberOfLayers < 2) return false
        val first = bg.getDrawable(0)
        if (first !is ColorDrawable || first.color != Color.WHITE) return false
        val second = bg.getDrawable(1)
        if (second !is BitmapDrawable) return false
        val bmp = second.bitmap ?: return false
        return bmp.width == 360 && bmp.height == 180
    }

    /** Activity 窗口背景那一层（见类注释「做法」）。 */
    private fun installActivityHook(module: XposedModule, loader: ClassLoader) {
        val activityClass = Reflect.findClass(loader, "android.app.Activity") ?: Activity::class.java
        val onCreate = Reflect.method(activityClass, METHOD_ON_CREATE, Bundle::class.java)
        if (onCreate == null) {
            XLog.w("没找到 Activity#$METHOD_ON_CREATE，启动屏深色化跳过")
            return
        }
        module.hookGuarded(onCreate) { chain ->
            // 先改背景，再放行宿主自己的 onCreate —— 顺序反了就会被主题值盖回去。
            // 我们做的事一律包住：抛什么都不影响 chain.proceed()。
            val self = chain.getThisObject()
            val background = if (self is Activity) guarded { prepare(self) } else null
            val result = chain.proceed()
            // 宿主的 onCreate 里才会 setContentView（→ installDecor → generateLayout），
            // 那时 decor 才存在。PhoneWindow 各版本对「提前设好的 mBackgroundDrawable」
            // 处理并不完全一致，所以 decor 建好后再以「实际挂在 decor 上的是不是我们那份」
            // 为准复核一次，不一致就补设。
            if (self is Activity && background != null) {
                guarded { ensureApplied(self, background) }
            }
            result
        }
        XLog.i("启动屏深色化已就绪：Activity#$METHOD_ON_CREATE")
    }

    /**
     * 品牌启动页那一层：把 `splash_container` 的白底换掉。
     *
     * 目标类两版同名（见 [BRAND_SPLASH_CLASS]），直接按类名解析；解析不到才退到
     * [FRAGMENT_BASE]，靠 id 自我识别（所以多出来那一层误伤风险由 [darkenBrandSplash] 兜住）。
     */
    private fun installBrandSplashHook(module: XposedModule, loader: ClassLoader) {
        val exact = Reflect.findClass(loader, BRAND_SPLASH_CLASS)
        val target = exact ?: Reflect.findClass(loader, FRAGMENT_BASE)
        if (target == null) {
            XLog.w("没找到 $BRAND_SPLASH_CLASS / $FRAGMENT_BASE，品牌启动页深色化跳过")
            return
        }
        brandHookOnExactClass = exact != null
        val onViewCreated = Reflect.method(
            target,
            METHOD_ON_VIEW_CREATED,
            View::class.java,
            Bundle::class.java,
        )
        if (onViewCreated == null) {
            XLog.w("没找到 ${target.name}#$METHOD_ON_VIEW_CREATED，品牌启动页深色化跳过")
            return
        }
        module.hookGuarded(onViewCreated) { chain ->
            // 放行宿主自己那段（它可能也会动背景），回来再覆盖上去 —— 后设的说了算。
            val result = chain.proceed()
            val view = chain.getArg(0) as? View
            if (view != null) guarded { darkenBrandSplash(view) }
            result
        }
        XLog.i("品牌启动页深色化已就绪：${target.name}#$METHOD_ON_VIEW_CREATED")
    }

    /**
     * App 侧的资源收口：`Resources#getDrawableForDensity(int, int, Theme)`。
     *
     * 系统侧那一半靠同一个方法收口（见 [SystemSplashTheme]），这里补上 **App 自己的进程**：
     * 只要有人从 App 的资源里取 `layerlist_splash`，拿到的就是深色版 —— 不管它是谁、
     * 什么时候取的。`PhoneWindow.generateLayout` 取主题 `windowBackground` 正是这条路
     *（`Context.getDrawable` → `getDrawableForDensity`），所以「窗口背景被写回主题值」这件事
     * 从这里就断了。
     *
     * 判据用**带包名的资源全名**（按名字解析到的 id），别的包的同号资源不会误伤。
     */
    private fun installDrawableHook(module: XposedModule) {
        val getDrawable = Reflect.method(
            Resources::class.java,
            METHOD_GET_DRAWABLE,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Resources.Theme::class.java,
        )
        if (getDrawable == null) {
            XLog.w("没找到 Resources#$METHOD_GET_DRAWABLE，App 侧资源收口跳过")
            return
        }
        module.hookGuarded(getDrawable) { chain ->
            // 只 proceed 一次：拿到结果之后再决定换不换。
            val result = chain.proceed()
            val res = chain.getThisObject() as? Resources
            val id = chain.getArg(0) as? Int
            if (res == null || id == null || result !is LayerDrawable) return@hookGuarded result
            if (id != resolveSplashIdStrict(res)) return@hookGuarded result
            if (!isNight(res)) return@hookGuarded result
            val dark = nightWindowBackground(res, HostEnv.context()?.applicationInfo?.theme ?: 0)
                ?: FALLBACK_DARK
            val out = guarded { darken(result, res, dark) }
            if (out == null) {
                result
            } else {
                XLog.i("启动屏资源收口：$SPLASH_DRAWABLE → #${Integer.toHexString(dark)}")
                out
            }
        }
        XLog.i("启动屏深色化已就绪：Resources#$METHOD_GET_DRAWABLE 收口")
    }

    /**
     * `PhoneWindow#setContentView` 之后复核窗口背景。
     *
     * `setContentView` 会触发 `generateLayout`，而后者正是「回头去主题里取 `windowBackground`」
     * 的地方。这里在它之后再看一眼：底色不是我们的深色版就补设，并把**内容根**
     * （`android.R.id.content`）也铺成深色 —— 启动页内容消失时露出来的是它。
     *
     * 挂 `PhoneWindow` 而不是 `Activity#onResume`：宿主的 Activity 覆写了 `onResume` 且不一定
     * 回调 super（实测这条路上一次都没触发），而 `setContentView` 一定会走。
     */
    private fun installPhoneWindowHook(module: XposedModule, loader: ClassLoader) {
        val cls = Reflect.findClass(loader, PHONE_WINDOW_CLASS)
        val setContentView =
            if (cls == null) null else Reflect.method(cls, METHOD_SET_CONTENT_VIEW, View::class.java)
        if (setContentView == null) {
            XLog.w("没找到 $PHONE_WINDOW_CLASS#$METHOD_SET_CONTENT_VIEW，窗口背景复核跳过")
            return
        }
        module.hookGuarded(setContentView) { chain ->
            val result = chain.proceed()
            val window = chain.getThisObject() as? Window
            val context = window?.context
            if (context is Activity) guarded { reapply(context) }
            result
        }
        XLog.i("启动屏深色化已就绪：$PHONE_WINDOW_CLASS#$METHOD_SET_CONTENT_VIEW 复核")
    }

    /** 复核 / 补设：见 [installPhoneWindowHook]。 */
    private fun reapply(activity: Activity) {
        val res = activity.resources ?: return
        if (!isNight(res)) return

        // 只认「这个 Activity 的主题把 windowBackground 指到 layerlist_splash」。
        val id = resolveSplashId(res)
        val value = TypedValue()
        if (!activity.theme.resolveAttribute(android.R.attr.windowBackground, value, true)) return
        if (value.resourceId != id) return

        val window = activity.window ?: return
        val decor = window.peekDecorView()
        val current = decor?.background
        val alreadyDark = current is LayerDrawable &&
            current.numberOfLayers > 0 &&
            (current.getDrawable(0) as? ColorDrawable)?.color == nightColor(res, activity)
        if (alreadyDark) return

        val dark = nightColor(res, activity)
        val background = runCatching { darken(res.getDrawable(id, activity.theme), res, dark) }
            .getOrElse { ColorDrawable(dark) }
        window.setBackgroundDrawable(background)
        decor?.setBackground(background)
        // 内容根也铺深色：启动页内容被移除时，露出来的是它。
        runCatching { activity.findViewById<View>(android.R.id.content)?.setBackgroundColor(dark) }
        XLog.i("启动屏窗口背景补设：${activity.javaClass.name} → #${Integer.toHexString(dark)}")
    }

    /** 与另外几层同源：问 App 自己的主题要深色窗口底色。 */
    private fun nightColor(res: Resources, activity: Activity): Int =
        nightWindowBackground(res, activity.applicationInfo?.theme ?: 0) ?: FALLBACK_DARK

    /**
     * 严格版：只有**按名字查得到**才返回 id，查不到返回 0（不回退常量）。
     *
     * 收口那一层要拿它跟 `getDrawableForDensity` 的入参比，回退常量有可能撞上别的资源，
     * 宁可不动。
     */
    private fun resolveSplashIdStrict(res: Resources): Int {
        val cached = splashId
        if (cached != 0) return cached
        if (splashIdMissing) return 0
        val found = runCatching {
            res.getIdentifier(SPLASH_DRAWABLE, "drawable", HostEnv.packageName())
        }.getOrDefault(0)
        if (found == 0) {
            splashIdMissing = true
            XLog.w("资源里没有 $SPLASH_DRAWABLE，App 侧资源收口跳过")
            return 0
        }
        splashId = found
        return found
    }

    /**
     * inflate 兜底那一层（见类注释「第四层」）：钩 `View#onFinishInflate`。
     *
     * 挂在**框架类** `android.view.View` 上，所以它会被 App 里每一次布局 inflate 调到 —— 这条
     * 是热路径，回调里必须先做最便宜的判据（一个 int 比较 / 一次 `isInstance`），命中了才去算颜色。
     * 装不上只降级：第三层的 Fragment Hook 照常。
     */
    private fun installInflateHook(module: XposedModule, loader: ClassLoader) {
        val viewClass = Reflect.findClass(loader, "android.view.View") ?: View::class.java
        val onFinishInflate = Reflect.method(viewClass, METHOD_ON_FINISH_INFLATE)
        if (onFinishInflate == null) {
            XLog.w("没找到 View#$METHOD_ON_FINISH_INFLATE，inflate 兜底跳过")
            return
        }
        module.hookGuarded(onFinishInflate) { chain ->
            // 先放行宿主（`onFinishInflate` 本身就是给宿主覆写的钩子），回来再动背景。
            val result = chain.proceed()
            val view = chain.getThisObject() as? View
            if (view != null) guarded { darkenInflatedRoot(view) }
            result
        }
        XLog.i("启动屏深色化已就绪：View#$METHOD_ON_FINISH_INFLATE（inflate 兜底 + 广告启动页）")
    }

    /**
     * 判一个「刚 inflate 完的布局根」是不是启动屏，是就把白底换掉。
     *
     * 两支：
     *  - `splash_container` —— 品牌启动页的根。第三层的 Fragment Hook 只覆盖
     *    `BaseBrandSplashFragment` 那条路，这里补上「被别的路径 inflate 出来」的情形。
     *  - `SplashContainerView` —— 广告启动页的根。它自己的白底、以及底部那条
     *    `logo_layout`（满宽白底 + `failureImage` 粉色字标）都换掉。
     *
     * 广告页的根也一起换：有广告时广告图整屏盖着它，看不见；没广告时它才是那一整屏白。
     */
    private fun darkenInflatedRoot(view: View) {
        // 热路径：第一次进来把几个 id / Class 解析好，之后只剩几个便宜的判据。
        if (!idsReady) {
            val res = view.resources ?: return
            resolveContainerId(res)
            resolveAdLogoBarId(res)
            resolveAdRootClass()
            resolveDownloadIds(res)
            idsReady = true
        }
        val container = containerId
        val isBrand = container != 0 && view.id == container
        val isAd = !isBrand && adRootClass?.isInstance(view) == true
        val isDownload = !isBrand && !isAd && looksLikeSplashDownload(view)
        if (!isBrand && !isAd && !isDownload) return

        val res = view.resources ?: return
        if (!isNight(res)) return
        val theme = runCatching { view.context.applicationInfo?.theme ?: 0 }.getOrDefault(0)
        val dark = nightWindowBackground(res, theme) ?: FALLBACK_DARK

        if (isBrand) {
            view.setBackgroundColor(dark)
            XLog.i("品牌启动页已跟随深色模式（inflate 兜底）：${view.javaClass.name} → #${Integer.toHexString(dark)}")
            return
        }

        if (isDownload) {
            // 启动页资源下载页：根是满屏白底，底部是粉色字标。整页换深色。
            view.setBackgroundColor(dark)
            XLog.i("启动页资源下载页已跟随深色模式：${view.javaClass.name} → #${Integer.toHexString(dark)}")
            return
        }

        view.setBackgroundColor(dark)
        val barId = resolveAdLogoBarId(res)
        val bar = if (barId != 0) view.findViewById<View>(barId) else null
        bar?.setBackgroundColor(dark)
        XLog.i("广告启动页已跟随深色模式：root=${view.javaClass.name} 底部白条=${bar != null} → #${Integer.toHexString(dark)}")
    }

    /**
     * 是不是「启动页资源下载页」（`layout/bili_layout_fragment_splash_mod_download`，`res/bub.xml`）。
     *
     * 它的根是**满屏白底**的 `LinearLayout`（`@color/white`）且**根上没有 id**，只能靠子 view 认：
     * `id/download_progress` + `id/logo`（`src=@drawable/ic_logo_default`，粉色字标）。
     * 先做一次「根背景是不是纯白」的便宜判据，命中才去 `findViewById` —— 这条在 inflate 热路径上。
     */
    private fun looksLikeSplashDownload(view: View): Boolean {
        val bg = view.background
        if (bg !is ColorDrawable || bg.color != Color.WHITE) return false
        val progress = downloadProgressId
        val logo = downloadLogoId
        if (progress == 0 || logo == 0) return false
        return view.findViewById<View>(progress) != null && view.findViewById<View>(logo) != null
    }

    /** 解析下载页那两个子 id（`download_progress` / `logo`），进程内一次。 */
    private fun resolveDownloadIds(res: Resources) {
        if (downloadProgressId == 0) {
            downloadProgressId = runCatching {
                res.getIdentifier(DOWNLOAD_PROGRESS_ID, "id", HostEnv.packageName())
            }.getOrDefault(0)
        }
        if (downloadLogoId == 0) {
            downloadLogoId = runCatching {
                res.getIdentifier(DOWNLOAD_LOGO_ID, "id", HostEnv.packageName())
            }.getOrDefault(0)
        }
        if (downloadProgressId == 0 || downloadLogoId == 0) {
            XLog.w("资源里没有 $DOWNLOAD_PROGRESS_ID / $DOWNLOAD_LOGO_ID，启动页资源下载页深色化跳过")
        }
    }

    /** 解析 `logo_layout` 的 id；查不到返回 0（放弃，不做 id 兜底：要拿去 `findViewById`）。 */
    private fun resolveAdLogoBarId(res: Resources): Int {
        val cached = adLogoBarId
        if (cached != 0) return cached
        val found = runCatching {
            res.getIdentifier(AD_LOGO_BAR_ID, "id", HostEnv.packageName())
        }.getOrDefault(0)
        if (found == 0) {
            if (!warnedNoAdLogoBarId) {
                warnedNoAdLogoBarId = true
                XLog.w("资源里没有 $AD_LOGO_BAR_ID，广告启动页只换根背景（底部白条照旧）")
            }
            return 0
        }
        adLogoBarId = found
        return found
    }

    /** 解析广告启动页根 view 的 `Class`；解析不到返回 null（那一支直接不做）。 */
    private fun resolveAdRootClass(): Class<*>? {
        if (adRootClassResolved) return adRootClass
        val loader = HostEnv.classLoader()
        val found = if (loader == null) null else Reflect.findClass(loader, AD_SPLASH_ROOT)
        adRootClass = found
        adRootClassResolved = true
        if (found == null) {
            XLog.w("没找到 $AD_SPLASH_ROOT，广告启动页深色化跳过（品牌启动页照常）")
        }
        return found
    }

    /**
     * 当前是不是深色。
     *
     * ⚠️ **判据必须和系统侧（[SystemSplashTheme]）一致**：两边都认
     * 「App 自己的 configuration 是深色 **或** 系统 configuration 是深色」。
     *
     * 只用 App 自己的会漏：冷启动那一瞬 B 站的 Resources 有可能还报浅色（`AppCompatDelegate`
     * 的夜间覆盖要等 Application / Activity 走完才落到 configuration 上），而这时系统早就是深色了。
     * 实测就是这种情况 —— 系统侧那一半照常生效，App 侧五层却全部提前 return，窗口背景留在主题里
     * 那份白的 `layerlist_splash` 上，于是冷启动末尾又露出白条。
     */
    private fun isNight(res: Resources): Boolean =
        (res.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES ||
            (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * 把品牌启动页的白底换成深色。
     *
     * 「这是不是启动页」只认一条：根 view 里能不能 `findViewById` 到 `splash_container`。
     * 认不出来就什么都不做 —— 挂到 [FRAGMENT_BASE] 时这个方法会被**每个** Fragment 调到，
     * 没有这道判据就会把无关页面的背景涂黑。
     */
    private fun darkenBrandSplash(view: View) {
        val res = view.resources ?: return
        if (!isNight(res)) return
        val id = resolveContainerId(res)
        if (id == 0) return
        // 布局里 `splash_container` 就是根节点本身，`findViewById` 会先查自身，所以
        // 正常情况这里拿到的就是 `view` 自己；拿不到就说明这个 Fragment 不是启动页。
        val container = view.findViewById<View>(id)
        if (container == null) {
            // 只有直接挂在品牌启动页类上时这里才可能是异常，值得记一条（退到基类时是常态）。
            if (brandHookOnExactClass) {
                XLog.w("品牌启动页里没有 $SPLASH_CONTAINER_ID（${view.javaClass.name}），本次不动")
            }
            return
        }

        // 与另外两层同源：问 App 自己的主题要深色窗口底色（`@color/Ga1` 的 night 值）。
        val theme = runCatching { view.context.applicationInfo?.theme ?: 0 }.getOrDefault(0)
        val dark = nightWindowBackground(res, theme) ?: FALLBACK_DARK
        container.setBackgroundColor(dark)
        XLog.i("品牌启动页已跟随深色模式：${container.javaClass.name} → #${Integer.toHexString(dark)}")
    }

    /**
     * 判断该不该管这个 Activity，该管就把深色背景准备好（返回 null 表示不用管）。
     *
     * 只有「当前是深色」+「这个 Activity 用的就是启动屏主题」两条都成立才动手，
     * 其余情况原样放行（宿主 621 个 Activity 里只有 4 个用这个主题）。
     */
    private fun prepare(activity: Activity): Drawable? {
        val res = activity.resources ?: return null

        // 判据不是「类名等于 MainActivityV2」，而是「主题的 windowBackground 就是那个
        // layer-list」—— 这样换版本、换入口 Activity 都不会漏，也不会误伤别的页面。
        val id = resolveSplashId(res)
        val windowBackground = TypedValue()
        if (!activity.theme.resolveAttribute(android.R.attr.windowBackground, windowBackground, true)) return null
        if (windowBackground.resourceId != id) return null
        if (!isNight(res)) return null

        val window = activity.window ?: return null
        val dark = nightWindowBackground(res, activity.applicationInfo?.theme ?: 0) ?: FALLBACK_DARK
        val background = runCatching { darken(res.getDrawable(id, activity.theme), res, dark) }
            .getOrElse { ColorDrawable(dark) }
        window.setBackgroundDrawable(background)
        XLog.i("启动屏已跟随深色模式：${activity.javaClass.name} → #${Integer.toHexString(dark)}")
        return background
    }

    /** decor 建好后复核：实际挂着的不是我们那份就补设一次。 */
    private fun ensureApplied(activity: Activity, background: Drawable) {
        val window = activity.window ?: return
        val decor = window.peekDecorView()
        if (decor != null && decor.background === background) return
        window.setBackgroundDrawable(background)
        XLog.i("启动屏背景补设一次：${activity.javaClass.name}（decor=${decor != null}）")
    }

    /** 与 [XLog.guard] 同义，只是要一个返回值（背景对象）给调用方用。 */
    private inline fun <T> guarded(block: () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        XLog.w("启动屏深色化失败（已忽略，不影响宿主启动）", e)
        null
    }

    /** 解析 `layerlist_splash` 的 id；名字查不到就退回 8.34.0 实测值。 */
    private fun resolveSplashId(res: Resources): Int {
        val cached = splashId
        if (cached != 0) return cached
        val found = runCatching {
            res.getIdentifier(SPLASH_DRAWABLE, "drawable", HostEnv.packageName())
        }.getOrDefault(0)
        val id = if (found != 0) found else FALLBACK_SPLASH_ID
        splashId = id
        return id
    }

    /**
     * 解析 `splash_container` 的 id，进程内一次。
     *
     * 查不到就返回 0（放弃），**不做 id 兜底** —— 和 [resolveSplashId] 不一样：
     * 那边 id 只用来跟主题里的 `windowBackground` 比对，错了顶多是不生效；这边 id 要拿去
     * `findViewById`，拿别的版本的 id 有可能命中某个无关的 view，宁可不动。
     */
    private fun resolveContainerId(res: Resources): Int {
        val cached = containerId
        if (cached != 0) return cached
        val found = runCatching {
            res.getIdentifier(SPLASH_CONTAINER_ID, "id", HostEnv.packageName())
        }.getOrDefault(0)
        if (found == 0) {
            if (!warnedNoContainerId) {
                warnedNoContainerId = true
                XLog.w("资源里没有 $SPLASH_CONTAINER_ID，品牌启动页深色化跳过（系统启动窗口那一层照常）")
            }
            return 0
        }
        containerId = found
        return found
    }

    /**
     * 取 App 自己在深色下的窗口底色，用来当启动屏底色。
     *
     * 不硬编码颜色，也不去猜混淆后的资源名：拿 `<application android:theme>` 这个主题
     * （`AppTheme`）单独套一份 theme，问它 `windowBackground` 是谁 —— 它的父链
     * `AppTheme → AppBaseTheme → BaseTheme` 上写的是 `@color/Ga1`，在深色配置下解析出来
     * 就是 B 站界面上那个 `#0a0b0c`。换版本时只要这条父链还在，颜色就自动跟着走。
     *
     * `internal` 是给 [SystemSplashTheme] 用的：系统侧那一半跑在 `com.android.systemui`
     * 进程里，但拿到的 `Context` 正是 B 站的，所以同一套解析照样能用 —— 两边的底色因此
     * 永远一致，不会出现「系统启动窗口一个深色、App 窗口另一个深色」。
     */
    internal fun nightWindowBackground(res: Resources, appTheme: Int): Int? {
        if (appTheme == 0) return null
        return runCatching {
            val theme = res.newTheme()
            theme.applyStyle(appTheme, true)
            val value = TypedValue()
            if (!theme.resolveAttribute(android.R.attr.windowBackground, value, true)) return@runCatching null
            when {
                value.resourceId != 0 -> res.getColor(value.resourceId, theme)
                value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> value.data
                else -> null
            }
        }.getOrNull()
    }

    /**
     * 把 layer-list 第一层的颜色换掉，其余层（logo）连同 gravity / inset / 尺寸一起照搬。
     *
     * 刻意**重建**而不是就地改：`Resources.getDrawable` 返回的是缓存实例，就地 `setDrawable`
     * 会污染整份资源缓存 —— 用户在 App 里切回浅色时，那个被改黑的 drawable 还会被复用。
     * 每层用 `constantState.newDrawable()` 复制一份，新的 LayerDrawable 和缓存彻底脱钩。
     *
     * 找不到颜色层（B 站改了 drawable 结构）时退化成纯色，只丢 logo，不会不生效。
     *
     * `internal` 是给 [SystemSplashTheme] 用的：系统启动窗口在 style 4 下会把 App 主题的
     * `windowBackground` 原样叠在底色之上，那份叠层得用同一套做法换深色版。
     */
    internal fun darken(base: Drawable?, res: Resources, dark: Int): Drawable {
        if (base !is LayerDrawable) return ColorDrawable(dark)

        val count = base.numberOfLayers
        val layers = ArrayList<Drawable>(count)
        var replaced = false
        for (i in 0 until count) {
            val child = base.getDrawable(i) ?: ColorDrawable(Color.TRANSPARENT)
            if (!replaced && child is ColorDrawable) {
                layers.add(ColorDrawable(dark))
                replaced = true
            } else {
                layers.add(child.constantState?.newDrawable(res, null) ?: child)
            }
        }
        if (!replaced) return ColorDrawable(dark)

        val out = LayerDrawable(layers.toTypedArray())
        for (i in 0 until count) {
            out.setLayerGravity(i, base.getLayerGravity(i))
            out.setLayerInsetLeft(i, base.getLayerInsetLeft(i))
            out.setLayerInsetTop(i, base.getLayerInsetTop(i))
            out.setLayerInsetRight(i, base.getLayerInsetRight(i))
            out.setLayerInsetBottom(i, base.getLayerInsetBottom(i))
            out.setLayerWidth(i, base.getLayerWidth(i))
            out.setLayerHeight(i, base.getLayerHeight(i))
        }
        return out
    }
}

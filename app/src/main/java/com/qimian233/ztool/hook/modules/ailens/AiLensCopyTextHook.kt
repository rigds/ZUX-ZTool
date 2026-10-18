package com.qimian233.ztool.hook.modules.ailens

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.util.WeakHashMap

/**
 * 「智慧识屏」AI 卡片左上角「复制原文」按钮。
 *
 * 移植自独立模块 ZSXCopyBtn v1.6.2（原实现为 Java + 文件桥接配置），并入 ZTool 后：
 *  - 配置改走 ZTool 统一的 remotePreferences，不再写 /data/misc 桥接文件；
 *  - Hook 统一走 hookWithId()，日志统一走 logger；
 *  - 三个开关每次点击时实时读取，改设置后无需重启宿主进程即可对下一张卡片生效。
 *
 * 宿主注入链路（逆向字节码确认）：
 *   UIController.showAICard(int, String 选中文字, ...)
 *     -> DetectResultView.showCard(...)
 *       -> new AICardView(String mOriginText, Context, AIServiceProxy, int, AttributeSet)
 *          -> this.mBinding.cardMenu (CardBottomMenus)
 *
 * ── 只在「顶层卡片」上注入（重要）────────────────────────────────────────
 * 宿主会同时创建多张 AICardView（主卡片、AI 翻译子卡片、AI 摘要子卡片 …），
 * 它们是各自独立的实例，但共享同一个父布局。若对每张卡片都注入按钮，就会在
 * 屏幕上出现一堆「复制原文」（子卡片可能被排到可视区外，按钮更是散落各处）。
 *
 * 因此这里加了两道闸：
 *   1. isTopLevelCard()：父链上还存在 AICardView 的卡片一律跳过 —— 只认最外层
 *      那一张，子卡片完全不动；
 *   2. injectedCards：WeakHashMap 记录已注入的卡片实例，同一实例只注入一次，
 *      避免 post 一帧 + 重试导致重复 addView。
 *
 * ── 注入时机 ─────────────────────────────────────────────────────────
 * 只保留构造函数 after hook（AICardView 未重写 onAttachedToWindow，构造后 post
 * 一帧最可靠）。onSizeChanged / onLayoutChanged 之类的兜底注入已全部移除 ——
 * 它们会被重绘频繁触发，是重复注入的第二大来源。
 * onConfigurationChanged / updateUi 只用于同步深浅色配色，不做注入。
 */
class AiLensCopyTextHook : AppHookModule() {

    override fun getModuleName(): String = PreferenceKeys.AI_LENS_COPY_TEXT.name

    override fun getTargetPackages(): Array<out String> =
        arrayOf(ScopeKeys.AI_LENS.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        val aiCardViewCls: Class<*> = try {
            param.defaultClassLoader.loadClass(AI_CARD_VIEW_CLASS)
        } catch (t: Throwable) {
            logger.warn("AICardView not found (host version changed?): $t")
            return
        }

        // 唯一注入入口：AICardView 构造完成后 post 一帧
        hookAllConstructors(aiCardViewCls) { cardView ->
            injectButtonWithRetry(cardView, 0)
        }

        // 仅同步配色，不做注入
        hookAll(aiCardViewCls, "onConfigurationChanged", "config") { cardView ->
            refreshButtonTheme(cardView)
        }

        hookAll(aiCardViewCls, "updateUi", "updateUi") { cardView ->
            refreshButtonTheme(cardView)
        }

        logger.info("AiLensCopyTextHook installed")
    }

    // ── 偏好读取（每次实时读，改设置无需重启宿主）────────────────────────────

    private fun isShowButton(): Boolean = readBool(
        PreferenceKeys.AI_LENS_COPY_TEXT.name,
        PreferenceKeys.AI_LENS_COPY_TEXT.default
    )

    private fun isCloseCardAfterCopy(): Boolean = readBool(
        PreferenceKeys.AI_LENS_CLOSE_CARD_AFTER_COPY.name,
        PreferenceKeys.AI_LENS_CLOSE_CARD_AFTER_COPY.default
    )

    private fun isExitLensAfterCopy(): Boolean = readBool(
        PreferenceKeys.AI_LENS_EXIT_LENS_AFTER_COPY.name,
        PreferenceKeys.AI_LENS_EXIT_LENS_AFTER_COPY.default
    )

    private fun readBool(key: String, default: Boolean): Boolean = try {
        remotePreferences.getBoolean(key, default)
    } catch (_: Throwable) {
        default
    }

    // ── Hook 注册辅助 ──────────────────────────────────────────────────────

    private fun interface CardAction {
        fun run(cardView: View)
    }

    /** 构造全部 Hook，主线程 after hook 执行并捕获异常。 */
    private fun hookAllConstructors(cls: Class<*>, action: CardAction) {
        try {
            for (ctor in cls.declaredConstructors) {
                hookWithId(
                    ctor as Executable,
                    "ai_lens_copy_ctor_${ctor.toGenericString()}",
                    afterHook("ctor", action)
                )
            }
        } catch (t: Throwable) {
            logger.warn("hook constructors skipped: $t")
        }
    }

    private fun hookAll(cls: Class<*>, method: String, tag: String, action: CardAction) {
        var current: Class<*>? = cls
        var hooked = 0
        while (current != null) {
            for (declared in current.declaredMethods) {
                if (declared.name != method) continue
                try {
                    hookWithId(
                        declared as Executable,
                        "ai_lens_copy_${tag}_${current.name}_${declared.parameterCount}",
                        afterHook(tag, action)
                    )
                    hooked++
                } catch (t: Throwable) {
                    logger.warn("hook $method skipped: $t")
                }
            }
            current = current.superclass
        }
        if (hooked == 0) logger.debug("method $method not found, skip ($tag)")
    }

    /** 把一个「对卡片 View 做某事」的动作包装成主线程执行的 after hook。 */
    private fun afterHook(tag: String, action: CardAction): XposedInterface.Hooker =
        XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            val obj = chain.thisObject
            if (obj is View) {
                obj.post {
                    try {
                        action.run(obj)
                    } catch (t: Throwable) {
                        logger.warn("card action($tag) failed: $t")
                    }
                }
            }
            result
        }

    // ── 注入逻辑 ───────────────────────────────────────────────────────────

    private fun injectButtonWithRetry(cardView: View, attempt: Int) {
        if (attempt > MAX_RETRY) {
            logger.debug("give up injecting after $attempt attempts")
            return
        }
        if (!tryInject(cardView)) {
            cardView.postDelayed({ injectButtonWithRetry(cardView, attempt + 1) }, 60L)
        }
    }

    /** @return true 表示已注入完毕（成功、已存在或不属于本模块处理范围），无需重试 */
    private fun tryInject(cardView: View): Boolean {
        // 闸一：只处理最外层卡片，子卡片直接放手
        if (!isTopLevelCard(cardView)) return true

        // AICardView 继承 FrameLayout，直接把按钮悬浮到卡片左上角
        val cardRoot = cardView as? FrameLayout ?: return true

        // 布局尚未 inflate 完（还没子 View）时稍后重试
        if (cardRoot.childCount == 0) return false

        // 总开关：关掉时确保按钮不在界面上，并且不再注入
        if (!isShowButton()) {
            cardRoot.findViewWithTag<View>(BTN_TAG)?.let {
                cardRoot.removeView(it)
                logger.debug("button removed (switch off)")
            }
            synchronized(injectedCards) { injectedCards.remove(cardView) }
            return true
        }

        // 闸二：同一张卡片实例只注入一次
        synchronized(injectedCards) {
            if (injectedCards.containsKey(cardView)) {
                // 标记在但视图真没了（被宿主内部清理），补一次
                if (cardRoot.findViewWithTag<View>(BTN_TAG) != null) return true
                injectedCards.remove(cardView)
            }
        }

        if (cardRoot.findViewWithTag<View>(BTN_TAG) != null) {
            synchronized(injectedCards) { injectedCards[cardView] = true }
            return true
        }

        val ctx = cardView.context ?: return false

        val selectedText = readOriginText(cardView)

        val btn = buildTopLeftButton(ctx)
        applyButtonTheme(btn, isDarkMode(ctx))
        btn.setOnClickListener { v ->
            val text = readOriginText(cardView)
            if (copyToClipboard(v.context, text)) {
                // 每次点击时读取开关，改设置后无需重启宿主
                val exitLens = isExitLensAfterCopy()
                val closeCard = isCloseCardAfterCopy()
                logger.debug("afterCopy: closeCard=$closeCard exitLens=$exitLens")
                afterCopy(cardView, closeCard, exitLens)
            }
        }

        cardRoot.addView(btn)
        btn.bringToFront()

        synchronized(injectedCards) { injectedCards[cardView] = true }

        logger.debug(
            "button injected at top-left, selectedText.len=${selectedText?.length ?: -1}"
        )
        return true
    }

    /**
     * 是否为「顶层」AI 卡片：父链上不存在其它 AICardView。
     *
     * 宿主的卡片是嵌套的 —— 主卡片里再放 AI 翻译 / AI 摘要的子卡片。
     * 子卡片如果也注入按钮，就会跟着子卡片的位置散落到屏幕各处（实际截图即如此）。
     * 因此只有父链干净的那一张才算顶层。
     */
    private fun isTopLevelCard(cardView: View): Boolean {
        try {
            var p: ViewParent? = cardView.parent
            var depth = 0
            while (p is View && depth < MAX_PARENT_DEPTH) {
                if (p.javaClass.name == AI_CARD_VIEW_CLASS) return false
                p = p.parent
                depth++
            }
        } catch (t: Throwable) {
            // 查不到父链时保守放行，交给闸二去重
            logger.debug("isTopLevelCard failed: $t")
        }
        return true
    }

    /**
     * 复制完成后的动作：按开关关闭卡片 / 退出识屏。
     * 两个都开时以「退出识屏」为准 —— exit() 内部已包含 removeAICard(),
     * 会连同卡片一起关掉，无需再单独调用。
     */
    private fun afterCopy(cardView: View, closeCard: Boolean, exitLens: Boolean) {
        if (!closeCard && !exitLens) return
        try {
            val uiController = findUiController(cardView)
            if (exitLens) {
                if (uiController != null) {
                    // 走系统原生退出路径：exit() -> removeAICard(5) -> playExitAnimation()
                    //   -> exitLens() -> 半透明 -> 200ms -> exitDetectView() -> stopProcessor()
                    callMethod(uiController, "exit")
                    logger.debug("afterCopy: exit() called (native path)")
                } else {
                    logger.warn("afterCopy: UIController not found, fallback to closeCard")
                    closeCard(cardView)
                }
            } else if (closeCard) {
                closeCard(cardView)
            }
        } catch (t: Throwable) {
            logger.warn("afterCopy failed: $t")
        }
    }

    /** 关闭 AI 卡片（等同点卡片右上角关闭按钮）。 */
    private fun closeCard(cardView: View) {
        // 路径一：AICardView.mCardListener.close()
        try {
            val listener = getField(cardView, "mCardListener")
            if (listener != null) {
                callMethod(listener, "close")
                logger.debug("closeCard: mCardListener.close() called")
                return
            }
        } catch (t: Throwable) {
            logger.debug("closeCard via mCardListener failed: $t")
        }

        // 路径二：找右上角 ivClose 模拟点击
        try {
            val binding = getField(cardView, "mBinding")
            val ivClose = binding?.let { getField(it, "ivClose") }
            if (ivClose is View) {
                ivClose.performClick()
                logger.debug("closeCard: iv_close clicked")
            }
        } catch (t: Throwable) {
            logger.debug("closeCard via iv_close failed: $t")
        }
    }

    /** 从卡片向上找 UIController 实例（DetectResultView.mDetectListener）。 */
    private fun findUiController(cardView: View): Any? {
        try {
            var p: ViewParent? = cardView.parent
            var depth = 0
            while (p is View && depth < MAX_PARENT_DEPTH) {
                val listener = try {
                    getField(p, "mDetectListener")
                } catch (_: Throwable) {
                    null
                }
                if (listener != null && listener.javaClass.name.contains("UIController")) {
                    return listener
                }
                p = p.parent
                depth++
            }
        } catch (t: Throwable) {
            logger.debug("findUiController by parent failed: $t")
        }
        return null
    }

    /** 读取 AICardView.mOriginText（用户选中的原文）。 */
    private fun readOriginText(cardView: View): String? = try {
        getField(cardView, "mOriginText") as? String
    } catch (t: Throwable) {
        logger.debug("read mOriginText failed: $t")
        null
    }

    // ── 反射小工具（宿主字段在父类，逐层向上找）────────────────────────────

    private fun findField(obj: Any, name: String): Field? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            try {
                return cls.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        return null
    }

    private fun getField(obj: Any, name: String): Any? =
        findField(obj, name)?.get(obj)

    private fun callMethod(obj: Any, name: String): Any? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            val method = cls.declaredMethods.firstOrNull {
                it.name == name && it.parameterCount == 0
            }
            if (method != null) {
                method.isAccessible = true
                return method.invoke(obj)
            }
            cls = cls.superclass
        }
        throw NoSuchMethodException("$name in ${obj.javaClass.name}")
    }

    // ── 按钮外观 ───────────────────────────────────────────────────────────

    private fun buildTopLeftButton(ctx: Context): TextView {
        val tv = TextView(ctx)
        tv.tag = BTN_TAG
        tv.text = "复制原文"

        val density = ctx.resources.displayMetrics.density

        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        tv.gravity = Gravity.CENTER
        val padH = (11 * density).toInt()
        val padV = (5 * density).toInt()
        tv.setPadding(padH, padV, padH, padV)
        tv.isSingleLine = true
        tv.isClickable = true
        tv.isFocusable = true

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            (28 * density).toInt()
        )
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.leftMargin = (22 * density).toInt()
        lp.topMargin = (22 * density).toInt()
        tv.layoutParams = lp

        return tv
    }

    /**
     * 按浅色/深色模式设置按钮外观，配色取自宿主自身资源：
     *   浅色 —— 文字 #FF191919，背景浅灰半透明 + 描边 #FFE9E9E9
     *   深色 —— 文字 #FFFFFFFF，背景白色半透明 + 描边 12% 白
     */
    private fun applyButtonTheme(tv: TextView?, dark: Boolean) {
        if (tv == null) return
        try {
            val density = tv.resources.displayMetrics.density
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 13f * density
                if (dark) {
                    setColor(0x33FFFFFF)
                    setStroke((1 * density).toInt(), 0x1FFFFFFF)
                } else {
                    setColor(0xF2F2F2F2.toInt())
                    setStroke((1 * density).toInt(), 0xFFE9E9E9.toInt())
                }
            }
            tv.background = bg
            tv.setTextColor(if (dark) Color.WHITE else 0xFF191919.toInt())
            tv.alpha = 1.0f
        } catch (t: Throwable) {
            logger.warn("applyButtonTheme failed: $t")
        }
    }

    /** 与宿主 UiModeUtils.isDarkMode 一致：uiMode & 0x30 == 0x20 */
    private fun isDarkMode(ctx: Context): Boolean = try {
        (ctx.resources.configuration.uiMode and 0x30) == 0x20
    } catch (_: Throwable) {
        false
    }

    private fun refreshButtonTheme(cardView: View) {
        try {
            val btn = cardView.findViewWithTag<View>(BTN_TAG) as? TextView ?: return
            btn.context?.let { applyButtonTheme(btn, isDarkMode(it)) }
        } catch (t: Throwable) {
            logger.warn("refreshButtonTheme failed: $t")
        }
    }

    // ── 剪贴板与提示 ───────────────────────────────────────────────────────

    private fun copyToClipboard(ctx: Context?, text: String?): Boolean {
        if (ctx == null) return false
        if (text.isNullOrEmpty()) {
            toastOnMain(ctx, "没有可复制的选中文字")
            return false
        }
        return try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (cm == null) {
                toastOnMain(ctx, "复制失败")
                false
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("Selected Text", text))
                toastOnMain(ctx, "已复制选中文字")
                true
            }
        } catch (t: Throwable) {
            logger.warn("copy failed: $t")
            toastOnMain(ctx, "复制失败")
            false
        }
    }

    private fun toastOnMain(ctx: Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    private companion object {
        const val AI_CARD_VIEW_CLASS = "com.zui.ai.card.sdk.AICardView"

        /** 注入标记，避免重复添加按钮（视图可能被复用/重复 attach） */
        const val BTN_TAG = "ztool_ai_lens_copy_btn"

        /** 每帧后的重试次数上限 */
        const val MAX_RETRY = 8

        /** 向上找 UIController / 判断顶层卡片的最大父层深度 */
        const val MAX_PARENT_DEPTH = 8

        /**
         * 已注入过按钮的卡片实例。
         * WeakHashMap 保证卡片被宿主回收后不会造成泄漏。
         */
        val injectedCards: MutableMap<View, Boolean> = WeakHashMap()
    }
}

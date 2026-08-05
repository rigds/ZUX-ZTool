package com.qimian233.ztool.hook.modules.launcher.grid

import android.annotation.SuppressLint
import android.view.View
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method

/**
 * No-label mode for ZUI Launcher.
 *
 * Instead of clearing text content (which breaks TalkBack accessibility),
 * we hook setTextVisibility / setTextAlpha / setIgnoreSetAlphaVisible so that
 * labels are **visually hidden** while the text content stays intact for
 * screen readers.
 */
@SuppressLint("PrivateApi")
class LauncherNoLabelMode : AppHookModule() {

    override fun getModuleName(): String {
        return "launcher_no_label_mode"
    }

    override fun getTargetPackages(): Array<out String?> {
        return arrayOf("com.zui.launcher")
    }

    override fun handleLoadPackage(param: XposedModuleInterface.PackageLoadedParam) {
        installBubbleTextViewVisibilityHook(param)
        installActiveIconViewVisibilityHook(param)
    }

    /**
     * 终极判断过滤：采用“宽进严出”策略保护长按菜单。
     * 只要带有任何长按菜单/弹窗特征，就放行（显示文字）。
     * 否则，没有任何特征则判定为桌面或抽屉图标，强制隐藏。
     */
    private fun shouldForceHideLabel(view: View): Boolean {
        // 1. 检查 View 自身的类名
        val viewName = view.javaClass.simpleName
        if (viewName.contains("DeepShortcut") || viewName.contains("SystemShortcut") || viewName.contains("Popup")) {
            return false // 明确是菜单项，不隐藏（显示文字）
        }

        // 2. 检查所有父级容器
        var p = view.parent
        while (p != null) {
            val pName = p.javaClass.simpleName
            // 注意避开 ShortcutAndWidgetContainer (这是桌面放图标的容器)
            // 只要在弹窗/菜单容器里，绝对不隐藏
            if (pName.contains("Popup") || pName.contains("Arrow") || pName.contains("DeepShortcut") || pName.contains("SystemShortcut") || pName.contains("ShortcutsItem")) {
                return false // 身处长按菜单中，不隐藏（显示文字）
            }
            p = p.parent
        }

        // 3. 检查调用栈 (处理刚创建还没挂载，或者动画状态恢复的情况)
        val trace = Thread.currentThread().stackTrace
        for (frame in trace) {
            val cls = frame.className
            if (cls.contains("Popup") || cls.contains("DeepShortcut") || cls.contains("SystemShortcut") || cls.contains("ShortcutsItem")) {
                // 确保匹配到的不是桌面网格容器 ShortcutAndWidgetContainer
                if (!cls.contains("ShortcutAndWidgetContainer")) {
                    return false // 来源于长按菜单逻辑，不隐藏（显示文字）
                }
            }
        }

        // 没有任何弹窗菜单特征，彻底认定为桌面/抽屉应用，坚决隐藏！
        return true
    }

    /**
     * Hook BubbleTextView (普通应用图标)
     */
    fun installBubbleTextViewVisibilityHook(param: XposedModuleInterface.PackageLoadedParam) {
        try {
            val loader: ClassLoader = param.defaultClassLoader
            val bubbleTextViewClass: Class<*> =
                loader.loadClass("com.android.launcher3.BubbleTextView")

            val setTextVisibilityMethod: Method = findMethod(
                bubbleTextViewClass, "setTextVisibility",
                Boolean::class.javaPrimitiveType
            )
            hookWithId(setTextVisibilityMethod, "set_text_visibility_1") {  chain ->
                val view = chain.thisObject as View
                if (shouldForceHideLabel(view)) {
                    val args = arrayOf<Any?>(java.lang.Boolean.valueOf(false))
                    chain.proceed(args) // 强制隐藏
                } else {
                    chain.proceed(chain.args.toTypedArray()) // 放行，保持原有显示
                }
            }

            val setTextAlphaMethod: Method = findMethod(
                bubbleTextViewClass, "setTextAlpha",
                Float::class.javaPrimitiveType
            )
            hookWithId(setTextAlphaMethod, "set_text_alpha_1") {  chain ->
                val view = chain.thisObject as View
                if (shouldForceHideLabel(view)) {
                    val args = arrayOf<Any?>(java.lang.Float.valueOf(0.0f))
                    chain.proceed(args) // 强制透明
                } else {
                    chain.proceed(chain.args.toTypedArray()) // 放行
                }
            }

            logger.info("BubbleTextView visibility-block hook installed successfully!")
        } catch (e: Throwable) {
            logger.error("Exception caught in BubbleTextView visibility hook: ", e)
        }
    }

    /**
     * Hook ActiveIconView (系统动态应用图标)
     */
    fun installActiveIconViewVisibilityHook(param: XposedModuleInterface.PackageLoadedParam) {
        try {
            val loader: ClassLoader = param.defaultClassLoader
            val activeIconViewClass: Class<*> =
                loader.loadClass("com.zui.launcher.ActiveIconView")

            val setTextVisibilityMethod: Method = findMethod(
                activeIconViewClass, "setTextVisibility",
                Boolean::class.javaPrimitiveType
            )
            hookWithId(setTextVisibilityMethod, "set_text_visibility_2") {  chain ->
                val view = chain.thisObject as View
                if (shouldForceHideLabel(view)) {
                    val args = arrayOf<Any?>(java.lang.Boolean.valueOf(false))
                    chain.proceed(args)
                } else {
                    chain.proceed(chain.args.toTypedArray())
                }
            }

            val setTextAlphaMethod: Method = findMethod(
                activeIconViewClass, "setTextAlpha",
                Float::class.javaPrimitiveType
            )
            hookWithId(setTextAlphaMethod, "set_text_alpha_2") {  chain ->
                val view = chain.thisObject as View
                if (shouldForceHideLabel(view)) {
                    val args = arrayOf<Any?>(java.lang.Float.valueOf(0.0f))
                    chain.proceed(args)
                } else {
                    chain.proceed(chain.args.toTypedArray())
                }
            }

            val setIgnoreMethod: Method = findMethod(
                activeIconViewClass, "setIgnoreSetAlphaVisible",
                Boolean::class.javaPrimitiveType
            )
            hookWithId(setIgnoreMethod, "set_ignore") {  chain ->
                val args = arrayOf<Any?>(java.lang.Boolean.valueOf(false))
                chain.proceed(args)
            }

            logger.info("ActiveIconView visibility-block hook installed successfully!")
        } catch (e: Throwable) {
            logger.error("Exception caught in ActiveIconView visibility hook: ", e)
        }
    }

}

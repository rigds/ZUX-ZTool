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
 *
 * Covered paths:
 * - BubbleTextView: desktop & folder icons for non-ZUI apps, folder names
 * - ActiveIconView: desktop & folder icons for ZUI system apps
 *   (Calendar, SafeCenter, Lenovo Switch, etc.)
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
     * 精准判断当前 View 是否属于长按弹窗（而不是被弹窗恢复状态的桌面图标）
     */
    private fun isPopupItem(view: View): Boolean {
        // 1. 优先通过父级容器层级判断（最准确，能完美避开弹窗关闭时的状态恢复）
        var parent = view.parent
        while (parent != null) {
            val name = parent.javaClass.simpleName
            // 如果在这些弹窗容器里，说明是真正的菜单项，允许显示文字
            if (name.contains("Popup") || name.contains("DeepShortcut") || name.contains("ShortcutsItem")) {
                return true
            }
            // 如果在这些桌面/抽屉容器里，说明是桌面图标，坚决隐藏
            if (name.contains("Workspace") || name.contains("CellLayout") || name.contains("Folder") || name.contains("Hotseat")) {
                return false
            }
            parent = parent.parent
        }

        // 2. 如果 View 还没挂载（parent为null），退回到严格的调用栈检查（仅限初始化快捷方式时）
        return Thread.currentThread().stackTrace.any { frame ->
            val cls = frame.className
            val mtd = frame.methodName
            (cls.contains("PopupContainerWithArrow") && mtd.contains("initializeSystemShortcut")) ||
            (cls.contains("DeepShortcut") && mtd.contains("apply"))
        }
    }

    /**
     * Hook BubbleTextView.setTextVisibility and setTextAlpha so that labels
     * on BubbleTextView icons (non-ZUI apps, folder names) are always hidden.
     */
    fun installBubbleTextViewVisibilityHook(param: XposedModuleInterface.PackageLoadedParam) {
        try {
            val loader: ClassLoader = param.defaultClassLoader
            val bubbleTextViewClass: Class<*> =
                loader.loadClass("com.android.launcher3.BubbleTextView")

            // Force setTextVisibility to always hide
            val setTextVisibilityMethod: Method = findMethod(
                bubbleTextViewClass, "setTextVisibility",
                Boolean::class.javaPrimitiveType
            )
            hookWithId(setTextVisibilityMethod, "set_text_visibility_1") {  chain ->
                val view = chain.thisObject as View
                if (isPopupItem(view)) {
                    chain.proceed(chain.args.toTypedArray())
                } else {
                    val args = arrayOf<Any?>(java.lang.Boolean.valueOf(false))
                    chain.proceed(args)
                }
            }

            // Force setTextAlpha to always stay at 0 (hidden).
            val setTextAlphaMethod: Method = findMethod(
                bubbleTextViewClass, "setTextAlpha",
                Float::class.javaPrimitiveType
            )
            hookWithId(setTextAlphaMethod, "set_text_alpha_1") {  chain ->
                val view = chain.thisObject as View
                if (isPopupItem(view)) {
                    chain.proceed(chain.args.toTypedArray())
                } else {
                    val args = arrayOf<Any?>(java.lang.Float.valueOf(0.0f))
                    chain.proceed(args)
                }
            }

            logger.info("BubbleTextView visibility-block hook installed successfully!")
        } catch (e: Throwable) {
            logger.error("Exception caught in BubbleTextView visibility hook: ", e)
        }
    }

    /**
     * Hook ActiveIconView.setTextVisibility, setTextAlpha and
     * setIgnoreSetAlphaVisible so that labels on ActiveIconView icons
     * are always hidden. (长按菜单里不用这个类，直接无脑隐藏)
     */
    fun installActiveIconViewVisibilityHook(param: XposedModuleInterface.PackageLoadedParam) {
        try {
            val loader: ClassLoader = param.defaultClassLoader
            val activeIconViewClass: Class<*> =
                loader.loadClass("com.zui.launcher.ActiveIconView")

            // Force setTextVisibility to always hide
            val setTextVisibilityMethod: Method = findMethod(
                activeIconViewClass, "setTextVisibility",
                Boolean::class.javaPrimitiveType
            )
            hookWithId(setTextVisibilityMethod, "set_text_visibility_2") {  chain ->
                val args = arrayOf<Any?>(java.lang.Boolean.valueOf(false))
                chain.proceed(args)
            }

            // Force setTextAlpha to always stay at 0 (hidden)
            val setTextAlphaMethod: Method = findMethod(
                activeIconViewClass, "setTextAlpha",
                Float::class.javaPrimitiveType
            )
            hookWithId(setTextAlphaMethod, "set_text_alpha_2") {  chain ->
                val args = arrayOf<Any?>(java.lang.Float.valueOf(0.0f))
                chain.proceed(args)
            }

            // Prevent setIgnoreSetAlphaVisible from being set to true
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

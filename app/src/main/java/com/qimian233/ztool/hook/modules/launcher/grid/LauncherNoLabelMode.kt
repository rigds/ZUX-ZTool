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
     * 三层漏斗精准判断当前 View 是否属于长按弹窗
     */
    private fun isFromPopup(view: View): Boolean {
        // 1. 检查父级容器（最靠谱，专门对付已经挂载的 View 和状态恢复）
        var parent = view.parent
        while (parent != null) {
            val name = parent.javaClass.simpleName
            if (name.contains("Popup") || name.contains("DeepShortcut") || name.contains("ShortcutsItem")) {
                return true // 明确在长按菜单中
            }
            if (name.contains("Workspace") || name.contains("CellLayout") || name.contains("Folder") || name.contains("Hotseat") || name.contains("AllApps") || name.contains("RecyclerView")) {
                return false // 明确在桌面或抽屉中，坚决隐藏
            }
            parent = parent.parent
        }

        // 2. 检查布局参数（专门对付即将加入桌面/抽屉，但 parent 还是 null 的 View）
        val lp = view.layoutParams
        if (lp != null) {
            val lpName = lp.javaClass.simpleName
            if (lpName.contains("CellLayout") || lpName.contains("RecyclerView") || lpName.contains("AllApps")) {
                return false // 带有桌面或抽屉特征的布局，坚决隐藏
            }
        }

        // 3. 兜底策略：刚 new 出来的 View，用宽松的调用栈判断（完美修复长按菜单不显示文字）
        return Thread.currentThread().stackTrace.any { frame ->
            val cls = frame.className
            cls.contains("PopupContainerWithArrow") ||
            cls.contains("DeepShortcut") ||
            cls.contains("SystemShortcut") ||
            cls.contains("ShortcutsItemView")
        }
    }

    /**
     * Hook BubbleTextView.setTextVisibility and setTextAlpha
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
                if (isFromPopup(view)) {
                    chain.proceed(chain.args.toTypedArray())
                } else {
                    val args = arrayOf<Any?>(java.lang.Boolean.valueOf(false))
                    chain.proceed(args)
                }
            }

            val setTextAlphaMethod: Method = findMethod(
                bubbleTextViewClass, "setTextAlpha",
                Float::class.javaPrimitiveType
            )
            hookWithId(setTextAlphaMethod, "set_text_alpha_1") {  chain ->
                val view = chain.thisObject as View
                if (isFromPopup(view)) {
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
     * Hook ActiveIconView.setTextVisibility, setTextAlpha and setIgnoreSetAlphaVisible
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
                if (isFromPopup(view)) {
                    chain.proceed(chain.args.toTypedArray())
                } else {
                    val args = arrayOf<Any?>(java.lang.Boolean.valueOf(false))
                    chain.proceed(args)
                }
            }

            val setTextAlphaMethod: Method = findMethod(
                activeIconViewClass, "setTextAlpha",
                Float::class.javaPrimitiveType
            )
            hookWithId(setTextAlphaMethod, "set_text_alpha_2") {  chain ->
                val view = chain.thisObject as View
                if (isFromPopup(view)) {
                    chain.proceed(chain.args.toTypedArray())
                } else {
                    val args = arrayOf<Any?>(java.lang.Float.valueOf(0.0f))
                    chain.proceed(args)
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

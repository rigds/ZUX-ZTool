package com.qimian233.ztool.hook.modules.launcher.grid

import android.annotation.SuppressLint
import android.view.View
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method

/**
 * No-label mode for ZUI Launcher.
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
     * 终极逻辑：彻底阻断桌面图标状态恢复导致的名字重现。
     */
    private fun shouldForceHideLabel(view: View): Boolean {
        // 1. 优先通过父容器精确识别身份 (这是对付长按结束后状态恢复的最强杀招)
        var p = view.parent
        while (p != null) {
            val pName = p.javaClass.simpleName
            
            // 如果它住在桌面、文件夹、全部应用（抽屉）里，绝对是需要隐藏的桌面图标！
            if (pName.contains("Workspace") || 
                pName.contains("CellLayout") || 
                pName.contains("Folder") || 
                pName.contains("AllApps") || 
                pName.contains("RecyclerView") || 
                pName.contains("ShortcutAndWidgetContainer") || 
                pName.contains("Hotseat")) {
                return true // 强制隐藏
            }

            // 如果它住在弹窗菜单的布局中，绝对是菜单项
            if (pName.contains("Popup") || 
                pName.contains("DeepShortcut") || 
                pName.contains("SystemShortcut") || 
                pName.contains("ShortcutsItem")) {
                return false // 允许显示文字
            }
            p = p.parent
        }

        // 2. 如果 parent 为 null (刚被 new 出来还没添加到界面里)
        val trace = Thread.currentThread().stackTrace
        for (frame in trace) {
            val cls = frame.className
            val mtd = frame.methodName

            // 如果是长按菜单正在【创建/绑定】快捷方式，放行
            if (cls.contains("Popup") || cls.contains("Shortcut")) {
                // 重点：必须是创建方法，绝不能是 close 等动画恢复方法！
                if (mtd.contains("initialize") || mtd.contains("populate") || mtd.contains("create") || mtd.contains("bind") || mtd.contains("apply")) {
                    return false
                }
            }
        }

        // 默认兜底：隐藏
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
                    chain.proceed(chain.args.toTypedArray()) // 放行
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
     * Hook ActiveIconView (系统动态应用图标，长按菜单不用这个，无条件隐藏)
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
                val args = arrayOf<Any?>(java.lang.Boolean.valueOf(false))
                chain.proceed(args)
            }

            val setTextAlphaMethod: Method = findMethod(
                activeIconViewClass, "setTextAlpha",
                Float::class.javaPrimitiveType
            )
            hookWithId(setTextAlphaMethod, "set_text_alpha_2") {  chain ->
                val args = arrayOf<Any?>(java.lang.Float.valueOf(0.0f))
                chain.proceed(args)
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

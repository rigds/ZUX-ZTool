package com.qimian233.ztool.hook.modules.gametool

import android.util.TypedValue
import android.widget.TextView
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Danmu (barrage) notification text-size hook for the game service app.
 *
 * <p>The notification barrage text is inflated from a layout that hardcodes
 * {@code android:textSize} and the Java side never calls {@code setTextSize} again,
 * so the only place where the size can be reliably overridden is right after
 * {@code NotificationMsgController.getTextView(...)} returns.
 *
 * <p>{@code 0f} (or anything {@code <= 0f}) means "disabled": the hook installs but every
 * callback returns immediately, so the system keeps its original size.
 */
class DanmuFontSizeHook : AppHookModule() {

    override fun getModuleName(): String = PreferenceKeys.DANMU_FONT_SIZE_ENABLE.name

    override fun getTargetPackages(): Array<out String> = arrayOf(ScopeKeys.GAME_SERVICE.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        hookNotificationDanmu(param.defaultClassLoader)
    }

    /**
     * Hooks {@code getTextView} and rescales the returned TextView.
     *
     * <p>After {@code getTextView} the app only calls setText/setVisibility/setAlpha and
     * never resets the text size, so an override in the after-phase sticks.
     */
    private fun hookNotificationDanmu(classLoader: ClassLoader) {
        try {
            val cls = classLoader.loadClass(NOTIF_CLASS)

            val getTextView = cls.declaredMethods.firstOrNull { it.name == "getTextView" }
            if (getTextView == null) {
                logger.error("getTextView not found in $NOTIF_CLASS")
                return
            }

            hookWithId(getTextView, "danmu_font_size_get_text_view") { chain ->
                val result = chain.proceed()
                val sp = readSizeSp()
                if (sp > 0f && result is TextView) {
                    val before = px2sp(result)
                    result.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                    scaleCompoundIcon(result, before, sp)
                    logger.debug("getTextView -> ${before}sp => ${sp}sp")
                }
                result
            }

            logger.info("Successfully hooked $NOTIF_CLASS.getTextView")
        } catch (t: Throwable) {
            logger.error("Failed to hook danmu font size", t)
        }
    }

    /**
     * Keeps a compound (left/right) icon proportional to the text height.
     *
     * <p>The app sets the icon bounds once, before the text size is changed; without this
     * adjustment the icon keeps its old pixel size and no longer matches the text.
     *
     * <p>Important: the new size is derived from the drawable's <em>current bounds</em>, which is
     * the only value the app is guaranteed to have set. {@code getIntrinsicWidth()} frequently
     * returns -1 (bitmaps without density, vector drawables, remotely loaded icons), and scaling
     * -1 yields a negative width/height, which makes the framework fall back to a degenerate
     * vertical rectangle. Both axes are therefore scaled by the same factor, keeping the aspect
     * ratio and the centre point exactly as the app laid them out.
     */
    private fun scaleCompoundIcon(tv: TextView, beforeSp: Float, afterSp: Float) {
        if (beforeSp <= 0f || afterSp <= 0f) return
        try {
            val ds = tv.compoundDrawables
            if (ds.all { it == null }) return

            val ratio = afterSp / beforeSp
            if (ratio > 0.98f && ratio < 1.02f) return

            for (d in ds) {
                if (d == null) continue
                val b = d.bounds
                val baseW = if (b.width() > 0) b.width() else d.intrinsicWidth
                val baseH = if (b.height() > 0) b.height() else d.intrinsicHeight
                if (baseW <= 0 || baseH <= 0) continue

                val w = (baseW * ratio).toInt().coerceAtLeast(1)
                val h = (baseH * ratio).toInt().coerceAtLeast(1)

                // Scale around the existing centre so the icon stays where the app put it.
                val cx = (b.left + b.right) / 2f
                val cy = (b.top + b.bottom) / 2f
                val left = (cx - w / 2f).toInt()
                val top = (cy - h / 2f).toInt()
                d.setBounds(left, top, left + w, top + h)
            }
            // Re-apply so the TextView re-layouts with the new bounds.
            tv.setCompoundDrawables(ds[0], ds[1], ds[2], ds[3])
        } catch (t: Throwable) {
            logger.error("Failed to scale compound icon", t)
        }
    }

    private fun px2sp(tv: TextView): Float =
        tv.textSize / tv.resources.displayMetrics.scaledDensity

    /**
     * Reads the configured size in sp. {@code <= 0f} means the feature is off.
     *
     * <p>Read on every render so a change takes effect on the very next barrage without
     * restarting the game service. RemotePreferences is cached in-process, so this is cheap.
     */
    private fun readSizeSp(): Float {
        return try {
            remotePreferences.getFloat(PreferenceKeys.DANMU_FONT_SIZE.name, 0f)
        } catch (t: Throwable) {
            logger.debug("Failed to read danmu font size: ${t.message}")
            0f
        }
    }

    companion object {
        private const val NOTIF_CLASS = "com.zui.game.service.ui.NotificationMsgController"
    }
}

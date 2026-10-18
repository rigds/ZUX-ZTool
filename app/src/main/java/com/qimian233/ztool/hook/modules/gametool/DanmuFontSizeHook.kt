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
                    // Set the size first so the following measure pass sees it, then resize
                    // the compound icon and force a re-measure. Order matters: the TextView
                    // allocates horizontal space for a compound drawable from its *bounds*,
                    // so growing the bounds after a measure pass leaves the icon clipped.
                    result.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                    scaleCompoundIcon(result, before, sp)
                    result.requestLayout()
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
     * Keeps the compound (left) app icon sized to the text height.
     *
     * <p>The barrage text's app icon is a compound drawable, not a separate ImageView. The app
     * sets its bounds once with {@code setCompoundDrawables}, using the text height at that
     * moment, and never recomputes them — so changing the text size afterwards leaves the icon
     * at the old pixel size.
     *
     * <p>A TextView allocates horizontal space for a compound drawable from the drawable's
     * <em>bounds</em>, and draws it inside that allocated box. Resizing the bounds by a ratio of
     * the previous bounds is therefore fragile: the old bounds may already carry a size written
     * by an earlier pass, so the error compounds and the icon ends up clipped. Instead the size
     * is recomputed from scratch as an absolute, square pixel value derived from the target text
     * size — the same approach that is proven to work.
     */
    private fun scaleCompoundIcon(tv: TextView, beforeSp: Float, afterSp: Float) {
        if (beforeSp <= 0f || afterSp <= 0f) return
        try {
            val ds = tv.compoundDrawables
            if (ds.all { it == null }) return

            val ratio = afterSp / beforeSp
            if (ratio <= 1.01f) return

            val dm = tv.resources.displayMetrics
            val textH = Math.round(afterSp * dm.scaledDensity)
            val iconSize = Math.round(textH * ICON_SIZE_FACTOR)

            for (d in ds) {
                if (d == null) continue
                // Always a square anchored at the origin, so the aspect ratio can never skew.
                d.setBounds(0, 0, iconSize, iconSize)
            }
            // Re-apply so the TextView re-measures and allocates the new drawable width.
            tv.setCompoundDrawables(ds[0], ds[1], ds[2], ds[3])
            logger.debug("compoundIcon: iconSize=${iconSize}px (ratio x$ratio, textH=${textH}px)")
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

        /** Icon edge length relative to the text height; matches the app's own proportions. */
        private const val ICON_SIZE_FACTOR = 1.15f
    }
}

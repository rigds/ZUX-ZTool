package com.qimian233.ztool.data.ailens

import android.content.Context
import com.qimian233.ztool.EnhancedShellExecutor
import com.qimian233.ztool.data.features.FeatureDestination
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.utils.ModulePreferencesUtils
import com.qimian233.ztool.utils.ScopeUtils

/**
 * 智慧识屏（com.zui.ai.lens）复制原文按钮相关设置。
 */
class AiLensSettingsRepository(
    private val context: Context,
    private val shellExecutor: EnhancedShellExecutor = EnhancedShellExecutor.getInstance()
) {
    private val prefsUtils = ModulePreferencesUtils(context)

    fun loadState(): AiLensSettingsUiState = AiLensSettingsUiState(
        showCopyButton = prefsUtils.loadBooleanSetting(
            KEY_SHOW_COPY_BUTTON, DEFAULT_SHOW_COPY_BUTTON
        ),
        closeCardAfterCopy = prefsUtils.loadBooleanSetting(
            KEY_CLOSE_CARD_AFTER_COPY, DEFAULT_CLOSE_CARD_AFTER_COPY
        ),
        exitLensAfterCopy = prefsUtils.loadBooleanSetting(
            KEY_EXIT_LENS_AFTER_COPY, DEFAULT_EXIT_LENS_AFTER_COPY
        )
    )

    fun saveShowCopyButton(enabled: Boolean) {
        prefsUtils.saveBooleanSetting(KEY_SHOW_COPY_BUTTON, enabled)
    }

    fun saveCloseCardAfterCopy(enabled: Boolean) {
        prefsUtils.saveBooleanSetting(KEY_CLOSE_CARD_AFTER_COPY, enabled)
    }

    fun saveExitLensAfterCopy(enabled: Boolean) {
        prefsUtils.saveBooleanSetting(KEY_EXIT_LENS_AFTER_COPY, enabled)
    }

    fun restartScope(): AiLensRestartResult {
        val scopes = ScopeUtils.getScopes(FeatureDestination.AiLens)
        return when (val result = ScopeUtils.restartScope(scopes, shellExecutor)) {
            is ScopeUtils.RestartResult.Success -> AiLensRestartResult.Success
            is ScopeUtils.RestartResult.PartialSuccess -> AiLensRestartResult.Failure(
                "Partial failure: ${result.failed.joinToString()}"
            )
            is ScopeUtils.RestartResult.Failure -> AiLensRestartResult.Failure(result.message)
        }
    }

    companion object {
        private val KEY_SHOW_COPY_BUTTON = PreferenceKeys.AI_LENS_COPY_TEXT.name
        private val DEFAULT_SHOW_COPY_BUTTON = PreferenceKeys.AI_LENS_COPY_TEXT.default

        private val KEY_CLOSE_CARD_AFTER_COPY = PreferenceKeys.AI_LENS_CLOSE_CARD_AFTER_COPY.name
        private val DEFAULT_CLOSE_CARD_AFTER_COPY =
            PreferenceKeys.AI_LENS_CLOSE_CARD_AFTER_COPY.default

        private val KEY_EXIT_LENS_AFTER_COPY = PreferenceKeys.AI_LENS_EXIT_LENS_AFTER_COPY.name
        private val DEFAULT_EXIT_LENS_AFTER_COPY =
            PreferenceKeys.AI_LENS_EXIT_LENS_AFTER_COPY.default
    }
}

data class AiLensSettingsUiState(
    val showCopyButton: Boolean = true,
    val closeCardAfterCopy: Boolean = false,
    val exitLensAfterCopy: Boolean = true,
    val showRestartDialog: Boolean = false
)

sealed interface AiLensRestartResult {
    data object Success : AiLensRestartResult
    data class Failure(val error: String) : AiLensRestartResult
}

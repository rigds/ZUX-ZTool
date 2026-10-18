package com.qimian233.ztool.screens.ailens

import android.widget.Toast
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.qimian233.ztool.R
import com.qimian233.ztool.data.ailens.AiLensSettingsRepository
import com.qimian233.ztool.data.ailens.AiLensSettingsUiState
import com.qimian233.ztool.ui.components.HighlightAnchorRegistry
import com.qimian233.ztool.ui.components.HighlightController
import com.qimian233.ztool.ui.components.SettingItem
import com.qimian233.ztool.ui.components.SettingSection
import com.qimian233.ztool.ui.components.ZToolDialog
import com.qimian233.ztool.ui.components.ZToolExtendedFloatingActionButton
import com.qimian233.ztool.ui.components.ZToolScaffold
import com.qimian233.ztool.ui.components.ZToolSettingsList
import com.qimian233.ztool.ui.components.ZToolTextButton
import com.qimian233.ztool.ui.components.ZToolTopAppBar
import com.qimian233.ztool.viewmodel.AiLensSettingsViewModel

@Composable
fun AiLensSettingsRoute(
    title: String,
    packageName: String,
    onBack: () -> Unit,
    targetId: String? = null
) {
    val context = LocalContext.current
    val owner = LocalViewModelStoreOwner.current
        ?: error("AiLensSettingsRoute requires a ViewModelStoreOwner")
    val viewModel = remember(owner) {
        ViewModelProvider(
            owner,
            AiLensSettingsViewModelFactory(
                AiLensSettingsRepository(context.applicationContext)
            )
        )[AiLensSettingsViewModel::class.java]
    }

    LaunchedEffect(viewModel) {
        viewModel.loadSettings()
    }

    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    val highlightRegistry = remember { HighlightAnchorRegistry() }

    HighlightController(
        highlightTargetId = targetId,
        scrollState = scrollState,
        registry = highlightRegistry,
        onConsumed = { }
    ) {
        AiLensSettingsScreen(
            title = title,
            state = uiState,
            onBack = onBack,
            onShowCopyButtonChanged = viewModel::setShowCopyButton,
            onCloseCardAfterCopyChanged = viewModel::setCloseCardAfterCopy,
            onExitLensAfterCopyChanged = viewModel::setExitLensAfterCopy,
            onRestartScope = viewModel::showRestartDialog,
            scrollState = scrollState,
            highlightRegistry = highlightRegistry
        )
    }

    if (uiState.showRestartDialog) {
        ZToolDialog(
            onDismissRequest = viewModel::dismissRestartDialog,
            title = { Text(stringResource(R.string.common_restart_xp_title)) },
            text = {
                Text(
                    stringResource(R.string.common_restart_xp_message_header) +
                            packageName + " " +
                            stringResource(R.string.common_restart_xp_message)
                )
            },
            confirmButton = {
                ZToolTextButton(
                    onClick = {
                        viewModel.restartScope(
                            onFailure = {
                                Toast.makeText(
                                    context,
                                    R.string.system_update_restart_failed,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    },
                    text = stringResource(R.string.common_restart_yes)
                )
            },
            dismissButton = {
                ZToolTextButton(
                    onClick = viewModel::dismissRestartDialog,
                    text = stringResource(R.string.common_restart_no),
                    isPrimary = false
                )
            }
        )
    }
}

private class AiLensSettingsViewModelFactory(
    private val repository: AiLensSettingsRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AiLensSettingsViewModel::class.java)) {
            return AiLensSettingsViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

@Composable
private fun AiLensSettingsScreen(
    title: String,
    state: AiLensSettingsUiState,
    onBack: () -> Unit,
    onShowCopyButtonChanged: (Boolean) -> Unit,
    onCloseCardAfterCopyChanged: (Boolean) -> Unit,
    onExitLensAfterCopyChanged: (Boolean) -> Unit,
    onRestartScope: () -> Unit,
    scrollState: ScrollState,
    highlightRegistry: HighlightAnchorRegistry
) {
    ZToolScaffold(
        topBar = {
            ZToolTopAppBar(
                title = title,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                }
            )
        },
        floatingActionButton = {
            ZToolExtendedFloatingActionButton(
                onClick = onRestartScope,
                icon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                text = { Text(stringResource(R.string.common_restart_yes)) }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 960.dp)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp, vertical = 24.dp)
            ) {
                ZToolSettingsList(
                    sections = aiLensSettingsSections(
                        state = state,
                        onShowCopyButtonChanged = onShowCopyButtonChanged,
                        onCloseCardAfterCopyChanged = onCloseCardAfterCopyChanged,
                        onExitLensAfterCopyChanged = onExitLensAfterCopyChanged
                    ),
                    bottomPadding = 88.dp,
                    highlightRegistry = highlightRegistry
                )
            }
        }
    }
}

@Composable
private fun aiLensSettingsSections(
    state: AiLensSettingsUiState,
    onShowCopyButtonChanged: (Boolean) -> Unit,
    onCloseCardAfterCopyChanged: (Boolean) -> Unit,
    onExitLensAfterCopyChanged: (Boolean) -> Unit
): List<SettingSection> = listOf(
    SettingSection(
        title = stringResource(R.string.ai_lens_section_copy_button),
        items = listOf(
            SettingItem.Switch(
                title = stringResource(R.string.ai_lens_copy_button_title),
                summary = stringResource(R.string.ai_lens_copy_button_summary),
                checked = state.showCopyButton,
                onCheckedChange = onShowCopyButtonChanged,
                key = "ai_lens_copy_text"
            )
        )
    ),
    SettingSection(
        title = stringResource(R.string.ai_lens_section_after_copy),
        items = listOf(
            SettingItem.Switch(
                title = stringResource(R.string.ai_lens_close_card_title),
                summary = stringResource(R.string.ai_lens_close_card_summary),
                checked = state.closeCardAfterCopy,
                onCheckedChange = onCloseCardAfterCopyChanged,
                enabled = state.showCopyButton,
                key = "ai_lens_close_card_after_copy"
            ),
            SettingItem.Switch(
                title = stringResource(R.string.ai_lens_exit_lens_title),
                summary = stringResource(R.string.ai_lens_exit_lens_summary),
                checked = state.exitLensAfterCopy,
                onCheckedChange = onExitLensAfterCopyChanged,
                enabled = state.showCopyButton,
                key = "ai_lens_exit_lens_after_copy"
            )
        )
    ),
    SettingSection(
        items = listOf(
            SettingItem.Custom(
                content = {
                    Text(
                        text = stringResource(R.string.ai_lens_usage_tip),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                },
                key = "ai_lens_usage_tip"
            )
        )
    )
)

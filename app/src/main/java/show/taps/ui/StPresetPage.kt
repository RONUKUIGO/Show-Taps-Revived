package show.taps.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rosan.installer.R
import com.rosan.installer.ui.page.miuix.widgets.MiuixBadge
import com.rosan.installer.ui.theme.getMiuixAppBarColor
import com.rosan.installer.ui.theme.installerMiuixBlurEffect
import com.rosan.installer.ui.theme.rememberMiuixBlurBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import show.taps.settings.StPreset
import show.taps.settings.StPresetKind
import show.taps.settings.StPresetStore
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「预设」页：接管 IRX 原来的「记录」页位置。
 *
 * 这里只做预设/效果包的**库**：导入、展示、套用、删除。
 *  - 右下角的 + 由外层 Scaffold 的 FAB 提供（和「配置」页同一个按钮），
 *    点击后通过 [StPresetStore.requestImport] 通知本页拉起文件选择器。
 *  - 预设（PRESET）导入后点卡片即可套用成一套配置。
 *  - 效果包（PACK）当前只做保存 + 素材解压，渲染引擎接入后才会真正作画。
 */
@Composable
fun MiuixStPresetPage(
    enableBlur: Boolean,
    title: String,
    outerPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val layoutDirection = LocalLayoutDirection.current
    val backdrop = rememberMiuixBlurBackdrop(enableBlur)

    val set by StPresetStore.state.collectAsState()
    val importRequest by StPresetStore.importRequest.collectAsState()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { StPresetStore.init(context) }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) { StPresetStore.import(context, uri) }
                result.onSuccess { preset ->
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.st_preset_imported, preset.name)
                    )
                }.onFailure { e ->
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.st_preset_import_failed, e.message ?: "?")
                    )
                }
            }
        }
    }

    // FAB 每次点击都会 +1，这里响应它；>0 才拉起选择器（初值 0 不触发）
    LaunchedEffect(importRequest) {
        if (importRequest > 0L) {
            // 用 */* 而不是限定 MIME：很多文件管理器的 .json/.zip/.sttheme 上报的 MIME 五花八门，
            // 限定太死会导致文件在选择器里是灰的。真正的类型判断在 StPresetStore 里靠内容嗅探。
            picker.launch(arrayOf("*/*"))
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                modifier = Modifier.installerMiuixBlurEffect(backdrop),
                color = backdrop.getMiuixAppBarColor(),
                title = title,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDirection) + 12.dp,
                top = innerPadding.calculateTopPadding() + 12.dp,
                end = innerPadding.calculateEndPadding(layoutDirection) + 12.dp,
                bottom = outerPadding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (set.items.isEmpty()) {
                item { StPresetEmptyCard() }
            } else {
                item {
                    Text(
                        text = "共 ${set.items.size} 项 · 点击预设套用，点右侧按钮删除",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                items(set.items, key = { it.id }) { preset ->
                    StPresetCard(
                        preset = preset,
                        onApply = {
                            if (StPresetStore.apply(context, preset)) {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.st_preset_applied, preset.name)
                                    )
                                }
                            } else {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.st_preset_import_failed, preset.name)
                                    )
                                }
                            }
                        },
                        onDelete = {
                            StPresetStore.delete(context, preset.id)
                        },
                    )
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun StPresetEmptyCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Text(
                text = stringResource(R.string.st_preset_empty_title),
                style = MiuixTheme.textStyles.title4,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.st_preset_empty_desc),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.subtitle,
            )
        }
    }
}

@Composable
private fun StPresetCard(
    preset: StPreset,
    onApply: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onApply)
                .padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = preset.name,
                    style = MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.width(8.dp))
                MiuixBadge(text = preset.kindLabel())
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = preset.summary(),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.subtitle,
            )
        }

        HorizontalDivider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (preset.kind == StPresetKind.PACK) "素材：" +
                        (preset.assetDir?.let { "已解压" } ?: "无") else preset.kindLabel(),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                minHeight = 35.dp,
                minWidth = 35.dp,
                backgroundColor = MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
                onClick = onDelete,
            ) {
                Icon(
                    modifier = Modifier.size(20.dp),
                    imageVector = MiuixIcons.Regular.Delete,
                    tint = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                    contentDescription = stringResource(R.string.st_preset_delete),
                )
            }
        }
    }
}

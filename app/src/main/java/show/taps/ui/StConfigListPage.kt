package show.taps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rosan.installer.ui.navigation.LocalNavigator
import com.rosan.installer.ui.navigation.Route
import com.rosan.installer.ui.page.miuix.widgets.MiuixBadge
import com.rosan.installer.ui.theme.getMiuixAppBarColor
import com.rosan.installer.ui.theme.installerMiuixBlurEffect
import com.rosan.installer.ui.theme.rememberMiuixBlurBackdrop
import show.taps.StEngine
import show.taps.rememberStConfigSet
import show.taps.settings.StConfig
import show.taps.settings.StConfigStore
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
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * ST 配置列表（接手 IRX 原来的「配置」页）。
 *
 * 卡片沿用 IRX 原来的配置卡结构（标题 / 副标题 / 分割线 / 底部操作行），只把底部那排换成
 * 「该配置的 10 个 ST 颜色 + 编辑按钮」。
 *
 *  - 点整张卡 → 立刻切换为「使用中」（快捷切换）
 *  - 右下角铅笔 → 进编辑页
 *  - 右上「新增」→ 新建一套
 */
@Composable
fun MiuixStConfigListPage(
    enableBlur: Boolean,
    title: String,
    outerPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
) {
    val navigator = LocalNavigator.current
    val scrollBehavior = MiuixScrollBehavior()
    val layoutDirection = LocalLayoutDirection.current
    val configSet = rememberStConfigSet()
    val backdrop = rememberMiuixBlurBackdrop(enableBlur)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                modifier = Modifier.installerMiuixBlurEffect(backdrop),
                color = backdrop.getMiuixAppBarColor(),
                title = "ST 配置",
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
                bottom = outerPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "共 ${configSet.configs.size} 套 · 点击卡片切换生效配置",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            items(configSet.configs, key = { it.id }) { cfg ->
                StConfigCard(
                    cfg = cfg,
                    active = cfg.id == configSet.activeId,
                    onSelect = {
                        StConfigStore.setActive(cfg.id)
                        StConfigStore.byId(cfg.id)?.let { StEngine.onConfigChanged(it) }
                    },
                    onEdit = { navigator.push(Route.EditConfig(cfg.id)) },
                )
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

/**
 * 单张配置卡（结构对齐 IRX 的 DataItemWidget）：
 *   主标题 / 副标题 / 分割线 / [10 色 + 编辑]
 */
@Composable
private fun StConfigCard(
    cfg: StConfig,
    active: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        // ── 主标题 + 副标题 ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = cfg.name,
                    style = MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.Medium,
                )
                if (active) {
                    Spacer(modifier = Modifier.width(8.dp))
                    MiuixBadge(text = "使用中")
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = cfg.summary(),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.subtitle,
            )
        }

        HorizontalDivider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )

        // ── 底部：该配置的触摸颜色 + 编辑 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                cfg.colors.take(10).forEach { argb ->
                    Box(
                        modifier = Modifier
                            .size(19.dp)
                            .clip(CircleShape)
                            .background(Color(argb)),
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            IconButton(
                minHeight = 35.dp,
                minWidth = 35.dp,
                backgroundColor = MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
                onClick = onEdit,
            ) {
                Icon(
                    modifier = Modifier.size(20.dp),
                    imageVector = MiuixIcons.Regular.Edit,
                    tint = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                    contentDescription = "编辑",
                )
            }
        }
    }
}

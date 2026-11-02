package show.taps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rosan.installer.R
import com.rosan.installer.ui.navigation.LocalNavigator
import com.rosan.installer.ui.page.miuix.widgets.MiuixBackButton
import com.rosan.installer.ui.page.miuix.widgets.MiuixHintTextField
import com.rosan.installer.ui.theme.getMiuixAppBarColor
import com.rosan.installer.ui.theme.installerMiuixBlurEffect
import com.rosan.installer.ui.theme.rememberMiuixBlurBackdrop
import show.taps.StEngine
import show.taps.rememberStConfigSet
import show.taps.settings.StConfig
import show.taps.settings.StConfigStore
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * ST 配置编辑页（接手 IRX 原来的 `Route.EditConfig`）。
 *
 * @param id null / -1 → 新建
 */
@Composable
fun MiuixStConfigEditPage(id: Long?, useBlur: Boolean) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val layoutDirection = LocalLayoutDirection.current
    val backdrop = rememberMiuixBlurBackdrop(useBlur)
    val snackbarHostState = remember { SnackbarHostState() }
    val configSet = rememberStConfigSet()

    val existing = remember(id, configSet.configs) { StConfigStore.byId(id) }
    val isNew = existing == null

    // 编辑草稿（进入页面时快照一份，保存时才落地）
    var name by remember(id, configSet.configs) {
        mutableStateOf(existing?.name ?: "配置 ${configSet.configs.size + 1}")
    }
    var pointSize by remember(id, configSet.configs) { mutableStateOf(existing?.pointSize ?: 80) }
    var fadeTime by remember(id, configSet.configs) { mutableStateOf(existing?.fadeTime ?: 200) }
    var circleStroke by remember(id, configSet.configs) { mutableStateOf(existing?.circleStroke ?: 5) }
    var lineStroke by remember(id, configSet.configs) { mutableStateOf(existing?.lineStroke ?: 5) }
    var alpha by remember(id, configSet.configs) { mutableStateOf(existing?.alpha ?: 100) }
    var colors by remember(id, configSet.configs) {
        mutableStateOf(existing?.colors ?: StConfig.DEFAULT_COLORS)
    }
    // 正在编辑第几个手指的颜色（ = 没弹窗）
    var editingColorIndex by remember { mutableStateOf(-1) }

    fun buildConfig(): StConfig = (existing ?: StConfig()).copy(
        name = name.ifBlank { "未命名配置" },
        pointSize = pointSize,
        fadeTime = fadeTime,
        circleStroke = circleStroke,
        lineStroke = lineStroke,
        alpha = alpha,
        colors = colors,
    )

    fun save() {
        val draft = buildConfig()
        android.util.Log.d("StEdit", "save routeId=$id existingId=${existing?.id} name=${draft.name} size=${draft.pointSize} colors0=${draft.colors.firstOrNull()}")
        val saved = StConfigStore.upsert(draft)
        StConfigStore.byId(saved)?.let { StEngine.onConfigChanged(it) }
        navigator.pop()
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                modifier = Modifier.installerMiuixBlurEffect(backdrop),
                color = backdrop.getMiuixAppBarColor(),
                scrollBehavior = scrollBehavior,
                title = if (isNew) "新增 ST 配置" else "编辑 ST 配置",
                navigationIcon = {
                    MiuixBackButton(
                        icon = MiuixIcons.Regular.Close,
                        onClick = { navigator.pop() },
                    )
                },
                actions = {
                    // 沿用 IRX 原来的「✓」保存按钮
                    IconButton(onClick = { save() }) {
                        Icon(
                            imageVector = MiuixIcons.Regular.Ok,
                            contentDescription = "保存",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
    ) { paddingValues ->
        val editingIndex = editingColorIndex
        if (editingIndex >= 0) {
            StColorPickerDialog(
                index = editingIndex,
                initialArgb = colors.getOrElse(editingIndex) { 0xFFFFFFFF.toInt() },
                onDismiss = { editingColorIndex = -1 },
                onPicked = { newArgb ->
                    colors = colors.toMutableList().also { it[editingIndex] = newArgb }
                    editingColorIndex = -1
                },
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = paddingValues.calculateStartPadding(layoutDirection) + 12.dp,
                top = paddingValues.calculateTopPadding() + 12.dp,
                end = paddingValues.calculateEndPadding(layoutDirection) + 12.dp,
                bottom = paddingValues.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                // 名称输入框沿用 IRX 原版（MiuixEditItemWidget.MiuixDataNameWidget）：
                // 直接一个 MiuixHintTextField，labelText = R.string.config_name，外层只加 padding + focusable。
                // IRX 原版的「描述」输入框不要。
                MiuixHintTextField(
                    value = name,
                    onValueChange = { name = it },
                    labelText = stringResource(id = R.string.config_name),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp, horizontal = 12.dp)
                        .focusable(),
                )
            }

            item { SmallTitle("绘制参数") }

            item {
                Card {
                    SliderRow("圆环大小", pointSize, 0..150, "dp") { pointSize = it }
                    SliderRow("轨迹淡出", fadeTime, 0..1000, "ms") { fadeTime = it }
                    SliderRow("圆环描边", circleStroke, 0..30, "dp") { circleStroke = it }
                    SliderRow("轨迹描边", lineStroke, 0..30, "dp") { lineStroke = it }
                    SliderRow("整体透明度", alpha, 1..100, "%") { alpha = it }
                }
            }

            item { SmallTitle("手指颜色") }

            item {
                Card {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "点圆点修改对应手指的颜色（第 1~10 指），保存后生效",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        colors.chunked(5).forEachIndexed { rowIndex, rowColors ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                rowColors.forEachIndexed { columnIndex, argb ->
                                    val index = rowIndex * 5 + columnIndex
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(Color(argb))
                                            .clickable { editingColorIndex = index },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item { SmallTitle("操作") }

            item {
                Card {
                    BasicComponent(
                        title = "保存",
                        summary = "写入配置并在引擎运行时立即生效",
                        onClick = { save() },
                    )
                    if (!isNew) {
                        BasicComponent(
                            title = "删除这套配置",
                            summary = "至少会保留一套配置",
                            onClick = {
                                existing?.let { StConfigStore.delete(it.id) }
                                navigator.pop()
                            },
                        )
                    }
                }
            }

            item {
                Text(
                    text = "注：颜色改完记得点右上角 ✓ 保存。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Int,
    range: IntRange,
    suffix: String,
    onValueChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = title, fontSize = 15.sp)
            Text(
                text = "$value$suffix",
                fontSize = 15.sp,
                color = MiuixTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            modifier = Modifier.fillMaxWidth(),
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            hapticEffect = SliderDefaults.SliderHapticEffect.Step,
        )
    }
}

/** 第 [index] 指的颜色选择弹窗（miuix ColorPicker） */
@Composable
private fun StColorPickerDialog(
    index: Int,
    initialArgb: Int,
    onDismiss: () -> Unit,
    onPicked: (Int) -> Unit,
) {
    var picked by remember(initialArgb) { mutableStateOf(Color(initialArgb)) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "第 ${index + 1} 指颜色",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                )
                ColorPicker(
                    color = picked,
                    onColorChanged = { picked = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(text = "取消", onClick = onDismiss)
                    TextButton(text = "确定", onClick = { onPicked(picked.toArgb()) })
                }
            }
        }
    }
}

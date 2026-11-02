// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
package com.rosan.installer.ui.page.miuix.settings.home

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rosan.installer.R
import com.rosan.installer.core.env.DeviceConfig
import com.rosan.installer.domain.settings.model.config.Authorizer
import com.rosan.installer.domain.settings.model.preferences.RootMode
import com.rosan.installer.ui.icons.AppIcons
import com.rosan.installer.ui.navigation.LocalNavigator
import com.rosan.installer.ui.navigation.Route
import com.rosan.installer.ui.page.main.settings.home.HomePageViewAction
import com.rosan.installer.ui.page.main.settings.home.HomePageViewEvent
import com.rosan.installer.ui.page.main.settings.home.HomePageViewModel
import com.rosan.installer.ui.page.main.settings.home.HomePageViewState
import com.rosan.installer.ui.page.main.widget.util.OnLifecycleEvent
import com.rosan.installer.ui.theme.InstallerTheme
import com.rosan.installer.ui.theme.getMiuixAppBarColor
import com.rosan.installer.ui.theme.installerMiuixBlurEffect
import com.rosan.installer.ui.theme.miuixHomeStatusCardColorActivated
import com.rosan.installer.ui.theme.miuixHomeStatusCardColorDeactivated
import com.rosan.installer.ui.theme.rememberMiuixBlurBackdrop
import org.koin.androidx.compose.koinViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.isDynamicColor
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun MiuixHomePage(
    enableBlur: Boolean,
    viewModel: HomePageViewModel = koinViewModel(),
    title: String,
    configCount: Int = 0,
    outerPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    onNavigateToProfiles: () -> Unit = {},
) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior()
    val layoutDirection = LocalLayoutDirection.current

    OnLifecycleEvent(event = Lifecycle.Event.ON_RESUME) {
        viewModel.dispatch(HomePageViewAction.RefreshActivateStatus)
    }

    @SuppressLint("LocalContextGetResourceValueCall")
    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is HomePageViewEvent.ShowDefaultInstallerResult -> {
                    snackbarHostState.showSnackbar(context.getString(event.messageResId))
                }

                is HomePageViewEvent.ShowDefaultInstallerErrorDetail -> {
                    snackbarHostState.showSnackbar(context.getString(event.titleResId))
                }
            }
        }
    }

    val backdrop = rememberMiuixBlurBackdrop(enableBlur)

    // ── ST 状态（大卡片、开关都读同一份） ──
    val stRunning by show.taps.StRuntime.running.collectAsStateWithLifecycle()
    val stConfigSet = show.taps.rememberStConfigSet()
    val stActiveName = stConfigSet.active?.name

    fun toggleSt(enable: Boolean) {
        if (enable) {
            // Shizuku 在跑但本应用没被授权 → 先弹授权（Sui/Shizuku 自己的对话框）
            if (rikka.shizuku.Shizuku.pingBinder() &&
                rikka.shizuku.Shizuku.checkSelfPermission() !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                runCatching { rikka.shizuku.Shizuku.requestPermission(0x5701) }
                return
            }
            if (!android.provider.Settings.canDrawOverlays(context)) {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${context.packageName}")
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                return
            }
            show.taps.StEngine.startActive(context)
        } else {
            show.taps.StEngine.stop(context)
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
                .then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier)
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDirection) + 12.dp,
                top = innerPadding.calculateTopPadding() + 12.dp,
                end = innerPadding.calculateEndPadding(layoutDirection) + 12.dp,
                bottom = outerPadding.calculateBottomPadding(),
            ),
            overscrollEffect = null,
        ) {
            item {
                MiuixStatusGrid(
                    uiState = uiState,
                    // 「配置数量」现在统计的是 ST 配置（配置 tab 已被 ST 接管）
                    configCount = stConfigSet.configs.size,
                    stRunning = stRunning,
                    stConfigName = stActiveName,
                    onStToggle = { toggleSt(!stRunning) },
                    onInstallerClick = { navigator.push(Route.DefaultInstaller) },
                    onAuthorizersClick = { navigator.push(Route.Priv) },
                    onProfilesClick = onNavigateToProfiles,
                )
            }

            item {
                Card(modifier = Modifier.padding(vertical = 12.dp)) {
                    // ── ST（Show Taps）总开关 ──────────────────────────────
                    // 上游这里原本是「机型 / 系统 / 正在使用的特权 / 当前系统使用的安装活动」四行，
                    // 本分支已整块删掉（2026-11）。
                    BasicComponent(
                        title = "Show Taps 触摸指示",
                        summary = if (stRunning) {
                            "正在显示 · 配置：${stActiveName ?: "-"}"
                        } else {
                            "已关闭 · 当前配置：${stActiveName ?: "-"}"
                        },
                        endActions = {
                            Switch(
                                checked = stRunning,
                                onCheckedChange = { toggleSt(it) },
                            )
                        },
                        onClick = { toggleSt(!stRunning) },
                    )
                }
            }

            item { Spacer(modifier = Modifier.navigationBarsPadding()) }
        }
    }
}

@Composable
private fun MiuixStatusGrid(
    uiState: HomePageViewState,
    configCount: Int,
    stRunning: Boolean = false,
    stConfigName: String? = null,
    onStToggle: () -> Unit = {},
    onInstallerClick: () -> Unit,
    onAuthorizersClick: () -> Unit,
    onProfilesClick: () -> Unit,
) {
    // 大卡片的「激活/未激活」外观跟随 ST 覆盖层的开关状态
    val isActive = stRunning
    val isSystemInstallerActive = uiState.isSystemApp && uiState.globalAuthorizer == Authorizer.None

    // 1. Calculate container background color based on state and theme
    val containerColor = if (isActive) {
        when {
            isDynamicColor -> MiuixTheme.colorScheme.secondaryContainer
            InstallerTheme.isDark -> Color(0xFF1A3825)
            else -> Color(0xFFDFFAE4)
        }
    } else {
        when {
            isDynamicColor -> MiuixTheme.colorScheme.errorContainer
            InstallerTheme.isDark -> Color(0xFF381A1A)
            else -> Color(0xFFFAEEEE)
        }
    }

    // 2. Calculate text content color
    // Use proper Material 3 on-container colors for dynamic, and fallback to onSurface (black/white) for hardcoded
    val textContentColor = if (isActive) {
        if (isDynamicColor) MiuixTheme.colorScheme.onSecondaryContainer else MiuixTheme.colorScheme.onSurface
    } else {
        if (isDynamicColor) MiuixTheme.colorScheme.onErrorContainer else MiuixTheme.colorScheme.onSurface
    }

    // 3. Derive description text color with slight transparency for hierarchy
    val descTextColor = textContentColor.copy(alpha = 0.8f)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Large Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(color = containerColor),
            onClick = onStToggle,
            showIndication = true,
            pressFeedbackType = PressFeedbackType.Tilt,
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                // Background Icon
                // Keep the icon colorful to maintain visual appeal, independent of text color
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .offset(50.dp, 38.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Icon(
                        modifier = Modifier.size(170.dp),
                        imageVector = if (isActive) AppIcons.Active else Icons.Rounded.ErrorOutline,
                        tint = if (isActive) {
                            if (isDynamicColor) {
                                MiuixTheme.colorScheme.primary.copy(
                                    alpha = 0.8f,
                                )
                            } else {
                                miuixHomeStatusCardColorActivated
                            }
                        } else {
                            if (isDynamicColor) {
                                MiuixTheme.colorScheme.error.copy(
                                    alpha = 0.8f,
                                )
                            } else {
                                miuixHomeStatusCardColorDeactivated
                            }
                        },
                        contentDescription = null,
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(all = 16.dp),
                ) {
                    // Slot 1: Main Status Title
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = if (stRunning) "触摸指示已开启" else "触摸指示已关闭",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textContentColor,
                    )
                    Spacer(Modifier.height(2.dp))

                    // Slot 2: Status Description
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = if (stRunning) {
                            "配置：${stConfigName ?: "-"} · 点一下关闭"
                        } else {
                            "点一下开启（使用「${stConfigName ?: "-"}」）"
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = descTextColor,
                    )

                    Spacer(Modifier.height(36.dp))

                    // Slot 3: Current Authorizer Type
                    if (!isSystemInstallerActive) {
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = stringResource(uiState.globalAuthorizer.displayNameRes),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = descTextColor,
                        )
                    } else {
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }

        // Two smaller cards placed flat on the second row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MiuixStatCard(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                title = stringResource(R.string.home_stat_authorizers),
                value = uiState.availableAuthorizerCount.toString(),
                onClick = onAuthorizersClick,
            )
            MiuixStatCard(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                title = stringResource(R.string.home_stat_profiles),
                value = configCount.toString(),
                onClick = onProfilesClick,
            )
        }
    }
}

@Composable
private fun MiuixStatCard(modifier: Modifier = Modifier, title: String, value: String, onClick: () -> Unit) {
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(16.dp),
        onClick = onClick,
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = title,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = value,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MiuixStatusGridPreview() {
    val uiState = HomePageViewState(
        isDefaultInstaller = true,
        availableAuthorizerCount = 3,
        isSystemApp = false,
    )
    MiuixStatusGrid(
        uiState = uiState,
        configCount = 5,
        onInstallerClick = {},
        onAuthorizersClick = {},
        onProfilesClick = {},
    )
}

@Preview(showBackground = true)
@Composable
private fun MiuixStatusGridInactivePreview() {
    val uiState = HomePageViewState(
        isDefaultInstaller = false,
        availableAuthorizerCount = 1,
        isSystemApp = false,
    )
    MiuixStatusGrid(
        uiState = uiState,
        configCount = 2,
        onInstallerClick = {},
        onAuthorizersClick = {},
        onProfilesClick = {},
    )
}

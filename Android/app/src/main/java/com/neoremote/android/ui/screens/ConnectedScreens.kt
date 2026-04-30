package com.neoremote.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.neoremote.android.core.model.DesktopEndpoint
import com.neoremote.android.core.model.SessionUiState
import com.neoremote.android.core.model.TouchSensitivitySettings
import com.neoremote.android.core.model.TouchSurfaceOutput
import com.neoremote.android.core.model.VideoActionKind
import com.neoremote.android.core.model.displayText
import com.neoremote.android.ui.components.DeviceCard
import com.neoremote.android.ui.components.LiquidGlassBottomBar
import com.neoremote.android.ui.components.LiquidGlassTabItem
import com.neoremote.android.ui.components.SectionCard
import com.neoremote.android.ui.components.StatusChip
import com.neoremote.android.ui.components.TouchSurfaceHost

private enum class ConnectedTab(
    val label: String,
) {
    REMOTE("Remote"),
    DEVICES("Devices"),
    SETTINGS("Settings"),
}

private enum class RemoteMode {
    TOUCHPAD,
    DOUYIN,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectedShell(
    state: SessionUiState,
    onRefreshDiscovery: () -> Unit,
    onConnect: (DesktopEndpoint) -> Unit,
    onDisconnect: () -> Unit,
    onClearRecent: () -> Unit,
    onHapticsEnabledChange: (Boolean) -> Unit,
    onCursorSensitivityChange: (Double) -> Unit,
    onSwipeSensitivityChange: (Double) -> Unit,
    onTouchOutput: (TouchSurfaceOutput) -> Unit,
    onVideoAction: (VideoActionKind) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(ConnectedTab.REMOTE) }
    val contentBackdrop = rememberLayerBackdrop()
    val bottomInsetPadding = PaddingValues(bottom = 120.dp)
    val tabs = listOf(
        LiquidGlassTabItem(label = "Remote", icon = Icons.Outlined.Handyman),
        LiquidGlassTabItem(label = "Devices", icon = Icons.Filled.Devices),
        LiquidGlassTabItem(label = "Settings", icon = Icons.Filled.Settings),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NeoRemote") },
                actions = {
                    if (selectedTab == ConnectedTab.DEVICES) {
                        IconButton(onClick = onRefreshDiscovery) {
                            Icon(Icons.Outlined.Sync, contentDescription = "刷新")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(contentBackdrop),
            ) {
                when (selectedTab) {
                    ConnectedTab.REMOTE -> RemoteScreen(
                        state = state,
                        onDisconnect = onDisconnect,
                        onTouchOutput = onTouchOutput,
                        onVideoAction = onVideoAction,
                        bottomPadding = bottomInsetPadding,
                    )

                    ConnectedTab.DEVICES -> DevicesScreen(
                        state = state,
                        onConnect = onConnect,
                        bottomPadding = bottomInsetPadding,
                    )

                    ConnectedTab.SETTINGS -> SettingsScreen(
                        state = state,
                        onDisconnect = onDisconnect,
                        onClearRecent = onClearRecent,
                        onHapticsEnabledChange = onHapticsEnabledChange,
                        onCursorSensitivityChange = onCursorSensitivityChange,
                        onSwipeSensitivityChange = onSwipeSensitivityChange,
                        bottomPadding = bottomInsetPadding,
                    )
                }
            }

            state.lastHudMessage?.let { hud ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 118.dp),
                ) {
                    Text(
                        text = hud,
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(100.dp),
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        color = MaterialTheme.colorScheme.surface,
                    )
                }
            }

            LiquidGlassBottomBar(
                items = tabs,
                selectedIndex = selectedTab.ordinal,
                onSelectedIndexChange = { selectedTab = ConnectedTab.entries[it] },
                backdrop = contentBackdrop,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun RemoteScreen(
    state: SessionUiState,
    onDisconnect: () -> Unit,
    onTouchOutput: (TouchSurfaceOutput) -> Unit,
    onVideoAction: (VideoActionKind) -> Unit,
    bottomPadding: PaddingValues,
) {
    var mode by rememberSaveable { mutableStateOf(RemoteMode.TOUCHPAD) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 20.dp, top = 20.dp, end = 20.dp)
            .padding(bottomPadding),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.activeEndpoint?.displayName ?: "Desktop",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = state.statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusChip(status = state.status)
            IconButton(onClick = onDisconnect) {
                Icon(Icons.Outlined.PowerSettingsNew, contentDescription = "断开")
            }
        }

        RemoteModeSwitch(mode = mode, onModeChange = { mode = it })

        when (mode) {
            RemoteMode.TOUCHPAD -> {
                TouchSurfaceHost(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    settings = state.touchSensitivitySettings,
                    onOutput = onTouchOutput,
                )

                Text(
                    text = "单指移动/点击 · 双击拖拽 · 双指右键/滚动 · 三指中键",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            RemoteMode.DOUYIN -> ShortVideoControlPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                onVideoAction = onVideoAction,
            )
        }
    }
}

@Composable
private fun RemoteModeSwitch(
    mode: RemoteMode,
    onModeChange: (RemoteMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RemoteModeButton(
            label = "触控板",
            selected = mode == RemoteMode.TOUCHPAD,
            onClick = { onModeChange(RemoteMode.TOUCHPAD) },
            modifier = Modifier.weight(1f),
        )
        RemoteModeButton(
            label = "短视频",
            selected = mode == RemoteMode.DOUYIN,
            onClick = { onModeChange(RemoteMode.DOUYIN) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RemoteModeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.background(
            color = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f)
            },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        ),
    ) {
        Text(
            text = label,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun ShortVideoControlPanel(
    modifier: Modifier = Modifier,
    onVideoAction: (VideoActionKind) -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            ShortVideoActionButton(
                label = "上一条",
                action = VideoActionKind.SWIPE_DOWN,
                onVideoAction = onVideoAction,
                modifier = Modifier.weight(1f),
            )
            ShortVideoActionButton(
                label = "下一条",
                action = VideoActionKind.SWIPE_UP,
                onVideoAction = onVideoAction,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            ShortVideoActionButton(
                label = "左滑",
                action = VideoActionKind.SWIPE_LEFT,
                onVideoAction = onVideoAction,
                modifier = Modifier.weight(1f),
            )
            ShortVideoActionButton(
                label = "右滑",
                action = VideoActionKind.SWIPE_RIGHT,
                onVideoAction = onVideoAction,
                modifier = Modifier.weight(1f),
            )
        }
        ShortVideoActionButton(
            label = "双击点赞",
            action = VideoActionKind.DOUBLE_TAP_LIKE,
            onVideoAction = onVideoAction,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            ShortVideoActionButton(
                label = "播放/暂停",
                action = VideoActionKind.PLAY_PAUSE,
                onVideoAction = onVideoAction,
                modifier = Modifier.weight(1f),
            )
            ShortVideoActionButton(
                label = "返回",
                action = VideoActionKind.BACK,
                onVideoAction = onVideoAction,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ShortVideoActionButton(
    label: String,
    action: VideoActionKind,
    onVideoAction: (VideoActionKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = { onVideoAction(action) },
        modifier = modifier.height(74.dp),
    ) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DevicesScreen(
    state: SessionUiState,
    onConnect: (DesktopEndpoint) -> Unit,
    bottomPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            top = 20.dp,
            end = 20.dp,
            bottom = 20.dp + bottomPadding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (state.discoveredDevices.isNotEmpty()) {
            item {
                Text("附近设备", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            itemsIndexed(
                state.discoveredDevices,
                key = { index, device -> "discovered-$index-${device.id}-${device.addressText}" },
            ) { _, device ->
                DeviceCard(endpoint = device, actionLabel = "连接") { onConnect(device) }
            }
        }

        if (state.recentDevices.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(6.dp))
                Text("最近连接", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            itemsIndexed(
                state.recentDevices,
                key = { index, device -> "recent-$index-${device.id}-${device.addressText}" },
            ) { _, device ->
                DeviceCard(endpoint = device, actionLabel = "恢复") { onConnect(device) }
            }
        }

        if (state.discoveredDevices.isEmpty() && state.recentDevices.isEmpty()) {
            item {
                SectionCard(
                    title = "还没有可用设备",
                    subtitle = "先在 MacOS 端启动 NeoRemote，并确保与 Android 处在同一局域网。",
                ) {}
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: SessionUiState,
    onDisconnect: () -> Unit,
    onClearRecent: () -> Unit,
    onHapticsEnabledChange: (Boolean) -> Unit,
    onCursorSensitivityChange: (Double) -> Unit,
    onSwipeSensitivityChange: (Double) -> Unit,
    bottomPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            top = 20.dp,
            end = 20.dp,
            bottom = 20.dp + bottomPadding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            SectionCard(title = "连接策略") {
                SettingRow(label = "自动发现", value = "Bonjour / LAN")
                SettingRow(label = "协议编码", value = "JSON v1")
                SettingRow(label = "恢复策略", value = "最近设备手动恢复")
            }
        }
        item {
            SectionCard(title = "触控反馈") {
                SwitchSettingRow(
                    label = "震动反馈",
                    checked = state.hapticsEnabled,
                    onCheckedChange = onHapticsEnabledChange,
                )
                SliderSettingRow(
                    label = "光标灵敏度",
                    value = state.touchSensitivitySettings.cursorSensitivity,
                    range = TouchSensitivitySettings.CURSOR_RANGE,
                    onValueChange = onCursorSensitivityChange,
                )
                SliderSettingRow(
                    label = "滑动灵敏度",
                    value = state.touchSensitivitySettings.swipeSensitivity,
                    range = TouchSensitivitySettings.SWIPE_RANGE,
                    onValueChange = onSwipeSensitivityChange,
                )
            }
        }
        item {
            SectionCard(title = "当前会话") {
                SettingRow(label = "状态", value = state.status.displayText)
                SettingRow(label = "Desktop", value = state.activeEndpoint?.displayName ?: "未连接")
                SettingRow(label = "地址", value = state.activeEndpoint?.addressText ?: "—")
            }
        }
        item {
            SectionCard(title = "维护操作") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = onClearRecent) {
                        Text("清空最近设备")
                    }
                    Button(onClick = onDisconnect) {
                        Text("断开当前连接")
                    }
                }
            }
        }
    }
}

@Composable
private fun SliderSettingRow(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    onValueChange: (Double) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(String.format("%.1f", value), fontWeight = FontWeight.Medium)
        }
        CompositionLocalProvider(LocalHapticFeedback provides NoOpHapticFeedback) {
            Slider(
                value = value.toFloat(),
                onValueChange = { raw ->
                    val stepped = kotlin.math.round(raw / TouchSensitivitySettings.STEP.toFloat()) *
                        TouchSensitivitySettings.STEP
                    onValueChange(stepped)
                },
                valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            )
        }
    }
}

private object NoOpHapticFeedback : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}

@Composable
private fun SwitchSettingRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

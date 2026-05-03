package com.neoremote.android.ui.screens

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwapVert
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.neoremote.android.core.model.ControlMode
import com.neoremote.android.core.model.DesktopEndpoint
import com.neoremote.android.core.model.ScreenGestureKind
import com.neoremote.android.core.model.SessionUiState
import com.neoremote.android.core.model.SystemAction
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectedShell(
    state: SessionUiState,
    onRefreshDiscovery: () -> Unit,
    onConnect: (DesktopEndpoint) -> Unit,
    onDisconnect: () -> Unit,
    onHapticsEnabledChange: (Boolean) -> Unit,
    onCursorSensitivityChange: (Double) -> Unit,
    onSwipeSensitivityChange: (Double) -> Unit,
    onControlModeChange: (ControlMode) -> Unit,
    onDefaultControlModeChange: (ControlMode) -> Unit,
    onTouchOutput: (TouchSurfaceOutput) -> Unit,
    onScreenGesture: (ScreenGestureKind, Double, Double, Double, Double, Long) -> Unit,
    onSystemAction: (SystemAction) -> Unit,
    onVideoAction: (VideoActionKind) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(ConnectedTab.REMOTE) }
    val backgroundColor = MaterialTheme.colorScheme.background
    val contentBackdrop = rememberLayerBackdrop {
        drawRect(backgroundColor)
        drawContent()
    }
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
                    when (selectedTab) {
                        ConnectedTab.REMOTE -> {
                            if (state.isAndroidReceiverTarget) {
                                RemoteModeChip(
                                    mode = state.controlMode,
                                    onToggle = {
                                        onControlModeChange(
                                            if (state.controlMode == ControlMode.SHORT_VIDEO) {
                                                ControlMode.SCREEN_CONTROL
                                            } else {
                                                ControlMode.SHORT_VIDEO
                                            },
                                        )
                                    },
                                    modifier = Modifier.padding(end = 12.dp),
                                )
                            }
                        }

                        ConnectedTab.DEVICES -> IconButton(onClick = onRefreshDiscovery) {
                            Icon(Icons.Outlined.Sync, contentDescription = "刷新")
                        }

                        ConnectedTab.SETTINGS -> Unit
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
                        onTouchOutput = onTouchOutput,
                        onScreenGesture = onScreenGesture,
                        onVideoAction = onVideoAction,
                        bottomPadding = bottomInsetPadding,
                    )

                    ConnectedTab.DEVICES -> DevicesScreen(
                        state = state,
                        onConnect = onConnect,
                        onDisconnect = onDisconnect,
                        bottomPadding = bottomInsetPadding,
                    )

                    ConnectedTab.SETTINGS -> SettingsScreen(
                        state = state,
                        onDisconnect = onDisconnect,
                        onHapticsEnabledChange = onHapticsEnabledChange,
                        onCursorSensitivityChange = onCursorSensitivityChange,
                        onSwipeSensitivityChange = onSwipeSensitivityChange,
                        onDefaultControlModeChange = onDefaultControlModeChange,
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
    onTouchOutput: (TouchSurfaceOutput) -> Unit,
    onScreenGesture: (ScreenGestureKind, Double, Double, Double, Double, Long) -> Unit,
    onVideoAction: (VideoActionKind) -> Unit,
    bottomPadding: PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 14.dp, top = 10.dp, end = 14.dp)
            .padding(bottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!state.isAndroidReceiverTarget) {
            DesktopTouchPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                settings = state.touchSensitivitySettings,
                onTouchOutput = onTouchOutput,
            )
        } else {
            when (state.controlMode) {
                ControlMode.SCREEN_CONTROL -> ScreenControlPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    onScreenGesture = onScreenGesture,
                )

                ControlMode.SHORT_VIDEO -> ShortVideoControlPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    onVideoAction = onVideoAction,
                )
            }
        }
    }
}

@Composable
private fun DesktopTouchPanel(
    settings: TouchSensitivitySettings,
    onTouchOutput: (TouchSurfaceOutput) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        TouchSurfaceHost(
            settings = settings,
            onOutput = onTouchOutput,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "桌面触控板",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "单指移动、轻点点击，双指滚动",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RemoteModeChip(
    mode: ControlMode,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onToggle,
        modifier = modifier
            .height(40.dp)
            .background(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
            ),
    ) {
        Text(
            text = mode.displayName,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ScreenControlPanel(
    modifier: Modifier = Modifier,
    onScreenGesture: (ScreenGestureKind, Double, Double, Double, Double, Long) -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var downPoint by remember { mutableStateOf(Offset.Zero) }
    var downTime by remember { mutableStateOf(0L) }

    Box(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(30.dp),
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(30.dp),
            )
            .onSizeChanged { size = it }
            .pointerInteropFilter { event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downPoint = Offset(event.x, event.y)
                        downTime = event.eventTime
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        val upPoint = Offset(event.x, event.y)
                        val elapsed = (event.eventTime - downTime).coerceAtLeast(80L)
                        val width = size.width.coerceAtLeast(1).toFloat()
                        val height = size.height.coerceAtLeast(1).toFloat()
                        val startX = (downPoint.x / width).toDouble().coerceIn(0.0, 1.0)
                        val startY = (downPoint.y / height).toDouble().coerceIn(0.0, 1.0)
                        val endX = (upPoint.x / width).toDouble().coerceIn(0.0, 1.0)
                        val endY = (upPoint.y / height).toDouble().coerceIn(0.0, 1.0)
                        val distance = kotlin.math.hypot(
                            (upPoint.x - downPoint.x).toDouble(),
                            (upPoint.y - downPoint.y).toDouble(),
                        )
                        val kind = if (distance < 18.0) {
                            ScreenGestureKind.TAP
                        } else {
                            ScreenGestureKind.SWIPE
                        }
                        onScreenGesture(kind, startX, startY, endX, endY, elapsed)
                        true
                    }

                    MotionEvent.ACTION_CANCEL -> true
                    else -> true
                }
            },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "屏幕镜像",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "直接点按、上滑、侧滑，动作会映射到被控端全面屏手势",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ShortVideoControlPanel(
    modifier: Modifier = Modifier,
    onVideoAction: (VideoActionKind) -> Unit,
) {
    Column(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(30.dp),
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.24f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(30.dp),
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ShortVideoActionButton(
                label = "点赞",
                action = VideoActionKind.DOUBLE_TAP_LIKE,
                onVideoAction = onVideoAction,
                modifier = Modifier.weight(1f),
                accent = ShortVideoAccent.LIKE,
                icon = { Icon(Icons.Outlined.FavoriteBorder, contentDescription = null) },
            )
            ShortVideoActionButton(
                label = "收藏",
                action = VideoActionKind.FAVORITE,
                onVideoAction = onVideoAction,
                modifier = Modifier.weight(1f),
                icon = { Icon(Icons.Outlined.BookmarkBorder, contentDescription = null) },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(34.dp),
                    ),
            ) {
                ShortVideoRockerButton(
                    label = "上一条",
                    hint = "向下滑动",
                    action = VideoActionKind.SWIPE_DOWN,
                    onVideoAction = onVideoAction,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.28f)),
                )
                ShortVideoRockerButton(
                    label = "下一条",
                    hint = "向上滑动",
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
                    icon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null) },
                )
                ShortVideoActionButton(
                    label = "右滑",
                    action = VideoActionKind.SWIPE_RIGHT,
                    onVideoAction = onVideoAction,
                    modifier = Modifier.weight(1f),
                    icon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null) },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            ShortVideoActionButton(
                label = "播放/暂停",
                action = VideoActionKind.PLAY_PAUSE,
                onVideoAction = onVideoAction,
                modifier = Modifier.weight(1f),
                icon = { PlayPauseIcon() },
            )
            ShortVideoActionButton(
                label = "返回",
                action = VideoActionKind.BACK,
                onVideoAction = onVideoAction,
                modifier = Modifier.weight(1f),
                icon = { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null) },
            )
        }
    }
}

private enum class ShortVideoAccent {
    NORMAL,
    PRIMARY,
    LIKE,
}

@Composable
private fun PlayPauseIcon() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
        Icon(
            Icons.Outlined.Pause,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun ShortVideoActionButton(
    label: String,
    action: VideoActionKind,
    onVideoAction: (VideoActionKind) -> Unit,
    modifier: Modifier = Modifier,
    accent: ShortVideoAccent = ShortVideoAccent.NORMAL,
    icon: @Composable (() -> Unit)? = null,
) {
    val background = when (accent) {
        ShortVideoAccent.NORMAL -> MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
        ShortVideoAccent.PRIMARY -> MaterialTheme.colorScheme.primary
        ShortVideoAccent.LIKE -> MaterialTheme.colorScheme.error
    }
    val foreground = when (accent) {
        ShortVideoAccent.NORMAL -> MaterialTheme.colorScheme.onSurface
        ShortVideoAccent.PRIMARY -> MaterialTheme.colorScheme.onPrimary
        ShortVideoAccent.LIKE -> MaterialTheme.colorScheme.onError
    }
    Button(
        onClick = { onVideoAction(action) },
        modifier = modifier.height(72.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = background,
            contentColor = foreground,
        ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.invoke()
            Text(label, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ShortVideoRockerButton(
    label: String,
    hint: String,
    action: VideoActionKind,
    onVideoAction: (VideoActionKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = { onVideoAction(action) },
        modifier = modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.SwapVert,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
            )
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = hint,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.66f),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun DevicesScreen(
    state: SessionUiState,
    onConnect: (DesktopEndpoint) -> Unit,
    onDisconnect: () -> Unit,
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
            SectionCard(title = "控制状态") {
                SettingRow(label = "状态", value = state.status.displayText)
                SettingRow(label = "当前设备", value = state.activeEndpoint?.displayName ?: "未连接")
                SettingRow(label = "地址", value = state.activeEndpoint?.addressText ?: "—")
                SettingRow(label = "控制模式", value = state.controlMode.displayName)
                Button(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.activeEndpoint != null,
                ) {
                    Text("断开当前连接")
                }
            }
        }

        if (state.discoveredDesktopDevices.isNotEmpty()) {
            item {
                Text("附近桌面端", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            itemsIndexed(
                state.discoveredDesktopDevices,
                key = { index, device -> "discovered-desktop-$index-${device.id}-${device.addressText}" },
            ) { _, device ->
                DeviceCard(endpoint = device, actionLabel = "连接") { onConnect(device) }
            }
        }

        if (state.discoveredMobileDevices.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(6.dp))
                Text("附近移动端", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            itemsIndexed(
                state.discoveredMobileDevices,
                key = { index, device -> "discovered-mobile-$index-${device.id}-${device.addressText}" },
            ) { _, device ->
                DeviceCard(endpoint = device, actionLabel = "连接") { onConnect(device) }
            }
        }

        if (state.discoveredDevices.isEmpty()) {
            item {
                SectionCard(
                    title = "还没有可用设备",
                    subtitle = "先在 Desktop 或移动被控端启动 NeoRemote，并确保设备处在同一局域网。",
                ) {}
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: SessionUiState,
    onDisconnect: () -> Unit,
    onHapticsEnabledChange: (Boolean) -> Unit,
    onCursorSensitivityChange: (Double) -> Unit,
    onSwipeSensitivityChange: (Double) -> Unit,
    onDefaultControlModeChange: (ControlMode) -> Unit,
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
            SectionCard(title = "默认控制模式") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ControlModeButton(
                        label = "屏幕控制",
                        selected = state.defaultControlMode == ControlMode.SCREEN_CONTROL,
                        onClick = { onDefaultControlModeChange(ControlMode.SCREEN_CONTROL) },
                        modifier = Modifier.weight(1f),
                    )
                    ControlModeButton(
                        label = "短视频",
                        selected = state.defaultControlMode == ControlMode.SHORT_VIDEO,
                        onClick = { onDefaultControlModeChange(ControlMode.SHORT_VIDEO) },
                        modifier = Modifier.weight(1f),
                    )
                }
                SettingRow(label = "启动后进入", value = state.defaultControlMode.displayName)
            }
        }
        item {
            SectionCard(title = "连接策略") {
                SettingRow(label = "自动发现", value = "Bonjour / LAN")
                SettingRow(label = "协议编码", value = "JSON v1")
            }
        }
        item {
            SectionCard(title = "触控反馈") {
                SwitchSettingRow(
                    label = "震动反馈",
                    checked = state.hapticsEnabled,
                    onCheckedChange = onHapticsEnabledChange,
                )
            }
        }
        item {
            SectionCard(title = "维护操作") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onDisconnect, enabled = state.activeEndpoint != null) {
                        Text("断开当前连接")
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlModeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier
            .height(50.dp)
            .background(
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

private val ControlMode.displayName: String
    get() = when (this) {
        ControlMode.SCREEN_CONTROL -> "屏幕控制"
        ControlMode.SHORT_VIDEO -> "短视频"
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

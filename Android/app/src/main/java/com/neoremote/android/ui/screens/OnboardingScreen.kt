package com.neoremote.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.NetworkWifi
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.neoremote.android.core.model.DesktopEndpoint
import com.neoremote.android.core.model.ManualConnectDraft
import com.neoremote.android.core.model.SessionUiState
import com.neoremote.android.ui.components.DeviceCard
import com.neoremote.android.ui.components.SectionCard
import com.neoremote.android.ui.components.StatusChip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    state: SessionUiState,
    onRefreshDiscovery: () -> Unit,
    onEnterDemoMode: () -> Unit,
    isAndroidReceiverEnabled: Boolean,
    onOpenAndroidReceiverSettings: () -> Unit,
    onConnect: (DesktopEndpoint) -> Unit,
    onManualDraftChange: (String, String) -> Unit,
    onManualConnect: () -> Unit,
    onAdbWiredConnect: () -> Unit,
) {
    var showingManualDialog by rememberSaveable { mutableStateOf(false) }
    var showingAdbWiredDialog by rememberSaveable { mutableStateOf(false) }
    var hiddenRefreshTapCount by rememberSaveable { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NeoRemote") },
                actions = {
                    IconButton(
                        onClick = {
                            onRefreshDiscovery()
                            hiddenRefreshTapCount += 1
                            if (hiddenRefreshTapCount >= 10) {
                                hiddenRefreshTapCount = 0
                                onEnterDemoMode()
                            }
                        },
                    ) {
                        Icon(Icons.Outlined.Sync, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                SectionCard(
                    title = "把 Android 变成你的鼠标/触控板",
                    subtitle = "控制桌面端，也可以让这台 Android 作为被控端。",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("自动发现")
                        Text("手动连接")
                        Text("Android 被控")
                    }
                }
            }

            item {
                SectionCard(title = "连接状态") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        StatusChip(status = state.status)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                state.statusMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            state.errorMessage?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
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
                    Text("附近移动端", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                itemsIndexed(
                    state.discoveredMobileDevices,
                    key = { index, device -> "discovered-mobile-$index-${device.id}-${device.addressText}" },
                ) { _, device ->
                    DeviceCard(endpoint = device, actionLabel = "连接") { onConnect(device) }
                }
            }

            item {
                val receiverStatus = if (isAndroidReceiverEnabled) {
                    "已授权：这台 Android 会发布为 NeoRemote 被控端。"
                } else {
                    "未授权：打开辅助功能后，其他移动端才能控制这台 Android。"
                }
                SectionCard(
                    title = "本机作为 Android 被控端",
                    subtitle = receiverStatus,
                ) {
                    FilledTonalButton(onClick = onOpenAndroidReceiverSettings) {
                        Icon(Icons.Outlined.PhoneAndroid, contentDescription = null)
                        Spacer(modifier = Modifier.padding(horizontal = 6.dp))
                        Text(if (isAndroidReceiverEnabled) "查看辅助功能授权" else "前往授权")
                    }
                }
            }

            item {
                SectionCard(
                    title = "手动连接 Desktop",
                    subtitle = "如果当前网络无法自动发现 Desktop，可直接输入地址和端口。",
                ) {
                    FilledTonalButton(onClick = { showingManualDialog = true }) {
                        Icon(Icons.Outlined.Keyboard, contentDescription = null)
                        Spacer(modifier = Modifier.padding(horizontal = 6.dp))
                        Text("输入地址连接")
                    }
                }
            }

            item {
                SectionCard(
                    title = "ADB 有线调试 Desktop",
                    subtitle = "USB 调试连接后，通过 adb reverse 把电脑端口映射到手机本机。",
                ) {
                    FilledTonalButton(onClick = { showingAdbWiredDialog = true }) {
                        Icon(Icons.Outlined.Keyboard, contentDescription = null)
                        Spacer(modifier = Modifier.padding(horizontal = 6.dp))
                        Text("连接有线调试")
                    }
                }
            }
        }
    }

    if (showingManualDialog) {
        ManualConnectDialog(
            initialDraft = state.manualConnectDraft,
            onDismiss = { showingManualDialog = false },
            onConfirm = { host, port ->
                onManualDraftChange(host, port)
                onManualConnect()
                showingManualDialog = false
            },
        )
    }

    if (showingAdbWiredDialog) {
        AdbWiredDebugDialog(
            onDismiss = { showingAdbWiredDialog = false },
            onConfirm = {
                onAdbWiredConnect()
                showingAdbWiredDialog = false
            },
        )
    }
}

@Composable
private fun ManualConnectDialog(
    initialDraft: ManualConnectDraft,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var host by rememberSaveable { mutableStateOf(initialDraft.host) }
    var port by rememberSaveable { mutableStateOf(initialDraft.port) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.NetworkWifi, contentDescription = null) },
        title = { Text("手动连接") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Desktop 地址") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("端口") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(host, port) }) {
                Text("连接 Desktop")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun AdbWiredDebugDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Keyboard, contentDescription = null) },
        title = { Text("有线调试连接") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("1. 用 USB 连接 Android 手机和电脑，并开启 USB 调试。")
                Text("2. 在电脑上执行：adb reverse tcp:51101 tcp:51101")
                Text("3. 保持桌面端 NeoRemote 正在监听，然后连接 127.0.0.1:51101。")
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("连接 127.0.0.1:51101")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

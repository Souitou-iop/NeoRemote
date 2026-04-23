package com.neoremote.android.ui.screens

import com.neoremote.android.BuildConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.NetworkWifi
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
    onConnect: (DesktopEndpoint) -> Unit,
    onManualDraftChange: (String, String) -> Unit,
    onManualConnect: () -> Unit,
) {
    var showingManualDialog by rememberSaveable { mutableStateOf(false) }
    var debugRefreshTapCount by rememberSaveable { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NeoRemote") },
                actions = {
                    IconButton(
                        onClick = {
                            onRefreshDiscovery()
                            if (BuildConfig.DEBUG) {
                                debugRefreshTapCount += 1
                                if (debugRefreshTapCount >= 5) {
                                    debugRefreshTapCount = 0
                                    onEnterDemoMode()
                                }
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
                    subtitle = "自动发现、手动兜底、纯原生 Compose + MotionEvent 混合实现",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("自动发现")
                        Text("手动连接")
                        Text("状态回包")
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

            if (state.discoveredDevices.isNotEmpty()) {
                item {
                    Text("附近的 Desktop", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                items(state.discoveredDevices, key = { it.id }) { device ->
                    DeviceCard(endpoint = device, actionLabel = "连接") { onConnect(device) }
                }
            }

            if (state.recentDevices.isNotEmpty()) {
                item {
                    Text("最近连接", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                items(state.recentDevices, key = { it.id }) { device ->
                    DeviceCard(endpoint = device, actionLabel = "恢复") { onConnect(device) }
                }
            }

            item {
                SectionCard(
                    title = "手动连接",
                    subtitle = "如果当前网络无法自动发现 Desktop，可直接输入地址和端口。",
                ) {
                    FilledTonalButton(onClick = { showingManualDialog = true }) {
                        Icon(Icons.Outlined.Keyboard, contentDescription = null)
                        Spacer(modifier = Modifier.padding(horizontal = 6.dp))
                        Text("输入地址连接")
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

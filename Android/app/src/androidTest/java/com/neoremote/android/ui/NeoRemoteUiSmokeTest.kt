package com.neoremote.android.ui

import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.neoremote.android.core.model.SessionRoute
import com.neoremote.android.core.model.SessionStatus
import com.neoremote.android.core.model.SessionUiState
import com.neoremote.android.core.model.DesktopEndpoint
import com.neoremote.android.core.model.EndpointSource
import org.junit.Rule
import org.junit.Test

class NeoRemoteUiSmokeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun onboarding_shows_manual_connect_entry() {
        composeRule.setContent {
            NeoRemoteApp(
                state = SessionUiState(),
                onRefreshDiscovery = {},
                onEnterDemoMode = {},
                onConnect = {},
                onDisconnect = {},
                onClearRecent = {},
                onManualDraftChange = { _, _ -> },
                onManualConnect = {},
                onAdbWiredConnect = {},
                onHapticsEnabledChange = {},
                onTouchOutput = {},
            )
        }

        composeRule.onNodeWithText("输入地址连接").fetchSemanticsNode()
    }

    @Test
    fun connected_shell_defaults_to_remote_tab() {
        composeRule.setContent {
            NeoRemoteApp(
                state = SessionUiState(
                    status = SessionStatus.CONNECTED,
                    route = SessionRoute.CONNECTED,
                    statusMessage = "桌面端在线",
                ),
                onRefreshDiscovery = {},
                onEnterDemoMode = {},
                onConnect = {},
                onDisconnect = {},
                onClearRecent = {},
                onManualDraftChange = { _, _ -> },
                onManualConnect = {},
                onAdbWiredConnect = {},
                onHapticsEnabledChange = {},
                onTouchOutput = {},
            )
        }

        composeRule.onNodeWithText("Remote").fetchSemanticsNode()
        composeRule.onNodeWithText("单指移动、单击、双指滚动、双击后拖拽").fetchSemanticsNode()
    }

    @Test
    fun connected_shell_tab_switches_keep_content_available() {
        composeRule.setContent {
            NeoRemoteApp(
                state = SessionUiState(
                    status = SessionStatus.CONNECTED,
                    route = SessionRoute.CONNECTED,
                    statusMessage = "桌面端在线",
                ),
                onRefreshDiscovery = {},
                onEnterDemoMode = {},
                onConnect = {},
                onDisconnect = {},
                onClearRecent = {},
                onManualDraftChange = { _, _ -> },
                onManualConnect = {},
                onAdbWiredConnect = {},
                onHapticsEnabledChange = {},
                onTouchOutput = {},
            )
        }

        composeRule.onNodeWithText("Devices").performClick()
        composeRule.onNodeWithText("还没有可用设备").fetchSemanticsNode()

        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("连接策略").fetchSemanticsNode()
    }

    @Test
    fun liquid_glass_bottom_bar_tags_remain_clickable() {
        composeRule.setContent {
            NeoRemoteApp(
                state = SessionUiState(
                    status = SessionStatus.CONNECTED,
                    route = SessionRoute.CONNECTED,
                    statusMessage = "桌面端在线",
                ),
                onRefreshDiscovery = {},
                onEnterDemoMode = {},
                onConnect = {},
                onDisconnect = {},
                onClearRecent = {},
                onManualDraftChange = { _, _ -> },
                onManualConnect = {},
                onAdbWiredConnect = {},
                onHapticsEnabledChange = {},
                onTouchOutput = {},
            )
        }

        composeRule.onAllNodesWithTag("bottom-tab-devices")[0].performClick()
        composeRule.onNodeWithText("还没有可用设备").fetchSemanticsNode()

        composeRule.onAllNodesWithTag("bottom-tab-settings")[0].performClick()
        composeRule.onNodeWithText("连接策略").fetchSemanticsNode()
    }

    @Test
    fun onboarding_refresh_five_taps_enters_demo_mode() {
        var refreshCount = 0
        var demoCount = 0

        composeRule.setContent {
            NeoRemoteApp(
                state = SessionUiState(),
                onRefreshDiscovery = { refreshCount += 1 },
                onEnterDemoMode = { demoCount += 1 },
                onConnect = {},
                onDisconnect = {},
                onClearRecent = {},
                onManualDraftChange = { _, _ -> },
                onManualConnect = {},
                onAdbWiredConnect = {},
                onHapticsEnabledChange = {},
                onTouchOutput = {},
            )
        }

        repeat(5) {
            composeRule.onNodeWithContentDescription("刷新").performClick()
        }

        assert(refreshCount == 5)
        assert(demoCount == 1)
    }

    @Test
    fun onboarding_adb_wired_entry_triggers_connect() {
        var wiredConnectCount = 0

        composeRule.setContent {
            NeoRemoteApp(
                state = SessionUiState(),
                onRefreshDiscovery = {},
                onEnterDemoMode = {},
                onConnect = {},
                onDisconnect = {},
                onClearRecent = {},
                onManualDraftChange = { _, _ -> },
                onManualConnect = {},
                onAdbWiredConnect = { wiredConnectCount += 1 },
                onHapticsEnabledChange = {},
                onTouchOutput = {},
            )
        }

        composeRule.onNodeWithText("连接有线调试").performClick()
        composeRule.onNodeWithText("有线调试连接").fetchSemanticsNode()
        composeRule.onNodeWithText("连接 127.0.0.1:51101").performClick()

        assert(wiredConnectCount == 1)
    }

    @Test
    fun onboarding_allows_same_device_in_discovered_and_recent_sections() {
        val endpoint = DesktopEndpoint(
            id = "shared-device",
            displayName = "NeoRemote Mac",
            host = "192.168.1.10",
            port = 50505,
            source = EndpointSource.DISCOVERED,
        )

        composeRule.setContent {
            NeoRemoteApp(
                state = SessionUiState(
                    discoveredDevices = listOf(endpoint),
                    recentDevices = listOf(endpoint.copy(source = EndpointSource.RECENT)),
                ),
                onRefreshDiscovery = {},
                onEnterDemoMode = {},
                onConnect = {},
                onDisconnect = {},
                onClearRecent = {},
                onManualDraftChange = { _, _ -> },
                onManualConnect = {},
                onAdbWiredConnect = {},
                onHapticsEnabledChange = {},
                onTouchOutput = {},
            )
        }

        composeRule.onNodeWithText("附近的 Desktop").fetchSemanticsNode()
        composeRule.onNodeWithText("最近连接").fetchSemanticsNode()
    }

    @Test
    fun onboarding_survives_duplicate_discovered_device_keys() {
        val endpoint = DesktopEndpoint(
            id = "duplicate-device",
            displayName = "NeoRemote Mac",
            host = "192.168.1.10",
            port = 50505,
            source = EndpointSource.DISCOVERED,
        )

        composeRule.setContent {
            NeoRemoteApp(
                state = SessionUiState(
                    discoveredDevices = listOf(endpoint, endpoint),
                ),
                onRefreshDiscovery = {},
                onEnterDemoMode = {},
                onConnect = {},
                onDisconnect = {},
                onClearRecent = {},
                onManualDraftChange = { _, _ -> },
                onManualConnect = {},
                onAdbWiredConnect = {},
                onHapticsEnabledChange = {},
                onTouchOutput = {},
            )
        }

        composeRule.onNodeWithText("附近的 Desktop").fetchSemanticsNode()
    }
}

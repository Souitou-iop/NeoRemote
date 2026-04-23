package com.neoremote.android.ui

import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.neoremote.android.core.model.SessionRoute
import com.neoremote.android.core.model.SessionStatus
import com.neoremote.android.core.model.SessionUiState
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
                onConnect = {},
                onDisconnect = {},
                onClearRecent = {},
                onManualDraftChange = { _, _ -> },
                onManualConnect = {},
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
                onConnect = {},
                onDisconnect = {},
                onClearRecent = {},
                onManualDraftChange = { _, _ -> },
                onManualConnect = {},
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
                onConnect = {},
                onDisconnect = {},
                onClearRecent = {},
                onManualDraftChange = { _, _ -> },
                onManualConnect = {},
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
                onConnect = {},
                onDisconnect = {},
                onClearRecent = {},
                onManualDraftChange = { _, _ -> },
                onManualConnect = {},
                onTouchOutput = {},
            )
        }

        composeRule.onAllNodesWithTag("bottom-tab-devices")[0].performClick()
        composeRule.onNodeWithText("还没有可用设备").fetchSemanticsNode()

        composeRule.onAllNodesWithTag("bottom-tab-settings")[0].performClick()
        composeRule.onNodeWithText("连接策略").fetchSemanticsNode()
    }
}
